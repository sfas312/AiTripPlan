package managerAgent.agents;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.tool.Toolkit;
import managerAgent.hook.planHook;
import managerAgent.plan.TripPlan;
import managerAgent.tool.RemoteAgentTool;
import reactor.core.publisher.Flux;
import utils.AgentUtils;
import utils.ToolUtils;

/**
 * author: Imooc
 * description: 主管Agent
 * date: 2026
 */

public class ManagerAgent {

    private final ReActAgent agent;

    /**
     * author: Imooc
     * description: 构造方法
     * @param :
     * @return null
     */
    public ManagerAgent() {


        //PlanNotebook
        TripPlan plan = new TripPlan();
        //Toolkit
        ToolUtils toolUtils = new ToolUtils();
        //将远程Agent封装为工具的封装注册到工具包
        Toolkit toolkit = toolUtils.getToolkit(new RemoteAgentTool());

        //计划对象
        PlanNotebook planNotebook = plan.getPlan();


        agent = AgentUtils.getReActAgentBuilder(
                "ManagerAgent",
                "主管Agent"
        )
                /* **********************
                 *
                 * ReActAgent 能自主分解复杂任务, 并且会自动生成计划步骤：
                 * 1. .enablePlan()
                 * 2. .planNotebook()
                 *
                 * .enablePlan() 内部调用了 PlanNotebook的Builder 构造方法
                 * 是采用默认的 PlanNotebook 的属性
                 *
                 * .planNotebook() 它是传入 PlanNotebook的 实例,
                 * 可以对 PlanNotebook 进行自定义
                 *
                 *
                 * PlanNotebook对象 是Agent能自主分解任务和步骤执行的核心
                 *
                 * PlanNotebook整个流程：
                 * 1. 复杂任务分解
                 * 2. 生成执行步骤
                 * 3. 状态跟踪
                 * 4. 动态调整
                 * 5. 任务完成
                 *
                 * PlanNotebook对象：自主规划 (PlanAct) + 自主决策 (ReAct)
                 *
                 *
                 *
                 *
                 * *********************/

                // .enablePlan()
                .planNotebook(planNotebook)
                //拦截器
                .hook(new planHook(planNotebook))
                //工具包
                .toolkit(toolkit)
                .build();


    }

    /**
     * author: Imooc
     * description: Agent 运行
     * @param :
     * @return void
     */
    public void run() {
        String prompt =
                """
                   帮我制定2026年元旦,
                   深圳到惠州3日游自驾游计划，
                   请包含吃住行，天气，酒店，餐饮美食。
                   
                                       
                """;


        Flux<Event> stream = AgentUtils.streamResponse(agent,prompt);

        //把响应结打印出来
        stream
                .doOnNext(msg->System.out.println(msg.getMessage().getTextContent()))
                //阻塞直到结束
                .blockLast();
    }

}
