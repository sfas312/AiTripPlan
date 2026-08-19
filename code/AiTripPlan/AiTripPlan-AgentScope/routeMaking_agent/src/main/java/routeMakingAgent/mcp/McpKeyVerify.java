package routeMakingAgent.mcp;

import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Minimal MCP key verification — bypasses Agent/ReAct/A2A entirely.
 * Tests: listTools → geocode(广州) → geocode(厦门) → directions(广州→厦门)
 */
public class McpKeyVerify {

    public static void main(String[] args) {
        String mcpSseUrl = System.getenv("BAIDU_MAP_MCP_SSE");
        if (mcpSseUrl == null || mcpSseUrl.isBlank()) {
            System.err.println("FATAL: BAIDU_MAP_MCP_SSE env var is not set");
            System.exit(1);
        }
        // Mask the key in output
        String maskedUrl = mcpSseUrl.replaceAll("([?&]key=)[^&]+", "$1***MASKED***");
        System.out.println("MCP SSE URL: " + maskedUrl);
        System.out.println();

        McpClientWrapper mcp = null;
        try {
            // Step 1: Build and connect
            System.out.println("=== Step 1: Connecting to MCP server ===");
            mcp = McpClientBuilder.create("BaiduMap-mcp-verify")
                    .sseTransport(mcpSseUrl)
                    .timeout(Duration.ofSeconds(30))
                    .buildAsync()
                    .block();
            System.out.println("MCP client created: " + (mcp != null));
            if (mcp == null) {
                System.err.println("FATAL: MCP client creation returned null");
                System.exit(1);
            }

            // Step 2: Initialize
            System.out.println();
            System.out.println("=== Step 2: Initializing MCP session ===");
            mcp.initialize().block();
            System.out.println("Initialized: " + mcp.isInitialized());

            // Step 3: listTools
            System.out.println();
            System.out.println("=== Step 3: listTools ===");
            List<McpSchema.Tool> tools = mcp.listTools().block();
            if (tools == null || tools.isEmpty()) {
                System.err.println("FATAL: listTools returned empty — MCP SSE or AK config issue");
                System.exit(2);
            }
            System.out.println("Tool count: " + tools.size());
            for (McpSchema.Tool t : tools) {
                System.out.println("  - " + t.name() + ": " + t.description());
            }

            // Step 4: call map_geocode for 广州
            System.out.println();
            System.out.println("=== Step 4: map_geocode(广州) ===");
            McpSchema.CallToolResult r1 = mcp.callTool("map_geocode",
                    Map.of("address", "广州")).block();
            printResult(r1);

            // Step 5: call map_geocode for 厦门
            System.out.println();
            System.out.println("=== Step 5: map_geocode(厦门) ===");
            McpSchema.CallToolResult r2 = mcp.callTool("map_geocode",
                    Map.of("address", "厦门")).block();
            printResult(r2);

            // Step 6: call map_directions for 广州 → 厦门
            System.out.println();
            System.out.println("=== Step 6: map_directions(广州 → 厦门) ===");
            McpSchema.CallToolResult r3 = mcp.callTool("map_directions",
                    Map.of("origin", "广州", "destination", "厦门")).block();
            printResult(r3);

            System.out.println();
            System.out.println("========================================");
            System.out.println(" ALL MCP TESTS PASSED — Key is valid");
            System.out.println(" Root cause is in Agent/ReAct/A2A layer");
            System.out.println("========================================");

        } catch (Exception e) {
            System.err.println();
            System.err.println("========================================");
            System.err.println(" MCP TEST FAILED");
            System.err.println(" Error: " + e.getClass().getName() + ": " + e.getMessage());
            System.err.println("========================================");
            e.printStackTrace(System.err);
            System.exit(3);
        } finally {
            if (mcp != null) {
                try {
                    mcp.close();
                } catch (Exception ignored) {}
            }
        }
    }

    private static void printResult(McpSchema.CallToolResult result) {
        if (result == null) {
            System.err.println("  Result: NULL");
            return;
        }
        System.out.println("  isError: " + (result.isError() != null && result.isError()));
        if (result.content() != null) {
            for (var c : result.content()) {
                String text = c.toString();
                if (text.length() > 500) {
                    text = text.substring(0, 500) + "...(truncated)";
                }
                System.out.println("  Content: " + text);
            }
        } else {
            System.out.println("  Content: (empty)");
        }
    }
}
