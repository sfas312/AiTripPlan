package com.imooc.springAi_1_1.demo.a2a.provider.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * author: Imooc
 * description: 天气工具
 * date: 2026
 */

@Component
public class WeatherTool {

    @Tool(description = "获取天气")
    public void getWeather(String city) {

        System.out.println("正在获取" + city + "天气信息...");
    }
}
