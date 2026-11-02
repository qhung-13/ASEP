package io.github.qhung13.asep.codedelivery;

import java.util.List;
import java.util.UUID;

/** VN-T14 identity only, never an authorization or verification receipt. No VN-T13 codec changes. */
public record TestProjectDeliveryRequest(UUID operationId, UUID runId, UUID attemptId, long runVersion,
        UUID ownerGrantId, String policyId, long policyEpoch, String baseSha, String expectedTargetSha,
        String candidateCommitSha, String candidateTreeSha, String manifestSha256,
        UUID sealReceiptId, UUID verificationReceiptId, String verifierProfileSha256, String safeCiProfileSha256) {
    public TestProjectDeliveryRequest {
        if (operationId == null || runId == null || attemptId == null || ownerGrantId == null
                || sealReceiptId == null || verificationReceiptId == null || runVersion < 1 || policyEpoch < 1
                || policyId == null || !policyId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,100}")) throw invalid();
        for (String sha : new String[]{baseSha, expectedTargetSha, candidateCommitSha, candidateTreeSha}) {
            if (sha == null || !sha.matches("[0-9a-f]{40}")) throw invalid();
        }
        for (String digest : new String[]{manifestSha256, verifierProfileSha256, safeCiProfileSha256}) {
            if (digest == null || !digest.matches("[0-9a-f]{64}")) throw invalid();
        }
        // This local test profile is deliberately one commit on the exact inspected target.
        if (!baseSha.equals(expectedTargetSha) || sealReceiptId.equals(verificationReceiptId)) throw invalid();
    }
    public long projectId() { return 87786140L; }
    public String targetBranch() { return "main"; }
    public String sourceBranch() { return "test/vn-t14/" + operationId; }
    public String probePath() { return "vn-t14-probe-" + operationId + ".txt"; }
    public String bodyHash() {
        return DeliveryIntent.hash(List.of("asep-vn-t14-test-project-request-v1", operationId.toString(), runId.toString(),
                attemptId.toString(), Long.toString(runVersion), ownerGrantId.toString(), policyId, Long.toString(policyEpoch),
                Long.toString(projectId()), baseSha, sourceBranch(), targetBranch(), expectedTargetSha, candidateCommitSha,
                candidateTreeSha, manifestSha256, sealReceiptId.toString(), verificationReceiptId.toString(),
                verifierProfileSha256, safeCiProfileSha256));
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid VN-T14 test-project identity"); }
    @Override public String toString() { return "TestProjectDeliveryRequest[identities redacted]"; }
}
