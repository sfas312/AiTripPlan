package routeMakingAgent.mcp;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Optional;

/**
 * author: Imooc
 * description: baidu Map MCP Server
 * date: 2026
 */

@Slf4j
public class BaiduMapMCP {

    //MCP 客户端
    private McpClientWrapper baiduMapMCP = null;
    //MCP 客户端初始化
    private boolean mcpInitialized = false;

    /**
     * author: Imooc
     * description: 创建百度地图MCP客户端
     * @param :
     * @return void
     */
    //@Tool(description = "百度地图MCP Server")
    public void getBaiduMapMCP() {

//       log.info("==================");
//       log.info("正在调用百度地图MCP....");
//       log.info("==================");


        //创建MCP客户端
        baiduMapMCP = McpClientBuilder.create("BaiduMap-mcp")
                //和MCP Server以SSE方式进行通信
                .sseTransport("你的MCP地址")
                //请求超时
                .timeout(Duration.ofSeconds(120))
                //异步请求
                .buildAsync()
                .block();

    }



    /**
     * author: Imooc
     * description: 初始化百度地图MCP客户端
     * @param :
     * @return io.agentscope.core.tool.mcp.McpClientWrapper
     */
    public McpClientWrapper initBaiduMapMCP() {

        //通过Optional判断百度MCP客户端是否为null
        Optional<McpClientWrapper> mcpClientWrapper = Optional.ofNullable(baiduMapMCP);
        if(mcpClientWrapper.isPresent()) {
            log.info("==================");
            log.info("百度MCP客户端已经创建");
            log.info("==================");


            if(!mcpInitialized) {
                synchronized (this) {
                    if (!mcpInitialized) {

                        //MCP客户端初始化
                        baiduMapMCP.initialize().block();

                        //获取MCP服务端工具列表
                        if(baiduMapMCP.isInitialized()) {

                            log.info("=============");
                            log.info("百度地图MCP 客户端初始化成功！");
                            log.info("=============");

                            baiduMapMCP.listTools().block().forEach(tool -> {
                                log.info("==================");
                                log.info("百度地图MCP工具列表：" + tool.name());
                                log.info("==================");
                            });

                            mcpInitialized=true;
                        }

                    }
                }
            }

        }

        return baiduMapMCP;

    }

}
