package local.codex.skills.manager.service;

import java.time.Instant;
import java.util.List;

import local.codex.skills.manager.model.GeneratedSkill;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class SkillTtlGarbageCollector {
    private final SkillRepository repository;

    public SkillTtlGarbageCollector(SkillRepository repository) {
        this.repository = repository;
    }

    @Scheduled(fixedDelayString = "${skill.manager.gc-fixed-delay-millis:15000}")
    public void deleteExpiredSkills() {
        repository.deleteExpired(Instant.now());
    }

    public List<GeneratedSkill> runOnce() {
        return repository.deleteExpired(Instant.now());
    }
}
