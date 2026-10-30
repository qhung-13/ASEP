package io.github.qhung13.asep.integration;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
        /** Complete first/only offset page; links are never followed. */
        public static CollectionObservation singlePage(int count,Map<String,List<String>> headers) {
            java.util.function.Function<String,String> header=name->{var values=headers.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase(name)).flatMap(e->e.getValue().stream()).toList();return values.size()==1?values.getFirst().trim():null;};
            boolean complete=count<100&&"1".equals(header.apply("X-Page"))&&"100".equals(header.apply("X-Per-Page"))
                &&Integer.toString(count).equals(header.apply("X-Total"))&&("1".equals(header.apply("X-Total-Pages"))||count==0&&"0".equals(header.apply("X-Total-Pages")))
                &&"".equals(header.apply("X-Next-Page"))&&(header.apply("Link")==null||header.apply("Link").isEmpty())
                &&headers.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase("Link")).allMatch(e->e.getValue().stream().allMatch(String::isBlank));
            return new CollectionObservation(count,complete);
        }
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
