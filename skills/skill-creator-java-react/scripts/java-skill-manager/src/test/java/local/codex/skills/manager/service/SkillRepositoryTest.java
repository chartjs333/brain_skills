package local.codex.skills.manager.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import local.codex.skills.manager.SkillManagerProperties;
import local.codex.skills.manager.model.CreateSkillRequest;
import local.codex.skills.manager.model.GeneratedSkill;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void createsExtendsAndDeletesExpiredGeneratedSkill() throws java.io.IOException {
        Path skillsRoot = tempDir.resolve("skills");
        SkillRepository repository = new SkillRepository(new SkillManagerProperties(
                "http://localhost:8025",
                "9301",
                tempDir,
                skillsRoot,
                3600,
                1000,
                false,
                60000,
                "http://localhost:8080",
                "http://localhost:5173"
        ));

        GeneratedSkill created = repository.create(new CreateSkillRequest(
                "msg_1042",
                "symptom_extractor",
                "Extract symptoms from clinical text.",
                null,
                120L
        ));

        assertThat(created.skillId()).isEqualTo("skill_0001_msg_1042_symptom_extractor");
        assertThat(Files.exists(created.directory().resolve("SKILL.md"))).isTrue();
        assertThat(Files.exists(created.directory().resolve("generated/java/Skill0001Msg1042Service.java"))).isTrue();
        assertThat(Files.exists(created.directory().resolve("generated/java/Skill0001Msg1042Controller.java"))).isTrue();
        assertThat(Files.exists(created.directory().resolve("generated/react/Skill0001Msg1042Component.jsx"))).isTrue();
        assertThat(Files.readString(created.directory().resolve("generated/java/Skill0001Msg1042Controller.java")))
                .contains("@RequestMapping(\"/api/v1/skills/skill_0001_msg_1042_symptom_extractor\")")
                .doesNotContain("/generated");

        GeneratedSkill extended = repository.extend(created.skillId(), null, 60);

        assertThat(extended.ttlSeconds()).isEqualTo(180);
        assertThat(extended.expiresAt()).isAfter(created.expiresAt());
        assertThat(repository.findAll()).hasSize(1);

        assertThat(repository.deleteExpired(Instant.now().plusSeconds(3600)))
                .extracting(GeneratedSkill::skillId)
                .containsExactly(created.skillId());
        assertThat(repository.findAll()).isEmpty();
    }
}
