package local.codex.skills.manager.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import local.codex.skills.manager.SkillManagerProperties;
import local.codex.skills.manager.model.GenerateVariationsRequest;
import local.codex.skills.manager.model.OriginalSkillSummary;
import local.codex.skills.manager.model.SkillVariationDetail;
import local.codex.skills.manager.model.VariationGenerationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class OriginalSkillLibraryServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void uploadsOriginalAndStoresAiVariationsSeparately() throws Exception {
        OriginalSkillLibraryService service = service("""
                {"variations":[
                  {"fileName":"security-review.md","name":"security-review","description":"Security variant","difference":"Adds threat-model checks.","content":"# Security Review\\n\\n## Workflow\\n\\nCheck security risks."},
                  {"fileName":"performance-review.md","name":"performance-review","description":"Performance variant","difference":"Adds latency checks.","content":"# Performance Review\\n\\n## Workflow\\n\\nCheck latency risks."}
                ]}
                """);
        MockMultipartFile upload = new MockMultipartFile(
                "files",
                "code-review.md",
                "text/markdown",
                """
                        ---
                        name: code-review
                        description: Review code changes.
                        ---

                        # Code Review

                        Review code for defects.
                        """.getBytes(StandardCharsets.UTF_8)
        );

        List<OriginalSkillSummary> originals = service.upload(List.of(upload));
        String originalId = originals.get(0).originalId();
        VariationGenerationResult result = service.generateVariations(originalId, new GenerateVariationsRequest(
                2,
                "security and performance",
                0.8,
                "English",
                "moderate",
                "secure coding, latency",
                true
        ));

        assertThat(result.aiUsed()).isTrue();
        assertThat(result.variations()).hasSize(2);
        assertThat(Files.readString(tempDir.resolve("skills/originals").resolve(originalId).resolve("code-review.md")))
                .contains("Review code for defects.");
        assertThat(Files.list(tempDir.resolve("skills/variations").resolve(originalId)).toList())
                .extracting(path -> path.getFileName().toString())
                .containsExactlyInAnyOrder("security-review.md", "performance-review.md");
        assertThat(result.variations().get(0).content())
                .contains("source_original_id: \"" + originalId + "\"")
                .contains("source_file: \"code-review.md\"");
    }

    @Test
    void rejectsNonMarkdownUploads() {
        OriginalSkillLibraryService service = service("");
        MockMultipartFile upload = new MockMultipartFile(
                "files",
                "notes.txt",
                "text/plain",
                "plain text".getBytes(StandardCharsets.UTF_8)
        );

        assertThatThrownBy(() -> service.upload(List.of(upload)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Only .md files");
    }

    @Test
    void acceptsCommonLlmJsonAliasesForVariations() {
        OriginalSkillLibraryService service = service("""
                ```json
                {"variations":[
                  {"file_name":"onset-normalizer.md","skill_name":"onset-normalizer","short_description":"Normalize onset ages.","rationale":"Converts onset logic into a normalization skill.","skill_markdown_body":"# Onset Normalizer\\n\\n## Workflow\\n\\nNormalize onset ages."},
                  {"filename":"duration-extractor.md","title":"duration-extractor","description":"Extract duration.","changes":"Focuses on symptom duration.","markdown":"# Duration Extractor\\n\\n## Workflow\\n\\nExtract movement disorder duration."},
                  {"fileName":"progression-pattern.md","name":"progression-pattern","description":"Track progression.","difference":"Focuses on progression patterns.","body":"# Progression Pattern\\n\\n## Workflow\\n\\nTrack progression over time."}
                ]}
                ```
                """);
        MockMultipartFile upload = new MockMultipartFile(
                "files",
                "movement-disorder.md",
                "text/markdown",
                "# Movement Disorder\n\nExtract onset age.".getBytes(StandardCharsets.UTF_8)
        );
        String originalId = service.upload(List.of(upload)).get(0).originalId();

        VariationGenerationResult result = service.generateVariations(originalId, new GenerateVariationsRequest(
                3,
                "related specializations",
                0.65,
                "same as original",
                "moderate",
                "",
                true
        ));

        assertThat(result.aiUsed()).isTrue();
        assertThat(result.variations())
                .extracting(SkillVariationDetail::fileName)
                .containsExactlyInAnyOrder("onset-normalizer.md", "duration-extractor.md", "progression-pattern.md");
        assertThat(result.variations())
                .extracting(SkillVariationDetail::content)
                .allMatch(content -> content.contains("source_original_id: \"" + originalId + "\""));
    }

    @Test
    void rejectsVariationGenerationWhenLlmReturnsNoDrafts() {
        OriginalSkillLibraryService service = service("");
        MockMultipartFile upload = new MockMultipartFile(
                "files",
                "code-review.md",
                "text/markdown",
                "# Code Review\n\nReview code.".getBytes(StandardCharsets.UTF_8)
        );
        String originalId = service.upload(List.of(upload)).get(0).originalId();

        assertThatThrownBy(() -> service.generateVariations(originalId, new GenerateVariationsRequest(
                1,
                "security",
                0.7,
                "English",
                "moderate",
                "",
                true
        )))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("AI variation generation required")
                .hasMessageContaining("fake LLM returned no text");
    }

    private OriginalSkillLibraryService service(String response) {
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
                true,
                true
        );
        return new OriginalSkillLibraryService(properties, new FakeLlmService(response), new ObjectMapper());
    }

    private static final class FakeLlmService extends LlmService {
        private final String response;

        private FakeLlmService(String response) {
            super(HttpClient.newHttpClient(), null, new ObjectMapper(), null);
            this.response = response;
        }

        @Override
        public Optional<String> complete(String systemPrompt, String userPrompt) {
            return response == null || response.isBlank() ? Optional.empty() : Optional.of(response);
        }

        @Override
        public CompletionResult completeDetailed(String systemPrompt, String userPrompt) {
            return response == null || response.isBlank()
                    ? new CompletionResult(Optional.empty(), "fake LLM returned no text")
                    : new CompletionResult(Optional.of(response), "fake LLM returned text");
        }
    }
}
