package local.codex.skills.manager.model;

import java.time.Instant;
import java.util.Map;

public record SkillExecutionResult(
        String skillId,
        String status,
        Instant executedAt,
        Map<String, Object> echo
) {
}
