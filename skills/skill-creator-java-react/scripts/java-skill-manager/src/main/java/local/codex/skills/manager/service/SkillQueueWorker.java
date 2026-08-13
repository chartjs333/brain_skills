package local.codex.skills.manager.service;

import org.springframework.stereotype.Service;

@Service
public class SkillQueueWorker {
    private final QueueClient queueClient;

    public SkillQueueWorker(QueueClient queueClient) {
        this.queueClient = queueClient;
    }

    public QueueClient.QueueResponse pollOnce(boolean registerOn400) {
        return queueClient.pollWork(registerOn400);
    }

    public QueueClient.QueueResponse registerPhone() {
        return queueClient.registerPhone();
    }
}
