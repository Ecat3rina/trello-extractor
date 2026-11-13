package com.ecaterina;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-process HTTP server that imitates the Trello API endpoints used by the extractor. */
class FakeTrelloServer implements AutoCloseable {

    record Response(int status, String body) { }

    private final HttpServer server;
    private final Map<String, Deque<Response>> queued = new ConcurrentHashMap<>();
    private final Map<String, Response> fixed = new ConcurrentHashMap<>();
    final List<String> requests = new CopyOnWriteArrayList<>();

    FakeTrelloServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/1", exchange -> {
            String path = exchange.getRequestURI().getPath().substring("/1".length());
            requests.add(path + "?" + exchange.getRequestURI().getRawQuery());
            Response r;
            Deque<Response> q = queued.get(path);
            if (q != null && !q.isEmpty()) {
                r = q.poll();
            } else {
                r = fixed.getOrDefault(path, new Response(404, "not found"));
            }
            byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(r.status(), bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
    }

    /** Always answer {@code path} with this response. */
    FakeTrelloServer on(String path, int status, String body) {
        fixed.put(path, new Response(status, body));
        return this;
    }

    /** Answer the next request to {@code path} with this response (takes priority over {@link #on}). */
    FakeTrelloServer once(String path, int status, String body) {
        queued.computeIfAbsent(path, k -> new ArrayDeque<>()).add(new Response(status, body));
        return this;
    }

    long count(String path) {
        return requests.stream().filter(r -> r.startsWith(path + "?")).count();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/1";
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
