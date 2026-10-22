package io.github.qhung13.asep.integration;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** projectId is the target project; an unknown source project must never be inferred from it. */
public record MergeRequestMetadata(
        long projectId,
        long iid,
        String title,
        String state,
        String sourceBranch,
        String targetBranch,
        Optional<DiffReferences> diffReferences,
        OptionalLong sourceProjectId
) {

    public MergeRequestMetadata {
        if (projectId <= 0 || iid <= 0) {
            throw new IllegalArgumentException(
                    "Project ID and MR IID must be positive"
            );
        }

        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(sourceBranch, "sourceBranch");
        Objects.requireNonNull(targetBranch, "targetBranch");
        Objects.requireNonNull(diffReferences, "diffReferences");
        Objects.requireNonNull(sourceProjectId, "sourceProjectId");
        if (sourceProjectId.isPresent() && sourceProjectId.getAsLong() <= 0) {
            throw new IllegalArgumentException("Source project ID must be positive when present");
        }
    }

    public record DiffReferences(
            String baseSha,
            String startSha,
            String headSha
    ) {
        public DiffReferences {
            if (baseSha == null || baseSha.isBlank()
                    || startSha == null || startSha.isBlank()
                    || headSha == null || headSha.isBlank()) {
                throw new IllegalArgumentException(
                        "Diff references must contain complete SHAs"
                );
            }
        }
    }
}
