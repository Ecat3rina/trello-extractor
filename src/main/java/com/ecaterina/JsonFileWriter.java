package com.ecaterina;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Writes each entity to its own file: {@code <outputDir>/<category>/<id>.json}.
 *
 * <ul>
 *   <li>Files are written atomically (temp file + move), so a reader never sees half a file.</li>
 *   <li>A file is only rewritten when its content changed, which keeps frequent runs cheap.</li>
 *   <li>{@link #prune} removes files of entities that no longer exist in Trello.</li>
 * </ul>
 */
public class JsonFileWriter {

    public enum Result { CREATED, UPDATED, UNCHANGED }

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final Path outputDir;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public JsonFileWriter(Path outputDir) {
        this.outputDir = outputDir;
    }

    public Path getOutputDir() {
        return outputDir;
    }

    public Result write(String category, JsonNode entity) throws IOException {
        JsonNode idNode = entity.get("id");
        if (idNode == null || !SAFE_ID.matcher(idNode.asText()).matches()) {
            throw new IllegalArgumentException("Entity in '" + category + "' has a missing or invalid id: " + idNode);
        }
        Path dir = outputDir.resolve(category);
        Files.createDirectories(dir);
        Path target = dir.resolve(idNode.asText() + ".json");
        byte[] content = mapper.writeValueAsBytes(entity);

        boolean exists = Files.exists(target);
        if (exists && Arrays.equals(Files.readAllBytes(target), content)) {
            return Result.UNCHANGED;
        }
        Path tmp = Files.createTempFile(dir, idNode.asText(), ".tmp");
        try {
            Files.write(tmp, content);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return exists ? Result.UPDATED : Result.CREATED;
    }

    /** Deletes {@code <category>/<id>.json} files whose id is not in {@code keepIds}. Returns how many were deleted. */
    public int prune(String category, Set<String> keepIds) throws IOException {
        Path dir = outputDir.resolve(category);
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        int deleted = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                String id = name.substring(0, name.length() - ".json".length());
                if (!keepIds.contains(id)) {
                    Files.delete(file);
                    deleted++;
                }
            }
        }
        return deleted;
    }
}
