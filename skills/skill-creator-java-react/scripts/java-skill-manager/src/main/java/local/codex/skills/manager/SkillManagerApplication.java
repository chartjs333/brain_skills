package local.codex.skills.manager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class SkillManagerApplication {
    public static void main(String[] args) {
        SpringApplication.run(SkillManagerApplication.class, args);
    }
}
