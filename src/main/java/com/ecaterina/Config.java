package com.ecaterina;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Application settings. Each value is read, in order of priority, from:
 * command-line arguments, environment variables, then an optional
 * {@code trello.properties} file in the working directory.
 *
 * <pre>
 *   TRELLO_API_KEY       (required)
 *   TRELLO_API_TOKEN     (required)
 *   TRELLO_WORKSPACE_ID  (optional; id or short name of the Workspace)
 *   TRELLO_OUTPUT_DIR    (optional, default "output")
 *   TRELLO_INTERVAL      (optional, default "5m"; e.g. 30s, 5m, 1h)
 * </pre>
 *
 * Command-line: {@code --interval=30s}, {@code --once}, {@code --output=dir},
 * {@code --workspace=id}, {@code --config=file.properties}.
 */
public record Config(String apiKey, String apiToken, String workspaceId, Path outputDir,
                     Duration interval, boolean once, String baseUrl) {

    private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*(s|m|h)");

    public static Config load(String[] args, Map<String, String> env) throws IOException {
        return load(args, env, Path.of("trello.properties"));
    }

    /** Same as {@link #load(String[], Map)} but with a custom default properties file (used by tests). */
    public static Config load(String[] args, Map<String, String> env, Path defaultPropertiesFile) throws IOException {
        Properties file = new Properties();
        String configPath = argValue(args, "--config");
        Path propsPath = configPath != null ? Path.of(configPath) : defaultPropertiesFile;
        if (Files.exists(propsPath)) {
            try (Reader reader = Files.newBufferedReader(propsPath)) {
                file.load(reader);
            }
        } else if (configPath != null) {
            throw new IllegalArgumentException("Config file not found: " + propsPath.toAbsolutePath());
        }

        String key = pick(null, env, file, "TRELLO_API_KEY");
        String token = pick(null, env, file, "TRELLO_API_TOKEN");
        String workspace = pick(argValue(args, "--workspace"), env, file, "TRELLO_WORKSPACE_ID");
        String output = pick(argValue(args, "--output"), env, file, "TRELLO_OUTPUT_DIR");
        String interval = pick(argValue(args, "--interval"), env, file, "TRELLO_INTERVAL");
        String baseUrl = pick(null, env, file, "TRELLO_BASE_URL");
        boolean once = false;
        for (String arg : args) {
            if (arg.equals("--once")) once = true;
        }

        if (key == null || token == null) {
            throw new IllegalArgumentException(
                    "TRELLO_API_KEY and TRELLO_API_TOKEN must be set (environment variables or trello.properties)");
        }
        return new Config(key, token, workspace,
                Path.of(output != null ? output : "output"),
                parseInterval(interval != null ? interval : "5m"),
                once,
                baseUrl != null ? baseUrl : TrelloClient.DEFAULT_BASE_URL);
    }

    /** Parses "30s", "5m", "1h" (also "30", read as seconds). */
    public static Duration parseInterval(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        if (t.matches("\\d+")) {
            t = t + "s";
        }
        Matcher m = DURATION.matcher(t);
        if (!m.matches()) {
            throw new IllegalArgumentException("Invalid interval '" + text + "'. Use e.g. 30s, 5m or 1h");
        }
        long n = Long.parseLong(m.group(1));
        Duration d = switch (m.group(2)) {
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            default -> Duration.ofHours(n);
        };
        if (d.isZero()) {
            throw new IllegalArgumentException("Interval must be greater than 0");
        }
        return d;
    }

    private static String argValue(String[] args, String name) {
        for (String arg : args) {
            if (arg.startsWith(name + "=")) {
                return arg.substring(name.length() + 1);
            }
        }
        return null;
    }

    private static String pick(String fromArgs, Map<String, String> env, Properties file, String name) {
        if (fromArgs != null && !fromArgs.isBlank()) return fromArgs.trim();
        String v = env.get(name);
        if (v != null && !v.isBlank()) return v.trim();
        v = file.getProperty(name);
        return (v != null && !v.isBlank()) ? v.trim() : null;
    }

    /** Never print the secrets. */
    @Override
    public String toString() {
        return "Config[workspace=" + (workspaceId == null ? "(token owner's boards)" : workspaceId)
                + ", output=" + outputDir.toAbsolutePath() + ", interval=" + interval.toSeconds() + "s, once=" + once + "]";
    }
}
