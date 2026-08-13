package local.codex.skills.manager.model;

import java.time.Instant;

public record TtlStatus(
        String skillId,
        Instant createdAt,
        long ttlSeconds,
        Instant expiresAt,
        long remainingSeconds,
        boolean expired
) {
    public static TtlStatus from(GeneratedSkill skill, Instant now) {
        return new TtlStatus(
                skill.skillId(),
                skill.createdAt(),
                skill.ttlSeconds(),
                skill.expiresAt(),
                skill.remainingSeconds(now),
                skill.expiredAt(now)
        );
    }
}
