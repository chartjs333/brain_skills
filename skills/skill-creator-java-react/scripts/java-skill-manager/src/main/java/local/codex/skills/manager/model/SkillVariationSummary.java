package local.codex.skills.manager.model;

import java.time.Instant;

public record SkillVariationSummary(
        String variationId,
        String originalId,
        String fileName,
        String name,
        String description,
        String difference,
        Instant createdAt,
        long sizeBytes
) {
}
