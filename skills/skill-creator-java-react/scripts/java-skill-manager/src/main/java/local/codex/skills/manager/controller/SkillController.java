package local.codex.skills.manager.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import local.codex.skills.manager.model.CreateSkillRequest;
import local.codex.skills.manager.model.ExtendTtlRequest;
import local.codex.skills.manager.model.ExecuteSkillRequest;
import local.codex.skills.manager.model.GeneratedSkill;
import local.codex.skills.manager.model.SkillExecutionResult;
import local.codex.skills.manager.model.TtlStatus;
import local.codex.skills.manager.service.SkillRepository;
import local.codex.skills.manager.service.SkillTtlGarbageCollector;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/skills")
public class SkillController {
    private final SkillRepository repository;
    private final SkillTtlGarbageCollector garbageCollector;

    public SkillController(SkillRepository repository, SkillTtlGarbageCollector garbageCollector) {
        this.repository = repository;
        this.garbageCollector = garbageCollector;
    }

    @GetMapping
    public List<GeneratedSkill> list() {
        return repository.findAll();
    }

    @PostMapping
    public GeneratedSkill create(@Valid @RequestBody CreateSkillRequest request) {
        return repository.create(request);
    }

    @PostMapping("/{skillId}/execute")
    public ResponseEntity<SkillExecutionResult> execute(@PathVariable("skillId") String skillId, @RequestBody(required = false) ExecuteSkillRequest request) {
        return repository.findSkill(skillId, null)
                .map(skill -> ResponseEntity.ok(new SkillExecutionResult(
                        skill.skillId(),
                        skill.expiredAt(Instant.now()) ? "EXPIRED" : "EXECUTED",
                        Instant.now(),
                        request == null ? Map.of() : request.input()
                )))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{skillId}/ttl")
    public ResponseEntity<TtlStatus> ttl(@PathVariable("skillId") String skillId) {
        return repository.findSkill(skillId, null)
                .map(skill -> ResponseEntity.ok(TtlStatus.from(skill, Instant.now())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/ttl/extend")
    public GeneratedSkill extendTtl(@Valid @RequestBody ExtendTtlRequest request) {
        return repository.extend(request.skillId(), request.messageId(), request.seconds());
    }

    @PostMapping("/gc")
    public List<GeneratedSkill> gc() {
        return garbageCollector.runOnce();
    }
}
