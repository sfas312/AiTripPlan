package managerAgent;

import managerAgent.agents.ManagerAgent;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ManagerAgentApplication {

    public static void main(String[] args) {
        if (args != null && args.length > 0 && "--cli".equals(args[0])) {
            runCli(args);
            return;
        }
        SpringApplication.run(ManagerAgentApplication.class, args);
    }

    private static void runCli(String[] args) {
        ManagerAgent managerAgent = new ManagerAgent();
        String prompt = resolvePrompt(java.util.Arrays.copyOfRange(args, 1, args.length));

        if (prompt == null || prompt.isBlank()) {
            managerAgent.run();
            return;
        }

        managerAgent.run(prompt);
    }

    private static String resolvePrompt(String[] args) {
        if (args != null && args.length > 0) {
            return String.join(" ", args);
        }
        return System.getenv("AITRIPPLAN_PROMPT");
    }
}
