package local.codex.skills.manager.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import local.codex.skills.manager.SkillManagerProperties;
import org.springframework.stereotype.Component;

@Component
public class EnvFileLoader {
    private final SkillManagerProperties properties;

    public EnvFileLoader(SkillManagerProperties properties) {
        this.properties = properties;
    }

    public Optional<String> first(String... keys) {
        Map<String, String> envFile = loadEnvFile();
        for (String key : keys) {
            String environmentValue = System.getenv(key);
            if (!isBlank(environmentValue)) {
                return Optional.of(environmentValue.trim());
            }
            String fileValue = envFile.get(key);
            if (!isBlank(fileValue)) {
                return Optional.of(fileValue.trim());
            }
        }
        return Optional.empty();
    }

    private Map<String, String> loadEnvFile() {
        Path repoRoot = properties.repoRoot().toAbsolutePath().normalize();
        Path envFile = repoRoot.resolve(".env").normalize();
        if (!envFile.startsWith(repoRoot) || !Files.isRegularFile(envFile)) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        try {
            for (String rawLine : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
                String line = rawLine.trim();
                if (line.isBlank() || line.startsWith("#") || !line.contains("=")) {
                    continue;
                }
                int split = line.indexOf('=');
                String key = line.substring(0, split).trim();
                String value = stripOptionalQuotes(line.substring(split + 1).trim());
                if (!key.isBlank()) {
                    values.put(key, value);
                }
            }
        } catch (IOException ignored) {
            return Map.of();
        }
        return values;
    }

    private static String stripOptionalQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
