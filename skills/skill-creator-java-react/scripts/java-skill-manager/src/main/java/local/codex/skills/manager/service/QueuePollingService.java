package local.codex.skills.manager.service;

import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import local.codex.skills.manager.SkillManagerProperties;
import local.codex.skills.manager.model.CreateSkillRequest;
import local.codex.skills.manager.model.GeneratedSkill;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class QueuePollingService {
    private static final Pattern SKILL_ID = Pattern.compile("(?im)^\\s*-?\\s*(?:Skill ID|skill_id)\\s*:\\s*`?([A-Za-z0-9_-]+)`?\\s*$");
    private static final Pattern MESSAGE_ID = Pattern.compile("(?im)^\\s*-?\\s*(?:Associated Message ID|Message ID|message_id)\\s*:\\s*`?([A-Za-z0-9_-]+)`?\\s*$");
    private static final Pattern TTL_SECONDS = Pattern.compile("(?im)^\\s*-?\\s*(?:TTL Seconds|ttl_seconds|extend_seconds|seconds)\\s*:\\s*(\\d+)\\s*$");
    private static final Pattern PROMPT_TITLE = Pattern.compile("(?im)^\\s*#\\s*Prompt\\s*:\\s*(.+?)\\s*$");
    private static final Pattern SKILL_NAME = Pattern.compile("(?im)^\\s*-?\\s*(?:Skill Name|skill_name|name)\\s*:\\s*`?\"?([^`\"\\r\\n]+)\"?`?\\s*$");

    private final QueueClient queueClient;
    private final SkillRepository repository;
    private final ValidationReportService validationReportService;
    private final SkillManagerProperties properties;
    private final ObjectMapper objectMapper;

    private volatile PollingStatus status = new PollingStatus(
            Instant.EPOCH,
            0,
            "IDLE",
            "Automatic polling has not run yet.",
            null,
            null
    );

    public QueuePollingService(
            QueueClient queueClient,
            SkillRepository repository,
            ValidationReportService validationReportService,
            SkillManagerProperties properties,
            ObjectMapper objectMapper
    ) {
        this.queueClient = queueClient;
        this.repository = repository;
        this.validationReportService = validationReportService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${skill.manager.queue-poll-fixed-delay-millis:60000}")
    public void pollScheduled() {
        if (properties.queuePollingEnabled()) {
            pollAndProcess();
        }
    }

    public synchronized PollingStatus pollAndProcess() {
        QueueClient.QueueResponse response = queueClient.pollWork(true);
        if (response.statusCode() == 404) {
            return update("QUEUE_EMPTY", "No work is currently queued for phone " + properties.phone(), null, response.statusCode());
        }
        if (!response.success()) {
            return update("QUEUE_ERROR", response.body(), null, response.statusCode());
        }

        ParsedQueueMessage message = parseQueueMessage(response.body());
        if (message.text().isBlank()) {
            return update("EMPTY_MESSAGE", response.body(), message.envelopeId().orElse(null), response.statusCode());
        }

        String text = message.text();
        String upper = text.toUpperCase();
        if (upper.contains("STATUS: PASS")) {
            return update("VALIDATION_PASS", "Validator PASS received.", message.envelopeId().orElse(null), response.statusCode());
        }
        if (upper.contains("STATUS: FAIL")) {
            return update("VALIDATION_FAIL", "Validator FAIL received; manual code changes may be required.", message.envelopeId().orElse(null), response.statusCode());
        }
        if (upper.contains("ACTION: EXTEND_TTL")) {
            GeneratedSkill skill = extendTtl(text);
            QueueClient.QueueResponse submit = validationReportService.submitReport(
                    skill.skillId(),
                    skill.messageId(),
                    properties.reactUrl(),
                    properties.backendUrl(),
                    true
            );
            return update(
                    "TTL_EXTENDED",
                    "Extended " + skill.skillId() + " and submitted validator report with status " + submit.statusCode(),
                    message.envelopeId().orElse(null),
                    response.statusCode()
            );
        }

        GeneratedSkill skill = createSkill(text, message.envelopeId());
        QueueClient.QueueResponse submit = validationReportService.submitReport(
                skill.skillId(),
                skill.messageId(),
                properties.reactUrl(),
                properties.backendUrl(),
                true
        );
        return update(
                "SKILL_CREATED",
                "Created " + skill.skillId() + " and submitted validator report with status " + submit.statusCode(),
                message.envelopeId().orElse(null),
                response.statusCode()
        );
    }

    public PollingStatus status() {
        return status;
    }

    private GeneratedSkill extendTtl(String text) {
        String skillId = firstGroup(SKILL_ID, text).orElse(null);
        String messageId = firstGroup(MESSAGE_ID, text).orElse(null);
        long seconds = firstGroup(TTL_SECONDS, text)
                .map(Long::parseLong)
                .orElse(properties.defaultTtlSeconds());
        return repository.extend(skillId, messageId, seconds);
    }

    private GeneratedSkill createSkill(String text, Optional<String> envelopeId) {
        String messageId = firstGroup(MESSAGE_ID, text)
                .or(() -> envelopeId)
                .map(this::normalizeMessageId)
                .orElse("msg_" + Integer.toUnsignedString(text.hashCode(), 16));
        String skillName = firstGroup(SKILL_NAME, text)
                .or(() -> firstGroup(PROMPT_TITLE, text))
                .map(this::slugify)
                .orElse("queue_task");
        Long ttlSeconds = firstGroup(TTL_SECONDS, text)
                .map(Long::parseLong)
                .orElse(properties.defaultTtlSeconds());
        return repository.create(new CreateSkillRequest(messageId, skillName, text, null, ttlSeconds));
    }

    private ParsedQueueMessage parseQueueMessage(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            Optional<String> envelopeId = textValue(root.path("id"));
            JsonNode message = root.path("message");
            if (message.isObject()) {
                return new ParsedQueueMessage(textValue(message.path("message")).orElse(message.toString()), envelopeId);
            }
            return new ParsedQueueMessage(textValue(message).orElse(root.toString()), envelopeId);
        } catch (Exception ignored) {
            return new ParsedQueueMessage(body, Optional.empty());
        }
    }

    private static Optional<String> textValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }
        if (node.isTextual()) {
            return Optional.of(node.asText());
        }
        return Optional.of(node.toString());
    }

    private static Optional<String> firstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1).trim()) : Optional.empty();
    }

    private String normalizeMessageId(String value) {
        String normalized = slugify(value).replace('-', '_');
        return normalized.startsWith("msg_") ? normalized : "msg_" + normalized;
    }

    private String slugify(String value) {
        String slug = value == null ? "" : value.toLowerCase().replaceAll("[^a-z0-9]+", "_");
        slug = slug.replaceAll("_+", "_").replaceAll("^_|_$", "");
        return slug.isBlank() ? "untitled" : slug;
    }

    private PollingStatus update(String state, String detail, String messageId, int httpStatus) {
        PollingStatus updated = new PollingStatus(Instant.now(), status.pollCount() + 1, state, detail, messageId, httpStatus);
        status = updated;
        return updated;
    }

    private record ParsedQueueMessage(String text, Optional<String> envelopeId) {
    }

    public record PollingStatus(
            Instant checkedAt,
            long pollCount,
            String state,
            String detail,
            String queueMessageId,
            Integer httpStatus
    ) {
    }
}
