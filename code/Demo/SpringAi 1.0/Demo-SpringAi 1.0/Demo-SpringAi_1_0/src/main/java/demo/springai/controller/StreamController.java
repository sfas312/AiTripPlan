package demo.springai.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import javax.sound.midi.Sequence;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * author: Imooc
 * description: 大模型流式响应Api
 * date: 2025
 */

@RestController
public class StreamController {

    private final ChatClient chatClient;


    /**
     * author: Imooc
     * description: 构造方法
     * @param chatClient:
     * @return null
     */
    public StreamController(ChatClient.Builder chatClient) {

        /* **********************
         *
         * ChatClient对象随着SpringBoot自动装配机制进行了初始化
         *
         * *********************/

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

        this.chatClient = chatClient.build();
    }

    /**
     * author: Imooc
     * description: 简单的流式响应
     * @param :
     * @return java.lang.String
     */
    @GetMapping(value = "/simple/stream",produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat() {

        /* **********************
         *
         * 和大模型以流式响应的方式进行对话的前提条件：
         *
         * 1. SpringBoot Api 和 大模型应该是保持长连接
         *
         * 2. 大模型能够主动的向SpringBoot Api发送数据
         *
         * SpringAi 提供了 SSE协议 能方便实现和大模型以流式响应的方式进行对话
         *
         * SSE 协议
         * 1. 基于HTTP的长连接技术
         * 2. 客户端发送普通HTTP请求建立SSE长连接
         * 服务端以流式数据向客户端进行推送
         * 3. 客户端在请求头设置Accept: text/event-stream，
         * 告诉服务端需要建立SSE长连接
         * 4. 单向通信，SSE长连接建立之后
         * 客户端只负责消息的接收，客户端不能发送消息给服务端
         * 服务端只负责消息的推送，
         *
         * Flux技术：
         *
         * Java专门处理异步，流式的数据序列，数据流的容器，
         * 1. 接收Ai发送过来的逐字内容
         * 2. 逐段的发送数据
         *
         * Flux类是Java响应式编程核心类
         * WebFlux 引入了 Flux类，WebFlux也是响应式编程。
         *
         * 响应式编程：
         * 不会让线程傻傻的等待请求的处理，
         * 而是对系统说，我这个线程先去做其他的东西，
         * 你把请求处理的准备工作处理好，告诉我这个线程，这个线程就会回来处理这个请求。
         * 目标：用最少得线程，去处理大量的请求工作
         *
         * SpringBoot基于响应式编程模型 处理SSE数据流，
         * 因为SSE“持续的数据流”可以看做是响应式的事件流，
         * 响应式编程框架，提供了能够高效处理响应式的事件流的能力
         * 所以，SpringBoot基于响应式编程框架，也就是WebFlux框架，和SSE协议，是天然契合~

         *
         *
         *
         * *********************/


        //构建prompt -> 发送到大模型 -> 获取大模型返回

        //用户输入
        String message = "在咖啡馆里，想要杯星巴克";

        //链式调用

        return this.chatClient
                .prompt(message) //构建prompt
                //.call()  //发送到大模型
                .stream() //以流式响应的方式和大模型进行交互
                .content() //获取大模型文本返回
        ;


    }

    /**
     * author: Imooc
     * description: 基于SSE协议持续的发消息给前端页面
     * @param :  
     * @return reactor.core.publisher.Flux<java.lang.String>
     */
    @GetMapping(value = "/flux/interval",produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> sseToFrontPage() {

        /* **********************
         *
         * 基于SSE协议持续的发消息:
         * 1. 指定消息的MIME类型是 SSE事件类型 (text/event-stream)
         * 2. 消息要符合 SSE协议消息格式
         * event: 指定事件名称
         * data: 消息体
         * id: 消息ID
         *
         * 通过2个换行符 ( \n\n )表示一个完整事件消息结束
         *
         * 3. 能持续不断地发送SSE协议格式的事件消息
         *
         *
         * *********************/


        return Flux.interval(Duration.ofSeconds(1)) //每1秒发送SSE协议消息
                //将Map结构组装SSE协议格式消息，
                //.map(seq->"{data:{},event:{},id:{}}\\n\\n")  ;

                //通过 ServerSentEvent 组装 SSE协议格式
                .map(seq->ServerSentEvent
                    .<String>builder()
                    .event("")
                    .data("")
                    .id("")
                    .build())

                ;


    }


    /**
     * author: Imooc
     * description: 连续对话
     * @param message: 提示词
     * @param chatId:  对话ID
     * @return reactor.core.publisher.Flux<java.lang.String>
     */
    @GetMapping(value = "/chat/memory",produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatMemory(
            @RequestParam(value="message") String message,
            @RequestParam(value="chatId") String chatId
           ) {


        //构建prompt -> 发送到大模型 -> 获取大模型返回


        //链式调用

        return this.chatClient
                .prompt(message) //构建prompt

                /* **********************
                 *
                 * advisors() 相当于Spring AOP
                 * 针对单个的 ChatClient 实例
                 *
                 * 1. 请求信息的重复性工作，安全信息验证
                 * 2. 返回信息的格式化（json格式）
                 * 3. 监控(请求以及返回信息的网络延迟，网络错误), 写入日志
                 *
                 * 内置拦截器 (advisor 机制)：
                 *
                 * SimpleLoggerAdvisor：对话监控日志开启
                 * MessageChatMemoryAdvisor：多轮对话的上下文记忆开启
                 *
                 * 自定义拦截器：继承CallAdvisor, StreamAdvisor
                 *
                 *
                 * 对话管理：
                 *
                 * ChatMemory 对话历史管理对象
                 * MessageWindowChatMemory 实现了 ChatMemory接口
                 * ChatMemoryRepository：历史对话存储方式 (接口)
                 * InMemoryChatMemoryRepository 实现了 ChatMemoryRepository接口 : 将对话储存在内存
                 *
                 *
                 * *********************/

                .advisors(new Consumer<ChatClient.AdvisorSpec>() {
                    @Override
                    public void accept(ChatClient.AdvisorSpec advisorSpec) {
                        advisorSpec.param(ChatMemory.CONVERSATION_ID,chatId);
                    }
                }) //拦截器
                //.call()  //发送到大模型
                .stream() //以流式响应的方式和大模型进行交互
                .content() //获取大模型文本返回
                ;

    }
}
