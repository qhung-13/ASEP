package io.github.qhung13.asep.codedelivery;

import io.github.qhung13.asep.integration.GitLabTestProjectReader.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.github.qhung13.asep.codedelivery.TestProjectDeliveryGate.*;
import static org.junit.jupiter.api.Assertions.*;

class TestProjectTrustedEvidenceCompositeTest {

    private static final Instant NOW =
            Instant.parse("2026-10-29T07:00:00Z");
    private static final Clock CLOCK =
            Clock.fixed(NOW, ZoneOffset.UTC);

    private static UUID id(long value) {
        return new UUID(0, value);
    }

    private final TestProjectDeliveryRequest request =
            new TestProjectDeliveryRequest(
                    id(1), id(2), id(3), 1, id(4),
                    "local-fixture", 1,
                    "a".repeat(40), "a".repeat(40),
                    "b".repeat(40), "c".repeat(40),
                    "d".repeat(64), id(5), id(6),
                    "e".repeat(64), "f".repeat(64));

    private OwnerGrant ownerGrant() {
        return new OwnerGrant(
                request.ownerGrantId(),
                request.operationId(),
                request.bodyHash(),
                request.projectId(),
                "qhung-13/asep-m01-sandbox",
                request.sourceBranch(),
                request.targetBranch());
    }

    private TestProjectOwnerGrantVerifier.GrantRecord activeGrant() {
        return new TestProjectOwnerGrantVerifier.GrantRecord(
                ownerGrant(),
                NOW.minusSeconds(10),
                NOW.plusSeconds(30),
                false);
    }

    private TestProjectCurrentPolicyVerifier.Snapshot approvedPolicy() {
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
                NOW);
    }

    private TestProjectTrustedEvidenceComposite composite(
            TestProjectOwnerGrantVerifier.Source grantSource,
            TestProjectCurrentPolicyVerifier.AuthoritySource policySource) {
        return new TestProjectTrustedEvidenceComposite(
                new TestProjectOwnerGrantVerifier(grantSource, CLOCK),
                new TestProjectCurrentPolicyVerifier(
                        policySource, CLOCK, Duration.ofSeconds(30)));
    }

    private TestProjectTrustedEvidenceComposite validComposite() {
        return composite(
                grantId -> activeGrant(),
                (runId, attemptId) -> approvedPolicy());
    }

    private Inspection freshInspection() {
        var empty = new CollectionObservation(0, true);
        return new Inspection(
                87786140, "main",
                new Project(
                        87786140,
                        "qhung-13/asep-m01-sandbox",
                        "public", false, "main",
                        "disabled", false, 30),
                new Target(
                        "main",
                        request.expectedTargetSha(),
                        true, false),
                empty, empty, empty, empty, empty,
                List.of(), Set.of(), NOW, NOW);
    }

    private static void denied(Evidence evidence) {
        assertTrue(evidence.verified().isEmpty());
        assertNull(evidence.ownerGrant());
    }

    @Test
    void twoValidSourcesProduceExactlyTwoRequirements() {
        Evidence evidence = validComposite()
                .verify(request, freshInspection());

        assertEquals(
                Set.of(
                        Requirement.OWNER_WRITE_AUTHORIZATION,
                        Requirement.CURRENT_POLICY),
                evidence.verified());

        assertEquals(ownerGrant(), evidence.ownerGrant());

        assertFalse(evidence.verified().contains(
                Requirement.SAFE_CI_AND_INHERITED_SETTINGS));
        assertFalse(evidence.verified().contains(
                Requirement.TRUSTED_LIVE_EXECUTOR_WIRING));
    }

    @Test
    void twoValidSourcesStillCannotUnlockDeliveryGate() {
        var gate = new TestProjectDeliveryGate(
                validComposite(), CLOCK, Duration.ofSeconds(30));

        var decision = gate.evaluate(request, freshInspection());

        assertTrue(decision.blocked());

        assertFalse(decision.missing().contains(
                Requirement.OWNER_WRITE_AUTHORIZATION));
        assertFalse(decision.missing().contains(
                Requirement.CURRENT_POLICY));

        assertTrue(decision.missing().contains(
                Requirement.SAFE_CI_AND_INHERITED_SETTINGS));
        assertTrue(decision.missing().contains(
                Requirement.SEALED_INDEPENDENTLY_VERIFIED_CANDIDATE));
        assertTrue(decision.missing().contains(
                Requirement.TRUSTED_LIVE_EXECUTOR_WIRING));

        assertThrows(DeliveryException.class,
                () -> gate.requireBeforeMutation(
                        request, freshInspection()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "missing-grant", "revoked-grant",
            "missing-policy", "revoked-policy"
    })
    void invalidOneSideNeverLeavesPartialEvidence(String fault) {
        var grants = new AtomicReference<>(activeGrant());
        var policies = new AtomicReference<>(approvedPolicy());

        switch (fault) {
            case "missing-grant" -> grants.set(null);
            case "revoked-grant" ->
                    grants.set(new TestProjectOwnerGrantVerifier.GrantRecord(
                            ownerGrant(),
                            NOW.minusSeconds(10),
                            NOW.plusSeconds(30),
                            true));
            case "missing-policy" -> policies.set(null);
            case "revoked-policy" -> {
                var s = approvedPolicy();
                policies.set(
                        new TestProjectCurrentPolicyVerifier.Snapshot(
                                s.runId(), s.attemptId(),
                                s.runVersion(), s.state(),
                                s.policyId(), s.policyEpoch(),
                                s.projectId(), s.targetBranch(),
                                s.baseSha(), s.targetHeadSha(),
                                false, NOW));
            }
            default -> fail("Unexpected test fault");
        }

        denied(composite(
                grantId -> grants.get(),
                (runId, attemptId) -> policies.get())
                .verify(request, freshInspection()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"grant", "policy"})
    void unavailableSourceFailsClosed(String unavailable) {
        var composite = composite(
                grantId -> {
                    if (unavailable.equals("grant")) {
                        throw new IllegalStateException(
                                "PRIVATE_GRANT_STORE");
                    }
                    return activeGrant();
                },
                (runId, attemptId) -> {
                    if (unavailable.equals("policy")) {
                        throw new IllegalStateException(
                                "PRIVATE_POLICY_STORE");
                    }
                    return approvedPolicy();
                });

        denied(composite.verify(request, freshInspection()));
    }

    @Test
    void revokedGrantInvalidatesPreviouslyVerifiedEvidence() {
        var grants = new AtomicReference<>(activeGrant());

        var verifier = composite(
                grantId -> grants.get(),
                (runId, attemptId) -> approvedPolicy());

        assertEquals(
                Set.of(
                        Requirement.OWNER_WRITE_AUTHORIZATION,
                        Requirement.CURRENT_POLICY),
                verifier.verify(request, freshInspection()).verified());

        grants.set(new TestProjectOwnerGrantVerifier.GrantRecord(
                ownerGrant(),
                NOW.minusSeconds(10),
                NOW.plusSeconds(30),
                true));

        denied(verifier.verify(request, freshInspection()));
    }

    @Test
    void revokedPolicyInvalidatesPreviouslyVerifiedEvidence() {
        var policies = new AtomicReference<>(approvedPolicy());

        var verifier = composite(
                grantId -> activeGrant(),
                (runId, attemptId) -> policies.get());

        assertEquals(
                Set.of(
                        Requirement.OWNER_WRITE_AUTHORIZATION,
                        Requirement.CURRENT_POLICY),
                verifier.verify(request, freshInspection()).verified());

        var s = approvedPolicy();

        policies.set(new TestProjectCurrentPolicyVerifier.Snapshot(
                s.runId(), s.attemptId(), s.runVersion(),
                s.state(), s.policyId(), s.policyEpoch(),
                s.projectId(), s.targetBranch(),
                s.baseSha(), s.targetHeadSha(),
                false, NOW));

        denied(verifier.verify(request, freshInspection()));
    }

    @Test
    void gateReevaluationRejectsCancellationAfterEarlierApproval() {
        var policies = new AtomicReference<>(approvedPolicy());

        var gate = new TestProjectDeliveryGate(
                composite(
                        grantId -> activeGrant(),
                        (runId, attemptId) -> policies.get()),
                CLOCK,
                Duration.ofSeconds(30));

        var before = gate.evaluate(request, freshInspection());

        assertTrue(before.blocked());
        assertFalse(before.missing().contains(
                Requirement.OWNER_WRITE_AUTHORIZATION));
        assertFalse(before.missing().contains(
                Requirement.CURRENT_POLICY));

        var s = approvedPolicy();

        policies.set(new TestProjectCurrentPolicyVerifier.Snapshot(
                s.runId(), s.attemptId(), s.runVersion() + 1,
                "CANCEL_REQUESTED",
                s.policyId(), s.policyEpoch(),
                s.projectId(), s.targetBranch(),
                s.baseSha(), s.targetHeadSha(),
                false, NOW));

        var after = gate.evaluate(request, freshInspection());

        assertTrue(after.blocked());
        assertTrue(after.missing().contains(
                Requirement.OWNER_WRITE_AUTHORIZATION));
        assertTrue(after.missing().contains(
                Requirement.CURRENT_POLICY));

        assertThrows(DeliveryException.class,
                () -> gate.requireBeforeMutation(
                        request, freshInspection()));
    }

    @Test
    void nullRequestNeverInvokesEitherSource() {
        var composite = composite(
                grantId -> {
                    throw new AssertionError("Grant lookup invoked");
                },
                (runId, attemptId) -> {
                    throw new AssertionError("Policy lookup invoked");
                });

        denied(composite.verify(null, null));
    }
}
