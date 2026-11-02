package io.github.qhung13.asep.codedelivery;

import java.time.Clock;
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

class TestProjectOwnerGrantVerifierTest {

    private static final Instant NOW =
            Instant.parse("2026-10-29T05:00:00Z");

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
                    "d".repeat(64),
                    id(5), id(6),
                    "e".repeat(64), "f".repeat(64));

    private OwnerGrant grant() {
        return new OwnerGrant(
                request.ownerGrantId(),
                request.operationId(),
                request.bodyHash(),
                request.projectId(),
                "qhung-13/asep-m01-sandbox",
                request.sourceBranch(),
                request.targetBranch());
    }

    private TestProjectOwnerGrantVerifier.GrantRecord active() {
        return new TestProjectOwnerGrantVerifier.GrantRecord(
                grant(), NOW.minusSeconds(60),
                NOW.plusSeconds(60), false);
    }

    private TestProjectOwnerGrantVerifier verifier(
            AtomicReference<TestProjectOwnerGrantVerifier.GrantRecord> state) {
        return new TestProjectOwnerGrantVerifier(
                id -> state.get(), CLOCK);
    }

    private static void denied(Evidence evidence) {
        assertTrue(evidence.verified().isEmpty());
        assertNull(evidence.ownerGrant());
    }

    @Test
    void activeExactlyBoundGrantVerifiesOnlyOwnerAuthorization() {
        var state = new AtomicReference<>(active());

        Evidence evidence = verifier(state).verify(request, null);

        assertEquals(
                Set.of(Requirement.OWNER_WRITE_AUTHORIZATION),
                evidence.verified());
        assertEquals(grant(), evidence.ownerGrant());

        // Even a valid grant cannot satisfy current policy or safe CI.
        assertFalse(evidence.verified().contains(
                Requirement.CURRENT_POLICY));
        assertFalse(evidence.verified().contains(
                Requirement.SAFE_CI_AND_INHERITED_SETTINGS));
        assertFalse(evidence.verified().contains(
                Requirement.TRUSTED_LIVE_EXECUTOR_WIRING));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "missing",
            "revoked",
            "expired",
            "not-yet-valid",
            "wrong-id",
            "wrong-operation",
            "wrong-request-hash",
            "wrong-project",
            "wrong-source",
            "wrong-target"
    })
    void invalidOrUnavailableGrantsNeverAuthorize(String fault) {
        var g = grant();

        var record = switch (fault) {
            case "missing" -> null;
            case "revoked" ->
                    new TestProjectOwnerGrantVerifier.GrantRecord(
                            g, NOW.minusSeconds(60),
                            NOW.plusSeconds(60), true);
            case "expired" ->
                    new TestProjectOwnerGrantVerifier.GrantRecord(
                            g, NOW.minusSeconds(60), NOW, false);
            case "not-yet-valid" ->
                    new TestProjectOwnerGrantVerifier.GrantRecord(
                            g, NOW.plusSeconds(1),
                            NOW.plusSeconds(60), false);
            default -> {
                OwnerGrant wrong = new OwnerGrant(
                        fault.equals("wrong-id") ? id(99) : g.id(),
                        fault.equals("wrong-operation") ? id(99) : g.operationId(),
                        fault.equals("wrong-request-hash")
                                ? "0".repeat(64) : g.requestHash(),
                        fault.equals("wrong-project") ? 42L : g.projectId(),
                        g.repositoryPath(),
                        fault.equals("wrong-source")
                                ? "test/vn-t14/other" : g.sourceBranch(),
                        fault.equals("wrong-target")
                                ? "other" : g.targetBranch());

                yield new TestProjectOwnerGrantVerifier.GrantRecord(
                        wrong, NOW.minusSeconds(60),
                        NOW.plusSeconds(60), false);
            }
        };

        var state = new AtomicReference<>(record);
        denied(verifier(state).verify(request, null));
    }

    @Test
    void revocationIsObservedOnTheNextVerification() {
        var state = new AtomicReference<>(active());
        var verifier = verifier(state);

        assertTrue(verifier.verify(request, null).verified()
                .contains(Requirement.OWNER_WRITE_AUTHORIZATION));

        state.set(new TestProjectOwnerGrantVerifier.GrantRecord(
                grant(), NOW.minusSeconds(60),
                NOW.plusSeconds(60), true));

        denied(verifier.verify(request, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "wrong-repository",
            "missing-start",
            "missing-expiry",
            "zero-window",
            "reversed-window"
    })
    void repositoryAndValidityWindowMustBeExact(String fault) {
        OwnerGrant g = grant();

        if (fault.equals("wrong-repository")) {
            g = new OwnerGrant(
                    g.id(), g.operationId(), g.requestHash(),
                    g.projectId(), "unrelated/repository",
                    g.sourceBranch(), g.targetBranch());
        }

        Instant start = switch (fault) {
            case "missing-start" -> null;
            case "zero-window" -> NOW;
            case "reversed-window" -> NOW.plusSeconds(20);
            default -> NOW.minusSeconds(20);
        };

        Instant expiry = switch (fault) {
            case "missing-expiry" -> null;
            case "zero-window" -> NOW;
            case "reversed-window" -> NOW.plusSeconds(10);
            default -> NOW.plusSeconds(20);
        };

        var record = new TestProjectOwnerGrantVerifier.GrantRecord(
                g, start, expiry, false);

        denied(new TestProjectOwnerGrantVerifier(
                id -> record, CLOCK).verify(request, null));
    }

    @Test
    void grantBecomesValidAtInclusiveStartBoundary() {
        var record = new TestProjectOwnerGrantVerifier.GrantRecord(
                grant(), NOW, NOW.plusSeconds(30), false);

        var evidence = new TestProjectOwnerGrantVerifier(
                id -> record, CLOCK).verify(request, null);

        assertEquals(
                Set.of(Requirement.OWNER_WRITE_AUTHORIZATION),
                evidence.verified());
        assertEquals(grant(), evidence.ownerGrant());
    }

    @Test
    void trustedSourceFailureDeniesWithoutLeakingItsCause() {
        var verifier = new TestProjectOwnerGrantVerifier(
                id -> {
                    throw new IllegalStateException("PRIVATE_STORE_DETAILS");
                },
                CLOCK);

        denied(verifier.verify(request, null));
    }
}
