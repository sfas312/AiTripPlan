package utils;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;

import java.util.Properties;

/**
 * author: Imooc
 * description: TODO
 * date: 2026
 */

public class NacosUtil {

    public static AiService getNacosClient() throws NacosException {

        // Keep the programmatic client aligned with the Spring configuration.
        String serverAddr = System.getenv().getOrDefault("NACOS_SERVER_ADDR", "localhost:8848");
        Properties properties = new Properties();
        properties.put(PropertyKeyConst.SERVER_ADDR, serverAddr);
        putIfPresent(properties, PropertyKeyConst.USERNAME, System.getenv("NACOS_USERNAME"));
        putIfPresent(properties, PropertyKeyConst.PASSWORD, System.getenv("NACOS_PASSWORD"));
        // 创建 Nacos Client
        return AiFactory.createAiService(properties);

    }

    private static void putIfPresent(Properties properties, String key, String value) {
        if (value != null && !value.isBlank()) {
            properties.put(key, value);
        }
    }
}
