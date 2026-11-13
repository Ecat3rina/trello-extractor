package com.ecaterina;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigTest {

    private static final Map<String, String> ENV = Map.of("TRELLO_API_KEY", "k", "TRELLO_API_TOKEN", "t");

    /** A properties file that does not exist, so the developer's real trello.properties is never read by tests. */
    private static final Path NO_FILE = Path.of("target", "no-such-trello.properties");

    private static Config load(String[] args, Map<String, String> env) throws Exception {
        return Config.load(args, env, NO_FILE);
    }

    @Test
    void parsesSupportedIntervals() {
        assertEquals(Duration.ofMinutes(5), Config.parseInterval("5m"));
        assertEquals(Duration.ofSeconds(30), Config.parseInterval("30s"));
        assertEquals(Duration.ofSeconds(30), Config.parseInterval("30"));
        assertEquals(Duration.ofHours(1), Config.parseInterval("1H"));
    }

    @Test
    void rejectsInvalidIntervals() {
        assertThrows(IllegalArgumentException.class, () -> Config.parseInterval("abc"));
        assertThrows(IllegalArgumentException.class, () -> Config.parseInterval("0s"));
        assertThrows(IllegalArgumentException.class, () -> Config.parseInterval("-5m"));
    }

    @Test
    void defaultsToFiveMinutes() throws Exception {
        Config c = load(new String[0], ENV);
        assertEquals(Duration.ofMinutes(5), c.interval());
        assertFalse(c.once());
        assertNull(c.workspaceId());
    }

    @Test
    void commandLineOverridesEnvironment() throws Exception {
        Map<String, String> env = Map.of("TRELLO_API_KEY", "k", "TRELLO_API_TOKEN", "t",
                "TRELLO_INTERVAL", "5m", "TRELLO_WORKSPACE_ID", "env-ws");
        Config c = load(new String[]{"--interval=30s", "--workspace=cli-ws", "--once"}, env);
        assertEquals(Duration.ofSeconds(30), c.interval());
        assertEquals("cli-ws", c.workspaceId());
        assertTrue(c.once());
    }

    @Test
    void readsPropertiesFile(@TempDir Path dir) throws Exception {
        Path props = dir.resolve("my.properties");
        Files.writeString(props, "TRELLO_API_KEY=fk\nTRELLO_API_TOKEN=ft\nTRELLO_WORKSPACE_ID=fw\n");
        Config c = Config.load(new String[]{"--config=" + props}, Map.of());
        assertEquals("fk", c.apiKey());
        assertEquals("fw", c.workspaceId());
    }

    @Test
    void failsWithoutCredentials() {
        assertThrows(IllegalArgumentException.class,
                () -> Config.load(new String[]{"--config=does-not-exist.properties"}, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> load(new String[0], Map.of("TRELLO_API_KEY", "k")));
    }

    @Test
    void toStringHidesSecrets() throws Exception {
        String s = load(new String[0], Map.of("TRELLO_API_KEY", "SECRETKEY", "TRELLO_API_TOKEN", "SECRETTOKEN")).toString();
        assertFalse(s.contains("SECRET"));
    }
}
