package com.imooc.springAi_1_1.demo.a2a.provider;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * author: Imooc
 * description: A2A Provider启动类
 * date: 2026
 */

/* **********************
 *
 * Google 提出的 A2A协议 用于不同大语言模型的Agent 之间进行通信
 *
 * SpringAi alibaba 1.1 提供了实现A2A协议的Api
 * 这里的A2A协议除了用于不同模型的Agent 之间进行通信，
 * 也能用于同一个大语言模型，但位于不同节点(分布式)的Agent 之间的通信
 *
 * 有3个核心组件
 * A2A Provider (服务提供): ReActAgent
 * 注册中心 ( 阿里的Nacos 3.1 ) (服务注册 )
 * A2A Consumer ( 服务发现 ):  A2aRemoteAgent
 *
 * A2A协议的流程
 * 1. 服务提供者 将智能体卡片 存储在 注册中心
 * 2. 服务发现者 通过 AgentCardProvider 基于远程智能体name 获取 对应的智能体卡片
 * 3. 注册中心根据服务发现者所获取的智能体卡片，告诉远程Agent, 去执行服务发现者 所提供的任务
 *
 *
 * 智能体卡片：
 * 1. name 远程智能体的name
 * 2. description  远程智能体的描述
 * 3. url 注册中心的地址
 * 4. version 远程智能体的版本
 * 5. capabilities 远程智能体的能力
 * 6. skills 远程智能体的专业领域技能
 *
 *
 * *********************/



@SpringBootApplication
public class A2AProvider {
    public static void main(String[] args) {
        SpringApplication.run(A2AProvider.class, args);
    }
}
