package local.codex.skills.manager.model;

import java.time.Instant;

public record OriginalSkillDetail(
        String originalId,
        String fileName,
        String title,
        Instant uploadedAt,
        long sizeBytes,
        int variationCount,
        String content
) {
}
