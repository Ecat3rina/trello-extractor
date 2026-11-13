package com.ecaterina;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;

/**
 * Extracts members and boards from Trello and writes each one to its own JSON file.
 *
 * <p>With a Workspace id the whole extraction costs 2 API calls (one list call for
 * boards, one for members), no matter how many boards or members there are.
 * Without a Workspace id it uses the token owner's boards and collects members
 * board by board, in parallel; the shared rate limiter keeps the parallel calls
 * within Trello's limits.
 */
public class TrelloExtractor {

    private static final Logger LOG = Logger.getLogger(TrelloExtractor.class.getName());
    public static final String MEMBERS = "members";
    public static final String BOARDS = "boards";

    private final TrelloClient client;
    private final JsonFileWriter writer;
    private final String workspaceId;
    private final int parallelism;

    public TrelloExtractor(TrelloClient client, JsonFileWriter writer, String workspaceId, int parallelism) {
        this.client = client;
        this.writer = writer;
        this.workspaceId = (workspaceId == null || workspaceId.isBlank()) ? null : workspaceId;
        this.parallelism = Math.max(1, parallelism);
    }

    /** Summary of one run. */
    public record Summary(int boards, int members, int written, int unchanged, int deleted) {
        @Override
        public String toString() {
            return boards + " boards, " + members + " members (" + written + " written, "
                    + unchanged + " unchanged, " + deleted + " deleted)";
        }
    }

    public Summary extract() throws IOException, InterruptedException {
        JsonNode boards = workspaceId != null ? client.getWorkspaceBoards(workspaceId) : client.getMyBoards();
        Map<String, JsonNode> boardsById = indexById(boards);

        Map<String, JsonNode> membersById = workspaceId != null
                ? indexById(client.getWorkspaceMembers(workspaceId))
                : collectBoardMembers(boardsById.keySet());

        int written = 0;
        int unchanged = 0;
        for (JsonNode board : boardsById.values()) {
            if (writer.write(BOARDS, board) == JsonFileWriter.Result.UNCHANGED) unchanged++; else written++;
        }
        for (JsonNode member : membersById.values()) {
            if (writer.write(MEMBERS, member) == JsonFileWriter.Result.UNCHANGED) unchanged++; else written++;
        }
        int deleted = writer.prune(BOARDS, boardsById.keySet()) + writer.prune(MEMBERS, membersById.keySet());

        Summary summary = new Summary(boardsById.size(), membersById.size(), written, unchanged, deleted);
        LOG.info("Extraction finished: " + summary + " -> " + writer.getOutputDir().toAbsolutePath());
        return summary;
    }

    private Map<String, JsonNode> collectBoardMembers(Iterable<String> boardIds) throws IOException, InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        try {
            List<Future<JsonNode>> futures = new ArrayList<>();
            for (String boardId : boardIds) {
                futures.add(pool.submit(() -> client.getBoardMembers(boardId)));
            }
            Map<String, JsonNode> members = new LinkedHashMap<>();
            for (Future<JsonNode> future : futures) {
                try {
                    members.putAll(indexById(future.get()));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof IOException io) throw io;
                    if (cause instanceof InterruptedException ie) throw ie;
                    throw new IOException(cause);
                }
            }
            return members;
        } finally {
            pool.shutdownNow();
        }
    }

    private static Map<String, JsonNode> indexById(JsonNode array) throws IOException {
        if (array == null || !array.isArray()) {
            throw new IOException("Expected a JSON array from Trello but got: " + array);
        }
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode node : array) {
            byId.put(node.path("id").asText(), node);
        }
        return byId;
    }
}
