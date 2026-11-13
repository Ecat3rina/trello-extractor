package com.ecaterina;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class AppTest {

    @Test
    void rateLimitStaysBelowTrelloTokenLimit() {
        // Trello: 100 requests per 10 seconds per token
        assertEquals(Duration.ofSeconds(10), App.RATE_WINDOW);
        assertTrue(App.REQUESTS_PER_WINDOW < 100);
    }

    @Test
    void buildsExtractorFromConfig() {
        Config config = new Config("k", "t", "ws", Path.of("output"), Duration.ofSeconds(30), true,
                TrelloClient.DEFAULT_BASE_URL);
        assertNotNull(App.createExtractor(config));
    }
}
