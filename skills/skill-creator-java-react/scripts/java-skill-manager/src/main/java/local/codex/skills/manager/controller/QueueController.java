package local.codex.skills.manager.controller;

import java.util.Map;

import local.codex.skills.manager.service.QueueClient;
import local.codex.skills.manager.service.QueuePollingService;
import local.codex.skills.manager.service.SkillQueueWorker;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/queue")
public class QueueController {
    private final SkillQueueWorker queueWorker;
    private final QueuePollingService queuePollingService;

    public QueueController(SkillQueueWorker queueWorker, QueuePollingService queuePollingService) {
        this.queueWorker = queueWorker;
        this.queuePollingService = queuePollingService;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register() {
        QueueClient.QueueResponse response = queueWorker.registerPhone();
        return ResponseEntity.status(response.statusCode()).body(Map.of(
                "statusCode", response.statusCode(),
                "body", response.body()
        ));
    }

    @GetMapping("/poll")
    public ResponseEntity<Map<String, Object>> poll(@RequestParam(name = "registerOn400", defaultValue = "true") boolean registerOn400) {
        QueueClient.QueueResponse response = queueWorker.pollOnce(registerOn400);
        return ResponseEntity.status(response.statusCode()).body(Map.of(
                "statusCode", response.statusCode(),
                "body", response.body()
        ));
    }

    @PostMapping("/poll-and-process")
    public QueuePollingService.PollingStatus pollAndProcess() {
        return queuePollingService.pollAndProcess();
    }

    @GetMapping("/status")
    public QueuePollingService.PollingStatus status() {
        return queuePollingService.status();
    }
}
