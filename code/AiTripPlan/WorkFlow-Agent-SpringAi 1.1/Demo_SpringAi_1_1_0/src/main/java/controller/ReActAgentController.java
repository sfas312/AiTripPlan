package controller;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import tool.WeatherTool;

import java.util.function.BiFunction;

/**
 * author: Imooc
 * description: 通过ReActAgent和大模型Api
 * date: 2025
 */

@RestController
public class ReActAgentController {


    /* **********************
     *
     * SpringAi Alibaba 1.0 版本
     * 通过ChatClient对象 和 语言大模型进行交互 的方式，
     * 在 SpringAi Alibaba 1.1 版本 里同样适用
     *
     *
     * *********************/


    /**
     * author: Imooc
     * description: 简单的流式响应
     * @param :
     * @return java.lang.String
     */
    @GetMapping(value = "/simple/agent",produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void streamChat() throws GraphRunnerException {

        //用户输入
        String message = "在咖啡馆里，想要杯星巴克";


        //工具方法
        ToolCallback weatherTool = FunctionToolCallback.builder("get_weather", new WeatherTool())
                .description("Get weather for a given city")
                .inputType(String.class)
                .build();

        /* **********************
         *
         *
         * SpringAi Alibaba 1.0 还处于和大模型对话阶段 ( ChatClinet )
         *
         * SpringAi Alibaba 1.1
         * 进入到 Agent 自主决策与自主执行 的时代:
         *
         * ReActAgent 除了具备了大脑, 工具使用, 记忆能力，环境感知,
         * 还具备2个能力：
         * 规划能力 ( 复杂任务分解 )，
         * 自主决策能力
         *
         *
         * Agentic AI（智能体式AI）： 多个 ReActAgent 的协调合作。
         * 是一种设计范式，
         * 强调将AI系统构建为具备自主性、适应性和协作能力的智能体集合。
         * 其核心目标是通过多Agent协同解决复杂问题
         *
         *
         *
         *
         *
         * *********************/


        // 创建 agent
        ReactAgent agent = ReactAgent.builder()
                // Agent的名称 (必须)
                .name("weather_agent")
                // Agent的大脑 (语言大模型)
                .model(chatModel)
                // Agent的手脚(调用工具)
                .tools(weatherTool)
                // Agent的眼睛和耳朵(RAG)
                //.hooks()
                // 系统提示词
                .systemPrompt("You are a helpful assistant")
                // Agent的记忆 (多轮对话)
                //.saver(new MemorySaver())
                .build();


        //一次性的回答返回

//        AssistantMessage assistantMessage = agent.call(message);
//        //获取回答
//        assistantMessage.getText();


        //流式返回

        Flux<NodeOutput> stream = agent.stream(message);

        stream.subscribe(
                output -> {
                    // 检查是否为 StreamingOutput 类型
                    if (output instanceof StreamingOutput streamingOutput) {
                        OutputType type = streamingOutput.getOutputType();

                        // 处理模型推理的流式输出
                        if (type == OutputType.AGENT_MODEL_STREAMING) {
                            // 流式增量内容，逐步显示
                            System.out.print(streamingOutput.message().getText());
                        } else if (type == OutputType.AGENT_MODEL_FINISHED) {
                            // 模型推理完成，可获取完整响应
                            System.out.println("\n模型输出完成");
                        }

                        // 处理工具调用完成（目前不支持 STREAMING）
                        if (type == OutputType.AGENT_TOOL_FINISHED) {
                            System.out.println("工具调用完成: " + output.node());
                        }

                        // 对于 Hook 节点，通常只关注完成事件（如果Hook没有有效输出可以忽略）
                        if (type == OutputType.AGENT_HOOK_FINISHED) {
                            System.out.println("Hook 执行完成: " + output.node());
                        }
                    }
                },
                error -> System.err.println("错误: " + error),
                () -> System.out.println("Agent 执行完成")
        );

    }

}
