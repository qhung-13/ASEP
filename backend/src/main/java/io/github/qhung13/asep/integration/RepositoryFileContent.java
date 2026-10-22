package io.github.qhung13.asep.integration;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Transient UTF-8 source with requested provenance; does not establish review coverage. */
public record RepositoryFileContent(
        long projectId,
        String commitSha,
        String filePath,
        String text,
        int byteSize
) {

    public RepositoryFileContent {
        if (projectId <= 0) {
            throw new IllegalArgumentException("Project ID must be positive");
        }
        if (commitSha == null || !commitSha.matches("(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})")) {
            throw new IllegalArgumentException("Commit SHA must be a full hexadecimal hash");
        }
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("File path must not be blank");
        }
        Objects.requireNonNull(text, "text");
        if (byteSize < 0 || byteSize != text.getBytes(StandardCharsets.UTF_8).length) {
            throw new IllegalArgumentException("Byte size must match UTF-8 text size");
        }
    }

    @Override
    public String toString() {
        return "RepositoryFileContent[projectId=" + projectId + ", byteSize=" + byteSize + "]";
    }
}
