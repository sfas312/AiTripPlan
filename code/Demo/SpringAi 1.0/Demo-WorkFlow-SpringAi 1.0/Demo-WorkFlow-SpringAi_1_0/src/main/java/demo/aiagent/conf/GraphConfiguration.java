package demo.aiagent.conf;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import demo.aiagent.node.Node1;
import demo.aiagent.node.Node2;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * author: Imooc
 * description: 工作流配置类
 * date: 2025
 */

/* **********************
 *
 * 工作流涉及的对象：
 *
 * 1. OverAllState : 全局状态对象，任何节点都能够读取和修改
 * 全局状态是贯穿整个工作流
 *
 * 2. StateGraph: 工作流框架对象，
 * 工作流框架可以添加节点以及节点之间的箭头连线
 *
 * 3. CompiledGraph: 工作流的编译对象
 *
 *
 * *********************/

@Configuration
public class GraphConfiguration {

    //将链式工作流配置方法设置为Bean
    @Bean(name="parallelGraph")
    //配置链式工作流
    public StateGraph parallelGraph() throws GraphStateException {



        /* **********************
         *
         * OverAllStateFactory是一个接口，
         * 并且是一个函数式接口 (只有一个接口方法)
         *
         * lambda的 =()-> 表达式可以方便的实现函数式接口
         *
         *
         *
         * *********************/

        OverAllStateFactory overAllStateFactory =()-> {

            //创建全局状态
            OverAllState overAllState = new OverAllState();


            /* **********************
             *
             * 全局状态OverAllState以Key-Value的形式存放上下文数据
             *
             * KeyStrategy不只是指代key所对应的Value，还包括Value的更新策略：
             * 有2种更新策略：
             * 1. AppendStrategy：在旧值的基础上追加新值
             * 2. ReplaceStrategy：将新值覆盖旧值
             *
             * 更新策略是限定了节点对于OverAllState的Key所对应Value的操作
             *
             * *********************/
            Map<String, KeyStrategy> map = new HashMap<String, KeyStrategy>();
            //添加key以及Key所对应Value的更新策略
            map.put("key1", new ReplaceStrategy());
            map.put("key2", new AppendStrategy());

            //将上下文数据以Key-Value的形式注册到全局状态
            overAllState.registerKeyAndStrategy(map);

            return overAllState;
        };


        /* **********************
         *
         * StateGraph的创建需要3个参数：
         * 1. 工作流框架的名称
         * 2. 全局状态对象的工厂类
         * 3. Json框架：采用的是Gson，因为已经自动创建出来，所以不需要传入这个参数
         *
         * *********************/
        StateGraph stateGraph =  new StateGraph(
                "parallel",
                overAllStateFactory);

        /* **********************
         *
         * 添加节点,按道理是应该添加一个节点对象Node,
         * 采用
         * addNode(String,Node),
         *
         * 但是，
         * addNode(String,Node)是被
         * addNode(String id, AsyncNodeActionWithConfig actionWithConfig)
         * 所调用，
         *
         * addNode(String id, AsyncNodeActionWithConfig actionWithConfig)
         * 又被
         * addNode(String id, AsyncNodeAction action)
         * 所调用
         *
         * 所以，
         * 我们直接调用addNode(String id, AsyncNodeAction action)就可以了
         *
         * AsyncNodeAction的实例生成是采用
         * AsyncNodeAction.node_async()
         *
         * AsyncNodeAction：基于异步的节点操作
         *
         * node_async()需要传入NodeAction
         * NodeAction：是一个函数式接口
         *
         *
         * *********************/



        //添加节点, 需要传入2个参数（1.节点id，2.AsyncNodeAction）
        stateGraph.addNode("node1",AsyncNodeAction.node_async(new Node1()));
        stateGraph.addNode("node2",AsyncNodeAction.node_async(new Node2()));

        //添加节点间的箭头连线
        stateGraph.addEdge("node1","node2");

        stateGraph.addEdge(stateGraph.START,"node1");
        stateGraph.addEdge("node2",stateGraph.END);


        //以PlantUML格式打印工作流图形
        GraphRepresentation graph =
        stateGraph.getGraph(GraphRepresentation.Type.PLANTUML);

        System.out.println("##########################");
        System.out.println(graph.content());

        System.out.println("##########################");


        return stateGraph;

    }
}
