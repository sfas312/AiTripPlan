package tool;

import org.springframework.ai.chat.model.ToolContext;

import java.util.function.BiFunction;

/**
 * author: Imooc
 * description: Agent工具定义
 * date: 2026
 */

// 定义天气查询工具
public class WeatherTool implements BiFunction<String, ToolContext, String> {
    @Override
    public String apply(String city, ToolContext toolContext) {
        return "It's always sunny in " + city + "!";
    }
}