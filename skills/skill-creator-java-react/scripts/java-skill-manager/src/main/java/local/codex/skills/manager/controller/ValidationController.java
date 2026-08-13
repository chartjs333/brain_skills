package local.codex.skills.manager.controller;

import java.util.Map;

import local.codex.skills.manager.service.QueueClient;
import local.codex.skills.manager.service.ValidationReportService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/validation")
public class ValidationController {
    private final ValidationReportService validationReportService;

    public ValidationController(ValidationReportService validationReportService) {
        this.validationReportService = validationReportService;
    }

    @GetMapping(value = "/report", produces = MediaType.TEXT_PLAIN_VALUE)
    public String report(
            @RequestParam(name = "skillId", required = false) String skillId,
            @RequestParam(name = "messageId", required = false) String messageId,
            @RequestParam(name = "reactUrl", required = false) String reactUrl,
            @RequestParam(name = "backendUrl", required = false) String backendUrl,
            @RequestParam(name = "cleanerActive", defaultValue = "false") boolean cleanerActive
    ) {
        return validationReportService.buildReport(skillId, messageId, reactUrl, backendUrl, cleanerActive);
    }

    @PostMapping("/submit")
    public ResponseEntity<Map<String, Object>> submit(
            @RequestParam(name = "skillId", required = false) String skillId,
            @RequestParam(name = "messageId", required = false) String messageId,
            @RequestParam(name = "reactUrl", required = false) String reactUrl,
            @RequestParam(name = "backendUrl", required = false) String backendUrl,
            @RequestParam(name = "cleanerActive", defaultValue = "false") boolean cleanerActive
    ) {
        QueueClient.QueueResponse response = validationReportService.submitReport(skillId, messageId, reactUrl, backendUrl, cleanerActive);
        return ResponseEntity.status(response.statusCode()).body(Map.of(
                "statusCode", response.statusCode(),
                "body", response.body()
        ));
    }

    @PostMapping(value = "/submit-message", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<Map<String, Object>> submitMessage(
            @RequestBody String body,
            @RequestParam(name = "paired", defaultValue = "false") boolean paired,
            @RequestParam(name = "toPhone", required = false) String toPhone
    ) {
        QueueClient.QueueResponse response = validationReportService.submitMessage(body, paired, toPhone);
        return ResponseEntity.status(response.statusCode()).body(Map.of(
                "statusCode", response.statusCode(),
                "body", response.body(),
                "queue", response.queueName()
        ));
    }
}
