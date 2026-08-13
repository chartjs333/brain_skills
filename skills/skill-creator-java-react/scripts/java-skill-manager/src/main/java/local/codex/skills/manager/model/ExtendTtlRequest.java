package local.codex.skills.manager.model;

import jakarta.validation.constraints.Positive;

public record ExtendTtlRequest(
        String skillId,
        String messageId,
        @Positive long seconds
) {
}
