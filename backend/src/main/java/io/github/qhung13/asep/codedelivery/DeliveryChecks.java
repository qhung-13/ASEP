package io.github.qhung13.asep.codedelivery;

import java.util.UUID;

/**
 * Mandatory trusted ports, supplied outside agents. No production issuer is wired.
 * Implementations must read current service authority, policy, sealed artifacts and
 * independently issued verification records; values in an incoming intent are not proofs.
 */
public interface DeliveryChecks {
    record Access(boolean readAllowed, boolean writeAllowed) {}
    record VerifiedCandidate(UUID runId, UUID attemptId, String baseSha, String commitSha,
            String treeSha, String manifestSha256, UUID sealReceiptId, boolean writerStopped) {}
    record Verification(UUID runId, UUID attemptId, String manifestSha256, UUID receiptId,
            String profileSha256, boolean independentController, int discovered, int executed,
            int skipped, int failed, boolean timedOut) {}
    record Current(DeliveryIntent approvedIntent, boolean committedGoIntent, boolean ownerAuthorized,
            VerifiedCandidate candidate, Verification verification, String observedTargetSha,
            String safeCiProfileSha256, boolean safeCi) {}

    Current current(UUID operationId);
    Access access(String repositoryId);
    boolean trustedCallbackAcknowledgement();

    static DeliveryChecks unavailable() {
        return new DeliveryChecks() {
            @Override public Current current(UUID operationId) { return null; }
            @Override public Access access(String repositoryId) { return new Access(false, false); }
            @Override public boolean trustedCallbackAcknowledgement() { return false; }
        };
    }
}
