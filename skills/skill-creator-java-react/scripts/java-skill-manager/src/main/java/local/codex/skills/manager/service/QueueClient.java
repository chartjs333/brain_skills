package local.codex.skills.manager.service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import local.codex.skills.manager.SkillManagerProperties;
import org.springframework.stereotype.Component;

@Component
public class QueueClient {
    private final HttpClient httpClient;
    private final SkillManagerProperties properties;

    public QueueClient(HttpClient httpClient, SkillManagerProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    public QueueResponse pollWork(boolean registerOn400) {
        QueueResponse response = send("GET", "/work/" + properties.phone(), null, "text/plain", "work");
        if (response.statusCode() == 400
                && registerOn400
                && response.body().contains("not mapped to a Git context")) {
            registerPhone();
            response = send("GET", "/work/" + properties.phone(), null, "text/plain", "work");
        }
        if (response.statusCode() == 404) {
            QueueResponse paired = pollPairedWork();
            if (paired.statusCode() != 404) {
                return paired;
            }
        }
        return response;
    }

    private QueueResponse pollPairedWork() {
        String phone = encoded(properties.phone());
        return send("GET", "/worker/all/" + phone + "?to_phone=" + phone, null, "text/plain", "worker-all");
    }

    public QueueResponse registerPhone() {
        String gitAddress = resolveGitRemote();
        int port = URI.create(properties.baseUrl()).getPort();
        if (port < 0) {
            port = properties.baseUrl().startsWith("https://") ? 443 : 80;
        }
        String body = """
                {"port":%d,"git_address":"%s","phone":"%s"}
                """.formatted(port, jsonEscape(gitAddress), jsonEscape(properties.phone())).trim();
        return send("POST", "/git-config", body, "application/json", "git-config");
    }

    public QueueResponse submitValidation(String body) {
        String jsonBody = "{\"message\":\"" + jsonEscape(body) + "\"}";
        return send("POST", "/test/" + properties.phone(), jsonBody, "application/json", "test");
    }

    public QueueResponse submitPairedValidation(String body, String toPhone) {
        String receiver = toPhone == null || toPhone.isBlank() ? properties.phone() : toPhone;
        String jsonBody = """
                {"message":"%s","from_phone":"%s","to_phone":"%s"}
                """.formatted(jsonEscape(body), jsonEscape(properties.phone()), jsonEscape(receiver)).trim();
        return send("POST", "/tester/all/" + encoded(properties.phone()), jsonBody, "application/json", "tester-all");
    }

    private QueueResponse send(String method, String path, String body, String contentType, String queueName) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(properties.baseUrl().replaceAll("/+$", "") + path))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(10));
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", contentType + "; charset=utf-8")
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new QueueResponse(response.statusCode(), response.body(), queueName);
        } catch (IOException e) {
            throw new IllegalStateException("Queue request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Queue request interrupted", e);
        }
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String resolveGitRemote() {
        ProcessBuilder processBuilder = new ProcessBuilder("git", "-C", properties.repoRoot().toString(), "remote", "get-url", "origin");
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            byte[] output = process.getInputStream().readAllBytes();
            int exitCode = process.waitFor();
            String gitAddress = new String(output, StandardCharsets.UTF_8).trim();
            if (exitCode != 0 || gitAddress.isBlank()) {
                throw new IllegalStateException("Unable to resolve git remote origin: " + gitAddress);
            }
            return gitAddress;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to run git remote lookup", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Git remote lookup interrupted", e);
        }
    }

    private static String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(ch);
            }
        }
        return escaped.toString();
    }

    public record QueueResponse(int statusCode, String body, String queueName) {
        public QueueResponse(int statusCode, String body) {
            this(statusCode, body, "work");
        }

        public boolean success() {
            return statusCode >= 200 && statusCode < 300;
        }

        public Optional<String> emptyReason() {
            return statusCode == 404 ? Optional.of(body) : Optional.empty();
        }
    }
}
