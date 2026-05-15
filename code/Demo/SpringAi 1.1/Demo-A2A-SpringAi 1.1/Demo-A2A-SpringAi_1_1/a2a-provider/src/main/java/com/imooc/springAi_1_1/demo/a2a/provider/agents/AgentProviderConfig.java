package com.imooc.springAi_1_1.demo.a2a.provider.agents;

import com.alibaba.cloud.ai.dashscope.spec.DashScopeModel;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.imooc.springAi_1_1.demo.a2a.provider.tools.WeatherTool;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * author: Imooc
 * description: 创建agent provider
 * date: 2026
 */

@Configuration
public class AgentProviderConfig {

    @Bean
    public ReactAgent agentProvider(
            //以参数形式注入ChatModel
            ChatModel chatModel,
            WeatherTool weatherTool) {

        /* **********************
         *
         * SpringAi Alibaba 1.1 版本
         * 通过 ReactAgent的工厂方法(builder) 创建Agent,
         *
         * ReactAgent: 能够自主规划，自主决策，能够执行工具，有记忆能力，感知周边环境的智能体
         *
         *
         * *********************/


        return ReactAgent.builder()
                .name("AgentProvider")
                .description("")
                //.model需要的参数是 ChatModel,
                //ChatModel是以参数的形式注入的,
                //在后面课程会详细讲解ChatModel
                .model(chatModel)
                //先注释Agent工具配置, 在后面课程会详细讲述
                //.tool(weatherTool)
                .build();
    }
}

