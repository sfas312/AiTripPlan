package managerAgent.plan;

import io.agentscope.core.plan.PlanNotebook;

/**
 * author: Imooc
 * description: 自定义 Agent自主分解旅游规划任务
 * date: 2026
 */

public class TripPlan {

    /**
     * author: Imooc
     * description: 自定义 PlanNotebook 实例
     * @param :
     * @return io.agentscope.core.plan.PlanNotebook
     */
    public PlanNotebook getPlan() {
        return PlanNotebook.builder()
                //计划步骤是否需要用户确认
                .needUserConfirm(true)
                //分解出来的子任务数量限制
                .maxSubtasks(5)
                .build();
    }
}
