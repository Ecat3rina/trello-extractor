package com.ecaterina;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Minimal Trello REST client (API key + token, no OAuth).
 *
 * <p>Every request goes through a shared {@link RateLimiter}. If Trello still answers
 * 429 (rate limit) or a 5xx error, the request is retried with exponential backoff,
 * honouring the {@code Retry-After} header when present.
 */
public class TrelloClient {

    private static final Logger LOG = Logger.getLogger(TrelloClient.class.getName());
    public static final String DEFAULT_BASE_URL = "https://api.trello.com/1";

    private final String baseUrl;
    private final String apiKey;
    private final String apiToken;
    private final RateLimiter rateLimiter;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final int maxAttempts;
    private final Duration initialBackoff;

    public TrelloClient(String baseUrl, String apiKey, String apiToken, RateLimiter rateLimiter) {
        this(baseUrl, apiKey, apiToken, rateLimiter, 5, Duration.ofSeconds(1));
    }

    public TrelloClient(String baseUrl, String apiKey, String apiToken, RateLimiter rateLimiter,
                        int maxAttempts, Duration initialBackoff) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = requireNonBlank(apiKey, "API key");
        this.apiToken = requireNonBlank(apiToken, "API token");
        this.rateLimiter = rateLimiter;
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** Boards of a Workspace (organization), all fields. */
    public JsonNode getWorkspaceBoards(String workspaceId) throws IOException, InterruptedException {
        return get("/organizations/" + encode(workspaceId) + "/boards", Map.of("fields", "all"));
    }

    /** Members of a Workspace (organization). */
    public JsonNode getWorkspaceMembers(String workspaceId) throws IOException, InterruptedException {
        return get("/organizations/" + encode(workspaceId) + "/members", Map.of("fields", "all"));
    }

    /** Boards the token's user belongs to (used when no Workspace is configured). */
    public JsonNode getMyBoards() throws IOException, InterruptedException {
        return get("/members/me/boards", Map.of("fields", "all"));
    }

    /** Members of one board. */
    public JsonNode getBoardMembers(String boardId) throws IOException, InterruptedException {
        return get("/boards/" + encode(boardId) + "/members", Map.of("fields", "all"));
    }

    /** Performs a GET and parses the JSON response. */
    public JsonNode get(String path, Map<String, String> params) throws IOException, InterruptedException {
        Map<String, String> query = new LinkedHashMap<>(params);
        query.put("key", apiKey);
        query.put("token", apiToken);
        String queryString = query.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path + "?" + queryString))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        Duration backoff = initialBackoff;
        for (int attempt = 1; ; attempt++) {
            rateLimiter.acquire();
            HttpResponse<String> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                if (attempt >= maxAttempts) {
                    throw e;
                }
                LOG.warning("GET " + path + " failed (" + e.getMessage() + "), retrying in " + backoff.toMillis() + " ms");
                Thread.sleep(backoff.toMillis());
                backoff = backoff.multipliedBy(2);
                continue;
            }

            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return mapper.readTree(response.body());
            }
            boolean retryable = status == 429 || status >= 500;
            if (!retryable || attempt >= maxAttempts) {
                throw new TrelloApiException(status, "GET " + path + " returned HTTP " + status + ": " + truncate(response.body()));
            }
            Duration wait = retryAfter(response).orElse(backoff);
            LOG.warning("GET " + path + " returned HTTP " + status + ", retry " + attempt + "/" + (maxAttempts - 1)
                    + " in " + wait.toMillis() + " ms");
            Thread.sleep(wait.toMillis());
            backoff = backoff.multipliedBy(2);
        }
    }

    private static java.util.Optional<Duration> retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After").flatMap(v -> {
            try {
                return java.util.Optional.of(Duration.ofSeconds(Long.parseLong(v.trim())));
            } catch (NumberFormatException e) {
                return java.util.Optional.empty();
            }
        });
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String truncate(String s) {
        return s == null ? "" : (s.length() > 200 ? s.substring(0, 200) + "..." : s);
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Trello " + name + " is missing");
        }
        return value;
    }

    /** Non-retryable (or retries exhausted) error from the Trello API. */
    public static class TrelloApiException extends IOException {
        private final int status;

        public TrelloApiException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }
}
