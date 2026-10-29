package io.github.qhung13.asep.codedelivery;

import io.github.qhung13.asep.integration.GitLabTestProjectReader.Inspection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static io.github.qhung13.asep.codedelivery.TestProjectDeliveryGate.*;

/**
 * Verifies current independently loaded workflow authority; deployment and transport
 * authentication must be established by its protected source, never request fields.
 */
public final class TestProjectCurrentPolicyVerifier implements TrustedEvidenceVerifier {

    public interface AuthoritySource {
        Snapshot read(UUID runId, UUID attemptId);
    }

    /**
     * A trusted source must independently resolve current durable state and
     * Owner-approved delivery policy. Request fields are never authority.
     */
    public record Snapshot(
            UUID runId,
            UUID attemptId,
            long runVersion,
            String state,
            String policyId,
            long policyEpoch,
            long projectId,
            String targetBranch,
            String baseSha,
            String targetHeadSha,
            boolean deliveryPolicyApproved,
            Instant observedAt,
            UUID operationId,
            String authorizedRequestHash,
            Instant validUntil) {}

    private final AuthoritySource source;
    private final Clock clock;
    private final Duration maximumAge;

    public TestProjectCurrentPolicyVerifier(
            AuthoritySource source, Clock clock, Duration maximumAge) {
        this.source = Objects.requireNonNull(source);
        this.clock = Objects.requireNonNull(clock);
        if (maximumAge == null || maximumAge.isZero()
                || maximumAge.isNegative()) {
            throw new IllegalArgumentException("Explicit freshness policy required");
        }
        this.maximumAge = maximumAge;
    }

    @Override
    public Evidence verify(TestProjectDeliveryRequest request, Inspection inspection) {
        if (request == null) return denied();

        try {
            // Read current state independently from the trusted authority.
            // Caller-provided fields are comparison values, never evidence.
            Snapshot snapshot = source.read(
                    request.runId(),
                    request.attemptId());

            if (snapshot == null || !snapshot.deliveryPolicyApproved()) {
                return denied();
            }

            Instant now = clock.instant();
            Instant observedAt = snapshot.observedAt();

            // Reject missing, future or stale observations.
            if (snapshot.validUntil() == null || !snapshot.validUntil().isAfter(now)
                    || observedAt == null
                    || observedAt.isAfter(now)
                    || Duration.between(observedAt, now)
                            .compareTo(maximumAge) > 0) {
                return denied();
            }

            // Exact operation/candidate binding prevents reuse of one run-version
            // authorization for a different candidate or evidence tuple.
            boolean exactIdentity =
                    request.operationId().equals(snapshot.operationId())
                    && request.bodyHash().equals(snapshot.authorizedRequestHash())
                    && request.runId().equals(snapshot.runId())
                    && request.attemptId().equals(snapshot.attemptId())
                    && request.runVersion() == snapshot.runVersion()
                    && "DELIVERY_PENDING".equals(snapshot.state());

            boolean exactPolicy =
                    request.policyId().equals(snapshot.policyId())
                    && request.policyEpoch() == snapshot.policyEpoch();

            boolean exactRepository =
                    request.projectId() == snapshot.projectId()
                    && request.targetBranch().equals(snapshot.targetBranch())
                    && request.baseSha().equals(snapshot.baseSha())
                    && request.expectedTargetSha().equals(
                            snapshot.targetHeadSha());

            if (!exactIdentity || !exactPolicy || !exactRepository) {
                return denied();
            }

            // This verifier proves only CURRENT_POLICY.
            // It does not grant Owner authority or enable live writes.
            return new Evidence(
                    Set.of(Requirement.CURRENT_POLICY),
                    null);

        } catch (RuntimeException unavailable) {
            // Missing or failing authority must never become approval.
            // Do not expose trusted source details.
            return denied();
        }
    }

    private static Evidence denied() {
        return new Evidence(Set.of(), null);
    }
}
