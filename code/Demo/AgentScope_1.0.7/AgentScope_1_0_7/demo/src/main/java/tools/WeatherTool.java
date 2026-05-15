package tools;


import io.agentscope.core.tool.Tool;

/**
 * author: Imooc
 * description: 天气工具定义
 * date: 2026
 */


public class WeatherTool  {

    @Tool(name = "get_weather",description = "查询天气")
    // 天气查询工具
    public String getWeather()
    {
        return "深圳天气24度";
    }
}