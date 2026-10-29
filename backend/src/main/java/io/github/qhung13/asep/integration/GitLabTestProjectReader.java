package io.github.qhung13.asep.integration;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Read-only enrollment observations. An empty collection is not a safe-CI or Owner authorization proof. */
public interface GitLabTestProjectReader {
    Inspection inspect(long enrolledProjectId, String targetBranch);

    enum Gap { PROJECT, TARGET, PERMISSIONS, PROTECTED_BRANCHES, RUNNERS, VARIABLES, ENVIRONMENTS, PIPELINES }
    record CollectionObservation(int count, boolean complete) {
        public CollectionObservation {
            if (count < 0) throw new IllegalArgumentException("Invalid observation count");
        }
        public static CollectionObservation unavailable() { return new CollectionObservation(0, false); }
    }
    record Project(long id, String path, String visibility, boolean archived, String defaultBranch,
            String buildsAccessLevel, Boolean sharedRunnersEnabled, int effectiveAccessLevel) {
        @Override public String toString() { return "GitLabTestProject[metadata redacted]"; }
    }
    record Target(String name, String headSha, boolean protectedBranch, boolean canPush) {}
    record Pipeline(long id, String sha, String status, String source) {}
    /** The entire collection window; consumers must age evidence from startedAt, not completedAt. */
    record Inspection(long requestedProjectId, String requestedTarget, Project project, Target target,
            CollectionObservation protectedBranches, CollectionObservation runners, CollectionObservation variables,
            CollectionObservation environments, CollectionObservation pipelines, List<Pipeline> observedPipelines,
            Set<Gap> gaps, Instant startedAt, Instant completedAt) {
        public Inspection {
            observedPipelines = List.copyOf(observedPipelines);
            gaps = Set.copyOf(gaps);
        }
        // No inferred subscription tier, inherited-variable safety, secret values or deployment authorization.
        @Override public String toString() { return "GitLabTestProjectInspection[gaps=" + gaps + "]"; }
    }
}
