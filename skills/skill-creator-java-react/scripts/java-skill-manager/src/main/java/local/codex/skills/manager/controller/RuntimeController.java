package local.codex.skills.manager.controller;

import local.codex.skills.manager.SkillManagerProperties;
import local.codex.skills.manager.model.RuntimeStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/runtime")
public class RuntimeController {
    private final SkillManagerProperties properties;

    public RuntimeController(SkillManagerProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/status")
    public RuntimeStatus status() {
        return new RuntimeStatus(
                Boolean.TRUE.equals(properties.llmEnabled()),
                Boolean.TRUE.equals(properties.queueProcessingEnabled()),
                properties.queuePollingEnabled()
        );
    }
}
