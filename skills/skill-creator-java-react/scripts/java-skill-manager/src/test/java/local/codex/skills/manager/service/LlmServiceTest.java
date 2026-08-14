package local.codex.skills.manager.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import local.codex.skills.manager.SkillManagerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LlmServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void returnsEmptyWithoutReadingEnvWhenLlmDisabled() throws Exception {
        Files.writeString(tempDir.resolve(".env"), """
                LLM_BASE_URI=::::not-a-uri
                LLM_PRIMARY_MODEL=test-model
                LLM_API_KEY=secret
                """);
        SkillManagerProperties properties = new SkillManagerProperties(
                "http://localhost:8025",
                "9301",
                tempDir,
                tempDir.resolve("skills"),
                3600,
                1000,
                false,
                60000,
                "http://localhost:8080",
                "http://localhost:5173",
                false,
                false
        );
        LlmService service = new LlmService(
                HttpClient.newHttpClient(),
                new EnvFileLoader(properties),
                new ObjectMapper(),
                properties
        );

        LlmService.CompletionResult result = service.completeDetailed("system", "user");

        assertThat(result.text()).isEmpty();
        assertThat(result.diagnostic()).contains("LLM disabled");
    }

    @Test
    void redactsSecretLikeTokensFromDiagnostics() {
        String diagnostic = LlmService.safeDiagnosticText("""
                Authorization: Bearer sk-proj-1234567890abcdef
                api_key=sk-plain-1234567890abcdef
                echoed=AIza1234567890abcdefghijklmnop
                """);

        assertThat(diagnostic).doesNotContain("sk-proj-1234567890abcdef");
        assertThat(diagnostic).doesNotContain("sk-plain-1234567890abcdef");
        assertThat(diagnostic).doesNotContain("AIza1234567890abcdefghijklmnop");
        assertThat(diagnostic).contains("Bearer [redacted]");
        assertThat(diagnostic).contains("api_key=[redacted]");
        assertThat(diagnostic).contains("[redacted-key]");
    }

    @Test
    void keepsDefaultModelAsAdditionalCandidateWhenPrimaryExists() throws Exception {
        Files.writeString(tempDir.resolve(".env"), """
                LLM_BASE_URL=https://llm.example/v1
                LLM_PRIMARY_MODEL=primary-model
                LLM_FALLBACK_MODEL=fallback-model
                LLM_DEFAULT_MODEL=default-model
                """);
        SkillManagerProperties properties = properties(true);

        Optional<LlmSettings> settings = LlmSettings.from(new EnvFileLoader(properties));

        assertThat(settings).isPresent();
        assertThat(settings.get().primaryModel()).isEqualTo("primary-model");
        assertThat(settings.get().fallbackModel()).isEqualTo("fallback-model");
        assertThat(settings.get().defaultModel()).isEqualTo("default-model");
    }

    private SkillManagerProperties properties(boolean llmEnabled) {
        return new SkillManagerProperties(
                "http://localhost:8025",
                "9301",
                tempDir,
                tempDir.resolve("skills"),
                3600,
                1000,
                false,
                60000,
                "http://localhost:8080",
                "http://localhost:5173",
                false,
                llmEnabled
        );
    }
}
