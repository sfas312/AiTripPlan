import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.classpath.ClasspathSkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

/**
 * author: Imooc
 * description: Skills Hello World
 * date: 2026
 */

public class HelloSkills {
    public static void main(String[] args) throws GraphRunnerException {


        //将 DashScope 模型平台的 ApIKey放到环境变量
        DashScopeApi dashScopeApi = DashScopeApi.builder()
                .apiKey(System.getenv("AI_DASHSCOPE_API_KEY"))
                .build();

        // 创建 ChatModel
        ChatModel chatModel = DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .build();

        //Skills.md 文件
        SkillRegistry skillsMarkdown = ClasspathSkillRegistry.builder()
                .classpathPath("skills")
                .build();


        //将Skills注册到Hook
        SkillsAgentHook skillsHook = SkillsAgentHook.builder()
                .skillRegistry(skillsMarkdown)
                .build();


        //创建 ReAct Agent
        ReactAgent agent = ReactAgent.builder()
                .name("skills-agent")
                //需要传入ChatModel对象
                .model(chatModel)
                //通过Hook挂载Skills
                .hooks(List.of())
                .build();

        //运行 Agent
        AssistantMessage response = agent.call("请介绍你有哪些技能");

        //打印出Agent的回答
        System.out.println(response.getText());
    }
}
