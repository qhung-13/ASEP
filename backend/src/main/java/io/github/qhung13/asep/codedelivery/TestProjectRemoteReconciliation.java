package io.github.qhung13.asep.codedelivery;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static io.github.qhung13.asep.codedelivery.DeliveryRecord.State;

/** Pure classification of scoped read evidence after a recorded send. No transport, retry or ledger writer.
 * A live caller must load RecordedSend from its durable ledger, not accept it from an agent/request.
 * CONFIRMED here describes remote facts only; it never establishes current mutation/workflow authority.
 */
public final class TestProjectRemoteReconciliation {
    private TestProjectRemoteReconciliation() {}
    public record RecordedSend(UUID operationId, String requestHash, boolean pushClaimed, boolean mrClaimed,
            Set<Long> knownMrIids, String lastConfirmedPushHead, boolean residualRace) {
        public RecordedSend {
            knownMrIids = Set.copyOf(knownMrIids);
            if (lastConfirmedPushHead != null && (!pushClaimed || !lastConfirmedPushHead.matches("[0-9a-f]{40}"))) throw new IllegalArgumentException("Invalid recorded head");
            if (knownMrIids.stream().anyMatch(id -> id < 1) || mrClaimed && !pushClaimed
                    || !mrClaimed && !knownMrIids.isEmpty()) throw new IllegalArgumentException("Invalid recorded send");
        }
    }
    public record Branch(long projectId, String name, String head) {}
    public record MergeRequest(long projectId, long sourceProjectId, long targetProjectId, long iid,
            String source, String target, String head, boolean draft, String title, String body) {
        @Override public String toString() { return "TestProjectMrObservation[remote text redacted]"; }
    }
    /** Complete means an exact scoped, non-truncated successful read, not that an empty list proves no effect. */
    public record Observation(boolean readable, boolean branchComplete, Branch branch, boolean mrListComplete,
            List<MergeRequest> mergeRequests, String targetHead) {
        public Observation { mergeRequests = List.copyOf(mergeRequests); }
        @Override public String toString() { return "TestProjectRemoteObservation[redacted]"; }
    }
    public record Result(State branch, State mergeRequest, Set<Long> retainedMrIids, String lastConfirmedPushHead, boolean residualRace) {
        public Result { retainedMrIids = Set.copyOf(retainedMrIids); }
        /** Not overall delivery acceptance. Policy/enrollment/receipts must still be independently revalidated. */
        public boolean exactRemoteCandidateObserved() {
            return branch == State.CONFIRMED && mergeRequest == State.CONFIRMED && !residualRace;
        }
    }
    public static Result reconcile(TestProjectDeliveryRequest r, RecordedSend recorded, Observation remote) {
        if (r == null || recorded == null || !r.operationId().equals(recorded.operationId())
                || !r.bodyHash().equals(recorded.requestHash())
                || recorded.lastConfirmedPushHead() != null && !r.candidateCommitSha().equals(recorded.lastConfirmedPushHead())) throw new IllegalArgumentException("Unbound reconciliation request");
        var retained = new HashSet<>(recorded.knownMrIids());
        boolean race = recorded.residualRace();
        String confirmedHead = recorded.lastConfirmedPushHead();
        if (remote == null || !remote.readable()) return new Result(
                recorded.pushClaimed() ? State.MANUAL_RECONCILIATION_REQUIRED : State.PLANNED,
                recorded.mrClaimed() ? State.MANUAL_RECONCILIATION_REQUIRED : State.PLANNED, retained, confirmedHead, race);
        boolean branchMatches = remote.branchComplete() && matches(r, remote.branch());
        if (branchMatches && recorded.pushClaimed()) confirmedHead = r.candidateCommitSha();
        State branch = !remote.branchComplete() ? State.MANUAL_RECONCILIATION_REQUIRED
                : remote.branch() == null ? recorded.pushClaimed() ? State.UNKNOWN : State.PLANNED
                : branchMatches && recorded.pushClaimed() ? State.CONFIRMED : State.BLOCKED;
        race |= recorded.pushClaimed() && remote.branchComplete() && remote.branch() != null && !branchMatches;
        var expected = TestProjectDeliveryPreparation.render(r);
        var seen = new HashSet<Long>();
        boolean allMatch = true;
        for (MergeRequest mr : remote.mergeRequests()) {
            boolean scoped = mr.projectId() == r.projectId() && mr.sourceProjectId() == r.projectId()
                    && mr.targetProjectId() == r.projectId() && r.sourceBranch().equals(mr.source()) && mr.iid() > 0;
            boolean attributed = scoped && expected.title().equals(mr.title()) && expected.body().equals(mr.body());
            if (recorded.mrClaimed() && attributed) retained.add(mr.iid());
            allMatch &= scoped && attributed && r.targetBranch().equals(mr.target())
                    && r.candidateCommitSha().equals(mr.head()) && mr.draft() && seen.add(mr.iid());
        }
        boolean targetMoved = remote.targetHead() != null && !r.expectedTargetSha().equals(remote.targetHead());
        race |= targetMoved;
        if (!recorded.mrClaimed()) return new Result(branch,
                !remote.mrListComplete() ? State.MANUAL_RECONCILIATION_REQUIRED
                        : remote.mergeRequests().isEmpty() ? State.PLANNED : State.BLOCKED, retained, confirmedHead, race);
        if (!remote.mrListComplete() || !remote.branchComplete() || remote.targetHead() == null) {
            return new Result(branch, State.MANUAL_RECONCILIATION_REQUIRED, retained, confirmedHead, race);
        }
        // Absence after a send is UNKNOWN, never permission to issue another create.
        if (remote.mergeRequests().isEmpty()) return new Result(branch, State.UNKNOWN, retained, confirmedHead, race);
        if (remote.mergeRequests().size() != 1 || retained.size() > 1) {
            return new Result(branch, State.UNKNOWN, retained, confirmedHead, race || !allMatch || !branchMatches);
        }
        MergeRequest mr = remote.mergeRequests().getFirst();
        if (!allMatch || !branchMatches || !retained.contains(mr.iid())) {
            return new Result(branch, State.BLOCKED, retained, confirmedHead, true);
        }
        return new Result(branch, State.CONFIRMED, retained, confirmedHead, race);
    }
    private static boolean matches(TestProjectDeliveryRequest r, Branch branch) {
        return branch != null && branch.projectId() == r.projectId() && r.sourceBranch().equals(branch.name())
                && r.candidateCommitSha().equals(branch.head());
    }
}
