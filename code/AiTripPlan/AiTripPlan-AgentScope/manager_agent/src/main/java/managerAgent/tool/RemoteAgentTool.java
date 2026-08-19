package managerAgent.tool;

import com.alibaba.nacos.api.exception.NacosException;
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.nacos.a2a.discovery.NacosAgentCardResolver;
import io.agentscope.core.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import utils.NacosUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@Slf4j
public class RemoteAgentTool {

    // A transient A2A transport failure should not invalidate an experiment run immediately.
    private static final int MAX_RETRIES = 1;
    private static final Duration A2A_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(3);

    /**
     * AgentScope Tool interface: called by ReActAgent during plan execution.
     * For experiment orchestration, use {@link #callRouteMakingAgentWithResult} instead.
     */
    @Tool(description = "从 Nacos 注册中心获取路线制定 Agent，并通过 A2A 协议调用。参数 taskDescription 是路线规划任务描述。")
    public String callRouteMakingAgent(String taskDescription) throws NacosException {
        RemoteAgentResult result = callRouteMakingAgentWithResult(taskDescription);
        if (!result.isLinkSuccess()) {
            log.warn("RouteMakingAgent A2A call failed: {}", result.getErrorMessage());
        }
        return result.getContent();
    }

    /**
     * AgentScope Tool interface: called by ReActAgent during plan execution.
     * For experiment orchestration, use {@link #callTripPlannerAgentWithResult} instead.
     */
    @Tool(description = "从 Nacos 注册中心获取行程规划 Agent，并通过 A2A 协议调用。参数 taskDescription 是景点、住宿、餐饮等行程规划任务描述。")
    public String callTripPlannerAgent(String taskDescription) throws NacosException {
        RemoteAgentResult result = callTripPlannerAgentWithResult(taskDescription);
        if (!result.isLinkSuccess()) {
            log.warn("TripPlannerAgent A2A call failed: {}", result.getErrorMessage());
        }
        return result.getContent();
    }

    /**
     * For experiment orchestration: returns full RemoteAgentResult with link success, content, error, latency.
     */
    public RemoteAgentResult callRouteMakingAgentWithResult(String taskDescription) {
        if (Boolean.parseBoolean(System.getenv().getOrDefault("AITRIPPLAN_DEMO_ROUTE", "false"))) {
            return demoRouteResult();
        }
        return callRemoteAgentWithRetry(
                "RouteMakingAgent",
                buildRouteTask(taskDescription));
    }

    /**
     * For experiment orchestration: returns full RemoteAgentResult with link success, content, error, latency.
     */
    public RemoteAgentResult callTripPlannerAgentWithResult(String taskDescription) {
        return callRemoteAgentWithRetry(
                "TripPlannerAgent",
                buildTripTask(taskDescription));
    }

    private RemoteAgentResult callRemoteAgentWithRetry(String agentName, String prompt) {
        int attempt = 0;
        Exception lastException = null;

        while (attempt <= MAX_RETRIES) {
            if (attempt > 0) {
                log.warn("Retrying A2A call to {} (attempt {}/{})", agentName, attempt, MAX_RETRIES);
                try {
                    Thread.sleep(RETRY_DELAY.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            try {
                return callRemoteAgentOnce(agentName, prompt, attempt);
            } catch (Exception e) {
                lastException = e;
                attempt++;

                // Don't retry NacosException (service discovery failure)
                if (e instanceof NacosException) {
                    log.error("Nacos error calling {}, not retrying: {}", agentName, e.getMessage());
                    break;
                }

                // Retry IOException (network/chunked transfer errors)
                if (e instanceof IOException || hasIOExceptionCause(e)) {
                    log.warn("IO error calling {} (attempt {}): {}", agentName, attempt, e.getMessage());
                    continue;
                }

                // For other exceptions, log and don't retry
                log.error("Unexpected error calling {}: {}", agentName, e.getMessage());
                break;
            }
        }

        String errorMsg = lastException != null
                ? lastException.getClass().getSimpleName() + ": " + lastException.getMessage()
                : "Unknown error after " + attempt + " attempts";
        return RemoteAgentResult.builder(agentName)
                .linkSuccess(false)
                .errorMessage(errorMsg)
                .retryCount(attempt)
                .build();
    }

    private RemoteAgentResult callRemoteAgentOnce(String agentName, String prompt, int attempt)
            throws NacosException, IOException {

        log.info("[ROUTE_DEBUG] === A2A call to {} (attempt={}) ===", agentName, attempt);
        log.info("[ROUTE_DEBUG] prompt_length={}", prompt.length());

        long startMs = System.currentTimeMillis();

        // Blocking A2A to avoid SSE backpressure — concurrency handled by caller delay
        io.a2a.client.config.ClientConfig clientConfig = io.a2a.client.config.ClientConfig.builder()
                .setStreaming(false)
                .build();
        io.agentscope.core.a2a.agent.A2aAgentConfig a2aConfig = io.agentscope.core.a2a.agent.A2aAgentConfig.builder()
                .clientConfig(clientConfig)
                .build();

        A2aAgent agent;
        try {
            log.info("[ROUTE_DEBUG] before_nacos_discovery");
            agent = A2aAgent.builder()
                    .name(agentName)
                    .agentCardResolver(new NacosAgentCardResolver(NacosUtil.getNacosClient()))
                    .a2aAgentConfig(a2aConfig)
                    .build();
            log.info("[ROUTE_DEBUG] after_nacos_discovery agent_desc={}", agent.getDescription());
        } catch (NacosException e) {
            long latencyMs = System.currentTimeMillis() - startMs;
            log.error("[ROUTE_DEBUG] nacos_discovery_failed: {}", e.getMessage());
            throw e;
        }

        log.info("[ROUTE_DEBUG] before_agent_call (blocking)");
        Msg response;
        try {
            response = agent.call(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(List.of(TextBlock.builder().text(prompt).build()))
                            .build()
            ).block(A2A_TIMEOUT);
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - startMs;
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("[ROUTE_DEBUG] agent_call_exception: {} (cause: {})", e.getClass().getSimpleName(),
                    cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : "null");
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException("A2A reactive error: " + cause.getMessage(), cause);
        }
        log.info("[ROUTE_DEBUG] after_agent_call");

        long latencyMs = System.currentTimeMillis() - startMs;

        String responseText = response != null ? response.getTextContent() : null;
        log.info("[ROUTE_DEBUG] response_is_null={} textLen={}",
                response == null, responseText != null ? responseText.length() : -1);

        if (responseText == null || responseText.isBlank()) {
            log.warn("[ROUTE_DEBUG] collected_text_empty — SSE stream returned {} bytes",
                    responseText == null ? 0 : responseText.length());
            return RemoteAgentResult.builder(agentName)
                    .linkSuccess(false)
                    .errorMessage("Agent returned empty content via SSE stream")
                    .retryCount(attempt)
                    .latencyMs(latencyMs)
                    .build();
        }

        log.info("[ROUTE_DEBUG] response_text_preview={}",
                responseText.length() > 300 ? responseText.substring(0, 300) + "..." : responseText);

        if ("RouteMakingAgent".equals(agentName) && isMapMcpFailure(responseText)) {
            String reason = "MAP_MCP_FAILURE: route agent reported a map MCP failure; route generation stopped without retry.";
            log.warn("[ROUTE_DEBUG] {}", reason);
            return RemoteAgentResult.builder(agentName)
                    .linkSuccess(false)
                    .content("MCP_CALL_STATUS: failed\nUSED_TOOLS: map_directions\n"
                            + "ERROR: 百度地图 MCP 路线调用失败，已停止继续等待。请提供可解析的起点和终点，或在演示环境设置 AITRIPPLAN_DEMO_ROUTE=true。")
                    .errorMessage(reason)
                    .retryCount(attempt)
                    .latencyMs(latencyMs)
                    .build();
        }

        System.out.println(agentName + " 响应:");
        System.out.println(responseText);

        return RemoteAgentResult.builder(agentName)
                .linkSuccess(true)
                .content(responseText)
                .retryCount(attempt)
                .latencyMs(latencyMs)
                .build();
    }

    private boolean hasIOExceptionCause(Throwable e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof IOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isMapMcpFailure(String responseText) {
        String value = responseText == null ? "" : responseText.toLowerCase(java.util.Locale.ROOT);
        return value.contains("mcp_call_status: failed") || value.contains("mcp_tool_failed")
                || value.contains("map_directions") && (value.contains("timeout") || value.contains("error"));
    }

    /**
     * Demo-only route source. Production continues to use the RouteMakingAgent through A2A.
     * A non-empty cache file is preferred; otherwise a fixed Shenzhen--Huizhou route keeps the
     * end-to-end UI demonstrable when a live map service is unavailable.
     */
    private RemoteAgentResult demoRouteResult() {
        long started = System.currentTimeMillis();
        String content = loadDemoRouteCache();
        if (content == null) {
            content = """
                    MCP_CALL_STATUS: success
                    USED_TOOLS: demo_route_cache
                    DISTANCE: 115 km
                    DURATION: 2 h 10 min
                    TOLL: 45 yuan
                    ROUTE_SUMMARY: Shenzhen -> G15 Shenhai Expressway -> Huizhou
                    ERROR: N/A
                    SOURCE: fixed demonstration route
                    """;
        }
        return RemoteAgentResult.builder("RouteMakingAgent")
                .linkSuccess(true)
                .content(content)
                .retryCount(0)
                .latencyMs(System.currentTimeMillis() - started)
                .build();
    }

    private String loadDemoRouteCache() {
        String cacheFile = System.getenv("AITRIPPLAN_ROUTE_CACHE_FILE");
        if (cacheFile == null || cacheFile.isBlank()) return null;
        try {
            String content = Files.readString(Path.of(cacheFile), StandardCharsets.UTF_8).trim();
            if (!content.isBlank()) return content;
        } catch (Exception e) {
            log.warn("Unable to read demo route cache '{}': {}", cacheFile, e.getMessage());
        }
        return null;
    }

    private String normalizeTask(String taskDescription, String defaultTask) {
        if (taskDescription == null || taskDescription.isBlank()) {
            return defaultTask;
        }
        return taskDescription;
    }

    private String buildRouteTask(String taskDescription) {
        String task = normalizeTask(taskDescription, "请基于百度地图工具验证深圳到惠州自驾路线规划能力。");
        return """
                路线Agent。调用1次map_directions(origin=起点,destination=终点)获取驾车路线。禁止geocode。凭模型知识提取起点终点即可。200字内输出:
                MCP_CALL_STATUS: <success|failed>
                USED_TOOLS: <工具名>
                DISTANCE: <公里>
                DURATION: <小时>
                TOLL: <元>
                ROUTE_SUMMARY: <主线高速>
                ERROR: <N/A>
                /no_think
                需求:""" + task;
    }

    private String buildTripTask(String taskDescription) {
        String task = normalizeTask(taskDescription, "请验证惠州 3 日游景点、住宿和餐饮行程规划能力。");
        return """
                行程Agent。只负责每日景点住宿餐饮预算，不负责路线。精简输出300字内，直接给结果不解释。
                输出格式:
                1.每日行程 2.住宿 3.餐饮 4.预算拆分 5.注意事项
                /no_think
                用户需求:""" + task;
    }
}
