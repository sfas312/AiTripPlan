package utils;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.Event;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.OpenAIChatModel;
import reactor.core.publisher.Flux;

import java.util.List;

public class AgentUtils {

    private static final String DEFAULT_MODEL_NAME = "mimo-v2.5";
    private static final String DEFAULT_BASE_URL = "https://api.xiaomimimo.com/v1";

    public static ReActAgent.Builder getReActAgentBuilder(
            String name,
            String description
    ) {
        String apiKey = System.getenv("MIMO_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Missing required environment variable: MIMO_API_KEY");
        }

        String modelName = System.getenv("MIMO_MODEL_NAME");
        if (modelName == null || modelName.isBlank()) {
            modelName = DEFAULT_MODEL_NAME;
        }
        String baseUrl = System.getenv("MIMO_BASE_URL");
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }
        int maxTokens = parseInt(System.getenv("MIMO_MAX_TOKENS"), 1500);

        System.out.println("Using Mimo model: " + modelName);
        System.out.println("Using Mimo OpenAI-compatible base URL: " + baseUrl);
        System.out.println("Using Mimo max tokens: " + maxTokens);

        GenerateOptions.Builder optionsBuilder = GenerateOptions.builder()
                .temperature(0.2)
                .topP(0.8)
                .maxTokens(maxTokens);
        return ReActAgent.builder()
                .name(name)
                .description(description)
                .model(OpenAIChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(modelName)
                        .baseUrl(baseUrl)
                        .stream(false)
                        .generateOptions(optionsBuilder.build())
                        .build());
    }

    public static Flux<Event> streamResponse(
            AgentBase agent,
            String prompt
    ) {
        return agent.stream(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(List.of(
                                TextBlock.builder()
                                        .text(prompt)
                                        .build()
                        ))
                        .build()
        );
    }

    private static int parseInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

}
