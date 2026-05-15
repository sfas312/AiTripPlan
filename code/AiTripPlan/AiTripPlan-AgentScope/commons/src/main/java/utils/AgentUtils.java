package utils;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.Event;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import jakarta.annotation.Resource;
import org.springframework.core.env.PropertyResolver;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * author: Imooc
 * description: ReActAgent 工具类
 * date: 2026
 */


public class AgentUtils {


    /**
     * author: Imooc
     * description: 创建ReAct Agent Builder
     * @param name:
     * @param description:
     * @return io.agentscope.core.ReActAgent.Builder
     */
    public static ReActAgent.Builder getReActAgentBuilder(
            String name,
            String description
    ) {



        return ReActAgent.builder()
                        .name(name)
                        .description(description)
                        .model(DashScopeChatModel.builder()
                                //请求语言大模型的apikey
                                .apiKey("你的API_KEY")
                                //所使用的语言大模型
                                .modelName("qwen3-max")
                                .stream(true)
                                .build())
                        ;

    }

    /**
     * author: Imooc
     * description: ReAct Agent 流式响应
     * @param agent:
     * @param prompt:
     * @return reactor.core.publisher.Flux<io.agentscope.core.agent.Event>
     */
    public static Flux<Event> streamResponse(
            AgentBase agent,
            String prompt) {

       return agent.stream(
                        //Prompt
                        Msg.builder()
                                //消息角色
                                .role(MsgRole.USER)
                                //消息内容 (Prompt)
                                .content(List.of(
                                        TextBlock.builder()
                                                .text(prompt)
                                                .build()
                                ))
                                .build()
                );


    }


}
