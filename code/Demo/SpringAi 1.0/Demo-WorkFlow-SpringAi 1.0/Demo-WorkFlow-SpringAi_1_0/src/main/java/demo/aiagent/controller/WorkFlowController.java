package demo.aiagent.controller;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * author: Imooc
 * description: 工作流控制器
 * date: 2025
 */

@RestController
public class WorkFlowController {

    /* **********************
     *
     * CompiledGraph提供了2种运行工作流的方式：
     *
     * 1. invoke(): 以阻塞的方式运行工作流，工作流的结果是一次性返回
     *
     * 2. stream(); 以非阻塞，流式的方式运行工作流，
     * 执行一个节点，就返回这个节点完成的结果~
     *
     * *********************/



    //工作流编译后对象
    private final CompiledGraph compiledGraph;


    /* **********************
     *
     * @Qualifier注入指定名称的Bean
     *
     * *********************/

    public WorkFlowController(@Qualifier("parallelGraph") StateGraph graph) throws GraphStateException {

        this.compiledGraph = graph.compile();

    }

    /**
     * author: Imooc
     * description: 链式工作流的接口
     * @param :
     * @return void
     */
    @GetMapping(value = "/workflow/parallel")
    public void parallelWorkFlow() {

        String prompt = "在咖啡馆里，想要杯星巴克";

        Map<String, Object> map = new HashMap<String, Object>();
        map.put("prompt", "prompt");

        compiledGraph.invoke(map);
    }
}
