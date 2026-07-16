package managerAgent.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Direct, non-streaming Mimo client used only by the reproducible baseline runner. */
final class MimoBaselineClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    private final String apiKey;
    private final String endpoint;
    private final String model;
    private final int maxTokens;

    MimoBaselineClient(String apiKey, String baseUrl, String model, int maxTokens) {
        this.apiKey = apiKey;
        this.endpoint = baseUrl.replaceAll("/+$", "") + "/chat/completions";
        this.model = model;
        this.maxTokens = maxTokens;
    }

    Invocation generate(String systemPrompt, String userPrompt) {
        try {
            ObjectNode payload = JSON.createObjectNode();
            payload.put("model", model);
            payload.put("stream", false);
            payload.put("temperature", 0.2);
            payload.put("top_p", 0.8);
            payload.put("max_tokens", maxTokens);
            ArrayNode messages = payload.putArray("messages");
            addMessage(messages, "system", systemPrompt);
            addMessage(messages, "user", userPrompt);

            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(120))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return parse(response.statusCode(), response.body());
        } catch (Exception e) {
            return new Invocation(0, "", "", "", 0, 0, false, 0,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static void addMessage(ArrayNode messages, String role, String content) {
        ObjectNode message = messages.addObject();
        message.put("role", role);
        message.put("content", content);
    }

    private static Invocation parse(int statusCode, String rawResponse) {
        try {
            JsonNode root = JSON.readTree(rawResponse);
            JsonNode choice = root.path("choices").isArray() && root.path("choices").size() > 0
                    ? root.path("choices").get(0) : JSON.createObjectNode();
            String content = choice.path("message").path("content").asText("");
            String finishReason = choice.path("finish_reason").asText("");
            JsonNode usage = root.path("usage");
            String error = statusCode >= 200 && statusCode < 300
                    ? "" : root.path("error").path("message").asText("HTTP " + statusCode);
            return new Invocation(statusCode, rawResponse, content, finishReason,
                    usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0),
                    false, 0, error);
        } catch (Exception e) {
            return new Invocation(statusCode, rawResponse, "", "", 0, 0, false, 0,
                    "ResponseParseException: " + e.getMessage());
        }
    }

    record Invocation(int httpStatus, String rawResponse, String content, String finishReason,
                      int promptTokens, int completionTokens, boolean streaming, int accumulatedChunks,
                      String error) {
        boolean hasContent() {
            return content != null && !content.isBlank();
        }
    }
}
