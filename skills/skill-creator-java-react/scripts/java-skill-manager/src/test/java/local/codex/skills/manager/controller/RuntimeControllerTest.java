package local.codex.skills.manager.controller;

import static org.assertj.core.api.Assertions.assertThat;

import local.codex.skills.manager.SkillManagerProperties;
import local.codex.skills.manager.model.RuntimeStatus;
import org.junit.jupiter.api.Test;

class RuntimeControllerTest {
    @Test
    void exposesSafeRuntimeFlags() {
        SkillManagerProperties properties = new SkillManagerProperties(
                "http://localhost:8025",
                "9301",
                null,
                null,
                3600,
                15000,
                false,
                60000,
                "http://localhost:8084",
                "http://localhost:5178",
                false,
                false
        );

        RuntimeStatus status = new RuntimeController(properties).status();

        assertThat(status.llmEnabled()).isFalse();
        assertThat(status.queueProcessingEnabled()).isFalse();
        assertThat(status.queuePollingEnabled()).isFalse();
    }
}
