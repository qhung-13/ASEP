package io.github.qhung13.asep.codedelivery;

import io.github.qhung13.asep.integration.GitLabTestProjectReader.Inspection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static io.github.qhung13.asep.codedelivery.TestProjectDeliveryGate.*;

/**
 * VN-T14 local-only grant verifier.
 *
 * Source must be supplied by a trusted controller, never an agent or request.
 * No Spring wiring or live authorization is provided here.
 */
public final class TestProjectOwnerGrantVerifier implements TrustedEvidenceVerifier {

    public interface Source {
        GrantRecord find(UUID grantId);
    }

    public record GrantRecord(
            OwnerGrant grant,
            Instant notBefore,
            Instant expiresAt,
            boolean revoked) {}

    private final Source source;
    private final Clock clock;

    public TestProjectOwnerGrantVerifier(Source source, Clock clock) {
        this.source = Objects.requireNonNull(source);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Evidence verify(TestProjectDeliveryRequest request, Inspection inspection) {
        if (request == null) return denied();

        try {
            // The grant is loaded independently, never constructed from request fields.
            GrantRecord record = source.find(request.ownerGrantId());

            if (record == null || record.grant() == null || record.revoked()) {
                return denied();
            }

            Instant now = clock.instant();

            // notBefore is inclusive; expiresAt is exclusive.
            if (record.notBefore() == null
                    || record.expiresAt() == null
                    || record.notBefore().isAfter(now)
                    || !record.expiresAt().isAfter(now)
                    || !record.expiresAt().isAfter(record.notBefore())) {
                return denied();
            }

            OwnerGrant grant = record.grant();

            boolean exactScope =
                    request.ownerGrantId().equals(grant.id())
                    && request.operationId().equals(grant.operationId())
                    && request.bodyHash().equals(grant.requestHash())
                    && request.projectId() == grant.projectId()
                    && "qhung-13/asep-m01-sandbox".equals(grant.repositoryPath())
                    && request.sourceBranch().equals(grant.sourceBranch())
                    && request.targetBranch().equals(grant.targetBranch());

            if (!exactScope) return denied();

            // A valid grant proves only Owner authorization.
            // Policy, safe CI, credentials and executor wiring remain unverified.
            return new Evidence(
                    Set.of(Requirement.OWNER_WRITE_AUTHORIZATION),
                    grant);

        } catch (RuntimeException unavailable) {
            // Never expose trusted-store details or treat lookup errors as approval.
            return denied();
        }
    }

    private static Evidence denied() {
        return new Evidence(Set.of(), null);
    }
}
