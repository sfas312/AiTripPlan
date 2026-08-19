package managerAgent.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.tool.Toolkit;
import managerAgent.evaluate.QualityScorer;
import managerAgent.evaluate.QualityScorer.QualityResult;
import managerAgent.hook.planHook;
import managerAgent.plan.TripPlan;
import managerAgent.tool.RemoteAgentResult;
import managerAgent.tool.RemoteAgentTool;
import reactor.core.publisher.Flux;
import utils.AgentUtils;
import utils.ToolUtils;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.stream.Collectors;

public class ManagerAgent {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ReActAgent agent;

    public ManagerAgent() {
        TripPlan plan = new TripPlan();
        ToolUtils toolUtils = new ToolUtils();
        Toolkit toolkit = toolUtils.getToolkit(new RemoteAgentTool());
        PlanNotebook planNotebook = plan.getPlan();

        agent = AgentUtils.getReActAgentBuilder(
                        "ManagerAgent",
                        "负责拆解旅行规划任务、调度远程 Agent，并整合最终旅行方案的主管 Agent"
                )
                .planNotebook(planNotebook)
                .hook(new planHook(planNotebook))
                .toolkit(toolkit)
                .build();
    }

    // ── Entry points ───────────────────────────────────────────────────

    public void run() {
        run("""
                帮我制定 2026 年元旦，
                深圳到惠州 3 日自驾游计划，
                请包含吃住行、天气、酒店、餐饮美食。
                """);
    }

    public void run(String prompt) {
        // New experiment modes: baseline, multi_no_mcp, multi_with_mcp
        String mode = System.getenv("AITRIPPLAN_EXPERIMENT_MODE");
        if (mode != null && !mode.isBlank()) {
            runExperimentMode(prompt, mode.trim().toLowerCase());
            return;
        }

        // Legacy orchestration mode
        if (Boolean.parseBoolean(System.getenv().getOrDefault("AITRIPPLAN_EXPERIMENT_ORCHESTRATION", "false"))) {
            runExperimentOrchestration(prompt);
            return;
        }

        // Normal ReAct agent flow
        Flux<Event> stream = AgentUtils.streamResponse(agent, prompt);
        stream
                .doOnNext(msg -> System.out.println(msg.getMessage().getTextContent()))
                .blockLast();

        if (Boolean.parseBoolean(System.getenv().getOrDefault("AITRIPPLAN_VERIFY_REMOTE", "false"))) {
            verifyRemoteAgents(prompt);
        }
    }

    // ── Experiment Mode Dispatcher ─────────────────────────────────────

    /**
     * Runs a single experiment case with the given mode.
     * Modes:
     *   baseline      - Single LLM direct generation (Group A)
     *   multi_no_mcp  - Multi-Agent without Baidu Map MCP (Group B)
     *   multi_with_mcp - Multi-Agent with Baidu Map MCP enabled (Group C)
     */
    private void runExperimentMode(String prompt, String mode) {
        Map<String, Object> record = new LinkedHashMap<>();
        String caseId = System.getenv().getOrDefault("AITRIPPLAN_CASE_ID", "unknown");
        String runLabel = System.getenv().getOrDefault("AITRIPPLAN_RUN_LABEL", "").trim();
        record.put("case_id", caseId);
        record.put("mode", mode);
        record.put("run_label", runLabel);
        record.put("prompt", prompt);

        String startTime = LocalDateTime.now().format(DTF);
        record.put("start_time", startTime);

        long startMs = System.currentTimeMillis();

        try {
            switch (mode) {
                case "baseline" -> runBaseline(prompt, record);
                case "multi_no_mcp" -> runMultiAgent(prompt, record, false);
                case "multi_with_mcp" -> runMultiAgent(prompt, record, true);
                default -> {
                    record.put("error", "Unknown mode: " + mode + ". Use: baseline | multi_no_mcp | multi_with_mcp");
                    record.put("final_quality_success", false);
                }
            }
        } catch (Exception e) {
            record.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            record.put("final_quality_success", false);
        }

        long endMs = System.currentTimeMillis();
        String endTime = LocalDateTime.now().format(DTF);
        record.put("end_time", endTime);
        record.put("latency_seconds", String.format("%.1f", (endMs - startMs) / 1000.0));

        // Write JSON output
        try {
            String outputDir = System.getenv().getOrDefault("AITRIPPLAN_OUTPUT_DIR",
                    "experiments/results/outputs");
            new File(outputDir).mkdirs();
            String fileName = runLabel.isBlank()
                    ? caseId + "_" + mode + ".json"
                    : caseId + "_" + runLabel + "_" + mode + ".json";
            String jsonPath = outputDir + "/" + fileName;
            JSON.writeValue(new File(jsonPath), record);
            System.out.println("EXPERIMENT_JSON: " + jsonPath);
            System.out.println("EXPERIMENT_RESULT: " + JSON.writeValueAsString(record));
        } catch (Exception e) {
            System.err.println("Failed to write experiment JSON: " + e.getMessage());
        }
    }

    // ── Group A: Single-LLM Baseline ───────────────────────────────────

    private void runBaseline(String prompt, Map<String, Object> record) {
        System.out.println("#### Baseline Mode: Single LLM Direct Generation ####");

        String apiKey = System.getenv("MIMO_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: MIMO_API_KEY");
        }
        String modelName = System.getenv().getOrDefault("MIMO_MODEL_NAME", "mimo-v2.5");
        String baseUrl = System.getenv().getOrDefault("MIMO_BASE_URL", "https://api.xiaomimimo.com/v1");
        int maxTokens = parseInt(System.getenv("MIMO_MAX_TOKENS"), 1500);

        GenerateOptions.Builder optionsBuilder = GenerateOptions.builder()
                .temperature(0.2)
                .topP(0.8)
                .maxTokens(maxTokens);
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(baseUrl)
                .stream(false)
                .generateOptions(optionsBuilder.build())
                .build();

        // Build a comprehensive travel planning prompt
        String systemPrompt = """
                你是一个专业的旅行规划师。用户需要一份完整的自驾游旅行计划。
                请直接生成包含以下内容的完整旅行计划：
                1. 路线信息：起点、终点、推荐路线、预计里程、耗时、过路费
                2. 每日行程：景点安排、活动建议
                3. 住宿建议：推荐住宿区域或酒店类型
                4. 餐饮推荐：当地特色美食和餐厅
                5. 预算分析：各项费用估算
                6. 注意事项：天气、交通、安全提示
                请输出清晰、结构化的旅行计划，控制在 800 字以内。
                """;

        if (Boolean.parseBoolean(System.getenv().getOrDefault("MIMO_BASELINE_AUDIT_MODE", "true"))) {
            runAuditedMimoBaseline(apiKey, baseUrl, modelName, maxTokens, systemPrompt, prompt, record);
            return;
        }

        Msg systemMsg = Msg.builder()
                .role(MsgRole.SYSTEM)
                .content(List.of(TextBlock.builder().text(systemPrompt).build()))
                .build();

        Msg userMsg = Msg.builder()
                .role(MsgRole.USER)
                .content(List.of(TextBlock.builder().text(prompt).build()))
                .build();

        try {
            // Model.stream() returns Flux<ChatResponse> — collect all chunks and extract text
            List<ChatResponse> responses = model.stream(
                    List.of(systemMsg, userMsg),
                    Collections.emptyList(),  // no tools for baseline
                    null  // use default GenerateOptions from builder
            ).collectList().block();

            String content = "";
            if (responses != null) {
                content = responses.stream()
                        .flatMap(r -> r.getContent().stream())
                        .filter(b -> b instanceof TextBlock)
                        .map(b -> ((TextBlock) b).getText())
                        .collect(Collectors.joining("\n"));
            }
            System.out.println("#### Baseline Response ####");
            System.out.println(content);

            record.put("link_success", true);
            record.put("response", content);

            // Quality scoring (baseline has no separate route/trip agents)
            QualityResult qr = QualityScorer.evaluate(content, "", prompt, true, false);
            record.put("route_score", qr.routeScore());
            record.put("trip_score", qr.tripScore());
            record.put("budget_score", qr.budgetScore());
            record.put("constraint_score", qr.constraintScore());
            record.put("content_quality_score", qr.contentQualityScore());
            record.put("overall_score", qr.overallScore());
            record.put("route_quality_success", qr.routeQualitySuccess());
            record.put("trip_quality_success", qr.tripQualitySuccess());
            record.put("budget_satisfied", qr.budgetSatisfied());
            record.put("constraint_satisfied", qr.constraintSatisfied());
            record.put("final_quality_success", qr.finalQualitySuccess());

        } catch (Exception e) {
            record.put("link_success", false);
            record.put("error", "Baseline LLM call failed: " + e.getMessage());
            record.put("final_quality_success", false);
            zeroScores(record);
        }
    }

    // ── Group B/C: Multi-Agent Orchestration ───────────────────────────

    private void runAuditedMimoBaseline(
            String apiKey,
            String baseUrl,
            String modelName,
            int maxTokens,
            String systemPrompt,
            String prompt,
            Map<String, Object> record) {
        MimoBaselineClient client = new MimoBaselineClient(apiKey, baseUrl, modelName, maxTokens);
        MimoBaselineClient.Invocation first = client.generate(systemPrompt, prompt);
        MimoBaselineClient.Invocation selected = first;
        List<Map<String, Object>> attempts = new java.util.ArrayList<>();
        attempts.add(toAttemptRecord(1, first));

        int retryCount = 0;
        if (!first.hasContent()) {
            retryCount = 1;
            MimoBaselineClient.Invocation retry = client.generate(systemPrompt, prompt);
            attempts.add(toAttemptRecord(2, retry));
            selected = retry;
        }

        String content = selected.content();
        boolean generated = selected.httpStatus() >= 200
                && selected.httpStatus() < 300
                && selected.hasContent();
        record.put("model", modelName);
        record.put("base_url", baseUrl);
        record.put("max_tokens", maxTokens);
        record.put("retry_count", retryCount);
        record.put("attempts", attempts);
        record.put("http_status", selected.httpStatus());
        record.put("raw_api_response", selected.rawResponse());
        record.put("finish_reason", selected.finishReason());
        record.put("prompt_tokens", selected.promptTokens());
        record.put("completion_tokens", selected.completionTokens());
        record.put("content_char_length", content.length());
        record.put("streaming", selected.streaming());
        record.put("accumulated_stream_chunks", selected.accumulatedChunks());

        System.out.println("MIMO_BASELINE_DIAGNOSTIC: " + attempts);
        System.out.println("#### Baseline Response ####");
        System.out.println(content);

        if (generated) {
            record.put("link_success", true);
            record.put("response", content);
            QualityResult qr = QualityScorer.evaluate(content, "", prompt, true, false);
            record.put("route_score", qr.routeScore());
            record.put("trip_score", qr.tripScore());
            record.put("budget_score", qr.budgetScore());
            record.put("constraint_score", qr.constraintScore());
            record.put("content_quality_score", qr.contentQualityScore());
            record.put("overall_score", qr.overallScore());
            record.put("route_quality_success", qr.routeQualitySuccess());
            record.put("trip_quality_success", qr.tripQualitySuccess());
            record.put("budget_satisfied", qr.budgetSatisfied());
            record.put("constraint_satisfied", qr.constraintSatisfied());
            record.put("final_quality_success", qr.finalQualitySuccess());
        } else {
            record.put("link_success", false);
            record.put("response", "");
            record.put("failure_reason", selected.error().isBlank()
                    ? "empty_content_after_retry" : selected.error());
            record.put("final_quality_success", false);
            zeroScores(record);
        }
    }

    private static Map<String, Object> toAttemptRecord(int number, MimoBaselineClient.Invocation invocation) {
        Map<String, Object> attempt = new LinkedHashMap<>();
        attempt.put("attempt", number);
        attempt.put("http_status", invocation.httpStatus());
        attempt.put("raw_api_response", invocation.rawResponse());
        attempt.put("finish_reason", invocation.finishReason());
        attempt.put("prompt_tokens", invocation.promptTokens());
        attempt.put("completion_tokens", invocation.completionTokens());
        attempt.put("content_char_length", invocation.content().length());
        attempt.put("streaming", invocation.streaming());
        attempt.put("accumulated_stream_chunks", invocation.accumulatedChunks());
        attempt.put("error", invocation.error());
        return attempt;
    }

    private void runMultiAgent(String prompt, Map<String, Object> record, boolean withMcp) {
        String groupLabel = withMcp ? "Multi-Agent WITH MCP (Group C)" : "Multi-Agent WITHOUT MCP (Group B)";
        System.out.println("#### " + groupLabel + " ####");

        RemoteAgentTool remoteAgentTool = new RemoteAgentTool();
        System.out.println("#### User Prompt ####");
        System.out.println(prompt);

        // ── Route Agent ──
        RemoteAgentResult routeResult = remoteAgentTool.callRouteMakingAgentWithResult(prompt);
        record.put("route_link_success", routeResult.isLinkSuccess());
        record.put("route_agent_called", true);
        record.put("route_content", routeResult.getContent());
        record.put("route_error", routeResult.getErrorMessage());
        record.put("route_retry_count", routeResult.getRetryCount());
        record.put("route_latency_ms", routeResult.getLatencyMs());
        System.out.println("【路线 Agent 输出】");
        System.out.println(routeResult.getContent());

        // Delay to let RouteMakingAgent fully release before TripPlannerAgent starts
        try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // ── Trip Agent ──
        RemoteAgentResult tripResult = remoteAgentTool.callTripPlannerAgentWithResult(prompt);
        record.put("trip_link_success", tripResult.isLinkSuccess());
        record.put("trip_agent_called", true);
        record.put("trip_content", tripResult.getContent());
        record.put("trip_error", tripResult.getErrorMessage());
        record.put("trip_retry_count", tripResult.getRetryCount());
        record.put("trip_latency_ms", tripResult.getLatencyMs());
        System.out.println("【行程 Agent 输出】");
        System.out.println(tripResult.getContent());

        // ── Quality Evaluation ──
        String routeContent = routeResult.getContent();
        String tripContent = tripResult.getContent();
        QualityResult qr = QualityScorer.evaluate(
                routeContent, tripContent, prompt,
                routeResult.isLinkSuccess(), tripResult.isLinkSuccess());

        record.put("route_score", qr.routeScore());
        record.put("trip_score", qr.tripScore());
        record.put("budget_score", qr.budgetScore());
        record.put("constraint_score", qr.constraintScore());
        record.put("content_quality_score", qr.contentQualityScore());
        record.put("overall_score", qr.overallScore());
        record.put("route_quality_success", qr.routeQualitySuccess());
        record.put("trip_quality_success", qr.tripQualitySuccess());
        record.put("budget_satisfied", qr.budgetSatisfied());
        record.put("constraint_satisfied", qr.constraintSatisfied());
        record.put("final_quality_success", qr.finalQualitySuccess());
        record.put("link_success", routeResult.isLinkSuccess() && tripResult.isLinkSuccess());

        System.out.println("#### Quality Evaluation: " + qr + " ####");
    }

    // ── Legacy: Experiment Orchestration (backward compat) ─────────────

    private void runExperimentOrchestration(String prompt) {
        RemoteAgentTool remoteAgentTool = new RemoteAgentTool();
        try {
            System.out.println("#### User Prompt ####");
            System.out.println(prompt);
            System.out.println("#### Experiment Orchestration ####");
            String routePlan = remoteAgentTool.callRouteMakingAgent(prompt);
            String tripPlan = remoteAgentTool.callTripPlannerAgent(prompt);
            System.out.println("#### Final Multi-Agent Result ####");
            System.out.println("【路线 Agent 输出】");
            System.out.println(routePlan);
            System.out.println("【行程 Agent 输出】");
            System.out.println(tripPlan);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to run experiment orchestration through A2A/Nacos", e);
        }
    }

    private void verifyRemoteAgents(String prompt) {
        RemoteAgentTool remoteAgentTool = new RemoteAgentTool();
        try {
            remoteAgentTool.callRouteMakingAgent(prompt);
            remoteAgentTool.callTripPlannerAgent(prompt);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to verify remote agents through A2A/Nacos", e);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private void zeroScores(Map<String, Object> record) {
        for (String key : List.of("route_score", "trip_score", "budget_score", "constraint_score",
                "content_quality_score", "overall_score")) {
            record.putIfAbsent(key, 0);
        }
        for (String key : List.of("route_quality_success", "trip_quality_success", "budget_satisfied",
                "constraint_satisfied", "final_quality_success")) {
            record.putIfAbsent(key, false);
        }
    }

    private static int parseInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

}
