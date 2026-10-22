package io.github.qhung13.asep.integration;

import java.util.List;

/** Read-only access to explicitly selected GitLab MR diff versions. */
public interface GitLabMergeRequestDiffReader {

    List<DiffVersionSummary> listDiffVersions(long projectId, long mergeRequestIid);

    MergeRequestDiffVersion getDiffVersion(long projectId, long mergeRequestIid, long versionId);
}
