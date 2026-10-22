package io.github.qhung13.asep.integration;

import io.github.qhung13.asep.integration.MergeRequestMetadata.DiffReferences;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One requested revision with the diff evidence returned by GitLab.
 * Neither the state, reported size nor files establish complete review coverage.
 */
public record MergeRequestDiffVersion(
        long projectId,
        long mergeRequestIid,
        long versionId,
        DiffReferences revision,
        String state,
        Optional<String> gitlabReportedSize,
        List<ChangedFileDiff> files
) {

    public MergeRequestDiffVersion {
        if (projectId <= 0 || mergeRequestIid <= 0 || versionId <= 0) {
            throw new IllegalArgumentException("Project ID, MR IID and version ID must be positive");
        }
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(state, "state");
        if (state.isBlank()) {
            throw new IllegalArgumentException("Diff version state must not be blank");
        }
        Objects.requireNonNull(gitlabReportedSize, "gitlabReportedSize");
        files = List.copyOf(files);
    }
}
