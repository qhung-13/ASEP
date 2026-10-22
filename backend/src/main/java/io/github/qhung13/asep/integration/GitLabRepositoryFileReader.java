package io.github.qhung13.asep.integration;

/**
 * Reads one caller-selected logical repository path at an immutable commit.
 * The caller supplies the actual source project, authorization and context budget.
 */
public interface GitLabRepositoryFileReader {

    RepositoryFileContent readTextFile(long projectId, String commitSha, String filePath);
}
