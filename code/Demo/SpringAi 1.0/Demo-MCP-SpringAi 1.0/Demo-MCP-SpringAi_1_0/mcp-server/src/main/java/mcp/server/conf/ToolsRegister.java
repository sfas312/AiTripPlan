package mcp.server.conf;

import mcp.server.tools.WeatherTool;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * author: Imooc
 * description: 把MCP工具写入工具清单 (在MCP服务端进行工具注册)
 * date: 2025
 */

@Configuration
public class ToolsRegister {

    /**
     * author: Imooc
     * description: 将MCP工具写入工具清单
     * @param weatherTool:
     * @return org.springframework.ai.tool.ToolCallbackProvider
     */
    @Bean
    public ToolCallbackProvider toolList(WeatherTool weatherTool) {

        /* **********************
         *
         * MethodToolCallbackProvider是实现了ToolCallbackProvider接口
         *
         * MethodToolCallbackProvider通过Builder模式生成实例
         *
         * Java对象实例生成的方式：
         * 1. New
         * 2. Builder模式
         *
         * *********************/
        return MethodToolCallbackProvider.builder().toolObjects(weatherTool).build();
    }
}
