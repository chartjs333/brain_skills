package local.codex.skills.manager.model;

import java.util.List;

public record VariationGenerationResult(
        String originalId,
        boolean aiUsed,
        List<SkillVariationDetail> variations
) {
}
