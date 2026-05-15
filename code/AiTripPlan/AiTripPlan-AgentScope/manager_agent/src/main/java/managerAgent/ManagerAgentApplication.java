package managerAgent;

import com.alibaba.nacos.api.exception.NacosException;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.user.UserAgent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.nacos.a2a.discovery.NacosAgentCardResolver;
import io.agentscope.core.tool.Toolkit;
import managerAgent.agents.ManagerAgent;
import managerAgent.tool.RemoteAgentTool;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import reactor.core.publisher.Flux;
import utils.AgentUtils;
import utils.NacosUtil;
import utils.ToolUtils;

import java.util.List;

/**
 * author: Imooc
 * description: 启动类
 * date: 2026
 */

//@SpringBootApplication
public class ManagerAgentApplication {
    public static void main(String[] args) throws NacosException
    {

        ManagerAgent managerAgent = new ManagerAgent();
        managerAgent.run();


































//        A2aAgent agent = A2aAgent.builder()
//                .name("TripPlannerAgent")
//                .agentCardResolver(
//                        //创建 Nacos 的 AgentCardResolver
//                        new NacosAgentCardResolver(NacosUtil.getNacosClient()))
//                .build();

//        System.out.println("智能体描述: " + agent.getDescription());


        //远程Agent运行
//        Msg response =agent.call(
//                Msg.builder()
//                        .role(MsgRole.USER)
//                        .content(TextBlock.builder()
//                                .text("说一下你这个智能体的名称")
//                                .build())
//                        .build()
//        ).block();
//
//        System.out.println("远程响应: " + response.getTextContent());
//
//        ToolUtils toolUtils = new ToolUtils();
//        //将远程Agent封装为工具的封装注册到工具包
//        Toolkit toolkit = toolUtils.getToolkit(new RemoteAgentTool());
//
//
//        ReActAgent managerAgent = AgentUtils.getReActAgentBuilder(
//                "ManagerAgent",
//                "主管Agent"
//        ).toolkit(toolkit).build();
//
//        Flux<Event> stream = AgentUtils.streamResponse(managerAgent,"调用远程的行程规划智能体");

//        agent.stream(
//                //Prompt
//                Msg.builder()
//                        //消息角色
//                        .role(MsgRole.USER)
//                        //消息内容 (Prompt)
//                        .content(List.of(
//                                TextBlock.builder()
//                                        .text("说一下TripPlannerAgent是怎样一个智能体")
//                                        .build()
//                        ))
//                        .build()
//        )
//        stream
//                .doOnNext(msg->System.out.println(msg.getMessage().getTextContent()))
//                //阻塞直到结束
//                .blockLast();







//        SpringApplication.run(ManagerAgentApplication.class, args);
    }
}
