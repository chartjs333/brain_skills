package local.codex.skills.manager.service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import local.codex.skills.manager.SkillManagerProperties;
import org.springframework.stereotype.Service;

@Service
public class LlmService {
    private final HttpClient httpClient;
    private final EnvFileLoader envFileLoader;
    private final ObjectMapper objectMapper;
    private final SkillManagerProperties properties;

    public LlmService(HttpClient httpClient, EnvFileLoader envFileLoader, ObjectMapper objectMapper, SkillManagerProperties properties) {
        this.httpClient = httpClient;
        this.envFileLoader = envFileLoader;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Optional<String> complete(String systemPrompt, String userPrompt) {
        return completeDetailed(systemPrompt, userPrompt).text();
    }

    public CompletionResult completeDetailed(String systemPrompt, String userPrompt) {
        if (properties != null && Boolean.FALSE.equals(properties.llmEnabled())) {
            return new CompletionResult(Optional.empty(), "LLM disabled by skill.manager.llm-enabled=false");
        }
        Optional<LlmSettings> settings = LlmSettings.from(envFileLoader);
        if (settings.isEmpty()) {
            return new CompletionResult(Optional.empty(), "LLM settings are missing base URI or primary model");
        }

        List<String> failures = new ArrayList<>();
        for (String model : modelOrder(settings.get())) {
            try {
                Optional<String> response = isGemini(settings.get(), model)
                        ? completeWithGemini(settings.get(), model, systemPrompt, userPrompt)
                        : completeWithOpenAiCompatible(settings.get(), model, systemPrompt, userPrompt);
                if (response.isPresent() && !response.get().isBlank()) {
                    return new CompletionResult(response, "LLM returned text from model " + model + " at " + settings.get().baseUri());
                }
                failures.add(model + ": empty response text");
            } catch (RuntimeException ignored) {
                failures.add(model + ": " + safeMessage(ignored));
            }
        }
        return new CompletionResult(
                Optional.empty(),
                "No usable LLM completion from " + settings.get().baseUri() + "; attempts: " + String.join("; ", failures)
        );
    }

    private Optional<String> completeWithOpenAiCompatible(LlmSettings settings, String model, String systemPrompt, String userPrompt) {
        List<OpenAiRequestVariant> variants = List.of(
                new OpenAiRequestVariant("standard chat payload", true, false),
                new OpenAiRequestVariant("minimal chat payload", false, false),
                new OpenAiRequestVariant("minimal single-user payload", false, true)
        );
        List<String> failures = new ArrayList<>();
        for (OpenAiRequestVariant variant : variants) {
            try {
                JsonNode response = send(openAiRequest(settings, model, systemPrompt, userPrompt, variant));
                Optional<String> text = textValue(response.at("/choices/0/message/content"))
                        .or(() -> textValue(response.at("/choices/0/text")));
                if (text.isPresent() && !text.get().isBlank()) {
                    return text;
                }
                failures.add(variant.label() + ": empty response text");
            } catch (RuntimeException e) {
                String diagnostic = safeMessage(e);
                failures.add(variant.label() + ": " + diagnostic);
                if (shouldStopVariantRetries(diagnostic)) {
                    break;
                }
            }
        }
        throw new IllegalStateException("OpenAI-compatible request variants failed: " + String.join(" | ", failures));
    }

    private HttpRequest openAiRequest(
            LlmSettings settings,
            String model,
            String systemPrompt,
            String userPrompt,
            OpenAiRequestVariant variant
    ) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        if (variant.includeOptionalParameters()) {
            root.put("temperature", 0.2);
            root.put("max_tokens", 2048);
        }
        ArrayNode messages = root.putArray("messages");
        if (variant.singleUserMessage()) {
            messages.addObject().put("role", "user").put("content", systemPrompt + "\n\n" + userPrompt);
        } else {
            messages.addObject().put("role", "system").put("content", systemPrompt);
            messages.addObject().put("role", "user").put("content", userPrompt);
        }

        HttpRequest.Builder builder = requestBuilder(openAiChatCompletionsUri(settings.baseUri()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(root.toString(), StandardCharsets.UTF_8));
        if (settings.hasApiKey()) {
            builder.header("Authorization", "Bearer " + settings.apiKey());
        }
        return builder.build();
    }

    private Optional<String> completeWithGemini(LlmSettings settings, String model, String systemPrompt, String userPrompt) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode contents = root.putArray("contents");
        ObjectNode content = contents.addObject();
        content.put("role", "user");
        content.putArray("parts").addObject().put("text", systemPrompt + "\n\n" + userPrompt);
        root.putObject("generationConfig").put("temperature", 0.2);

        HttpRequest.Builder builder = requestBuilder(geminiGenerateContentUri(settings.baseUri(), model))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(root.toString(), StandardCharsets.UTF_8));
        if (settings.hasApiKey()) {
            builder.header("x-goog-api-key", settings.apiKey());
        }

        JsonNode response = send(builder.build());
        return textValue(response.at("/candidates/0/content/parts/0/text"));
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String body = clip(safeDiagnosticText(response.body()), 1200);
                String detail = body.isBlank() ? "" : ": " + body;
                throw new IllegalStateException("LLM request failed with HTTP " + response.statusCode() + detail);
            }
            return objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("LLM request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LLM request interrupted", e);
        }
    }

    private HttpRequest.Builder requestBuilder(URI uri) {
        return HttpRequest.newBuilder()
                .uri(uri)
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(45));
    }

    private static Set<String> modelOrder(LlmSettings settings) {
        Set<String> models = new LinkedHashSet<>();
        models.add(settings.primaryModel());
        if (settings.fallbackModel() != null && !settings.fallbackModel().isBlank()) {
            models.add(settings.fallbackModel());
        }
        if (settings.defaultModel() != null && !settings.defaultModel().isBlank()) {
            models.add(settings.defaultModel());
        }
        return models;
    }

    private static boolean isGemini(LlmSettings settings, String model) {
        String base = settings.baseUri().toString().toLowerCase();
        return base.contains("generativelanguage.googleapis.com") || model.toLowerCase().startsWith("gemini");
    }

    private static URI openAiChatCompletionsUri(URI baseUri) {
        String raw = trimTrailingSlash(baseUri.toString());
        if (raw.endsWith("/chat/completions")) {
            return URI.create(raw);
        }
        if (raw.endsWith("/v1")) {
            return URI.create(raw + "/chat/completions");
        }
        return URI.create(raw + "/v1/chat/completions");
    }

    private static URI geminiGenerateContentUri(URI baseUri, String model) {
        String raw = trimTrailingSlash(baseUri.toString());
        if (raw.endsWith(":generateContent")) {
            return URI.create(raw);
        }
        if (raw.matches(".*/models/[^/]+")) {
            return URI.create(raw + ":generateContent");
        }
        String encodedModel = URLEncoder.encode(model, StandardCharsets.UTF_8).replace("+", "%20");
        if (raw.endsWith("/v1") || raw.endsWith("/v1beta")) {
            return URI.create(raw + "/models/" + encodedModel + ":generateContent");
        }
        return URI.create(raw + "/v1beta/models/" + encodedModel + ":generateContent");
    }

    private static Optional<String> textValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }
        return Optional.of(node.asText());
    }

    private static String trimTrailingSlash(String value) {
        return value.replaceAll("/+$", "");
    }

    private static boolean shouldStopVariantRetries(String diagnostic) {
        String lower = diagnostic == null ? "" : diagnostic.toLowerCase();
        return lower.contains("invalid model")
                || lower.contains("model not found")
                || lower.contains("model does not exist");
    }

    private static String safeMessage(RuntimeException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return safeDiagnosticText(message);
    }

    static String safeDiagnosticText(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        return message.replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+\\-/=]+", "$1[redacted]")
                .replaceAll("(?i)(api[_-]?key[=: ]+)[^\\s,;]+", "$1[redacted]")
                .replaceAll("sk-[A-Za-z0-9._-]{10,}", "[redacted-key]")
                .replaceAll("AIza[A-Za-z0-9_-]{20,}", "[redacted-key]");
    }

    private static String clip(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength) + "...[truncated]";
    }

    public record CompletionResult(Optional<String> text, String diagnostic) {
    }

    private record OpenAiRequestVariant(String label, boolean includeOptionalParameters, boolean singleUserMessage) {
    }
}
