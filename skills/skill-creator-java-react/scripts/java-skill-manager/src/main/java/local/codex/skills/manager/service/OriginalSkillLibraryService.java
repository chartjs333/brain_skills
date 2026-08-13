package local.codex.skills.manager.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import local.codex.skills.manager.SkillManagerProperties;
import local.codex.skills.manager.model.GenerateVariationsRequest;
import local.codex.skills.manager.model.OriginalSkillDetail;
import local.codex.skills.manager.model.OriginalSkillSummary;
import local.codex.skills.manager.model.SkillVariationDetail;
import local.codex.skills.manager.model.SkillVariationSummary;
import local.codex.skills.manager.model.VariationGenerationResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OriginalSkillLibraryService {
    private static final long MAX_MARKDOWN_BYTES = 512L * 1024L;
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,119}");

    private final SkillManagerProperties properties;
    private final LlmService llmService;
    private final ObjectMapper objectMapper;

    public OriginalSkillLibraryService(SkillManagerProperties properties, LlmService llmService, ObjectMapper objectMapper) {
        this.properties = properties;
        this.llmService = llmService;
        this.objectMapper = objectMapper;
    }

    public synchronized List<OriginalSkillSummary> upload(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw badRequest("At least one .md file is required");
        }
        return files.stream().map(this::storeOriginal).toList();
    }

    public synchronized List<OriginalSkillSummary> listOriginals() {
        Path root = originalsRoot();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var stream = Files.list(root)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(this::readOriginalSummary)
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparing(OriginalSkillSummary::uploadedAt).reversed())
                    .toList();
        } catch (IOException e) {
            throw serverError("Unable to list original skills", e);
        }
    }

    public synchronized OriginalSkillDetail getOriginal(String originalId) {
        OriginalMetadata metadata = readOriginalMetadata(originalDirectory(originalId))
                .orElseThrow(() -> notFound("Original skill not found"));
        Path contentPath = originalDirectory(originalId).resolve(metadata.storedFileName()).normalize();
        ensureInside(originalDirectory(originalId), contentPath);
        try {
            String content = Files.readString(contentPath, StandardCharsets.UTF_8);
            return new OriginalSkillDetail(
                    metadata.originalId(),
                    metadata.fileName(),
                    metadata.title(),
                    Instant.parse(metadata.uploadedAt()),
                    metadata.sizeBytes(),
                    countVariations(metadata.originalId()),
                    content
            );
        } catch (IOException e) {
            throw serverError("Unable to read original skill", e);
        }
    }

    public synchronized List<SkillVariationSummary> listVariations(String originalId) {
        Path dir = variationDirectory(originalId);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                    .map(path -> readVariation(originalId, stripMarkdownExtension(path.getFileName().toString())))
                    .flatMap(Optional::stream)
                    .map(detail -> new SkillVariationSummary(
                            detail.variationId(),
                            detail.originalId(),
                            detail.fileName(),
                            detail.name(),
                            detail.description(),
                            detail.difference(),
                            detail.createdAt(),
                            detail.sizeBytes()
                    ))
                    .sorted(Comparator.comparing(SkillVariationSummary::createdAt).reversed())
                    .toList();
        } catch (IOException e) {
            throw serverError("Unable to list variations", e);
        }
    }

    public synchronized SkillVariationDetail getVariation(String originalId, String variationId) {
        return readVariation(originalId, variationId).orElseThrow(() -> notFound("Variation not found"));
    }

    public synchronized VariationGenerationResult generateVariations(String originalId, GenerateVariationsRequest request) {
        OriginalSkillDetail original = getOriginal(originalId);
        GenerationOptions options = GenerationOptions.from(request);
        List<VariationDraft> drafts = new ArrayList<>(generateWithAi(original, options)
                .filter(list -> !list.isEmpty())
                .orElseGet(() -> fallbackDrafts(original, options)));
        boolean aiUsed = drafts.stream().anyMatch(VariationDraft::aiGenerated);
        if (drafts.size() < options.count()) {
            drafts.addAll(fallbackDrafts(original, options).stream()
                    .limit(options.count() - drafts.size())
                    .toList());
        }
        Path dir = variationDirectory(original.originalId());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw serverError("Unable to create variation directory", e);
        }

        List<SkillVariationDetail> created = new ArrayList<>();
        for (VariationDraft draft : drafts.stream().limit(options.count()).toList()) {
            created.add(storeVariation(original, draft, options));
        }
        return new VariationGenerationResult(original.originalId(), aiUsed, created);
    }

    public synchronized void deleteVariation(String originalId, String variationId) {
        Path path = variationFile(originalId, variationId);
        if (!Files.isRegularFile(path)) {
            throw notFound("Variation not found");
        }
        try {
            Files.delete(path);
        } catch (IOException e) {
            throw serverError("Unable to delete variation", e);
        }
    }

    private OriginalSkillSummary storeOriginal(MultipartFile file) {
        String cleanName = cleanOriginalFileName(file.getOriginalFilename());
        byte[] bytes = readValidatedBytes(file, cleanName);
        String content = decodeMarkdown(bytes, cleanName);
        String base = stripMarkdownExtension(cleanName);
        String originalId = uniqueOriginalId(slugify(base));
        Path dir = originalsRoot().resolve(originalId).normalize();
        ensureInside(originalsRoot(), dir);
        String title = titleFromMarkdown(content).orElse(base);
        Instant uploadedAt = Instant.now();
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve(cleanName), bytes, StandardOpenOption.CREATE_NEW);
            writeOriginalMetadata(dir, new OriginalMetadata(
                    originalId,
                    cleanName,
                    cleanName,
                    uploadedAt.toString(),
                    bytes.length,
                    title
            ));
            return new OriginalSkillSummary(originalId, cleanName, title, uploadedAt, bytes.length, 0);
        } catch (IOException e) {
            throw serverError("Unable to store original skill", e);
        }
    }

    private byte[] readValidatedBytes(MultipartFile file, String cleanName) {
        if (file.isEmpty()) {
            throw badRequest(cleanName + " is empty");
        }
        if (file.getSize() > MAX_MARKDOWN_BYTES) {
            throw badRequest(cleanName + " exceeds " + MAX_MARKDOWN_BYTES + " bytes");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw serverError("Unable to read upload", e);
        }
    }

    private String decodeMarkdown(byte[] bytes, String cleanName) {
        try {
            String content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            if (content.indexOf('\0') >= 0) {
                throw badRequest(cleanName + " contains NUL bytes");
            }
            if (content.trim().isBlank()) {
                throw badRequest(cleanName + " has no markdown content");
            }
            return content;
        } catch (CharacterCodingException e) {
            throw badRequest(cleanName + " is not valid UTF-8 markdown");
        }
    }

    private Optional<List<VariationDraft>> generateWithAi(OriginalSkillDetail original, GenerationOptions options) {
        String systemPrompt = """
                You generate Codex SKILL.md files. Return strict JSON only, with a top-level "variations" array.
                Each item must include fileName, name, description, difference, and content.
                The content must be a complete standalone Markdown skill that preserves the source concept while changing specialization, audience, workflow, or scenario.
                Do not mechanically rewrite the original. Do not combine unrelated source skills.
                """;
        String userPrompt = """
                Original file: %s
                Requested variations: %d
                Direction: %s
                Creativity: %.2f
                Language: %s
                Max deviation: %s
                Themes: %s
                Avoid existing names: %s

                Original skill markdown:
                ```markdown
                %s
                ```
                """.formatted(
                original.fileName(),
                options.count(),
                options.direction(),
                options.creativity(),
                options.language(),
                options.maxDeviation(),
                options.themes(),
                options.avoidExisting(),
                clip(original.content(), 18000)
        );
        return llmService.complete(systemPrompt, userPrompt).flatMap(this::parseAiDrafts);
    }

    private Optional<List<VariationDraft>> parseAiDrafts(String raw) {
        try {
            JsonNode root = objectMapper.readTree(extractJson(raw));
            JsonNode array = root.isArray() ? root : root.path("variations");
            if (!array.isArray()) {
                return Optional.empty();
            }
            List<VariationDraft> drafts = new ArrayList<>();
            for (JsonNode node : array) {
                String name = text(node, "name").orElse("Related Skill");
                String fileName = text(node, "fileName").orElse(slugify(name) + ".md");
                String description = text(node, "description").orElse("AI-generated related skill.");
                String difference = text(node, "difference").orElse("Creative specialization generated from the original skill.");
                String content = text(node, "content").orElse("");
                if (!content.isBlank()) {
                    drafts.add(new VariationDraft(fileName, name, description, difference, content, true));
                }
            }
            return Optional.of(drafts);
        } catch (RuntimeException | IOException e) {
            return Optional.empty();
        }
    }

    private List<VariationDraft> fallbackDrafts(OriginalSkillDetail original, GenerationOptions options) {
        List<String> requestedFocus = parseFocusList(options.direction() + "," + options.themes());
        List<String> defaults = List.of("security", "performance", "beginner friendly", "strict review", "java specialization", "team workflow");
        List<String> focus = requestedFocus.isEmpty() ? defaults : requestedFocus;
        List<VariationDraft> drafts = new ArrayList<>();
        for (int i = 0; i < options.count(); i++) {
            String focusName = focus.get(i % focus.size());
            String title = titleCase(focusName) + " " + original.title();
            String slug = slugify(focusName + "-" + original.title());
            String description = "A " + focusName + " variation derived from " + original.fileName() + ".";
            String difference = "Focuses the original skill on " + focusName + " while preserving its core workflow.";
            String body = """
                    # %s

                    ## Purpose

                    Apply the core idea from `%s` in a %s context.

                    ## Source Relationship

                    This variation keeps the original intent, but changes the operating emphasis, checks, examples, and expected outputs for the selected focus.

                    ## Workflow

                    1. Read the user's task and identify where the %s focus changes priorities.
                    2. Preserve the useful structure of the original skill without copying its wording mechanically.
                    3. Adapt examples, validation, and output expectations to the new scenario.
                    4. State caveats when the source skill's assumptions do not fit this variation.

                    ## Validation

                    - The original markdown file remains unchanged.
                    - The variation is stored separately under `skills/variations/%s/`.
                    - The variation can be downloaded as a standalone `.md` skill.
                    """.formatted(title, original.fileName(), focusName, focusName, original.originalId());
            drafts.add(new VariationDraft(slug + ".md", slug, description, difference, body, false));
        }
        return drafts;
    }

    private SkillVariationDetail storeVariation(OriginalSkillDetail original, VariationDraft draft, GenerationOptions options) {
        Path dir = variationDirectory(original.originalId());
        String requestedName = cleanVariationFileName(firstNonBlank(draft.fileName(), draft.name() + ".md"));
        String fileName = uniqueVariationFileName(dir, requestedName, options.avoidExisting());
        String variationId = stripMarkdownExtension(fileName);
        Instant createdAt = Instant.now();
        String content = renderVariationMarkdown(original, draft, fileName, variationId, createdAt);
        Path path = dir.resolve(fileName).normalize();
        ensureInside(dir, path);
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            return readVariation(original.originalId(), variationId).orElseThrow(() -> serverError("Stored variation could not be read", null));
        } catch (IOException e) {
            throw serverError("Unable to store variation", e);
        }
    }

    private String renderVariationMarkdown(OriginalSkillDetail original, VariationDraft draft, String fileName, String variationId, Instant createdAt) {
        String name = slugify(firstNonBlank(draft.name(), variationId));
        String description = firstNonBlank(draft.description(), "Related variation of " + original.fileName());
        String difference = firstNonBlank(draft.difference(), "Creative variation generated from the original skill.");
        String body = stripExistingFrontmatter(firstNonBlank(draft.content(), fallbackDrafts(original, GenerationOptions.defaults()).get(0).content())).trim();
        if (!body.startsWith("#")) {
            body = "# " + titleCase(name.replace("-", " ")) + "\n\n" + body;
        }
        return """
                ---
                name: "%s"
                description: "%s"
                source_original_id: "%s"
                source_file: "%s"
                variation_file: "%s"
                created_at: "%s"
                difference: "%s"
                ---

                %s
                """.formatted(
                escapeYaml(name),
                escapeYaml(description),
                escapeYaml(original.originalId()),
                escapeYaml(original.fileName()),
                escapeYaml(fileName),
                createdAt,
                escapeYaml(difference),
                body
        );
    }

    private Optional<SkillVariationDetail> readVariation(String originalId, String variationId) {
        Path path = variationFile(originalId, variationId);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            Map<String, String> metadata = parseFrontmatter(content);
            String name = stripQuotes(metadata.getOrDefault("name", variationId));
            String description = stripQuotes(metadata.getOrDefault("description", "Generated skill variation."));
            String difference = stripQuotes(metadata.getOrDefault("difference", "Related variation of the original skill."));
            Instant createdAt = parseInstant(stripQuotes(metadata.get("created_at"))).orElse(Instant.EPOCH);
            return Optional.of(new SkillVariationDetail(
                    variationId,
                    originalId,
                    path.getFileName().toString(),
                    name,
                    description,
                    difference,
                    createdAt,
                    Files.size(path),
                    content
            ));
        } catch (IOException e) {
            throw serverError("Unable to read variation", e);
        }
    }

    private Optional<OriginalSkillSummary> readOriginalSummary(Path dir) {
        return readOriginalMetadata(dir).map(metadata -> new OriginalSkillSummary(
                metadata.originalId(),
                metadata.fileName(),
                metadata.title(),
                Instant.parse(metadata.uploadedAt()),
                metadata.sizeBytes(),
                countVariations(metadata.originalId())
        ));
    }

    private void writeOriginalMetadata(Path dir, OriginalMetadata metadata) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("originalId", metadata.originalId());
        root.put("fileName", metadata.fileName());
        root.put("storedFileName", metadata.storedFileName());
        root.put("uploadedAt", metadata.uploadedAt());
        root.put("sizeBytes", metadata.sizeBytes());
        root.put("title", metadata.title());
        Files.writeString(dir.resolve("metadata.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private Optional<OriginalMetadata> readOriginalMetadata(Path dir) {
        Path metadataPath = dir.resolve("metadata.json").normalize();
        ensureInside(dir, metadataPath);
        if (!Files.isRegularFile(metadataPath)) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(metadataPath, StandardCharsets.UTF_8));
            String originalId = text(root, "originalId").orElse(dir.getFileName().toString());
            return Optional.of(new OriginalMetadata(
                    originalId,
                    text(root, "fileName").orElse("unknown.md"),
                    text(root, "storedFileName").orElse(text(root, "fileName").orElse("unknown.md")),
                    text(root, "uploadedAt").orElse(Instant.EPOCH.toString()),
                    root.path("sizeBytes").asLong(0),
                    text(root, "title").orElse(stripMarkdownExtension(text(root, "fileName").orElse("unknown.md")))
            ));
        } catch (IOException e) {
            throw serverError("Unable to read original metadata", e);
        }
    }

    private int countVariations(String originalId) {
        Path dir = variationDirectory(originalId);
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (var stream = Files.list(dir)) {
            return (int) stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }

    private String uniqueOriginalId(String base) {
        String safeBase = base.isBlank() ? "original" : base;
        for (int attempt = 0; attempt < 20; attempt++) {
            String id = safeBase + "-" + UUID.randomUUID().toString().substring(0, 8);
            if (!Files.exists(originalsRoot().resolve(id))) {
                return id;
            }
        }
        throw serverError("Unable to allocate original id", null);
    }

    private String uniqueVariationFileName(Path dir, String requestedName, boolean avoidExisting) {
        String base = stripMarkdownExtension(requestedName);
        String candidate = base + ".md";
        for (int index = 2; Files.exists(dir.resolve(candidate)); index++) {
            candidate = avoidExisting ? base + "-" + index + ".md" : base + "-" + UUID.randomUUID().toString().substring(0, 6) + ".md";
        }
        return candidate;
    }

    private Path originalDirectory(String originalId) {
        assertSafeId(originalId, "original id");
        Path path = originalsRoot().resolve(originalId).normalize();
        ensureInside(originalsRoot(), path);
        return path;
    }

    private Path variationDirectory(String originalId) {
        assertSafeId(originalId, "original id");
        Path path = variationsRoot().resolve(originalId).normalize();
        ensureInside(variationsRoot(), path);
        return path;
    }

    private Path variationFile(String originalId, String variationId) {
        assertSafeId(variationId, "variation id");
        Path dir = variationDirectory(originalId);
        Path path = dir.resolve(variationId + ".md").normalize();
        ensureInside(dir, path);
        return path;
    }

    private Path originalsRoot() {
        Path path = properties.skillsRoot().resolve("originals").normalize();
        ensureInside(properties.skillsRoot(), path);
        return path;
    }

    private Path variationsRoot() {
        Path path = properties.skillsRoot().resolve("variations").normalize();
        ensureInside(properties.skillsRoot(), path);
        return path;
    }

    private static String cleanOriginalFileName(String value) {
        String fileName = baseFileName(value);
        if (fileName.length() > 120) {
            throw badRequest("File name is too long");
        }
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".md")) {
            throw badRequest("Only .md files are accepted");
        }
        if (fileName.startsWith(".") || fileName.contains("..")) {
            throw badRequest("Unsafe markdown file name");
        }
        String cleaned = fileName.replaceAll("[^A-Za-z0-9._ -]", "_").replaceAll("\\s+", " ").trim();
        if (cleaned.isBlank() || cleaned.equals(".md")) {
            throw badRequest("Invalid markdown file name");
        }
        return cleaned;
    }

    private static String cleanVariationFileName(String value) {
        String fileName = baseFileName(value);
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".md")) {
            fileName += ".md";
        }
        String base = slugify(stripMarkdownExtension(fileName));
        return base + ".md";
    }

    private static String baseFileName(String value) {
        String raw = value == null ? "" : value.replace('\\', '/');
        int split = raw.lastIndexOf('/');
        String fileName = split >= 0 ? raw.substring(split + 1) : raw;
        if (fileName.isBlank()) {
            throw badRequest("Missing markdown file name");
        }
        return fileName;
    }

    private static void assertSafeId(String id, String label) {
        if (id == null || !SAFE_ID.matcher(id).matches()) {
            throw badRequest("Invalid " + label);
        }
    }

    private static Map<String, String> parseFrontmatter(String content) {
        String normalized = content.replace("\r\n", "\n");
        if (!normalized.startsWith("---\n")) {
            return Map.of();
        }
        int end = normalized.indexOf("\n---\n", 4);
        if (end < 0) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : normalized.substring(4, end).split("\n")) {
            if (!line.contains(":")) {
                continue;
            }
            int idx = line.indexOf(':');
            values.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
        }
        return values;
    }

    private static String stripExistingFrontmatter(String content) {
        String normalized = content.replace("\r\n", "\n");
        if (!normalized.startsWith("---\n")) {
            return content;
        }
        int end = normalized.indexOf("\n---\n", 4);
        return end < 0 ? content : normalized.substring(end + "\n---\n".length());
    }

    private static Optional<String> titleFromMarkdown(String content) {
        Map<String, String> frontmatter = parseFrontmatter(content);
        String frontmatterName = stripQuotes(frontmatter.get("name"));
        if (frontmatterName != null && !frontmatterName.isBlank()) {
            return Optional.of(frontmatterName);
        }
        return content.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("# "))
                .map(line -> line.substring(2).trim())
                .filter(line -> !line.isBlank())
                .findFirst();
    }

    private static List<String> parseFocusList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Pattern.compile("[,;\\n]").splitAsStream(value)
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
    }

    private static String extractJson(String raw) {
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstLine >= 0 && lastFence > firstLine) {
                text = text.substring(firstLine + 1, lastFence).trim();
            }
        }
        int arrayStart = text.indexOf('[');
        int objectStart = text.indexOf('{');
        int start;
        char opener;
        if (arrayStart >= 0 && (objectStart < 0 || arrayStart < objectStart)) {
            start = arrayStart;
            opener = '[';
        } else {
            start = objectStart;
            opener = '{';
        }
        if (start < 0) {
            return text;
        }
        int end = opener == '[' ? text.lastIndexOf(']') : text.lastIndexOf('}');
        return end > start ? text.substring(start, end + 1) : text.substring(start);
    }

    private static Optional<String> text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return Optional.empty();
        }
        return Optional.of(value.asText()).filter(text -> !text.isBlank());
    }

    private static Optional<Instant> parseInstant(String value) {
        try {
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(Instant.parse(value));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String stripQuotes(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.trim();
        if ((stripped.startsWith("\"") && stripped.endsWith("\"")) || (stripped.startsWith("'") && stripped.endsWith("'"))) {
            return stripped.substring(1, stripped.length() - 1);
        }
        return stripped;
    }

    private static String stripMarkdownExtension(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".md") ? fileName.substring(0, fileName.length() - 3) : fileName;
    }

    private static String slugify(String value) {
        String slug = value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        slug = slug.replaceAll("-+", "-").replaceAll("^-|-$", "");
        return slug.isBlank() ? "untitled" : slug;
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

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private static String clip(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "\n\n[Content clipped for AI context]";
    }

    private static String escapeYaml(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private void ensureInside(Path root, Path target) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(normalizedRoot)) {
            throw badRequest("Path escapes managed skill storage");
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private static ResponseStatusException serverError(String message, Throwable cause) {
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, message, cause);
    }

    private record OriginalMetadata(
            String originalId,
            String fileName,
            String storedFileName,
            String uploadedAt,
            long sizeBytes,
            String title
    ) {
    }

    private record GenerationOptions(
            int count,
            String direction,
            double creativity,
            String language,
            String maxDeviation,
            String themes,
            boolean avoidExisting
    ) {
        static GenerationOptions from(GenerateVariationsRequest request) {
            if (request == null) {
                return defaults();
            }
            int count = request.count() == null ? 3 : Math.max(1, Math.min(12, request.count()));
            double creativity = request.creativity() == null ? 0.6 : Math.max(0.0, Math.min(1.0, request.creativity()));
            return new GenerationOptions(
                    count,
                    blankToDefault(request.direction(), "creative related specializations"),
                    creativity,
                    blankToDefault(request.language(), "same language as the original"),
                    blankToDefault(request.maxDeviation(), "moderate"),
                    blankToDefault(request.themes(), "none"),
                    request.avoidExisting() == null || request.avoidExisting()
            );
        }

        static GenerationOptions defaults() {
            return new GenerationOptions(3, "creative related specializations", 0.6, "same language as the original", "moderate", "none", true);
        }

        private static String blankToDefault(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }

    private record VariationDraft(
            String fileName,
            String name,
            String description,
            String difference,
            String content,
            boolean aiGenerated
    ) {
    }
}
