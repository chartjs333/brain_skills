package local.codex.skills.manager.model;

import java.nio.file.Path;
import java.time.Instant;

public record GeneratedSkill(
        String skillId,
        String seqNumber,
        String messageId,
        String skillName,
        long ttlSeconds,
        Instant createdAt,
        Instant expiresAt,
        Path directory
) {
    public boolean expiredAt(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    public long remainingSeconds(Instant now) {
        if (expiresAt == null) {
            return Long.MAX_VALUE;
        }
        return Math.max(0, expiresAt.getEpochSecond() - now.getEpochSecond());
    }
}
