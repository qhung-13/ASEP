package io.github.qhung13.asep.integration;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Untrusted text, in memory only. The fixture digest is NOT a Jira business revision. */
public record JiraTicketSnapshot(JiraTicketReader.Selection identity, String issueType, String status,
        String summary, String description, String fixtureRevisionSha256) {
    public JiraTicketSnapshot {
        Objects.requireNonNull(identity);
        for (String value : new String[]{issueType, status, summary}) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Invalid Jira snapshot");
        }
        Objects.requireNonNull(description);
        for (String value : new String[]{issueType, status, summary, description}) {
            if (!StandardCharsets.UTF_8.newEncoder().canEncode(value) || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Invalid Jira snapshot text encoding");
            }
        }
        if (fixtureRevisionSha256 == null || !fixtureRevisionSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid synthetic Jira revision");
        }
    }

    /** Synthetic v1 codec: UTF-8 fields prefixed with unsigned 32-bit big-endian byte lengths. */
    public String fingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : new String[]{"asep-jira-fixture-v1", identity.siteId(), identity.projectId(),
                    identity.issueId(), issueType, status, fixtureRevisionSha256,
                    sha256(summary.getBytes(StandardCharsets.UTF_8)), sha256(description.getBytes(StandardCharsets.UTF_8))}) {
                byte[] value = field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(value.length).array());
                digest.update(value);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    @Override public String toString() { return "JiraTicketSnapshot[untrusted text redacted; synthetic provenance]"; }
}
