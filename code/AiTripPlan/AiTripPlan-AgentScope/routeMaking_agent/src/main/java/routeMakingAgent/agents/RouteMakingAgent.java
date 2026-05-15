package routeMakingAgent.agents;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import routeMakingAgent.mcp.BaiduMapMCP;
import utils.AgentUtils;
import utils.ToolUtils;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * author: Imooc
 * description: 路线制定Agent
 * date: 2026
 */

@Component
@Slf4j
public class RouteMakingAgent {


    @Bean
    public ReActAgent getRouteMakingAgent() {

        BaiduMapMCP mcp = new BaiduMapMCP();
        //创建百度地图MCP客户端
        mcp.getBaiduMapMCP();
        //初始化百度地图MCP客户端
        McpClientWrapper mcpClient = mcp.initBaiduMapMCP();

        //Toolkit
        ToolUtils toolUtils = new ToolUtils();
        Toolkit toolkit = toolUtils.getToolkit(mcpClient);

        //打印挂载的工具
        Set<String> toolNames = toolkit.getToolNames();
        log.info("=============");
        toolNames.stream().forEach(
                value -> log.info("挂载的工具名称："+value)
        );
        log.info("=============");



        //注入到Nacos
        return AgentUtils.getReActAgentBuilder(
                "RouteMakingAgent",
                "擅长处理自驾游路线制定"
        )
                //工具包
                .toolkit(toolkit)
                .build();
    }


}
