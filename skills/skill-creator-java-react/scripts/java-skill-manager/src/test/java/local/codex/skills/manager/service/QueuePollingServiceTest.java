package local.codex.skills.manager.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import local.codex.skills.manager.SkillManagerProperties;
import local.codex.skills.manager.model.CreateSkillRequest;
import local.codex.skills.manager.model.GeneratedSkill;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QueuePollingServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void createsSkillFromQueuedPromptAndSubmitsValidationReport() {
        TestFixture fixture = fixture();
        fixture.queueClient.pollResponse = new QueueClient.QueueResponse(200, """
                {"id":"abc123","message":{"message":"# Prompt: Sample Parser\\nTTL Seconds: 120\\nCreate a focused parser skill."}}
                """);

        QueuePollingService.PollingStatus status = fixture.pollingService.pollAndProcess();

        assertThat(status.state()).isEqualTo("SKILL_CREATED");
        assertThat(fixture.repository.findAll())
                .extracting(GeneratedSkill::skillId)
                .containsExactly("skill_0001_msg_abc123_sample_parser");
        assertThat(fixture.queueClient.submittedValidation)
                .contains("Skill ID: skill_0001_msg_abc123_sample_parser")
                .contains("React UI URL: http://localhost:5176")
                .contains("Java Backend API URL: http://localhost:8082");
    }

    @Test
    void extendsTtlFromQueuedActionAndSubmitsValidationReport() {
        TestFixture fixture = fixture();
        GeneratedSkill created = fixture.repository.create(new CreateSkillRequest(
                "msg_2000",
                "sample_parser",
                "Create a focused parser skill.",
                null,
                120L
        ));
        fixture.queueClient.pollResponse = new QueueClient.QueueResponse(200, """
                {"id":"extend-1","message":{"message":"ACTION: EXTEND_TTL\\nSkill ID: %s\\nseconds: 60"}}
                """.formatted(created.skillId()));

        QueuePollingService.PollingStatus status = fixture.pollingService.pollAndProcess();

        GeneratedSkill extended = fixture.repository.findSkill(created.skillId(), null).orElseThrow();
        assertThat(status.state()).isEqualTo("TTL_EXTENDED");
        assertThat(extended.ttlSeconds()).isEqualTo(180);
        assertThat(fixture.queueClient.submittedValidation)
                .contains("Skill ID: " + created.skillId())
                .contains("TTL Seconds: 180");
    }

    private TestFixture fixture() {
        SkillManagerProperties properties = new SkillManagerProperties(
                "http://localhost:8025",
                "9301",
                tempDir,
                tempDir.resolve("skills"),
                3600,
                1000,
                false,
                60000,
                "http://localhost:8082",
                "http://localhost:5176"
        );
        SkillRepository repository = new SkillRepository(properties);
        FakeQueueClient queueClient = new FakeQueueClient(properties);
        ValidationReportService validationReportService = new ValidationReportService(properties, repository, queueClient);
        FakeGitPublishService gitPublishService = new FakeGitPublishService(properties);
        QueuePollingService pollingService = new QueuePollingService(
                queueClient,
                repository,
                validationReportService,
                gitPublishService,
                properties,
                new ObjectMapper()
        );
        return new TestFixture(repository, queueClient, gitPublishService, pollingService);
    }

    private record TestFixture(
            SkillRepository repository,
            FakeQueueClient queueClient,
            FakeGitPublishService gitPublishService,
            QueuePollingService pollingService
    ) {
    }

    @Test
    void publishesGitChangesWhenValidatorPassArrives() {
        TestFixture fixture = fixture();
        fixture.queueClient.pollResponse = new QueueClient.QueueResponse(200, """
                {"id":"pass-1","message":{"message":"STATUS: PASS\\n- Skill ID: skill_0002_msg_abc123_sample_parser\\n- Sequence Number: 0002\\n- Associated Message ID: abc123"}}
                """);

        QueuePollingService.PollingStatus status = fixture.pollingService.pollAndProcess();

        assertThat(status.state()).isEqualTo("VALIDATION_PASS");
        assertThat(status.detail()).contains("committed and pushed branch main");
        assertThat(fixture.gitPublishService.skillId).isEqualTo("skill_0002_msg_abc123_sample_parser");
        assertThat(fixture.gitPublishService.seqNumber).isEqualTo("0002");
        assertThat(fixture.gitPublishService.messageId).isEqualTo("abc123");
    }

    @Test
    void noisyFailReportDoesNotPublishWhenBodyMentionsPassText() {
        TestFixture fixture = fixture();
        fixture.queueClient.pollResponse = new QueueClient.QueueResponse(200, """
                {"id":"fail-1","message":{"message":"TO: SkillCreatorJavaReact\\nSTATUS: FAIL\\n\\nREMAINING_BLOCKER:\\nParser must ignore diagnostic text after the header.\\nSTATUS: PASS\\nEndpoint /queue/status OK."}}
                """);

        QueuePollingService.PollingStatus status = fixture.pollingService.pollAndProcess();

        assertThat(status.state()).isEqualTo("VALIDATION_FAIL");
        assertThat(fixture.gitPublishService.skillId).isNull();
        assertThat(fixture.gitPublishService.seqNumber).isNull();
        assertThat(fixture.gitPublishService.messageId).isNull();
    }

    private static final class FakeQueueClient extends QueueClient {
        private QueueResponse pollResponse = new QueueResponse(404, "");
        private String submittedValidation;

        private FakeQueueClient(SkillManagerProperties properties) {
            super(HttpClient.newHttpClient(), properties);
        }

        @Override
        public QueueResponse pollWork(boolean registerOn400) {
            return pollResponse;
        }

        @Override
        public QueueResponse submitValidation(String body) {
            submittedValidation = body;
            return new QueueResponse(201, "queued");
        }
    }

    private static final class FakeGitPublishService extends GitPublishService {
        private String skillId;
        private String seqNumber;
        private String messageId;

        private FakeGitPublishService(SkillManagerProperties properties) {
            super(properties);
        }

        @Override
        public PublishResult publishValidatedSkill(String skillId, String seqNumber, String messageId) {
            this.skillId = skillId;
            this.seqNumber = seqNumber;
            this.messageId = messageId;
            return new PublishResult(true, "main", "test commit", "");
        }
    }
}
