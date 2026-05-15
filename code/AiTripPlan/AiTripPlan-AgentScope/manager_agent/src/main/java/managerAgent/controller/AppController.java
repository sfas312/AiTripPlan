package managerAgent.controller;

import managerAgent.agents.ManagerAgent;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * author: Imooc
 * description: 用户和Agent互动的Api 接口
 * date: 2026
 */

@RestController
public class AppController {

    @RequestMapping(name = "/app", method = RequestMethod.POST)
    public void app() {
        ManagerAgent manager =  new ManagerAgent();

        //Agent处理用户提交的Prompt
        manager.run();
    }
}
