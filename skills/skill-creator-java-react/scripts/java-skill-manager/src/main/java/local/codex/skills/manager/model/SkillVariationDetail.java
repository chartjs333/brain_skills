package local.codex.skills.manager.model;

import java.time.Instant;

public record SkillVariationDetail(
        String variationId,
        String originalId,
        String fileName,
        String name,
        String description,
        String difference,
        Instant createdAt,
        long sizeBytes,
        String content
) {
}
