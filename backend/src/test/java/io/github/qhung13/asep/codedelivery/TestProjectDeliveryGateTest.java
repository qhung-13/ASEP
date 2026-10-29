package io.github.qhung13.asep.codedelivery;

import io.github.qhung13.asep.integration.GitLabTestProjectReader.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static io.github.qhung13.asep.codedelivery.TestProjectDeliveryGate.*;
import static org.junit.jupiter.api.Assertions.*;

class TestProjectDeliveryGateTest {
    private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static UUID id(long value) { return new UUID(0, value); }
    private final TestProjectDeliveryRequest request = new TestProjectDeliveryRequest(id(1), id(2), id(3),
            1, id(4), "local-fixture", 1, "a".repeat(40), "a".repeat(40), "b".repeat(40), "c".repeat(40), "d".repeat(64),
            id(5), id(6), "e".repeat(64), "f".repeat(64));
    private Inspection inspection(Instant start, Instant end, String head, Set<Gap> gaps) {
        var empty = new CollectionObservation(0, true);
        return new Inspection(87786140, "main", new Project(87786140, "qhung-13/asep-m01-sandbox", "public", false, "main", "disabled", false, 30),
                new Target("main", head, true, false), empty, empty, empty, empty, empty, List.of(), gaps, start, end);
    }
    private Inspection fresh() { return inspection(NOW, NOW, request.expectedTargetSha(), Set.of()); }
    private OwnerGrant grant() {
        return new OwnerGrant(request.ownerGrantId(), request.operationId(), request.bodyHash(), request.projectId(),
                "qhung-13/asep-m01-sandbox", request.sourceBranch(), request.targetBranch());
    }
    private Evidence all() { return new Evidence(EnumSet.allOf(Requirement.class), grant()); }
    private TestProjectDeliveryGate gate(TrustedEvidenceVerifier verifier) { return new TestProjectDeliveryGate(verifier, CLOCK, Duration.ofSeconds(30)); }
    @Test void requestReferencesAndFreshMetadataDoNotGrantAuthority() {
        var result = gate(TestProjectDeliveryGate.unavailable()).evaluate(request, fresh());
        assertTrue(result.blocked());
        for (var absent : List.of(Requirement.OWNER_WRITE_AUTHORIZATION, Requirement.SEALED_INDEPENDENTLY_VERIFIED_CANDIDATE,
                Requirement.SAFE_CI_AND_INHERITED_SETTINGS, Requirement.CREDENTIAL_ISOLATION_AND_LIFECYCLE, Requirement.TRUSTED_LIVE_EXECUTOR_WIRING)) assertTrue(result.missing().contains(absent));
        assertThrows(DeliveryException.class, () -> gate(TestProjectDeliveryGate.unavailable()).requireBeforeMutation(request, fresh()));
    }
    @ParameterizedTest @EnumSource(value = Requirement.class, names = {"COMPLETE_CURRENT_INSPECTION", "EXACT_TARGET_HEAD"}, mode = EnumSource.Mode.EXCLUDE)
    void everyIndependentTrustedPrerequisiteRemainsMandatory(Requirement absent) {
        var verifiedFixture = EnumSet.allOf(Requirement.class); verifiedFixture.remove(absent);
        var result = gate((r, facts) -> { assertEquals(request, r); return new Evidence(verifiedFixture, grant()); }).evaluate(request, fresh());
        assertTrue(result.blocked()); assertTrue(result.missing().contains(absent));
    }
    @ParameterizedTest @ValueSource(strings = {"legacy-project-42", "project-42", "requested-project", "path", "requested-target", "target", "default-branch", "unprotected", "can-push-main"})
    void allVerifiedFixtureCannotOverrideTheEnrolledScopeOrMainRestrictions(String fault) {
        var f = fresh(); var p = f.project(); var t = f.target();
        var changed = new Inspection((fault.equals("requested-project") || fault.equals("legacy-project-42")) ? 42 : f.requestedProjectId(), fault.equals("requested-target") ? "other" : f.requestedTarget(),
                new Project((fault.equals("project-42") || fault.equals("legacy-project-42")) ? 42 : p.id(), fault.equals("path") ? "unrelated/repository" : p.path(), p.visibility(), false,
                        fault.equals("default-branch") ? "other" : p.defaultBranch(), p.buildsAccessLevel(), p.sharedRunnersEnabled(), p.effectiveAccessLevel()),
                new Target(fault.equals("target") ? "other" : t.name(), t.headSha(), !fault.equals("unprotected"), fault.equals("can-push-main")),
                f.protectedBranches(), f.runners(), f.variables(), f.environments(), f.pipelines(), List.of(), Set.of(), NOW, NOW);
        var gate = gate((r, facts) -> all());
        assertTrue(gate.evaluate(request, changed).missing().contains(Requirement.EXACT_TEST_PROJECT_ENROLLMENT));
        assertThrows(DeliveryException.class, () -> gate.requireBeforeMutation(request, changed));
    }
    @ParameterizedTest @ValueSource(strings = {"missing", "id", "operation", "hash", "project", "path", "source", "source-main", "target"})
    void allVerifiedFixtureCannotOverrideMismatchedIndependentlyLoadedOwnerGrant(String fault) {
        var g = grant();
        var wrong = fault.equals("missing") ? null : new OwnerGrant(fault.equals("id") ? id(99) : g.id(),
                fault.equals("operation") ? id(99) : g.operationId(), fault.equals("hash") ? "0".repeat(64) : g.requestHash(),
                fault.equals("project") ? 42 : g.projectId(), fault.equals("path") ? "unrelated/path" : g.repositoryPath(),
                fault.equals("source") ? "test/vn-t14/another-operation" : fault.equals("source-main") ? "main" : g.sourceBranch(),
                fault.equals("target") ? "other" : g.targetBranch());
        var gate = gate((r, facts) -> new Evidence(EnumSet.allOf(Requirement.class), wrong));
        assertTrue(gate.evaluate(request, fresh()).missing().contains(Requirement.OWNER_WRITE_AUTHORIZATION));
        assertThrows(DeliveryException.class, () -> gate.requireBeforeMutation(request, fresh()));
    }
    @ParameterizedTest @ValueSource(strings = {"old-start", "future-end", "backward-clock", "missing-start", "missing-end"})
    void completionTimeCannotRefreshEarlierObservations(String fault) {
        var start = fault.equals("missing-start") ? null : fault.equals("old-start") ? NOW.minusSeconds(31) : NOW;
        var end = fault.equals("missing-end") ? null : fault.equals("future-end") ? NOW.plusSeconds(1) : fault.equals("backward-clock") ? NOW.minusSeconds(1) : NOW;
        assertTrue(gate((r, facts) -> all()).evaluate(request, inspection(start, end, request.expectedTargetSha(), Set.of()))
                .missing().contains(Requirement.COMPLETE_CURRENT_INSPECTION));
    }
    @Test void targetMismatchAndIncompleteInventoryCannotBeOverridden() {
        var gate = gate((r, facts) -> all());
        assertTrue(gate.evaluate(request, inspection(NOW, NOW, "9".repeat(40), Set.of())).missing().contains(Requirement.EXACT_TARGET_HEAD));
        assertTrue(gate.evaluate(request, inspection(NOW, NOW, request.expectedTargetSha(), Set.of(Gap.VARIABLES))).missing().contains(Requirement.COMPLETE_CURRENT_INSPECTION));
        var f = fresh();
        var missing = new Inspection(f.requestedProjectId(), f.requestedTarget(), f.project(), f.target(), null, null, null, null, null, List.of(), Set.of(), NOW, NOW);
        assertTrue(gate.evaluate(request, missing).missing().contains(Requirement.COMPLETE_CURRENT_INSPECTION));
    }
    @Test void unavailableVerifierDeniesWithoutRawCauseAndSyntheticIntentIsNotAnAcceptedApi() {
        var gate = gate((r, facts) -> { throw new IllegalStateException("PRIVATE_EVIDENCE"); });
        var failure = assertThrows(DeliveryException.class, () -> gate.requireBeforeMutation(request, fresh()));
        assertNull(failure.getCause()); assertFalse(failure.toString().contains("PRIVATE"));
        assertTrue(gate.evaluate(request, null).blocked()); assertTrue(gate.evaluate(null, null).blocked());
        assertThrows(NoSuchMethodException.class, () -> TestProjectDeliveryGate.class.getMethod("evaluate", DeliveryIntent.class, Inspection.class));
    }
    @Test void fullyBoundFixtureCanOnlySatisfyLocalValidationWithoutAnyRemoteCapability() {
        assertFalse(gate((r, facts) -> all()).evaluate(request, fresh()).blocked());
        assertEquals(0, TestProjectDeliveryGate.class.getAnnotations().length);
    }
}
