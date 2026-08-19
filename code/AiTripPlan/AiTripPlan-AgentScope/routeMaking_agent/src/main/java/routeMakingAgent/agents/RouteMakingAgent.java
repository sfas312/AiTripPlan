package routeMakingAgent.agents;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import routeMakingAgent.mcp.BaiduMapMCP;
import utils.AgentUtils;
import utils.ToolUtils;

import java.util.Set;

@Component
@Slf4j
public class RouteMakingAgent {

    @Bean
    public ReActAgent getRouteMakingAgent() {
        boolean disableBaiduMcp = Boolean.parseBoolean(
                System.getenv().getOrDefault("AITRIPPLAN_DISABLE_BAIDU_MCP", "false"));
        if (disableBaiduMcp) {
            log.warn("Baidu Map MCP disabled by AITRIPPLAN_DISABLE_BAIDU_MCP=true; RouteMakingAgent will use model-only route planning.");
            return AgentUtils.getReActAgentBuilder(
                            "RouteMakingAgent",
                            "只负责自驾路线制定、里程耗时、过路费和交通建议，不负责景点、住宿、餐饮和完整行程。"
                    )
                    .build();
        }

        Toolkit toolkit;
        try {
            BaiduMapMCP mcp = new BaiduMapMCP();
            mcp.getBaiduMapMCP();
            McpClientWrapper mcpClient = mcp.initBaiduMapMCP();
            toolkit = new ToolUtils().getToolkit(mcpClient);
        } catch (Exception e) {
            log.error("Baidu Map MCP unavailable; RouteMakingAgent will return an explicit route failure: {}", e.getMessage());
            return unavailableRouteAgent();
        }

        // ── Log and save tool schemas ──
        Set<String> toolNames = toolkit.getToolNames();
        log.info("=============");
        log.info("MCP Tool count: {}", toolNames.size());
        for (String name : toolNames) {
            log.info("  MCP Tool: {}", name);
        }
        log.info("=============");

        // Verify that required tools are present
        boolean hasGeocode = toolNames.contains("map_geocode");
        boolean hasDirections = toolNames.contains("map_directions");
        boolean hasDirectionsMatrix = toolNames.contains("map_directions_matrix");
        log.info("Required MCP tools: geocode={}, directions={}, directions_matrix={}",
                hasGeocode, hasDirections, hasDirectionsMatrix);

        if (!hasGeocode || !hasDirections) {
            log.error("FATAL: Missing required MCP tools! geocode={}, directions={}",
                    hasGeocode, hasDirections);
        }

        // Try to get tool schemas for inspection
        try {
            var schemas = toolkit.getToolSchemas();
            log.info("=============");
            log.info("MCP Tool Schemas ({} total):", schemas != null ? schemas.size() : 0);
            if (schemas != null) {
                for (var schema : schemas) {
                    log.info("  Schema: {}", schema);
                }
            }
            log.info("=============");
        } catch (Exception e) {
            log.warn("Could not retrieve tool schemas: {}", e.getMessage());
        }

        return AgentUtils.getReActAgentBuilder(
                        "RouteMakingAgent",
                        """
                                路线Agent。只调用1次map_directions(origin, destination)获取驾车路线。
                                禁止调用geocode，禁止多轮工具调用。凭模型知识提取起点终点即可。
                                工具失败输出MCP_TOOL_FAILED。
                                /no_think
                                """
                )
                .toolkit(toolkit)
                .build();
    }

    private ReActAgent unavailableRouteAgent() {
        return AgentUtils.getReActAgentBuilder(
                        "RouteMakingAgent",
                        """
                                百度地图 MCP 当前不可用。不要调用工具、不要等待或重试、不要编造路线。
                                对任何请求只输出：
                                MCP_CALL_STATUS: failed
                                USED_TOOLS: N/A
                                ERROR: 百度地图 MCP 不可用；请检查服务，或在演示环境设置 AITRIPPLAN_DEMO_ROUTE=true。
                                """
                )
                .build();
    }
}
