package local.codex.skills.manager.model;

public record RuntimeStatus(
        boolean llmEnabled,
        boolean queueProcessingEnabled,
        boolean queuePollingEnabled
) {
}
