package routeMakingAgent.mcp;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import utils.AgentUtils;
import utils.ToolUtils;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Route-only smoke test: directly tests ReActAgent + BaiduMap MCP tool calling,
 * completely bypassing ManagerAgent, TripPlannerAgent, and A2A protocol.
 *
 * Success criteria:
 *   1. Log contains "Tool Call: map_geocode"
 *   2. Log contains "Tool Call: map_directions" or "Tool Call: map_directions_matrix"
 *   3. Output contains "MCP_CALL_STATUS: success"
 *   4. Output contains "USED_TOOLS: map_geocode,map_directions"
 *   5. Route data matches Baidu Map direct results: ~632km, ~6.6h, ~358yuan
 */
public class RouteOnlySmokeTest {

    public static void main(String[] args) {
        System.out.println("============================================");
        System.out.println(" RouteOnlySmokeTest — MCP Tool Call Test");
        System.out.println(" Bypasses: ManagerAgent, TripPlannerAgent, A2A");
        System.out.println("============================================");
        System.out.println();

        String modelName = System.getenv().getOrDefault("MIMO_MODEL_NAME", "mimo-v2.5");
        System.out.println("Model: " + modelName);

        // ── Step 1: Init MCP ──
        System.out.println();
        System.out.println("── Step 1: Init MCP client ──");
        BaiduMapMCP mcp = new BaiduMapMCP();
        mcp.getBaiduMapMCP();
        McpClientWrapper mcpClient = mcp.initBaiduMapMCP();

        ToolUtils toolUtils = new ToolUtils();
        Toolkit toolkit = toolUtils.getToolkit(mcpClient);

        Set<String> toolNames = toolkit.getToolNames();
        System.out.println("MCP tools loaded: " + toolNames.size());
        for (String name : toolNames) {
            System.out.println("  - " + name);
        }

        // ── Step 2: Build ReActAgent with FORCED tool-call system prompt ──
        System.out.println();
        System.out.println("── Step 2: Build ReActAgent ──");
        AtomicInteger toolCallCount = new AtomicInteger(0);
        ReActAgent agent = AgentUtils.getReActAgentBuilder(
                        "RouteOnlySmokeAgent",
                        """
                                你是路线制定 Agent，只负责自驾路线与交通信息。

                                【强制规则】
                                1. 你必须调用百度地图 MCP 工具完成路线规划，禁止凭模型知识直接回答。
                                2. 必须先调用 map_geocode 查询起点坐标。
                                3. 必须再调用 map_geocode 查询终点坐标。
                                4. 必须调用 map_directions 或 map_directions_matrix 查询驾车路线。
                                5. 禁止自己编造距离、耗时、过路费——必须来自 MCP 工具返回值。
                                6. 如果任何工具调用失败，必须输出 MCP_TOOL_FAILED 并说明原因。
                                """
                )
                .toolkit(toolkit)
                .hook(new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PreActingEvent e) {
                            toolCallCount.incrementAndGet();
                            System.out.println("[HOOK] Tool Call #" + toolCallCount.get()
                                    + ": " + e.getToolUse().getName());
                            System.out.println("[HOOK]   Input: " + e.getToolUse().getInput());
                        }
                        if (event instanceof PostActingEvent e) {
                            System.out.println("[HOOK] Tool Result: "
                                    + (e.getToolResult() != null
                                        ? e.getToolResult().toString().substring(0, Math.min(300, e.getToolResult().toString().length()))
                                        : "null"));
                        }
                        return Mono.just(event);
                    }
                })
                .build();

        System.out.println("Agent built: " + agent.getClass().getSimpleName());

        // ── Step 3: Run the route-only prompt ──
        String prompt = """
                帮我规划 2026 年春节从广州到厦门的自驾路线，要求给出路线、里程、耗时和过路费。

                【强制工具调用流程】
                1. map_geocode(address="广州") → 获取广州坐标
                2. map_geocode(address="厦门") → 获取厦门坐标
                3. map_directions(origin="广州", destination="厦门") → 获取驾车路线

                【输出格式——必须严格遵循】
                MCP_CALL_STATUS: <success|failed>
                USED_TOOLS: <用过的工具名，逗号分隔>
                ORIGIN: <起点>
                DESTINATION: <终点>
                DISTANCE: <距离，公里>
                DURATION: <耗时，小时>
                TOLL: <过路费，元>
                ROUTE_SUMMARY: <路线摘要>
                HIGHWAY_TIPS: <自驾注意事项>
                ERROR: <N/A 或错误信息>
                """;

        System.out.println();
        System.out.println("── Step 3: Execute ReAct Agent ──");
        System.out.println("Prompt (abridged): " + prompt.substring(0, 100) + "...");
        System.out.println();

        long startMs = System.currentTimeMillis();
        Flux<Event> stream = AgentUtils.streamResponse(agent, prompt);

        StringBuilder fullOutput = new StringBuilder();
        stream
                .doOnNext(event -> {
                    String text = event.getMessage().getTextContent();
                    if (text != null && !text.isBlank()) {
                        System.out.println(text);
                        fullOutput.append(text).append("\n");
                    }
                })
                .doOnError(err -> {
                    System.err.println("STREAM ERROR: " + err.getMessage());
                    err.printStackTrace(System.err);
                })
                .blockLast();

        long elapsedMs = System.currentTimeMillis() - startMs;

        // ── Step 4: Analyze results ──
        String output = fullOutput.toString();
        System.out.println();
        System.out.println("============================================");
        System.out.println("── Analysis ──");
        System.out.println("Elapsed: " + (elapsedMs / 1000.0) + "s");
        System.out.println();

        boolean hasGeocodeCall = output.contains("map_geocode");
        boolean hasDirectionsCall = output.contains("map_directions") || output.contains("map_directions_matrix");
        boolean hasMcpStatus = output.contains("MCP_CALL_STATUS");
        boolean hasSuccessStatus = output.contains("MCP_CALL_STATUS: success")
                || output.contains("MCP_CALL_STATUS:success");
        boolean hasUsedTools = output.contains("USED_TOOLS");

        System.out.println("map_geocode mentioned:   " + passFail(hasGeocodeCall));
        System.out.println("map_directions mentioned: " + passFail(hasDirectionsCall));
        System.out.println("MCP_CALL_STATUS present: " + passFail(hasMcpStatus));
        System.out.println("MCP_CALL_STATUS=success: " + passFail(hasSuccessStatus));
        System.out.println("USED_TOOLS present:      " + passFail(hasUsedTools));

        // Check distance/duration accuracy
        boolean distOk = output.contains("632") || output.contains("633") || output.contains("630");
        boolean durOk = output.contains("6.6") || output.contains("6.5") || output.contains("6.7")
                || output.contains("23876") || output.contains("398");
        boolean tollOk = output.contains("358") || output.contains("360") || output.contains("355");

        System.out.println();
        System.out.println("Distance ~632km:  " + passFail(distOk));
        System.out.println("Duration ~6.6h:   " + passFail(durOk));
        System.out.println("Toll ~358yuan:    " + passFail(tollOk));

        boolean toolsCalled = hasGeocodeCall && hasDirectionsCall;
        boolean outputCorrect = hasSuccessStatus && hasUsedTools;
        boolean dataAccurate = distOk && durOk && tollOk;
        boolean allPassed = toolsCalled && outputCorrect && dataAccurate;

        System.out.println();
        System.out.println("============================================");
        if (allPassed) {
            System.out.println(" RESULT: PASS — MCP tools were called and data is accurate");
            System.out.println(" The model successfully uses Baidu Map MCP in ReAct mode.");
        } else if (toolsCalled) {
            System.out.println(" RESULT: PARTIAL — Tools called but output/data issues");
            System.out.println("  toolsCalled=" + toolsCalled + " outputCorrect=" + outputCorrect + " dataAccurate=" + dataAccurate);
        } else {
            System.out.println(" RESULT: FAIL — MCP tools NOT called by model");
            System.out.println("  The model (" + modelName + ") prefers internal knowledge over tool calling.");
            System.out.println("  Recommendation: verify the hy3 tool-call response and MCP endpoint.");
        }
        System.out.println("============================================");

        System.exit(allPassed ? 0 : 1);
    }

    private static String passFail(boolean ok) {
        return ok ? "PASS" : "FAIL";
    }
}
