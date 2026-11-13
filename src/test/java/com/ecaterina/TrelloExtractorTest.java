package com.ecaterina;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class TrelloExtractorTest {

    private static final String BOARDS = "[{\"id\":\"b1\",\"name\":\"Sprint\"},{\"id\":\"b2\",\"name\":\"Backlog\"}]";
    private static final String MEMBERS = "[{\"id\":\"m1\",\"fullName\":\"Ana\"},"
            + "{\"id\":\"m2\",\"fullName\":\"Ion\"},{\"id\":\"m3\",\"fullName\":\"Maria\"}]";

    @TempDir
    Path out;

    private FakeTrelloServer server;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        server = new FakeTrelloServer();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private TrelloExtractor extractor(String workspace) {
        TrelloClient client = new TrelloClient(server.baseUrl(), "k", "t",
                new RateLimiter(100, Duration.ofSeconds(10)), 3, Duration.ofMillis(10));
        return new TrelloExtractor(client, new JsonFileWriter(out), workspace, 4);
    }

    private long countFiles(String category) throws Exception {
        try (var files = Files.list(out.resolve(category))) {
            return files.count();
        }
    }

    @Test
    void workspaceModeWritesOneFilePerMemberAndBoardWithTwoApiCalls() throws Exception {
        server.on("/organizations/ws/boards", 200, BOARDS)
              .on("/organizations/ws/members", 200, MEMBERS);

        TrelloExtractor.Summary summary = extractor("ws").extract();

        assertEquals(2, summary.boards());
        assertEquals(3, summary.members());
        assertEquals(2, countFiles("boards"));
        assertEquals(3, countFiles("members"));
        assertEquals("Maria", mapper.readTree(out.resolve("members/m3.json").toFile()).get("fullName").asText());
        assertEquals("Sprint", mapper.readTree(out.resolve("boards/b1.json").toFile()).get("name").asText());
        assertEquals(2, server.requests.size());
    }

    @Test
    void withoutWorkspaceCollectsAndDeduplicatesBoardMembers() throws Exception {
        server.on("/members/me/boards", 200, BOARDS)
              .on("/boards/b1/members", 200, "[{\"id\":\"m1\"},{\"id\":\"m2\"}]")
              .on("/boards/b2/members", 200, "[{\"id\":\"m2\"},{\"id\":\"m3\"}]");

        TrelloExtractor.Summary summary = extractor(null).extract();

        assertEquals(2, summary.boards());
        assertEquals(3, summary.members());
        assertEquals(3, countFiles("members"));
    }

    @Test
    void secondRunWithSameDataWritesNothing() throws Exception {
        server.on("/organizations/ws/boards", 200, BOARDS)
              .on("/organizations/ws/members", 200, MEMBERS);

        extractor("ws").extract();
        TrelloExtractor.Summary second = extractor("ws").extract();

        assertEquals(0, second.written());
        assertEquals(5, second.unchanged());
    }

    @Test
    void removesFilesOfDeletedBoards() throws Exception {
        server.on("/organizations/ws/boards", 200, BOARDS)
              .on("/organizations/ws/members", 200, MEMBERS);
        extractor("ws").extract();

        server.on("/organizations/ws/boards", 200, "[{\"id\":\"b1\",\"name\":\"Sprint\"}]");
        TrelloExtractor.Summary summary = extractor("ws").extract();

        assertEquals(1, summary.deleted());
        assertFalse(Files.exists(out.resolve("boards/b2.json")));
    }

    @Test
    void survivesRateLimitDuringExtraction() throws Exception {
        server.once("/organizations/ws/members", 429, "limit")
              .on("/organizations/ws/boards", 200, BOARDS)
              .on("/organizations/ws/members", 200, MEMBERS);

        assertEquals(3, extractor("ws").extract().members());
    }

    @Test
    void failsClearlyOnUnexpectedResponse() {
        server.on("/organizations/ws/boards", 200, "{\"not\":\"an array\"}");

        assertThrows(java.io.IOException.class, () -> extractor("ws").extract());
    }
}
