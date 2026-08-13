package local.codex.skills.manager.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import local.codex.skills.manager.SkillManagerProperties;
import org.springframework.stereotype.Service;

@Service
public class GitPublishService {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(120);

    private final SkillManagerProperties properties;

    public GitPublishService(SkillManagerProperties properties) {
        this.properties = properties;
    }

    public PublishResult publishValidatedSkill(String skillId, String seqNumber, String messageId) {
        String branch = currentBranch();
        String commitMessage = "feat(skill): add validated skill %s (seq: %s, msg: %s)"
                .formatted(safeToken(skillId, "unknown_skill"), safeToken(seqNumber, "unknown_seq"), safeToken(messageId, "unknown_msg"));

        requireGit(List.of("add", "."));
        CommandResult diff = runGit(List.of("diff", "--cached", "--quiet"), true);
        if (diff.exitCode() > 1) {
            throw new IllegalStateException("Unable to inspect staged git changes: " + diff.output());
        }

        boolean committed = diff.exitCode() == 1;
        if (committed) {
            ensureNoSensitiveFilesStaged();
            requireGit(List.of("commit", "-m", commitMessage));
        }

        CommandResult push = requireGit(List.of("push", "origin", branch));
        return new PublishResult(committed, branch, commitMessage, push.output().trim());
    }

    private String currentBranch() {
        CommandResult result = requireGit(List.of("branch", "--show-current"));
        String branch = result.output().trim();
        if (branch.isBlank()) {
            throw new IllegalStateException("Unable to push after PASS because git is in detached HEAD state");
        }
        return branch;
    }

    private void ensureNoSensitiveFilesStaged() {
        CommandResult result = requireGit(List.of("diff", "--cached", "--name-only"));
        List<String> sensitive = result.output().lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .filter(GitPublishService::isSensitivePath)
                .toList();
        if (!sensitive.isEmpty()) {
            unstage(sensitive);
            throw new IllegalStateException("Refusing to commit sensitive file(s): " + String.join(", ", sensitive));
        }
    }

    private void unstage(List<String> paths) {
        List<String> command = new ArrayList<>();
        command.add("restore");
        command.add("--staged");
        command.add("--");
        command.addAll(paths);
        runGit(command, true);
    }

    private static boolean isSensitivePath(String path) {
        String normalized = path.replace('\\', '/').toLowerCase();
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
        return fileName.equals(".env")
                || fileName.startsWith(".env.")
                || fileName.endsWith(".pem")
                || fileName.endsWith(".key")
                || fileName.equals("id_rsa")
                || fileName.equals("id_ed25519");
    }

    private CommandResult requireGit(List<String> args) {
        CommandResult result = runGit(args, false);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + result.output());
        }
        return result;
    }

    private CommandResult runGit(List<String> args, boolean allowNonZero) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(args);
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(properties.repoRoot().toFile());
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            boolean completed = process.waitFor(COMMAND_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new IllegalStateException("git " + String.join(" ", args) + " timed out");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.exitValue();
            if (!allowNonZero && exitCode != 0) {
                return new CommandResult(exitCode, output);
            }
            return new CommandResult(exitCode, output);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to run git " + String.join(" ", args), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("git " + String.join(" ", args) + " interrupted", e);
        }
    }

    private static String safeToken(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String safe = value.trim().replaceAll("[^A-Za-z0-9_.-]+", "_");
        return safe.isBlank() ? fallback : safe;
    }

    private record CommandResult(int exitCode, String output) {
    }

    public record PublishResult(boolean committed, String branch, String commitMessage, String pushOutput) {
        public String summary() {
            if (committed) {
                return "committed and pushed branch " + branch + " with message: " + commitMessage;
            }
            return "no local changes to commit; pushed branch " + branch;
        }
    }
}
