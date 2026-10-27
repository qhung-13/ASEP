package io.github.qhung13.asep.codedelivery;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Synthetic v1 intent. IDs/digests only; no caller-provided MR text, URLs or credentials. */
public record DeliveryIntent(UUID operationId, UUID runId, UUID attemptId, long runVersion,
        String actorId, String policyId, long policyEpoch, String jiraSiteId, String jiraProjectId,
        String jiraIssueId, String jiraSnapshotSha256, String repositoryId, String baseSha,
        String targetBranch, String expectedTargetSha, String candidateCommitSha, String candidateTreeSha,
        String manifestSha256, UUID sealReceiptId, UUID verificationReceiptId, String verifierProfileSha256,
        String safeCiProfileSha256) {
    public DeliveryIntent {
        if (operationId == null || runId == null || attemptId == null || sealReceiptId == null
                || verificationReceiptId == null || runVersion < 1 || policyEpoch < 1) throw invalid();
        for (String value : new String[]{actorId, policyId, jiraSiteId}) {
            if (value == null || !value.matches("synthetic-[A-Za-z0-9._:-]{1,100}")) throw invalid();
        }
        for (String value : new String[]{jiraProjectId, jiraIssueId, repositoryId}) {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw invalid();
        }
        for (String value : new String[]{jiraSnapshotSha256, manifestSha256, verifierProfileSha256, safeCiProfileSha256}) {
            if (value == null || !value.matches("[0-9a-f]{64}")) throw invalid();
        }
        for (String value : new String[]{baseSha, expectedTargetSha, candidateCommitSha, candidateTreeSha}) {
            if (value == null || !value.matches("[0-9a-f]{40}")) throw invalid();
        }
        // This slice is a single enrolled synthetic main target, not arbitrary Git ref parsing.
        if (!"main".equals(targetBranch)) throw invalid();
    }

    public String sourceBranch() { return "asep/delivery/" + operationId; }

    /** Length-prefixed UTF-8 v1 codec, independent of JSON field ordering/escaping. */
    public String bodyHash() {
        return hash(List.of("asep-code-delivery-fake-v1", runId.toString(), attemptId.toString(),
                Long.toString(runVersion), actorId, policyId, Long.toString(policyEpoch), jiraSiteId,
                jiraProjectId, jiraIssueId, jiraSnapshotSha256, repositoryId, baseSha, sourceBranch(),
                targetBranch, expectedTargetSha, candidateCommitSha, candidateTreeSha, manifestSha256,
                sealReceiptId.toString(), verificationReceiptId.toString(), verifierProfileSha256, safeCiProfileSha256));
    }

    static String hash(List<String> fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid synthetic delivery identity"); }
    @Override public String toString() { return "DeliveryIntent[synthetic identities redacted]"; }
}
