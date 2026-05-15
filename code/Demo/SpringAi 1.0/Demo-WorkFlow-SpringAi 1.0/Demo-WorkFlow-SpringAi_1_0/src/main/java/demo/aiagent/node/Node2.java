package demo.aiagent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;

import java.util.HashMap;
import java.util.Map;

/**
 * author: Imooc
 * description: Key1节点生成
 * date: 2025
 */

public class Node2 implements NodeAction {

    /**
     * author: Imooc
     * description: 节点对于全局状态的操作
     * @param state:
     * @return java.util.Map<java.lang.String,java.lang.Object>
     */
    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {

        //根据key获取全局状态中的值
        Object object = state.value("key1");

        Map<String, Object> map = new HashMap<String, Object>();
        map.put("key1",object);

        return map;
    }
}
