package routeMakingAgent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import routeMakingAgent.agents.RouteMakingAgent;
import routeMakingAgent.mcp.BaiduMapMCP;

/**
 * author: Imooc
 * description: 启动类
 * date: 2026
 */

@SpringBootApplication
public class RouteMakingAgentApplication {
    public static void main(String[] args) {

        SpringApplication.run(RouteMakingAgentApplication.class, args);
    }
}
