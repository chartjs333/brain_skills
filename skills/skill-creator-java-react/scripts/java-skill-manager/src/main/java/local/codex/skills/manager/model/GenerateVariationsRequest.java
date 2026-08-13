package local.codex.skills.manager.model;

public record GenerateVariationsRequest(
        Integer count,
        String direction,
        Double creativity,
        String language,
        String maxDeviation,
        String themes,
        Boolean avoidExisting
) {
}
