package io.github.qhung13.asep.integration;

public interface GitLabMergeRequestReader {

    MergeRequestMetadata getMergeRequest(
            long projectId,
            long mergeRequestIid
    );
}