package io.github.qhung13.asep.integration;

import java.util.Objects;

public record DiffVersionSummary(
        long versionId,
        MergeRequestMetadata.DiffReferences revision,
        String state
) {

    public DiffVersionSummary {
        if (versionId <= 0) {
            throw new IllegalArgumentException(
                    "Diff version ID must be positive"
            );
        }

        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(state, "state");

        if (state.isBlank()) {
            throw new IllegalArgumentException(
                    "Diff version state must not be blank"
            );
        }
    }
}