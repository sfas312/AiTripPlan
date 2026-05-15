package com.imooc.springAi_1_1.demo.a2a.provider.service;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.a2a.A2aRemoteAgent;
import com.alibaba.cloud.ai.graph.agent.a2a.AgentCardProvider;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * author: Imooc
 * description: A2A 远程Agent的发现服务类
 * date: 2026
 */

@Service
public class AgentConsumerServ {


    @Autowired
    @Qualifier("imoocAgentCardProvider")
    private AgentCardProvider agentCardProvider;

    //发现远程的A2A Agent
    public void getAgentProvider() throws GraphRunnerException {

        /* **********************
         *
         * 这里创建的Agent，
         * 并不是用于执行业务的，
         * 执行业务的是远程的Agent，
         *
         * 这里创建的Agent，
         * 用于发现相应的远程的Agent，并获取这个远程Agent
         *
         *
         * *********************/

        //获取远程Agent
        A2aRemoteAgent agentProvider =
                A2aRemoteAgent.builder()
                        //AgentCardProvider 通过 远程Agent的name 获取指定的远程智能体卡片
                        .name("AgentProvider")
                        //必须要填
                        .description("description")
                        //必须要填
                        .instruction("instruction")
                        //远程智能体的卡片(远程Agent的元数据, 名片)
                        //远程智能体的卡片，存储在注册中心
                        //服务消费者，是通过 AgentCardProvider 对象获取注册中心的智能体卡片
                        .agentCardProvider(agentCardProvider)
                        //必须要填
                        .shareState(true)
                        .build();

        //运行远程的Agent
        Optional<OverAllState> res = agentProvider.invoke("提示词");

        /* **********************
         *
         *
         * OverAllState属于工作流的状态对象
         * 工作流每个节点的执行结果，多会更新到OverAllState对象里
         * OverAllState对象,在工作流的所有节点进行流动
         * 这样，工作流上一个节点，就可以把执行结果，传递到下一个节点
         * OverAllState是属于Key-Value
         *
         * *********************/

        res.ifPresent(state-> {
            System.out.println("远程Agent被成功调用");
        });

        //获取远程Agent执行完成任务之后的结果 (从OverAllState对象里获取回复)
//        res.flatMap(state -> state.value("key"))
//                .map(v->(String) v);
//
    }

}
