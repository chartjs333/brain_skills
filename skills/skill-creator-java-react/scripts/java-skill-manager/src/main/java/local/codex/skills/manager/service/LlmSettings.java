package local.codex.skills.manager.service;

import java.net.URI;
import java.util.Optional;

record LlmSettings(
        URI baseUri,
        String apiKey,
        String primaryModel,
        String fallbackModel
) {
    static Optional<LlmSettings> from(EnvFileLoader envFileLoader) {
        Optional<String> baseUri = envFileLoader.first("LLM_BASE_URI", "LLM_BASE_URL");
        Optional<String> primaryModel = envFileLoader.first("LLM_PRIMARY_MODEL", "LLM_DEFAULT_MODEL");
        Optional<String> fallbackModel = envFileLoader.first("LLM_FALLBACK_MODEL");
        if (baseUri.isEmpty() || primaryModel.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new LlmSettings(
                URI.create(baseUri.get()),
                envFileLoader.first("LLM_API_KEY", "OPENAI_API_KEY", "GEMINI_API_KEY").orElse(""),
                primaryModel.get(),
                fallbackModel.orElse("")
        ));
    }

    boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
