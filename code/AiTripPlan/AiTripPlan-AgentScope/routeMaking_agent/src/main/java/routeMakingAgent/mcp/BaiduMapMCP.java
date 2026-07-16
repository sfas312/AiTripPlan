package routeMakingAgent.mcp;

import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Optional;

@Slf4j
public class BaiduMapMCP {

    private McpClientWrapper baiduMapMCP = null;
    private boolean mcpInitialized = false;
    private static final int DEFAULT_TIMEOUT_SECONDS = 15;
    private static final int MAX_TIMEOUT_SECONDS = 30;

    public void getBaiduMapMCP() {
        String mcpSseUrl = System.getenv("BAIDU_MAP_MCP_SSE");
        if (mcpSseUrl == null || mcpSseUrl.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: BAIDU_MAP_MCP_SSE");
        }

        baiduMapMCP = McpClientBuilder.create("BaiduMap-mcp")
                .sseTransport(mcpSseUrl)
                .timeout(timeout())
                .buildAsync()
                .block(timeout());
    }

    public McpClientWrapper initBaiduMapMCP() {
        Optional<McpClientWrapper> mcpClientWrapper = Optional.ofNullable(baiduMapMCP);
        if (mcpClientWrapper.isPresent()) {
            log.info("==================");
            log.info("Baidu Map MCP client created");
            log.info("==================");

            if (!mcpInitialized) {
                synchronized (this) {
                    if (!mcpInitialized) {
                        baiduMapMCP.initialize().block(timeout());

                        if (baiduMapMCP.isInitialized()) {
                            log.info("=============");
                            log.info("Baidu Map MCP client initialized");
                            log.info("=============");

                            baiduMapMCP.listTools().block(timeout()).forEach(tool -> {
                                log.info("==================");
                                log.info("Baidu Map MCP tool: " + tool.name());
                                log.info("==================");
                            });

                            mcpInitialized = true;
                        }
                    }
                }
            }
        }

        return baiduMapMCP;
    }

    private Duration timeout() {
        String raw = System.getenv("BAIDU_MAP_MCP_TIMEOUT_SECONDS");
        try {
            int seconds = raw == null || raw.isBlank() ? DEFAULT_TIMEOUT_SECONDS : Integer.parseInt(raw.trim());
            return Duration.ofSeconds(Math.max(1, Math.min(MAX_TIMEOUT_SECONDS, seconds)));
        } catch (NumberFormatException ignored) {
            return Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS);
        }
    }
}
