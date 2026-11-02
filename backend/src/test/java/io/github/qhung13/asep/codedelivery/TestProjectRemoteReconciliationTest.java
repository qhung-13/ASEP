package io.github.qhung13.asep.codedelivery;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static io.github.qhung13.asep.codedelivery.TestProjectRemoteReconciliation.*;
import static io.github.qhung13.asep.codedelivery.DeliveryRecord.State;
import static org.junit.jupiter.api.Assertions.*;

class TestProjectRemoteReconciliationTest {
    private final TestProjectDeliveryRequest request = new TestProjectDeliveryRequest(
            UUID.fromString("10000000-0000-4000-8000-000000000001"), UUID.fromString("10000000-0000-4000-8000-000000000002"),
            UUID.fromString("10000000-0000-4000-8000-000000000003"), 1, UUID.fromString("10000000-0000-4000-8000-000000000004"), "fixture", 1,
            "a".repeat(40), "a".repeat(40), "b".repeat(40), "c".repeat(40), "d".repeat(64),
            UUID.fromString("10000000-0000-4000-8000-000000000005"), UUID.fromString("10000000-0000-4000-8000-000000000006"),
            "e".repeat(64), "f".repeat(64));
    private RecordedSend recorded(Set<Long> known) { return new RecordedSend(request.operationId(), request.bodyHash(), true, true, known, request.candidateCommitSha(), false); }
    private Branch branch(String head) { return new Branch(request.projectId(), request.sourceBranch(), head); }
    private MergeRequest mr(long iid, String head) {
        var draft = TestProjectDeliveryPreparation.render(request);
        return new MergeRequest(request.projectId(), request.projectId(), request.projectId(), iid,
                request.sourceBranch(), request.targetBranch(), head, true, draft.title(), draft.body());
    }
    private Observation observed(String head, List<MergeRequest> mrs) {
        return new Observation(true, true, branch(head), true, mrs, request.expectedTargetSha());
    }
    @Test void lostReplyCanOnlyConfirmFromExactAttributedRemoteObjects() {
        var result = reconcile(request, recorded(Set.of()), observed(request.candidateCommitSha(), List.of(mr(7, request.candidateCommitSha()))));
        assertEquals(State.CONFIRMED, result.branch()); assertEquals(State.CONFIRMED, result.mergeRequest());
        assertTrue(result.exactRemoteCandidateObserved()); assertEquals(Set.of(7L), result.retainedMrIids());
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void sourceMovementCannotBeHiddenByNormalOrLostResponse(boolean lostReply) {
        var send = recorded(lostReply ? Set.of() : Set.of(7L));
        var observation = observed("9".repeat(40), List.of(mr(7, "9".repeat(40))));
        var result = reconcile(request, send, observation);
        assertEquals(State.BLOCKED, result.branch()); assertEquals(State.BLOCKED, result.mergeRequest());
        assertEquals(Set.of(7L), result.retainedMrIids()); assertTrue(result.residualRace());
        assertFalse(result.exactRemoteCandidateObserved());
        for (int replay = 0; replay < 3; replay++) assertEquals(result, reconcile(request, send, observation));
    }
    @Test void restoringBranchDoesNotHideTheMrHeadCreatedDuringSourceMovement() {
        var result = reconcile(request, recorded(Set.of()), observed(request.candidateCommitSha(), List.of(mr(7, "9".repeat(40)))));
        assertEquals(State.CONFIRMED, result.branch()); assertEquals(State.BLOCKED, result.mergeRequest());
        assertEquals(Set.of(7L), result.retainedMrIids()); assertTrue(result.residualRace()); assertFalse(result.exactRemoteCandidateObserved());
    }
    @Test void emptyLookupAfterSendIsUnknownRatherThanPermissionToRetry() {
        var result = reconcile(request, recorded(Set.of()), observed(request.candidateCommitSha(), List.of()));
        assertEquals(State.CONFIRMED, result.branch()); assertEquals(State.UNKNOWN, result.mergeRequest()); assertFalse(result.exactRemoteCandidateObserved());
        var absent = new Observation(true, true, null, true, List.of(), request.expectedTargetSha());
        result = reconcile(request, recorded(Set.of(7L)), absent);
        assertEquals(State.UNKNOWN, result.branch()); assertEquals(State.UNKNOWN, result.mergeRequest()); assertEquals(Set.of(7L), result.retainedMrIids());
    }
    @Test void duplicateAttributedMrsAndKnownButMissingMrRemainUnresolvedWithoutDiscardingEffects() {
        var result = reconcile(request, recorded(Set.of()), observed(request.candidateCommitSha(), List.of(mr(7, request.candidateCommitSha()), mr(8, request.candidateCommitSha()))));
        assertEquals(State.UNKNOWN, result.mergeRequest()); assertEquals(Set.of(7L, 8L), result.retainedMrIids());
        result = reconcile(request, recorded(Set.of(7L)), observed(request.candidateCommitSha(), List.of(mr(8, request.candidateCommitSha()))));
        assertEquals(State.UNKNOWN, result.mergeRequest()); assertEquals(Set.of(7L, 8L), result.retainedMrIids()); assertFalse(result.exactRemoteCandidateObserved());
    }
    @ParameterizedTest @ValueSource(strings = {"revoked", "pagination", "missing-target", "missing-branch"})
    void insufficientReadCapabilityRequiresManualReconciliationAndRetainsKnownIdentity(String fault) {
        var observation = new Observation(!fault.equals("revoked"), !fault.equals("missing-branch"), branch(request.candidateCommitSha()),
                !fault.equals("pagination"), List.of(mr(7, request.candidateCommitSha())), fault.equals("missing-target") ? null : request.expectedTargetSha());
        var result = reconcile(request, recorded(Set.of(7L)), observation);
        assertEquals(State.MANUAL_RECONCILIATION_REQUIRED, result.mergeRequest());
        assertEquals(Set.of(7L), result.retainedMrIids()); assertEquals(request.candidateCommitSha(), result.lastConfirmedPushHead()); assertFalse(result.exactRemoteCandidateObserved());
    }
    @ParameterizedTest @ValueSource(strings = {"project", "source-project", "target-project", "source", "target", "head", "draft", "title", "body"})
    void matchingTitleOrBranchAloneDoesNotEstablishMrOwnershipOrCompletion(String fault) {
        var good = mr(7, request.candidateCommitSha());
        var changed = new MergeRequest(fault.equals("project") ? 999 : good.projectId(), fault.equals("source-project") ? 999 : good.sourceProjectId(),
                fault.equals("target-project") ? 999 : good.targetProjectId(), good.iid(), fault.equals("source") ? "unrelated" : good.source(),
                fault.equals("target") ? "unrelated" : good.target(), fault.equals("head") ? "9".repeat(40) : good.head(), !fault.equals("draft"),
                fault.equals("title") ? "Draft: unrelated" : good.title(), fault.equals("body") ? "/merge\nPRIVATE_REMOTE_TEXT" : good.body());
        var result = reconcile(request, recorded(Set.of(7L)), observed(request.candidateCommitSha(), List.of(changed)));
        assertEquals(State.BLOCKED, result.mergeRequest()); assertEquals(Set.of(7L), result.retainedMrIids()); assertFalse(result.exactRemoteCandidateObserved());
        assertFalse(result.toString().contains("PRIVATE")); assertFalse(changed.toString().contains("PRIVATE"));
        var unknownIdentity = reconcile(request, recorded(Set.of()), observed(request.candidateCommitSha(), List.of(changed)));
        if (Set.of("project", "source-project", "target-project", "source", "title", "body").contains(fault)) assertTrue(unknownIdentity.retainedMrIids().isEmpty());
    }
    @Test void postSendTargetMovementAndPreviouslyRecordedRaceCannotBecomeOverallCompletion() {
        var remote = new Observation(true, true, branch(request.candidateCommitSha()), true, List.of(mr(7, request.candidateCommitSha())), "9".repeat(40));
        var result = reconcile(request, recorded(Set.of()), remote);
        assertEquals(State.CONFIRMED, result.mergeRequest()); assertEquals(Set.of(7L), result.retainedMrIids());
        assertTrue(result.residualRace()); assertFalse(result.exactRemoteCandidateObserved());
        var raced = new RecordedSend(request.operationId(), request.bodyHash(), true, true, result.retainedMrIids(), request.candidateCommitSha(), true);
        result = reconcile(request, raced, observed(request.candidateCommitSha(), List.of(mr(7, request.candidateCommitSha()))));
        assertTrue(result.residualRace()); assertFalse(result.exactRemoteCandidateObserved());
    }
    @Test void preexistingBranchIsCollisionWithoutDurableRecordedOwnershipAndRequestHashIsMandatory() {
        var unclaimed = new RecordedSend(request.operationId(), request.bodyHash(), false, false, Set.of(), null, false);
        var result = reconcile(request, unclaimed, observed(request.candidateCommitSha(), List.of()));
        assertEquals(State.BLOCKED, result.branch()); assertFalse(result.exactRemoteCandidateObserved());
        assertThrows(IllegalArgumentException.class, () -> reconcile(request, new RecordedSend(request.operationId(), "0".repeat(64), true, true, Set.of(), null, false),
                observed(request.candidateCommitSha(), List.of())));
    }
}
