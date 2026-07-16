package managerAgent.hook;

import io.agentscope.core.agent.user.UserAgent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.plan.model.Plan;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
public class planHook implements Hook {

    private final UserAgent user;
    private final PlanNotebook plan;
    private final boolean autoConfirm;

    public planHook(PlanNotebook planNotebook) {
        this.user = UserAgent.builder()
                .name("User")
                .build();
        this.plan = planNotebook;
        this.autoConfirm = Boolean.parseBoolean(System.getenv().getOrDefault("AITRIPPLAN_AUTO_CONFIRM", "false"));
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        switch (event) {
            case PreReasoningEvent e -> {
                String reason = e.getInputMessages().get(0).getTextContent();
                log.info("#### User Prompt ####");
                log.info(reason);
            }

            case PostReasoningEvent e -> {
                String reason = e.getReasoningMessage().getTextContent();
                log.info("#### Reasoning ####");
                log.info(reason);

                Plan currentPlan = plan.getCurrentPlan();
                if (currentPlan != null) {
                    if (autoConfirm) {
                        log.info("AITRIPPLAN_AUTO_CONFIRM=true, skip manual plan confirmation.");
                    } else {
                        System.out.println("Please input modification advice: ");
                        user.call().block();
                    }
                }
            }

            case PostActingEvent e -> {
                String toolName = e.getToolUse().getName();
                log.info("##### Tool Call: " + toolName);
            }

            default -> {
            }
        }

        return Mono.just(event);
    }
}
