package io.github.qhung13.asep.codedelivery;

import io.github.qhung13.asep.integration.GitLabTestProjectReader.Inspection;
import java.util.Objects;
import java.util.Set;

import static io.github.qhung13.asep.codedelivery.TestProjectDeliveryGate.*;

/**
 * VN-T14 composition of exactly two independently validated facts.
 * Other enrollment requirements remain separate; no Spring activation or GitLab writer is created here.
 */
public final class TestProjectTrustedEvidenceComposite
        implements TrustedEvidenceVerifier {

    private final TestProjectOwnerGrantVerifier grants;
    private final TestProjectCurrentPolicyVerifier policy;

    public TestProjectTrustedEvidenceComposite(
            TestProjectOwnerGrantVerifier grants,
            TestProjectCurrentPolicyVerifier policy) {
        this.grants = Objects.requireNonNull(grants);
        this.policy = Objects.requireNonNull(policy);
    }

    @Override
    public Evidence verify(
            TestProjectDeliveryRequest request, Inspection inspection) {
        if (request == null) return denied();

        try {
            // Both verifiers receive the same immutable delivery request.
            // The Owner Grant must be independently validated first.
            Evidence grantEvidence = grants.verify(request, inspection);

            if (grantEvidence == null
                    || !Set.of(Requirement.OWNER_WRITE_AUTHORIZATION)
                            .equals(grantEvidence.verified())
                    || grantEvidence.ownerGrant() == null) {
                return denied();
            }

            // Current Policy is checked independently and must not
            // be allowed to contribute Owner authorization.
            Evidence policyEvidence = policy.verify(request, inspection);

            if (policyEvidence == null
                    || !Set.of(Requirement.CURRENT_POLICY)
                            .equals(policyEvidence.verified())
                    || policyEvidence.ownerGrant() != null) {
                return denied();
            }

            // Explicit allowlist: never union arbitrary verified flags.
            return new Evidence(
                    Set.of(
                            Requirement.OWNER_WRITE_AUTHORIZATION,
                            Requirement.CURRENT_POLICY),
                    grantEvidence.ownerGrant());

        } catch (RuntimeException unavailable) {
            // Failure of either source invalidates the entire composite.
            return denied();
        }
    }

    private static Evidence denied() {
        return new Evidence(Set.of(), null);
    }
}
