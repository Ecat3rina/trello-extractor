package com.ecaterina;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class JsonFileWriterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path dir;

    private JsonNode json(String s) throws Exception {
        return mapper.readTree(s);
    }

    @Test
    void writesEachEntityToItsOwnFile() throws Exception {
        JsonFileWriter writer = new JsonFileWriter(dir);

        writer.write("boards", json("{\"id\":\"b1\",\"name\":\"One\"}"));
        writer.write("boards", json("{\"id\":\"b2\",\"name\":\"Two\"}"));

        assertEquals("One", mapper.readTree(dir.resolve("boards/b1.json").toFile()).get("name").asText());
        assertEquals("Two", mapper.readTree(dir.resolve("boards/b2.json").toFile()).get("name").asText());
    }

    @Test
    void reportsCreatedUpdatedAndUnchanged() throws Exception {
        JsonFileWriter writer = new JsonFileWriter(dir);

        assertEquals(JsonFileWriter.Result.CREATED, writer.write("members", json("{\"id\":\"m1\",\"fullName\":\"A\"}")));
        assertEquals(JsonFileWriter.Result.UNCHANGED, writer.write("members", json("{\"id\":\"m1\",\"fullName\":\"A\"}")));
        assertEquals(JsonFileWriter.Result.UPDATED, writer.write("members", json("{\"id\":\"m1\",\"fullName\":\"B\"}")));
    }

    @Test
    void leavesNoTemporaryFiles() throws Exception {
        JsonFileWriter writer = new JsonFileWriter(dir);
        writer.write("boards", json("{\"id\":\"b1\"}"));
        writer.write("boards", json("{\"id\":\"b1\",\"x\":1}"));

        try (var files = Files.list(dir.resolve("boards"))) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void prunesEntitiesThatNoLongerExist() throws Exception {
        JsonFileWriter writer = new JsonFileWriter(dir);
        writer.write("boards", json("{\"id\":\"b1\"}"));
        writer.write("boards", json("{\"id\":\"b2\"}"));

        assertEquals(1, writer.prune("boards", Set.of("b1")));

        assertTrue(Files.exists(dir.resolve("boards/b1.json")));
        assertFalse(Files.exists(dir.resolve("boards/b2.json")));
    }

    @Test
    void rejectsUnsafeOrMissingIds() throws Exception {
        JsonFileWriter writer = new JsonFileWriter(dir);
        assertThrows(IllegalArgumentException.class, () -> writer.write("boards", json("{\"name\":\"no id\"}")));
        assertThrows(IllegalArgumentException.class, () -> writer.write("boards", json("{\"id\":\"../evil\"}")));
    }
}
