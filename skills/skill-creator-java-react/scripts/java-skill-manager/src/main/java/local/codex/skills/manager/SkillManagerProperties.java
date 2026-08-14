package local.codex.skills.manager;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "skill.manager")
public record SkillManagerProperties(
        String baseUrl,
        String phone,
        Path repoRoot,
        Path skillsRoot,
        long defaultTtlSeconds,
        long gcFixedDelayMillis,
        boolean queuePollingEnabled,
        long queuePollFixedDelayMillis,
        String backendUrl,
        String reactUrl,
        Boolean llmEnabled,
        Boolean queueProcessingEnabled
) {
    public SkillManagerProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:8025";
        }
        if (phone == null || phone.isBlank()) {
            phone = "9301";
        }
        if (repoRoot == null) {
            repoRoot = discoverRepoRoot(Path.of("").toAbsolutePath().normalize());
        }
        if (skillsRoot == null) {
            skillsRoot = repoRoot.resolve("skills").normalize();
        }
        if (defaultTtlSeconds <= 0) {
            defaultTtlSeconds = 3600;
        }
        if (gcFixedDelayMillis <= 0) {
            gcFixedDelayMillis = 15000;
        }
        if (queuePollFixedDelayMillis <= 0) {
            queuePollFixedDelayMillis = 60000;
        }
        if (backendUrl == null || backendUrl.isBlank()) {
            backendUrl = "http://localhost:8080";
        }
        if (reactUrl == null || reactUrl.isBlank()) {
            reactUrl = "http://localhost:5173";
        }
        if (llmEnabled == null) {
            llmEnabled = true;
        }
        if (queueProcessingEnabled == null) {
            queueProcessingEnabled = true;
        }
    }

    private static Path discoverRepoRoot(Path start) {
        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve(".git")) || Files.isRegularFile(candidate.resolve("AGENTS.md"))) {
                return candidate;
            }
        }
        return start;
    }
}
