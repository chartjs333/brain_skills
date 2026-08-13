package local.codex.skills.manager.service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

@Service
public class LlmService {
    private final HttpClient httpClient;
    private final EnvFileLoader envFileLoader;
    private final ObjectMapper objectMapper;

    public LlmService(HttpClient httpClient, EnvFileLoader envFileLoader, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.envFileLoader = envFileLoader;
        this.objectMapper = objectMapper;
    }

    public Optional<String> complete(String systemPrompt, String userPrompt) {
        Optional<LlmSettings> settings = LlmSettings.from(envFileLoader);
        if (settings.isEmpty()) {
            return Optional.empty();
        }

        for (String model : modelOrder(settings.get())) {
            try {
                Optional<String> response = isGemini(settings.get(), model)
                        ? completeWithGemini(settings.get(), model, systemPrompt, userPrompt)
                        : completeWithOpenAiCompatible(settings.get(), model, systemPrompt, userPrompt);
                if (response.isPresent() && !response.get().isBlank()) {
                    return response;
                }
            } catch (RuntimeException ignored) {
                // Fallback model or deterministic generation will handle unavailable LLMs.
            }
        }
        return Optional.empty();
    }

    private Optional<String> completeWithOpenAiCompatible(LlmSettings settings, String model, String systemPrompt, String userPrompt) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("temperature", 0.2);
        root.put("max_tokens", 4500);
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);

        HttpRequest.Builder builder = requestBuilder(openAiChatCompletionsUri(settings.baseUri()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(root.toString(), StandardCharsets.UTF_8));
        if (settings.hasApiKey()) {
            builder.header("Authorization", "Bearer " + settings.apiKey());
        }

        JsonNode response = send(builder.build());
        return textValue(response.at("/choices/0/message/content"))
                .or(() -> textValue(response.at("/choices/0/text")));
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
                throw new IllegalStateException("LLM request failed with HTTP " + response.statusCode());
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
}
