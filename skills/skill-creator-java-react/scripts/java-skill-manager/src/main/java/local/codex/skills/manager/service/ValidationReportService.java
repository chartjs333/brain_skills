package local.codex.skills.manager.service;

import java.time.Instant;
import java.util.List;

import local.codex.skills.manager.SkillManagerProperties;
import local.codex.skills.manager.model.GeneratedSkill;
import org.springframework.stereotype.Service;

@Service
public class ValidationReportService {
    private final SkillManagerProperties properties;
    private final SkillRepository repository;
    private final QueueClient queueClient;

    public ValidationReportService(SkillManagerProperties properties, SkillRepository repository, QueueClient queueClient) {
        this.properties = properties;
        this.repository = repository;
        this.queueClient = queueClient;
    }

    public String buildReport(String skillId, String messageId, String reactUrl, String backendUrl, boolean cleanerActive) {
        GeneratedSkill skill = repository.findSkill(skillId, messageId)
                .orElseThrow(() -> new IllegalArgumentException("Generated skill not found"));
        List<GeneratedSkill> active = repository.findAll().stream()
                .filter(candidate -> !candidate.expiredAt(Instant.now()))
                .toList();
        String resolvedReactUrl = isBlank(reactUrl) ? "http://localhost:<react_port>" : reactUrl;
        String resolvedBackendUrl = isBlank(backendUrl) ? "http://localhost:<java_port>" : backendUrl;
        String routeUrl = resolvedReactUrl.replaceAll("/+$", "") + "/skills/" + skill.skillId();
        String javaRunning = isBlank(backendUrl) ? "NO" : "YES (Spring Boot Port: " + portLabel(backendUrl) + ")";
        String reactRunning = isBlank(reactUrl) ? "NO" : "YES (Vite Port: " + portLabel(reactUrl) + ")";
        String cleaner = cleanerActive ? "ACTIVE" : "NOT_RUN";
        return """
                TO: SkillValidator
                FROM: SkillCreatorJavaReact
                STATUS: READY_FOR_VALIDATION
                PHONE: %s
                IMPLEMENTATION_LANGUAGE: Java 17 / Spring Boot

                GENERATED_SKILL_INFO:
                - Sequence Number: %s
                - Associated Message ID: %s
                - Skill ID: %s
                - Skill Name: %s
                - TTL Seconds: %d
                - Created At: %s
                - Expires At: %s
                - Active Skills Count in System: %d

                APPLICATION_URLS:
                - React UI URL: %s
                - Java Backend API URL: %s
                - Direct Skill Route: %s

                TEST_SCENARIO (For Java Skill %s / Msg %s):
                1. Open UI route: %s
                2. Execute representative actions in React UI.
                3. Check Java REST API: POST %s/api/v1/skills/%s/execute
                4. Check Java TTL API: GET %s/api/v1/skills/%s/ttl
                5. Check Java TTL extension API: POST %s/api/v1/skills/ttl/extend
                6. Check Java TTL GC API: POST %s/api/v1/skills/gc

                GENERATED_ARTIFACTS:
                - [Spec] %s
                - [Java Service] %s
                - [Java Controller] %s
                - [React Component] %s

                LOCAL_TESTING_STATUS:
                - Java Backend Running: %s
                - React Dev Server Running: %s
                - Java Scheduled TTL GC: %s
                """.formatted(
                properties.phone(),
                skill.seqNumber(),
                skill.messageId(),
                skill.skillId(),
                skill.skillName(),
                skill.ttlSeconds(),
                skill.createdAt(),
                skill.expiresAt(),
                active.size(),
                resolvedReactUrl,
                resolvedBackendUrl,
                routeUrl,
                skill.seqNumber(),
                skill.messageId(),
                routeUrl,
                resolvedBackendUrl.replaceAll("/+$", ""),
                skill.skillId(),
                resolvedBackendUrl.replaceAll("/+$", ""),
                skill.skillId(),
                resolvedBackendUrl.replaceAll("/+$", ""),
                resolvedBackendUrl.replaceAll("/+$", ""),
                skill.directory().resolve("SKILL.md"),
                skill.directory().resolve("generated/java").resolve(classBase(skill.seqNumber(), skill.messageId()) + "Service.java"),
                skill.directory().resolve("generated/java").resolve(classBase(skill.seqNumber(), skill.messageId()) + "Controller.java"),
                skill.directory().resolve("generated/react").resolve(classBase(skill.seqNumber(), skill.messageId()) + "Component.jsx"),
                javaRunning,
                reactRunning,
                cleaner
        );
    }

    public QueueClient.QueueResponse submitReport(String skillId, String messageId, String reactUrl, String backendUrl, boolean cleanerActive) {
        return queueClient.submitValidation(buildReport(skillId, messageId, reactUrl, backendUrl, cleanerActive));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String classBase(String seq, String messageId) {
        String messageToken = messageId == null ? "Unknown" : messageId.toLowerCase().replaceAll("[^a-z0-9]+", "_");
        if (messageToken.startsWith("msg_")) {
            messageToken = messageToken.substring(4);
        }
        messageToken = messageToken.replaceAll("[^a-zA-Z0-9]", "");
        if (messageToken.isBlank()) {
            messageToken = "Unknown";
        }
        return "Skill" + seq + "Msg" + Character.toUpperCase(messageToken.charAt(0)) + messageToken.substring(1);
    }

    private static String portLabel(String url) {
        try {
            int port = java.net.URI.create(url).getPort();
            return port < 0 ? "default" : Integer.toString(port);
        } catch (IllegalArgumentException e) {
            return "unknown";
        }
    }
}
