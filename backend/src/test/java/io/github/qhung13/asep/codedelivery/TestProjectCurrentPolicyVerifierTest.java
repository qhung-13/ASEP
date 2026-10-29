package io.github.qhung13.asep.codedelivery;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.github.qhung13.asep.codedelivery.TestProjectDeliveryGate.*;
import static org.junit.jupiter.api.Assertions.*;

class TestProjectCurrentPolicyVerifierTest {

    private static final Instant NOW = Instant.parse("2026-10-29T07:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static UUID id(long n) {
        return new UUID(0, n);
    }

    private final TestProjectDeliveryRequest request =
            new TestProjectDeliveryRequest(
                    id(1), id(2), id(3), 1, id(4),
                    "local-fixture", 1,
                    "a".repeat(40), "a".repeat(40),
                    "b".repeat(40), "c".repeat(40),
                    "d".repeat(64), id(5), id(6),
                    "e".repeat(64), "f".repeat(64));

    @Test void sharedGoRequestCodecHasExactCandidateBinding() {
        var golden=new TestProjectDeliveryRequest(id(1),id(2),id(3),9,id(4),"local-fixture",1,
                "a".repeat(40),"a".repeat(40),"b".repeat(40),"c".repeat(40),"d".repeat(64),id(5),id(6),"e".repeat(64),"f".repeat(64));
        assertEquals("679f2b93f889c6e966afa4c721fe9fd468c0d6533bdd542462266ee399ffdea6",golden.bodyHash());
    }

    private TestProjectCurrentPolicyVerifier.Snapshot approved() {
        return new TestProjectCurrentPolicyVerifier.Snapshot(
                request.runId(),
                request.attemptId(),
                request.runVersion(),
                "DELIVERY_PENDING",
                request.policyId(),
                request.policyEpoch(),
                request.projectId(),
                request.targetBranch(),
                request.baseSha(),
                request.expectedTargetSha(),
                true,
                NOW, request.operationId(), request.bodyHash(), NOW.plusSeconds(30));
    }

    private TestProjectCurrentPolicyVerifier verifier(
            AtomicReference<TestProjectCurrentPolicyVerifier.Snapshot> state) {
        return new TestProjectCurrentPolicyVerifier(
                (runId, attemptId) -> {
                    assertEquals(request.runId(), runId);
                    assertEquals(request.attemptId(), attemptId);
                    return state.get();
                },
                CLOCK, Duration.ofSeconds(30));
    }

    @Test
    void exactlyBoundCurrentPolicyVerifiesOnlyCurrentPolicy() {
        Evidence evidence = verifier(new AtomicReference<>(approved()))
                .verify(request, null);

        assertEquals(
                Set.of(Requirement.CURRENT_POLICY),
                evidence.verified());
        assertNull(evidence.ownerGrant());

        assertFalse(evidence.verified().contains(
                Requirement.OWNER_WRITE_AUTHORIZATION));
        assertFalse(evidence.verified().contains(
                Requirement.SAFE_CI_AND_INHERITED_SETTINGS));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "missing", "run", "attempt", "version",
            "cancel-requested", "cancelled", "investigating",
            "policy-id", "policy-epoch", "project",
            "target-branch", "base", "target-head",
            "policy-disabled", "stale", "future", "operation", "candidate-hash", "expired", "missing-expiry"
    })
    void mismatchedOrUnsafeSnapshotIsNeverAuthority(String fault) {
        var s = approved();

        var changed = fault.equals("missing") ? null :
                new TestProjectCurrentPolicyVerifier.Snapshot(
                        fault.equals("run") ? id(99) : s.runId(),
                        fault.equals("attempt") ? id(99) : s.attemptId(),
                        fault.equals("version") ? 2 : s.runVersion(),
                        switch (fault) {
                            case "cancel-requested" -> "CANCEL_REQUESTED";
                            case "cancelled" -> "CANCELLED";
                            case "investigating" -> "INVESTIGATING";
                            default -> s.state();
                        },
                        fault.equals("policy-id") ? "other" : s.policyId(),
                        fault.equals("policy-epoch") ? 2 : s.policyEpoch(),
                        fault.equals("project") ? 42 : s.projectId(),
                        fault.equals("target-branch") ? "other" : s.targetBranch(),
                        fault.equals("base") ? "9".repeat(40) : s.baseSha(),
                        fault.equals("target-head") ? "9".repeat(40) : s.targetHeadSha(),
                        !fault.equals("policy-disabled"),
                        fault.equals("stale") ? NOW.minusSeconds(31)
                                : fault.equals("future") ? NOW.plusSeconds(1)
                                : NOW, fault.equals("operation") ? id(99) : request.operationId(),
                        fault.equals("candidate-hash") ? "0".repeat(64) : request.bodyHash(),
                        fault.equals("missing-expiry") ? null : fault.equals("expired") ? NOW : NOW.plusSeconds(30));

        Evidence evidence = verifier(new AtomicReference<>(changed))
                .verify(request, null);

        assertTrue(evidence.verified().isEmpty());
        assertNull(evidence.ownerGrant());
    }

    @Test
    void cancellationObservedOnNextReadRevokesPolicyEvidence() {
        var current = new AtomicReference<>(approved());
        var verifier = verifier(current);

        assertEquals(Set.of(Requirement.CURRENT_POLICY),
                verifier.verify(request, null).verified());

        var s = approved();
        current.set(new TestProjectCurrentPolicyVerifier.Snapshot(
                s.runId(), s.attemptId(), s.runVersion() + 1,
                "CANCEL_REQUESTED", s.policyId(), s.policyEpoch(),
                s.projectId(), s.targetBranch(), s.baseSha(),
                s.targetHeadSha(), false, NOW, request.operationId(), request.bodyHash(), NOW.plusSeconds(30)));

        assertTrue(verifier.verify(request, null).verified().isEmpty());
    }

    private TestProjectCurrentPolicyVerifier.Snapshot snapshot(
            String state, boolean policyApproved, Instant observedAt) {
        var s = approved();
        return new TestProjectCurrentPolicyVerifier.Snapshot(
                s.runId(), s.attemptId(), s.runVersion(),
                state, s.policyId(), s.policyEpoch(),
                s.projectId(), s.targetBranch(),
                s.baseSha(), s.targetHeadSha(),
                policyApproved, observedAt, request.operationId(), request.bodyHash(), NOW.plusSeconds(30));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ADMITTED", "PLANNING", "VERIFYING", "REVIEWING",
            "BLOCKED", "FAILED", "ESCALATED",
            "SUPERSEDED", "COMPLETED_DRAFT_MR"
    })
    void otherWorkflowStatesCannotAuthorizeDelivery(String state) {
        var evidence = verifier(new AtomicReference<>(
                snapshot(state, true, NOW))).verify(request, null);

        assertTrue(evidence.verified().isEmpty());
        assertNull(evidence.ownerGrant());
    }

    @Test
    void policyRevocationIsRecheckedWithoutVersionChange() {
        var current = new AtomicReference<>(approved());
        var verifier = verifier(current);

        assertEquals(Set.of(Requirement.CURRENT_POLICY),
                verifier.verify(request, null).verified());

        current.set(snapshot("DELIVERY_PENDING", false, NOW));

        assertTrue(verifier.verify(request, null).verified().isEmpty());
    }

    @Test
    void freshnessBoundaryIsInclusiveButOlderOrMissingIsDenied() {
        var atLimit = snapshot(
                "DELIVERY_PENDING", true, NOW.minusSeconds(30));

        assertEquals(Set.of(Requirement.CURRENT_POLICY),
                verifier(new AtomicReference<>(atLimit))
                        .verify(request, null).verified());

        var laterClock = Clock.fixed(
                NOW.plusNanos(1), ZoneOffset.UTC);

        var laterVerifier = new TestProjectCurrentPolicyVerifier(
                (run, attempt) -> atLimit,
                laterClock, Duration.ofSeconds(30));

        assertTrue(laterVerifier.verify(request, null)
                .verified().isEmpty());

        var missingTimestamp = snapshot(
                "DELIVERY_PENDING", true, null);

        assertTrue(verifier(new AtomicReference<>(missingTimestamp))
                .verify(request, null).verified().isEmpty());
    }

    @Test
    void nullRequestDoesNotCallTrustedAuthority() {
        var verifier = new TestProjectCurrentPolicyVerifier(
                (run, attempt) -> {
                    throw new AssertionError(
                            "Authority must not be called for null request");
                },
                CLOCK, Duration.ofSeconds(30));

        var evidence = verifier.verify(null, null);

        assertTrue(evidence.verified().isEmpty());
        assertNull(evidence.ownerGrant());
    }

    @Test
    void unavailableAuthorityFailsClosedWithoutExposingCause() {
        var verifier = new TestProjectCurrentPolicyVerifier(
                (run, attempt) -> {
                    throw new IllegalStateException("PRIVATE_WORKFLOW_DETAILS");
                },
                CLOCK, Duration.ofSeconds(30));

        Evidence evidence = verifier.verify(request, null);
        assertTrue(evidence.verified().isEmpty());
        assertNull(evidence.ownerGrant());
    }
}
