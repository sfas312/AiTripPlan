package managerAgent.controller;

import managerAgent.evaluate.QualityScorer;
import managerAgent.evaluate.QualityScorer.QualityResult;
import managerAgent.tool.RemoteAgentResult;
import managerAgent.tool.RemoteAgentTool;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class AppController {

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @PostMapping("/api/plan")
    public ResponseEntity<?> createPlan(@RequestBody PlanRequest request) {
        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Prompt 不能为空"));
        }

        try {
            String prompt = request.prompt().trim();
            RemoteAgentTool remoteAgentTool = new RemoteAgentTool();
            RemoteAgentResult routeResult = remoteAgentTool.callRouteMakingAgentWithResult(prompt);
            RemoteAgentResult tripResult = remoteAgentTool.callTripPlannerAgentWithResult(prompt);

            String routeContent = cleanModelOutput(routeResult.getContent());
            String tripContent = cleanModelOutput(tripResult.getContent());
            boolean routeSuccess = executedSuccessfully(routeResult);
            boolean tripSuccess = executedSuccessfully(tripResult);
            QualityResult quality = QualityScorer.evaluate(
                    routeContent, tripContent, prompt, routeSuccess, tripSuccess);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", quality.finalQualitySuccess());
            response.put("route", agentPayload(routeResult, routeContent, routeSuccess));
            response.put("trip", agentPayload(tripResult, tripContent, tripSuccess));
            response.put("metrics", Map.of(
                    "overallScore", quality.overallScore(),
                    "routeScore", quality.routeScore(),
                    "tripScore", quality.tripScore(),
                    "budgetScore", quality.budgetScore(),
                    "constraintScore", quality.constraintScore(),
                    "finalQualitySuccess", quality.finalQualitySuccess()));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "规划请求失败: " + e.getMessage()));
        }
    }

    private Map<String, Object> agentPayload(
            RemoteAgentResult result, String content, boolean success) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", success);
        payload.put("content", content);
        payload.put("latencyMs", result.getLatencyMs());
        payload.put("retryCount", result.getRetryCount());
        payload.put("error", result.getErrorMessage());
        return payload;
    }

    private boolean executedSuccessfully(RemoteAgentResult result) {
        return result.isLinkSuccess()
                && !result.getContent().startsWith("Handle Agent execute error:");
    }

    private String cleanModelOutput(String content) {
        if (content == null) {
            return "";
        }
        return content.replaceFirst("(?s)^\\s*<think>.*?</think>\\s*", "").trim();
    }

    public record PlanRequest(String prompt) { }
}
