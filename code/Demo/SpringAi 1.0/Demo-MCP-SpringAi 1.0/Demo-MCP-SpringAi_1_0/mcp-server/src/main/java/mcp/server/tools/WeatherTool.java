package mcp.server.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * author: Imooc
 * description: 天气工具的业务逻辑定义
 * date: 2025
 */

@Component
public class WeatherTool {

    /* **********************
     *
     * 疑问：
     * 1. 大模型如何知道能够使用哪些工具
     * 2. 大模型如何知道选择对应工具
     *
     * @Tool注解：
     *
     * 1. 修饰的方法，告诉大模型，你能够使用这个方法
     * 2. @Tool的description属性非常重要，必须要写，
     * 大模型根据description的描述判断何时以及如何使用这个工具
     *
     * @ToolParam 注解：
     * 定义方法的参数
     *
     * *********************/

    @Tool(description = "获取指定城市当前时间的温度")
    public String getTemperature(@ToolParam String cityName) {
        return  cityName    + "温度值是30度";
    }

    @Tool(description = "获取指定城市的紫外线值")
    public String getUltraviolet(@ToolParam String cityName) {
        return cityName + "紫外线值：";
    }
}
