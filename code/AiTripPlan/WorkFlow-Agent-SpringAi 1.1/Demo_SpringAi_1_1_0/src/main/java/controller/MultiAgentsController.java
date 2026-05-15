package controller;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.AgentTool;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.LlmRoutingAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.ParallelAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SequentialAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * author: Imooc
 * description: 多Agent协同
 * date: 2026
 */

@RestController
public class MultiAgentsController {

    @GetMapping(value = "/multi/agent",produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void multiAgent() throws GraphRunnerException {

        /* **********************
         *
         * 旅游规划Agent团队协同合作逻辑：
         *
         * 1. 路线制定agent 和 行程规划agent 都嵌入了 费用统筹agent
         * 2. 路线制定agent 业务执行 和 行程规划agent 业务执行 没有业务依赖关系，是并行执行的业务
         * 3. 主管agent 负责业务分发的角色，将业务分发给相应的Agent
         *
         *
         * 通过工作流Agent ( FlowAgent )将执行业务的Agent (ReActAgent) 按照流程逻辑串联起来
         *
         *
         * *********************/


        // 费用统筹agent
        ReactAgent budgetAgent =
                ReactAgent.builder()
                        .name("budgetAgent")
                        .description("你是负责统筹旅游费用")
                        .tools()
                        .build();



        // 路线制定agent
        ReactAgent routeMakingAgent =
                ReactAgent.builder()
                        .name("routeMakingAgent")
                        .description("你是负责制定旅游出行路线")
                        // 将一个ReActAgent作为工具添加到另外一个ReActAgent
                        .tools(AgentTool.getFunctionToolCallback(budgetAgent))
                        .build();


        // 行程规划agent
        ReactAgent tripPlannerAgent =
                ReactAgent.builder()
                        .name("tripPlannerAgent")
                        .description("你是负责规划旅游行程")
                        // 将一个ReActAgent作为工具添加到另外一个ReActAgent
                        .tools(AgentTool.getFunctionToolCallback(budgetAgent))
                        .build();




        // 主管agent
        ReactAgent managerAgent =
                ReactAgent.builder()
                        .name("managerAgent")
                        .description("你是负责全局统筹旅游规划")
                        .tools()
                        .build();

        // ParallelAgent是工作流Agent，不是ReAct架构的Agent
        //工作流Agent是将多个Agent按照流程逻辑组织起来
        //ParallelAgent是多个Agent按照并行执行的流程逻辑进行协同
        ParallelAgent parallelAgent =  ParallelAgent.builder()
                .name("parallelAgent")
                .description("路线制定和行程规划是并行执行")
                .subAgents(List.of(tripPlannerAgent,routeMakingAgent))
                .build();


        //LlmRoutingAgent是工作流Agent，不是ReAct架构的Agent
        //LlmRoutingAgent是让语言大模型动态的分发任务 (起到路由的作用)
        LlmRoutingAgent llmRoutingAgent = LlmRoutingAgent.builder()
                .name("llmRoutingAgent")
                .description("将旅游出行工具选择的业务和旅游景点，住宿的规划业务分发给对应的智能体去执行")
                .subAgents(List.of(tripPlannerAgent,routeMakingAgent))
                .build();


        //SequentialAgent是工作流Agent，不是ReAct架构的Agent
        //SequentialAgent将多个Agent按照顺序的流程逻辑进行协同
        //将路由Agent(llmRoutingAgent)以及并行Agent(parallelAgent)按照顺序流程逻辑串联起来
        SequentialAgent sequentialAgent = SequentialAgent.builder()
                .name("sequentialAgent")
                .description("")
                .subAgents(List.of(llmRoutingAgent,parallelAgent))
                .build();


        // 启动工作流
        Optional<OverAllState> res = sequentialAgent.invoke("帮我规划深圳到惠州的3天旅游行程");


    }
}
