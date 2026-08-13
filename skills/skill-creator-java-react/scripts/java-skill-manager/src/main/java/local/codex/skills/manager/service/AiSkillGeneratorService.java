package local.codex.skills.manager.service;

import java.time.Instant;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

@Service
public class AiSkillGeneratorService {
    private final LlmService llmService;
    private final ObjectMapper objectMapper;

    public AiSkillGeneratorService(LlmService llmService, ObjectMapper objectMapper) {
        this.llmService = llmService;
        this.objectMapper = objectMapper;
    }

    public Optional<GeneratedSkillContent> generate(GenerationRequest request) {
        Optional<String> completion = llmService.complete(systemPrompt(), userPrompt(request));
        if (completion.isEmpty() || completion.get().isBlank()) {
            return Optional.empty();
        }
        return parseJson(completion.get()).flatMap(json -> toContent(json, request));
    }

    private Optional<GeneratedSkillContent> toContent(JsonNode json, GenerationRequest request) {
        String markdown = markdownBody(textValue(json.path("skill_markdown_body")).orElse(""), request);
        Optional<String> javaService = validJavaService(textValue(json.path("java_service")).orElse(""), request);
        Optional<String> javaController = validJavaController(textValue(json.path("java_controller")).orElse(""), request);
        Optional<String> reactComponent = validReactComponent(textValue(json.path("react_component")).orElse(""), request);
        if (markdown.isBlank() || javaService.isEmpty() || javaController.isEmpty() || reactComponent.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new GeneratedSkillContent(
                markdown,
                javaService.orElseThrow(),
                javaController.orElseThrow(),
                reactComponent.orElseThrow()
        ));
    }

    private Optional<JsonNode> parseJson(String text) {
        String trimmed = stripCodeFence(text).trim();
        int first = trimmed.indexOf('{');
        int last = trimmed.lastIndexOf('}');
        if (first >= 0 && last > first) {
            trimmed = trimmed.substring(first, last + 1);
        }
        try {
            return Optional.of(objectMapper.readTree(trimmed));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static String systemPrompt() {
        return """
                You generate ephemeral Codex skill artifacts for a Java 17 Spring Boot and React manager.
                Return valid JSON only, with these string keys:
                - skill_markdown_body
                - java_service
                - java_controller
                - react_component

                Do not include YAML frontmatter. Do not include markdown fences around the JSON.
                Java files must use package generated.skills. The service class must expose execute(Map<String,Object> input).
                The controller must map to the exact supplied skill route and delegate to the service.
                React must be JSX for a single default exported component with loading, success, error, and expired states.
                Keep generated files focused on the user's source request and avoid external dependencies.
                """;
    }

    private static String userPrompt(GenerationRequest request) {
        return """
                Generate files for this queue task.

                skill_id: %s
                seq_number: %s
                message_id: %s
                skill_slug: %s
                java_service_class: %sService
                java_controller_class: %sController
                react_component_name: %sComponent
                backend_execute_route: /api/v1/skills/%s/execute
                ttl_seconds: %d
                created_at: %s
                expires_at: %s

                Source request:
                %s
                """.formatted(
                request.skillId(),
                request.seq(),
                request.messageId(),
                request.skillSlug(),
                request.classBase(),
                request.classBase(),
                request.classBase(),
                request.skillId(),
                request.ttlSeconds(),
                request.createdAt(),
                request.expiresAt(),
                request.requestText()
        );
    }

    private static String markdownBody(String text, GenerationRequest request) {
        String body = stripFrontmatter(stripCodeFence(text)).trim();
        if (body.isBlank()) {
            return "";
        }
        if (!body.startsWith("#")) {
            body = "# " + titleCase(request.skillSlug().replace("_", " ")) + "\n\n" + body;
        }
        return body + "\n";
    }

    private static Optional<String> validJavaService(String text, GenerationRequest request) {
        String code = stripCodeFence(text).trim();
        if (code.contains("package generated.skills;")
                && code.contains("class " + request.classBase() + "Service")
                && code.contains("execute(")) {
            return Optional.of(code + "\n");
        }
        return Optional.empty();
    }

    private static Optional<String> validJavaController(String text, GenerationRequest request) {
        String code = stripCodeFence(text).trim();
        if (code.contains("package generated.skills;")
                && code.contains("class " + request.classBase() + "Controller")
                && code.contains("@RequestMapping(\"/api/v1/skills/" + request.skillId() + "\")")) {
            return Optional.of(code + "\n");
        }
        return Optional.empty();
    }

    private static Optional<String> validReactComponent(String text, GenerationRequest request) {
        String code = stripCodeFence(text).trim();
        if (code.contains(request.classBase() + "Component") && code.contains("export default")) {
            return Optional.of(code + "\n");
        }
        return Optional.empty();
    }

    private static Optional<String> textValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }
        return Optional.of(node.asText());
    }

    private static String stripCodeFence(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstLine = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        if (firstLine >= 0 && lastFence > firstLine) {
            return trimmed.substring(firstLine + 1, lastFence).trim();
        }
        return trimmed;
    }

    private static String stripFrontmatter(String text) {
        String normalized = text.replace("\r\n", "\n");
        if (!normalized.startsWith("---\n")) {
            return text;
        }
        int end = normalized.indexOf("\n---\n", 4);
        if (end < 0) {
            return text;
        }
        return normalized.substring(end + "\n---\n".length());
    }

    private static String titleCase(String value) {
        StringBuilder result = new StringBuilder();
        for (String word : value.split("\\s+")) {
            if (word.isBlank()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    public record GenerationRequest(
            String skillId,
            String seq,
            String messageId,
            String skillSlug,
            String classBase,
            long ttlSeconds,
            Instant createdAt,
            Instant expiresAt,
            String requestText
    ) {
    }
}
