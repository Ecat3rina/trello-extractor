package com.ecaterina;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TrelloClientTest {

    private FakeTrelloServer server;
    private TrelloClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new FakeTrelloServer();
        client = new TrelloClient(server.baseUrl(), "my-key", "my-token",
                new RateLimiter(100, Duration.ofSeconds(10)), 3, Duration.ofMillis(10));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void sendsKeyAndTokenAsQueryParameters() throws Exception {
        server.on("/members/me/boards", 200, "[]");

        client.getMyBoards();

        String request = server.requests.get(0);
        assertTrue(request.contains("key=my-key"), request);
        assertTrue(request.contains("token=my-token"), request);
        assertTrue(request.contains("fields=all"), request);
    }

    @Test
    void parsesJsonResponse() throws Exception {
        server.on("/organizations/ws1/boards", 200, "[{\"id\":\"b1\",\"name\":\"Board 1\"}]");

        JsonNode boards = client.getWorkspaceBoards("ws1");

        assertEquals(1, boards.size());
        assertEquals("Board 1", boards.get(0).get("name").asText());
    }

    @Test
    void retriesWhenRateLimitedAndThenSucceeds() throws Exception {
        server.once("/boards/b1/members", 429, "{\"message\":\"API_TOKEN_LIMIT_EXCEEDED\"}")
              .once("/boards/b1/members", 429, "{\"message\":\"API_TOKEN_LIMIT_EXCEEDED\"}")
              .on("/boards/b1/members", 200, "[{\"id\":\"m1\"}]");

        JsonNode members = client.getBoardMembers("b1");

        assertEquals("m1", members.get(0).get("id").asText());
        assertEquals(3, server.count("/boards/b1/members"));
    }

    @Test
    void retriesOnServerError() throws Exception {
        server.once("/members/me/boards", 503, "busy").on("/members/me/boards", 200, "[]");

        assertEquals(0, client.getMyBoards().size());
        assertEquals(2, server.count("/members/me/boards"));
    }

    @Test
    void givesUpAfterMaxAttempts() {
        server.on("/members/me/boards", 429, "limit");

        TrelloClient.TrelloApiException e =
                assertThrows(TrelloClient.TrelloApiException.class, () -> client.getMyBoards());

        assertEquals(429, e.getStatus());
        assertEquals(3, server.count("/members/me/boards"));
    }

    @Test
    void doesNotRetryOnUnauthorized() {
        server.on("/members/me/boards", 401, "invalid token");

        TrelloClient.TrelloApiException e =
                assertThrows(TrelloClient.TrelloApiException.class, () -> client.getMyBoards());

        assertEquals(401, e.getStatus());
        assertEquals(1, server.count("/members/me/boards"));
        assertFalse(e.getMessage().contains("my-token"), "token must not leak into error messages");
    }

    @Test
    void encodesPathSegments() throws Exception {
        server.on("/organizations/my team/members", 200, "[]");

        client.get("/organizations/" + java.net.URLEncoder.encode("my team", "UTF-8").replace("+", "%20") + "/members",
                Map.of());

        assertEquals(1, server.count("/organizations/my team/members"));
    }

    @Test
    void requiresCredentials() {
        RateLimiter limiter = new RateLimiter(1, Duration.ofSeconds(1));
        assertThrows(IllegalArgumentException.class, () -> new TrelloClient("http://x", "", "t", limiter));
        assertThrows(IllegalArgumentException.class, () -> new TrelloClient("http://x", "k", null, limiter));
    }
}
