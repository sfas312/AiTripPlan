package managerAgent.tool;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.nacos.a2a.discovery.NacosAgentCardResolver;
import io.agentscope.core.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import utils.AgentUtils;
import utils.NacosUtil;

import java.util.Properties;

/**
 * author: Imooc
 * description: 将远程Agent封装为工具
 * date: 2026
 */

@Slf4j
public class RemoteAgentTool {

    /**
     * author: Imooc
     * description: 基于A2A协议获取路线制定Agent
     * @param :
     * @return void
     */
    @Tool(description = "从Nacos注册中心获取路线制定Agent")
    public void callRouteMakingAgent() throws NacosException {

        log.info("============");
        log.info("工具方法：路线制定智能体...正在调用中");
        log.info("============");

        A2aAgent agent = A2aAgent.builder()
                .name("RouteMakingAgent")
                .agentCardResolver(
                        //创建 Nacos 的 AgentCardResolver
                        new NacosAgentCardResolver(NacosUtil.getNacosClient()))
                .build();

        log.info("============");
        log.info("获取到的远程Agent描述："+agent.getDescription());
        log.info("============");

        //远程Agent运行
        agent.call().block();

//        Flux<Event> stream = AgentUtils.streamResponse(
//                agent,
//                "调用百度地图MCP");
//
//        stream
//                .doOnNext(msg->System.out.println(msg.getMessage().getTextContent()))
//                //阻塞直到结束
//                .blockLast();



    }

    /**
     * author: Imooc
     * description: 基于A2A协议获取行程规划Agent
     * @param :
     * @return void
     */
    @Tool(description = "从Nacos注册中心获取行程规划Agent")
    public void callTripPlannerAgent() throws NacosException {

        A2aAgent agent = A2aAgent.builder()
                .name("TripPlannerAgent")
                .agentCardResolver(
                        //创建 Nacos 的 AgentCardResolver
                        new NacosAgentCardResolver(NacosUtil.getNacosClient()))
                .build();


        //远程Agent运行
        agent.call().block();

    }
}
