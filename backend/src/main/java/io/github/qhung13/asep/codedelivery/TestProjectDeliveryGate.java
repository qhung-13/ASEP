package io.github.qhung13.asep.codedelivery;

import io.github.qhung13.asep.integration.GitLabTestProjectReader.Inspection;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** VN-T14-only pre-write validation. No live verifier, credential source, writer or activation is wired.
 * Synthetic VN-T13 DeliveryIntent/DeliveryChecks are deliberately not accepted by this API.
 */
public final class TestProjectDeliveryGate {
    private static final long PROJECT_ID = 87786140L;
    private static final String REPOSITORY_PATH = "qhung-13/asep-m01-sandbox";
    public enum Requirement {
        EXACT_TEST_PROJECT_ENROLLMENT, OWNER_WRITE_AUTHORIZATION, ACCOUNT_AND_CREDENTIAL_REVIEW,
        CREDENTIAL_ISOLATION_AND_LIFECYCLE, CURRENT_POLICY, SEALED_INDEPENDENTLY_VERIFIED_CANDIDATE,
        SAFE_CI_AND_INHERITED_SETTINGS, CONTROLLED_MR_AND_FAULT_WINDOWS, TRUSTED_LIVE_EXECUTOR_WIRING,
        COMPLETE_CURRENT_INSPECTION, EXACT_TARGET_HEAD
    }
    /** Independently loaded from the trusted grant store, never caller request fields or agent flags. */
    public record OwnerGrant(UUID id, UUID operationId, String requestHash, long projectId,
            String repositoryPath, String sourceBranch, String targetBranch) {}
    public record Evidence(Set<Requirement> verified, OwnerGrant ownerGrant) {
        public Evidence { verified = Set.copyOf(verified); }
    }
    /** Trusted host must verify the actual current policy, grant, enrollment and protected receipts.
     * There is no live implementation. An incoming reference or all-verified flag is not proof.
     */
    public interface TrustedEvidenceVerifier {
        Evidence verify(TestProjectDeliveryRequest request, Inspection inspection);
    }
    public record Decision(Set<Requirement> missing) {
        public Decision { missing = Set.copyOf(missing); }
        public boolean blocked() { return !missing.isEmpty(); }
    }
    private final TrustedEvidenceVerifier verifier;
    private final Clock clock;
    private final Duration maximumObservationAge;
    public TestProjectDeliveryGate(TrustedEvidenceVerifier verifier, Clock clock, Duration maximumObservationAge) {
        this.verifier = Objects.requireNonNull(verifier);
        this.clock = Objects.requireNonNull(clock);
        if (maximumObservationAge == null || maximumObservationAge.isNegative() || maximumObservationAge.isZero()) {
            throw new IllegalArgumentException("Explicit inspection age policy required");
        }
        this.maximumObservationAge = maximumObservationAge;
    }
    public static TrustedEvidenceVerifier unavailable() { return (request, inspection) -> new Evidence(Set.of(), null); }

    public Decision evaluate(TestProjectDeliveryRequest request, Inspection inspection) {
        var missing = EnumSet.allOf(Requirement.class);
        if (request == null || inspection == null) return new Decision(missing);
        OwnerGrant grant = null;
        try {
            Evidence evidence = verifier.verify(request, inspection);
            if (evidence != null) { missing.removeAll(evidence.verified()); grant = evidence.ownerGrant(); }
        } catch (RuntimeException unavailable) { /* Keep every unmet prerequisite; never retain raw causes. */ }
        var now = clock.instant();
        boolean current = inspection.startedAt() != null && inspection.completedAt() != null
                && !inspection.startedAt().isAfter(inspection.completedAt()) && !inspection.completedAt().isAfter(now)
                && Duration.between(inspection.startedAt(), now).compareTo(maximumObservationAge) <= 0;
        boolean complete = current && inspection.gaps().isEmpty() && inspection.project() != null
                && !inspection.project().archived() && inspection.project().effectiveAccessLevel() >= 0
                && inspection.target() != null && inspection.protectedBranches() != null && inspection.protectedBranches().complete()
                && inspection.runners() != null && inspection.runners().complete()
                && inspection.variables() != null && inspection.variables().complete()
                && inspection.environments() != null && inspection.environments().complete()
                && inspection.pipelines() != null && inspection.pipelines().complete();
        if (!complete) missing.add(Requirement.COMPLETE_CURRENT_INSPECTION);
        else missing.remove(Requirement.COMPLETE_CURRENT_INSPECTION);
        String source = "test/vn-t14/" + request.operationId();
        boolean scope = request.projectId() == PROJECT_ID && source.equals(request.sourceBranch())
                && "main".equals(request.targetBranch()) && inspection.requestedProjectId() == PROJECT_ID
                && "main".equals(inspection.requestedTarget()) && inspection.project() != null
                && inspection.project().id() == PROJECT_ID && REPOSITORY_PATH.equals(inspection.project().path())
                && "main".equals(inspection.project().defaultBranch()) && inspection.target() != null
                && "main".equals(inspection.target().name()) && inspection.target().protectedBranch() && !inspection.target().canPush();
        if (!scope) missing.add(Requirement.EXACT_TEST_PROJECT_ENROLLMENT);
        if (grant == null || !request.ownerGrantId().equals(grant.id()) || !request.operationId().equals(grant.operationId())
                || !request.bodyHash().equals(grant.requestHash()) || grant.projectId() != PROJECT_ID
                || !REPOSITORY_PATH.equals(grant.repositoryPath()) || !source.equals(grant.sourceBranch())
                || !"main".equals(grant.targetBranch())) missing.add(Requirement.OWNER_WRITE_AUTHORIZATION);
        if (inspection.target() == null || !request.expectedTargetSha().equals(inspection.target().headSha())) missing.add(Requirement.EXACT_TARGET_HEAD);
        else missing.remove(Requirement.EXACT_TARGET_HEAD);
        // Counts/role/branch metadata do not establish safe CI, authority or independently issued receipts.
        return new Decision(missing);
    }
    public void requireBeforeMutation(TestProjectDeliveryRequest request, Inspection freshInspection) {
        if (evaluate(request, freshInspection).blocked()) throw new DeliveryException(DeliveryException.Kind.INVALID);
    }
}
