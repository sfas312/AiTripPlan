package demo.springai.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * author: Imooc
 * description: 和大模型对话API
 * date: 2025
 */

@RestController
public class ChatController {


    /* **********************
     *
     * ChatClient是对ChatModel的封装
     *
     *
     * *********************/

    private final ChatModel chatModel;

    private final ChatClient chatClient;


    /* **********************
     *
     *
     * SpringBoot 注入方式：
     * 1. @Autowired 或 @Resource
     *
     * 2. 构造方法的参数进行注入
     *
     * *********************/


    /**
     * author: Imooc
     * description: 构造方法
     * @param chatModel:
     * @return null
     */
    public ChatController(
            ChatModel chatModel,
            ChatClient.Builder chatClient,
            ToolCallbackProvider toolCallbackProvider) {

        /* **********************
         *
         * ChatModel对象随着SpringBoot自动装配机制进行了初始化
         *
         * *********************/

        this.chatModel = chatModel;

        /* **********************
         *
         * Java 以new方式创建对象的缺点：
         *
         * 1. 如果对象有很多属性，需要一个一个属性进行设置
         * 代码看上去很丑陋
         * 例如User对象，setAge(), setBirthday(), setName()
         *
         * 2. setter方式进行属性的设置，在高并发, 多线程的环境下，
         * 造成对象状态的不一致
         *
         * 例如User对象，线程A执行setAge()，线程B执行了setBirthday()
         * 正常情况下，User对象的age属性和birthday属性是应该更新到新值，
         * 但是在高并发环境下，如果没有对线程A和线程B进行线程保护，
         * User对象的age属性和birthday属性有可能，值没有进行更新，
         *
         * 3. 如果对象有很多属性，有些属性值可能Null，这种在一些业务逻辑，
         * 不注意处理的话会报错
         *
         *
         * Java 以Builder模式创建对象的优点
         *
         * 1. Builder模式并不是直接创建对象，先创建Builder对象，
         * 然后build()创建不可变的对象，所有的属性的都是final，
         * 这样子保证了对象初始状态的一致性，
         * 而传统的New方式创建对象的属性是可以更改的
         *
         * 2. Builder模式进行属性设置，是通过链式调用，
         * 链式调用设置属性，还是要注意在高并发环境下，线程的保护
         *
         *
         * 3. 属性的默认值没有Null
         *
         *
         *
         * *********************/

        this.chatClient = chatClient.defaultToolCallbacks(toolCallbackProvider).build();
    }


    /**
     * author: Imooc
     * description: 使用ChatModel和大模型对话
     * @param :
     * @return java.lang.String
     */
    @GetMapping("/simple/chat")
    public String simpleChat() {

        String res = "";

        //用户输入
        String message = "在咖啡馆里，想要杯星巴克";
        UserMessage userMessage = new UserMessage(message);

        //提示词(Prompt)
        Prompt prompt = new Prompt(userMessage);

        //调用大模型并获取响应(文本响应)
        ChatResponse chatResponse =  chatModel.call(prompt);

        if(chatResponse.getResult() != null) {

            if(chatResponse.getResult().getOutput() != null ) {
                res = chatResponse.getResult().getOutput().getText();
            }
        }


        return res;
    }



    /**
     * author: Imooc
     * description: 使用ChatClient和大模型对话
     * @param :
     * @return java.lang.String
     */
    @GetMapping("/simple/chatclient")
    public String simpleChatByChatClient() {

        String res = "";

        //构建prompt -> 发送到大模型 -> 获取大模型返回

        //用户输入
        String message = "在咖啡馆里，想要杯星巴克";

        //链式调用

        res = this.chatClient
                .prompt(message) //构建prompt
                .call()  //发送到大模型
                .content() //获取大模型文本返回
        ;


        return res;

    }


    /**
     * author: Imooc
     * description: 询问大模型拥有哪些本地MCP服务端的工具
     * @param message:
     * @return java.lang.String
     */
    @GetMapping("/mcp/weather/")
    String generation(
            @RequestParam(value = "message",defaultValue = "你有什么工具")
            String message
    ) {
        return this.chatClient
                .prompt(message)
                .call()
                .content();
    }
}
