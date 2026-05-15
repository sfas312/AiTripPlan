package com.imooc.springAi_1_1.demo.a2a.provider.controller;

import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.imooc.springAi_1_1.demo.a2a.provider.service.AgentConsumerServ;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * author: Imooc
 * description: 调用远程Agent的控制器
 * date: 2026
 */

@RestController
public class AgentConsumerController {

    @Autowired
    private AgentConsumerServ serv;

    //把要调用的远程Agent的智能体卡片属性(name)告诉注册中心
    @GetMapping("/demo/a2a/consumer")
    public void getRemoteAgent() throws GraphRunnerException {

        serv.getAgentProvider();
    }
}
