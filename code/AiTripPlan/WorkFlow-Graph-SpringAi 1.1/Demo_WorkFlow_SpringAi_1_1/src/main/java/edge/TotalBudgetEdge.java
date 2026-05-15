package edge;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.EdgeAction;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * author: Imooc
 * description: 总费用的条件判断Edge
 * date: 2026
 */

public class TotalBudgetEdge implements AsyncEdgeAction {

    //跳转到下一个节点 CompletableFuture<下一个节点的ID>
    @Override
    public CompletableFuture<String> apply(OverAllState state) {

        //取出全局状态的总费用
      Optional<Integer> toalBudget = state.value("TotalBudget",Integer.class);

      //判断总费用是否大于1000
        if(toalBudget.get() < 1000) {
            //跳到汇总节点
            return CompletableFuture.completedFuture("AggregationNode");
        }else {
            //跳到行程规划节点
            return CompletableFuture.completedFuture("TripPlannerNode");

        }

    }
}
