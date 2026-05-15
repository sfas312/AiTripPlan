import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.hook.*;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Toolkit;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.WeatherTool;

import java.util.List;

/**
 * author: Imooc
 * description: AgentScope ReActAgent Hello World
 * date: 2026
 */

/* **********************
 *
 * AgentScope是以 Application形式 启动的
 * 不是通过web接口访问的
 *
 * *********************/

public class HelloAgent {
    public static void main(String[] args) {

        //将阿里大模型平台(DashCope)的apikey放到Application的环境变量
        //获取Application环境变量的apikey
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("环境变量 DASHSCOPE_API_KEY 未设置");
            System.exit(1);
        }

        //创建工具包
        Toolkit toolkit = new Toolkit();
        //把工具添加到工具包，能自动扫描@Tool所注释的方法，作为Agent的工具
        toolkit.registerTool(new WeatherTool());


        ReActAgent agent =
                ReActAgent.builder()
                        .name("HelloAgent")
                        .description("AgentScope ReActAgent Hello World")

                        /* **********************
                         *
                         *  SpringAi Alibaba 1.1 .model()的参数ChatModel 对象
                         *  AgentScope .model()的参数 Model 对象
                         *
                         * DashScopeChatModel 实现了 Model 对象这个接口
                         *
                         * .formatter()的参数Formatter 接口
                         * AbstractBaseFormatter是实现了Formatter 接口
                         * DashScopeChatFormatter继承了AbstractBaseFormatter
                         *
                         * *********************/

                        .model(DashScopeChatModel.builder()
                                //请求语言大模型的apikey
                                .apiKey(apiKey)
                                //所使用的语言大模型
                                .modelName("qwen3-max")
                                //是否开启思考模式
                                .enableThinking(true)
                                //是否流式返回结果
                                .stream(true)
                                //返回结果的格式化
                                .formatter(new DashScopeChatFormatter())
                                .build())

                        /* **********************
                         *
                         *  SpringAi Alibaba 1.1 配置工具使用.tools()
                         *  AgentScope 配置工具使用.toolkit()
                         *
                         * *********************/

                        //配置工具包
                        .toolkit(toolkit)
                        .sysPrompt(
                           """
                           你是一个AI助手。
                           """
                        )
                        //赋予Agent记忆能力
                        .memory(new InMemoryMemory())
                        .build();


        System.out.println("############ 等待响应...\n");




        //运行Agent
        agent.stream(
                //Prompt
                Msg.builder()
                        //消息角色
                        .role(MsgRole.USER)
                        //消息内容 (Prompt)
                        .content(List.of(
                                TextBlock.builder()
                                        .text("你好!")
                                        .build()
                        ))
                        // 消息内容 (发送文字形式的Prompt )
                        //.textContent("")
                        .build()
                    )
                //把响应结打印出来
                .doOnNext(msg->System.out.println(msg.getMessage().getTextContent()))
                //阻塞直到结束
                .blockLast();


    }
}
