package local.codex.skills.manager.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import local.codex.skills.manager.SkillManagerProperties;
import local.codex.skills.manager.model.CreateSkillRequest;
import local.codex.skills.manager.model.GeneratedSkill;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

@Repository
public class SkillRepository {
    private static final Pattern GENERATED_DIR = Pattern.compile("^skill_(\\d{4})_msg_.+");

    private final SkillManagerProperties properties;
    private final AiSkillGeneratorService aiGeneratorService;

    public SkillRepository(SkillManagerProperties properties) {
        this(properties, null);
    }

    @Autowired
    public SkillRepository(SkillManagerProperties properties, AiSkillGeneratorService aiGeneratorService) {
        this.properties = properties;
        this.aiGeneratorService = aiGeneratorService;
    }

    public synchronized GeneratedSkill create(CreateSkillRequest request) {
        try {
            Files.createDirectories(properties.skillsRoot());
            String seq = normalizeSequence(request.seqNumber()).orElse(nextSequence());
            String skillSlug = slugify(request.skillName(), "_");
            String messagePart = messagePathPart(request.messageId());
            String skillId = "skill_" + seq + "_msg_" + messagePart + "_" + skillSlug;
            Path skillDir = properties.skillsRoot().resolve(skillId).normalize();
            ensureInsideSkillsRoot(skillDir);
            if (Files.exists(skillDir)) {
                throw new IllegalArgumentException("Generated skill already exists: " + skillId);
            }
            Instant createdAt = Instant.now();
            long ttlSeconds = request.ttlSeconds() == null ? properties.defaultTtlSeconds() : request.ttlSeconds();
            Instant expiresAt = createdAt.plusSeconds(ttlSeconds);
            String classBase = classBase(seq, request.messageId());
            GeneratedSkillContent generatedContent = generateWithLlm(
                    skillId,
                    seq,
                    request.messageId(),
                    skillSlug,
                    classBase,
                    ttlSeconds,
                    createdAt,
                    expiresAt,
                    request.requestText()
            ).orElse(null);
            Files.createDirectories(skillDir);
            String markdown = renderSkillMarkdown(
                    skillId,
                    seq,
                    request.messageId(),
                    skillSlug,
                    ttlSeconds,
                    createdAt,
                    expiresAt,
                    request.requestText(),
                    generatedContent == null ? null : generatedContent.skillMarkdownBody()
            );
            Files.writeString(skillDir.resolve("SKILL.md"), markdown, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            writeGeneratedJavaAndReactArtifacts(skillDir, skillId, classBase, skillSlug, generatedContent);
            return readSkill(skillDir).orElseThrow();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create generated skill", e);
        }
    }

    public synchronized GeneratedSkill extend(String skillId, String messageId, long seconds) {
        Path skillDir = find(skillId, messageId)
                .orElseThrow(() -> new IllegalArgumentException("Generated skill not found"));
        Path skillMd = skillDir.resolve("SKILL.md");
        try {
            String text = Files.readString(skillMd, StandardCharsets.UTF_8);
            FrontmatterSplit split = splitFrontmatter(text);
            Map<String, String> metadata = parseFrontmatter(split.frontmatter());
            Instant now = Instant.now();
            Instant oldExpiresAt = parseInstant(metadata.get("expires_at")).orElse(now);
            Instant newExpiresAt = (oldExpiresAt.isAfter(now) ? oldExpiresAt : now).plusSeconds(seconds);
            long oldTtl = parseLong(metadata.get("ttl_seconds")).orElse(0L);
            metadata.put("ttl_seconds", Long.toString(oldTtl + seconds));
            metadata.put("expires_at", quote(newExpiresAt.toString()));
            metadata.put("last_extended_at", quote(now.toString()));
            Files.writeString(skillMd, renderFrontmatter(metadata) + split.body(), StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            return readSkill(skillDir).orElseThrow();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to extend generated skill", e);
        }
    }

    public synchronized List<GeneratedSkill> findAll() {
        if (!Files.isDirectory(properties.skillsRoot())) {
            return List.of();
        }
        try (var stream = Files.list(properties.skillsRoot())) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(path -> GENERATED_DIR.matcher(path.getFileName().toString()).matches())
                    .map(this::readSkill)
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparing(GeneratedSkill::skillId))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to list generated skills", e);
        }
    }

    public synchronized Optional<GeneratedSkill> findSkill(String skillId, String messageId) {
        return find(skillId, messageId).flatMap(this::readSkill);
    }

    public synchronized List<GeneratedSkill> deleteExpired(Instant now) {
        List<GeneratedSkill> removed = new ArrayList<>();
        for (GeneratedSkill skill : findAll()) {
            if (skill.expiredAt(now)) {
                deleteDirectory(skill.directory());
                removed.add(skill);
            }
        }
        return removed;
    }

    private Optional<Path> find(String skillId, String messageId) {
        if (skillId != null && !skillId.isBlank()) {
            Path direct = properties.skillsRoot().resolve(skillId).normalize();
            ensureInsideSkillsRoot(direct);
            if (Files.isRegularFile(direct.resolve("SKILL.md"))) {
                return Optional.of(direct);
            }
        }
        String normalizedMessage = messageId == null ? null : messagePathPart(messageId);
        return findAll().stream()
                .filter(skill -> {
                    if (messageId != null && messageId.equals(skill.messageId())) {
                        return true;
                    }
                    return normalizedMessage != null && skill.skillId().contains("_msg_" + normalizedMessage + "_");
                })
                .map(GeneratedSkill::directory)
                .findFirst();
    }

    private Optional<GeneratedSkill> readSkill(Path skillDir) {
        Path skillMd = skillDir.resolve("SKILL.md");
        if (!Files.isRegularFile(skillMd)) {
            return Optional.empty();
        }
        try {
            FrontmatterSplit split = splitFrontmatter(Files.readString(skillMd, StandardCharsets.UTF_8));
            Map<String, String> metadata = parseFrontmatter(split.frontmatter());
            String skillId = metadata.getOrDefault("skill_id", skillDir.getFileName().toString()).replace("\"", "");
            String seq = metadata.getOrDefault("seq_number", seqFromDirectory(skillDir)).replace("\"", "");
            String messageId = metadata.getOrDefault("message_id", "").replace("\"", "");
            String skillName = metadata.getOrDefault("name", skillId).replace("\"", "");
            long ttl = parseLong(metadata.get("ttl_seconds")).orElse(properties.defaultTtlSeconds());
            Instant createdAt = parseInstant(metadata.get("created_at")).orElse(Instant.EPOCH);
            Instant expiresAt = parseInstant(metadata.get("expires_at")).orElse(createdAt.plusSeconds(ttl));
            return Optional.of(new GeneratedSkill(skillId, seq, messageId, skillName, ttl, createdAt, expiresAt, skillDir));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read generated skill: " + skillDir, e);
        }
    }

    private String nextSequence() {
        int highest = findAll().stream()
                .map(GeneratedSkill::seqNumber)
                .flatMap(seq -> parseLong(seq).stream())
                .mapToInt(Long::intValue)
                .max()
                .orElse(0);
        return "%04d".formatted(highest + 1);
    }

    private Optional<String> normalizeSequence(String seq) {
        if (seq == null || seq.isBlank()) {
            return Optional.empty();
        }
        if (!seq.matches("\\d{4}")) {
            throw new IllegalArgumentException("seqNumber must be four digits");
        }
        return Optional.of(seq);
    }

    private Optional<GeneratedSkillContent> generateWithLlm(
            String skillId,
            String seq,
            String messageId,
            String skillSlug,
            String classBase,
            long ttlSeconds,
            Instant createdAt,
            Instant expiresAt,
            String requestText
    ) {
        if (aiGeneratorService == null) {
            return Optional.empty();
        }
        return aiGeneratorService.generate(new AiSkillGeneratorService.GenerationRequest(
                skillId,
                seq,
                messageId,
                skillSlug,
                classBase,
                ttlSeconds,
                createdAt,
                expiresAt,
                requestText == null ? "" : requestText
        ));
    }

    private static String renderSkillMarkdown(String skillId, String seq, String messageId, String skillSlug, long ttlSeconds,
            Instant createdAt, Instant expiresAt, String requestText, String generatedBody) {
        String codexName = skillSlug.replace("_", "-");
        String source = requestText == null || requestText.isBlank() ? "No source request text was provided." : requestText.trim();
        String summary = source.lines().map(String::trim).filter(line -> !line.isBlank()).findFirst()
                .orElse("Ephemeral skill generated for message " + messageId);
        if (summary.length() > 180) {
            summary = summary.substring(0, 180);
        }
        String body = generatedBody == null || generatedBody.isBlank()
                ? renderFallbackSkillBody(skillSlug, messageId, source)
                : generatedBody.trim() + "\n";
        return """
                ---
                name: "%s"
                description: "%s"
                created_at: "%s"
                ttl_seconds: %d
                expires_at: "%s"
                seq_number: "%s"
                message_id: "%s"
                skill_id: "%s"
                ---

                %s
                """.formatted(
                codexName,
                escapeYaml(summary),
                createdAt,
                ttlSeconds,
                expiresAt,
                seq,
                escapeYaml(messageId),
                skillId,
                body
        );
    }

    private static String renderFallbackSkillBody(String skillSlug, String messageId, String source) {
        String displayName = titleCase(skillSlug.replace("_", " "));
        return """
                # %s

                ## Purpose

                Execute the queue request associated with `%s` while this Java-generated ephemeral skill is active.

                ## Source Request

                ```text
                %s
                ```

                ## Workflow

                1. Confirm the skill has not expired by checking the Java TTL endpoint.
                2. Follow the source request exactly, keeping generated artifacts scoped to this skill.
                3. Expose generated backend behavior through Java service/controller classes.
                4. Expose generated frontend behavior through the React skill route when a host UI exists.

                ## Validation

                - Verify `SKILL.md` frontmatter includes `created_at`, `ttl_seconds`, and `expires_at`.
                - Verify the Java endpoint compiles and serves the skill execution and TTL routes.
                - Verify the React route renders the skill and reports expired state cleanly.
                - Verify Java TTL garbage collection removes expired generated skill folders.
                """.formatted(
                displayName,
                messageId,
                source
        );
    }

    private static void writeGeneratedJavaAndReactArtifacts(
            Path skillDir,
            String skillId,
            String classBase,
            String skillSlug,
            GeneratedSkillContent generatedContent
    )
            throws IOException {
        Path javaDir = skillDir.resolve("generated/java");
        Path reactDir = skillDir.resolve("generated/react");
        Files.createDirectories(javaDir);
        Files.createDirectories(reactDir);
        Files.writeString(
                javaDir.resolve(classBase + "Service.java"),
                generatedContent == null || generatedContent.javaService() == null ? renderGeneratedService(classBase, skillId) : generatedContent.javaService(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW
        );
        Files.writeString(
                javaDir.resolve(classBase + "Controller.java"),
                generatedContent == null || generatedContent.javaController() == null ? renderGeneratedController(classBase, skillId) : generatedContent.javaController(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW
        );
        Files.writeString(
                reactDir.resolve(classBase + "Component.jsx"),
                generatedContent == null || generatedContent.reactComponent() == null ? renderGeneratedReactComponent(classBase, skillId, skillSlug) : generatedContent.reactComponent(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW
        );
    }

    private static String renderGeneratedService(String classBase, String skillId) {
        return """
                package generated.skills;

                import java.time.Instant;
                import java.util.Map;

                public class %sService {
                    public static final String SKILL_ID = "%s";

                    public Map<String, Object> execute(Map<String, Object> input) {
                        return Map.of(
                                "skillId", SKILL_ID,
                                "status", "EXECUTED",
                                "executedAt", Instant.now().toString(),
                                "input", input == null ? Map.of() : input
                        );
                    }
                }
                """.formatted(classBase, skillId);
    }

    private static String renderGeneratedController(String classBase, String skillId) {
        return """
                package generated.skills;

                import java.util.Map;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping("/api/v1/skills/%s")
                public class %sController {
                    private final %sService service = new %sService();

                    @PostMapping("/execute")
                    public Map<String, Object> execute(@RequestBody(required = false) Map<String, Object> input) {
                        return service.execute(input);
                    }
                }
                """.formatted(skillId, classBase, classBase, classBase);
    }

    private static String renderGeneratedReactComponent(String classBase, String skillId, String skillSlug) {
        String displayName = titleCase(skillSlug.replace("_", " "));
        return """
                import React, { useEffect, useState } from "react";

                export default function %sComponent() {
                  const [ttl, setTtl] = useState(null);
                  const [result, setResult] = useState(null);

                  useEffect(() => {
                    fetch("/api/v1/skills/%s/ttl")
                      .then((response) => response.json())
                      .then(setTtl)
                      .catch(() => setTtl({ expired: true }));
                  }, []);

                  const execute = async () => {
                    const response = await fetch("/api/v1/skills/%s/execute", {
                      method: "POST",
                      headers: { "Content-Type": "application/json" },
                      body: JSON.stringify({ input: { sample: "validation" } }),
                    });
                    setResult(await response.json());
                  };

                  return (
                    <main>
                      <h1>%s</h1>
                      <p>Skill ID: %s</p>
                      <p>Status: {ttl?.expired ? "EXPIRED" : "ACTIVE"}</p>
                      <button type="button" onClick={execute}>Execute</button>
                      {result && <pre>{JSON.stringify(result, null, 2)}</pre>}
                    </main>
                  );
                }
                """.formatted(classBase, skillId, skillId, displayName, skillId);
    }

    private static FrontmatterSplit splitFrontmatter(String text) {
        String normalized = text.replace("\r\n", "\n");
        if (!normalized.startsWith("---\n")) {
            throw new IllegalArgumentException("SKILL.md has no YAML frontmatter");
        }
        int end = normalized.indexOf("\n---\n", 4);
        if (end < 0) {
            throw new IllegalArgumentException("SKILL.md frontmatter is not closed");
        }
        String frontmatter = normalized.substring(4, end);
        String body = normalized.substring(end + "\n---\n".length());
        return new FrontmatterSplit(frontmatter, body);
    }

    private static Map<String, String> parseFrontmatter(String frontmatter) {
        Map<String, String> metadata = new LinkedHashMap<>();
        for (String line : frontmatter.split("\n")) {
            if (line.isBlank() || line.trim().startsWith("#") || !line.contains(":")) {
                continue;
            }
            int idx = line.indexOf(':');
            metadata.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
        }
        return metadata;
    }

    private static String renderFrontmatter(Map<String, String> metadata) {
        StringBuilder builder = new StringBuilder("---\n");
        metadata.forEach((key, value) -> builder.append(key).append(": ").append(value).append('\n'));
        return builder.append("---\n").toString();
    }

    private static Optional<Instant> parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Instant.parse(stripQuotes(value)));
    }

    private static Optional<Long> parseLong(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Long.parseLong(stripQuotes(value)));
    }

    private static String stripQuotes(String value) {
        String stripped = value.trim();
        if ((stripped.startsWith("\"") && stripped.endsWith("\"")) || (stripped.startsWith("'") && stripped.endsWith("'"))) {
            return stripped.substring(1, stripped.length() - 1);
        }
        return stripped;
    }

    private static String quote(String value) {
        return "\"" + escapeYaml(value) + "\"";
    }

    private static String slugify(String value, String separator) {
        String slug = value == null ? "" : value.toLowerCase().replaceAll("[^a-z0-9]+", separator);
        slug = slug.replaceAll(Pattern.quote(separator) + "+", separator).replaceAll("^" + Pattern.quote(separator) + "|" + Pattern.quote(separator) + "$", "");
        return slug.isBlank() ? "untitled" : slug;
    }

    private static String messagePathPart(String messageId) {
        String slug = slugify(messageId, "_");
        return slug.startsWith("msg_") ? slug.substring(4) : slug;
    }

    private static String seqFromDirectory(Path skillDir) {
        Matcher matcher = GENERATED_DIR.matcher(skillDir.getFileName().toString());
        return matcher.matches() ? matcher.group(1) : "0000";
    }

    private static String classBase(String seq, String messageId) {
        String messageToken = messagePathPart(messageId).replaceAll("[^a-zA-Z0-9]", "");
        if (messageToken.isBlank()) {
            messageToken = "Unknown";
        }
        return "Skill" + seq + "Msg" + Character.toUpperCase(messageToken.charAt(0)) + messageToken.substring(1);
    }

    private void ensureInsideSkillsRoot(Path path) {
        Path root = properties.skillsRoot().toAbsolutePath().normalize();
        Path target = path.toAbsolutePath().normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Refusing to access path outside skills root: " + target);
        }
    }

    private static void deleteDirectory(Path root) {
        try (var stream = Files.walk(root)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to delete expired generated skill: " + root, e);
        }
    }

    private static String titleCase(String value) {
        StringBuilder result = new StringBuilder();
        for (String word : value.split("\\s+")) {
            if (word.isBlank()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    private static String escapeYaml(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private record FrontmatterSplit(String frontmatter, String body) {
    }
}
