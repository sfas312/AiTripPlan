package tripPlannerAgent.agents;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.card.ConfigurableAgentCard;
import io.agentscope.core.a2a.server.transport.DeploymentProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import utils.AgentUtils;

/**
 * author: Imooc
 * description: 行程规划Agent
 * date: 2026
 */

@Component
public class TripPlannerAgent {

    @Bean
    public ReActAgent getTripPlannerAgent() {

        /* **********************
         *
         * 1.
         * AgentScope框架自带了注册中心： AgentScopeA2aServer
         *
         * 2.
         * AgentScope框架将智能体卡片注册到注册中心,有2种方案：
         * a. 通过SpringBoot, 以Bean的形式自动注入
         * b. 手动写入注册中心, 主要针对于AgentScopeA2aServer
         *
         *
         * *********************/



        //行程规划Agent Builder
        ReActAgent.Builder builder = AgentUtils.getReActAgentBuilder(
                "TripPlannerAgent",
                "擅长处理景点行程规划"
        );

        return builder.build();


        //=========== 手动写入注册中心，项目不用种方式 START ====

//        //行程规划Agent 智能体卡片
//        ConfigurableAgentCard agentCard =  new ConfigurableAgentCard.Builder()
//                .name("TripPlannerAgent")
//                .description("行程规划Agent")
//                .build();
//
//        //将智能体卡片写入到AgentScope自带的注册中心
//        AgentScopeA2aServer.builder(builder)
//                .agentCard(agentCard)
//                .deploymentProperties(
//                       new DeploymentProperties(
//                               "localhost",
//                               8080)
//                )
//                .build();

        //还需要AgentScopeA2aServer启动


        //======== 手动写入注册中心，项目不用种方式 END ====


    }
}
