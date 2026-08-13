package local.codex.skills.manager.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record CreateSkillRequest(
        @NotBlank String messageId,
        @NotBlank String skillName,
        String requestText,
        String seqNumber,
        @Positive Long ttlSeconds
) {
}
