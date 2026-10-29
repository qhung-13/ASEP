package io.github.qhung13.asep.codedelivery;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Local preparation only: no remote client, credentials, mutation, receipt issuer or application wiring.
 * Artifacts/receipts must come from protected trusted controllers, never request JSON or agent flags.
 * These ports have no configured live implementation. A Prepared value is NOT write authorization.
 */
public final class TestProjectDeliveryPreparation {
    public enum ObjectType { COMMIT, TREE, BLOB }
    public interface Artifacts {
        /** Trusted content-addressed store must enforce the bound before materializing bytes. */
        byte[] read(ObjectType type, String sha, int maximumBytes);
    }
    public interface Receipts {
        Seal seal(UUID receiptId);
        Verification verification(UUID receiptId);
        static Receipts unavailable() {
            return new Receipts() {
                @Override public Seal seal(UUID id) { return null; }
                @Override public Verification verification(UUID id) { return null; }
            };
        }
    }
    public record Seal(UUID receiptId, UUID runId, UUID attemptId, String baseSha, String commitSha,
            String treeSha, String manifestSha256, Instant writerStoppedAt, Instant sealedAt, Instant expiresAt) {}
    public record Verification(UUID receiptId, UUID runId, UUID attemptId, UUID sealReceiptId,
            String manifestSha256, String profileSha256, String harnessSha256, String imageSha256,
            String logsSha256, int discovered, int executed, int skipped, int failed, int exitCode,
            boolean timedOut, Instant issuedAt, Instant expiresAt) {}
    /** Explicit controller profile; no production thresholds or time-to-live are guessed here. */
    public record Profile(String profileSha256, String harnessSha256, String imageSha256,
            int minimumTests, Duration maximumReceiptAge) {
        public Profile {
            if (!sha256(profileSha256) || !sha256(harnessSha256) || !sha256(imageSha256)
                    || minimumTests < 1 || maximumReceiptAge == null || maximumReceiptAge.isNegative()
                    || maximumReceiptAge.isZero()) throw blocked();
        }
    }
    public record Draft(String title, String body) {
        @Override public String toString() { return "TestProjectDraft[controlled text]"; }
    }
    public record Prepared(TestProjectDeliveryRequest request, Draft draft) {
        @Override public String toString() { return "PreparedTestProjectCandidate[not write authority]"; }
    }
    private record Entry(String mode, String name, String sha) {}
    private record CommitObject(String tree, List<String> parents, String message) {}
    private final Artifacts artifacts;
    private final Receipts receipts;
    private final Profile profile;
    private final Clock clock;
    public TestProjectDeliveryPreparation(Artifacts artifacts, Receipts receipts, Profile profile, Clock clock) {
        this.artifacts = Objects.requireNonNull(artifacts);
        this.receipts = Objects.requireNonNull(receipts);
        this.profile = Objects.requireNonNull(profile);
        this.clock = Objects.requireNonNull(clock);
    }

    public Prepared prepare(TestProjectDeliveryRequest request) {
        try {
            Objects.requireNonNull(request);
            inspectObjects(request);
            Seal seal = receipts.seal(request.sealReceiptId());
            Verification verification = receipts.verification(request.verificationReceiptId());
            Instant now = clock.instant();
            if (seal == null || verification == null || !request.sealReceiptId().equals(seal.receiptId())
                    || !request.runId().equals(seal.runId()) || !request.attemptId().equals(seal.attemptId())
                    || !request.baseSha().equals(seal.baseSha()) || !request.candidateCommitSha().equals(seal.commitSha())
                    || !request.candidateTreeSha().equals(seal.treeSha()) || !request.manifestSha256().equals(seal.manifestSha256())
                    || !fresh(seal.sealedAt(), seal.expiresAt(), now) || seal.writerStoppedAt() == null
                    || seal.writerStoppedAt().isAfter(seal.sealedAt())
                    || !request.verificationReceiptId().equals(verification.receiptId())
                    || !request.runId().equals(verification.runId()) || !request.attemptId().equals(verification.attemptId())
                    || !seal.receiptId().equals(verification.sealReceiptId())
                    || !request.manifestSha256().equals(verification.manifestSha256())
                    || !request.verifierProfileSha256().equals(profile.profileSha256())
                    || !profile.profileSha256().equals(verification.profileSha256())
                    || !profile.harnessSha256().equals(verification.harnessSha256())
                    || !profile.imageSha256().equals(verification.imageSha256()) || !sha256(verification.logsSha256())
                    || !fresh(verification.issuedAt(), verification.expiresAt(), now)
                    || verification.issuedAt().isBefore(seal.sealedAt())
                    || verification.discovered() < profile.minimumTests() || verification.executed() != verification.discovered()
                    || verification.skipped() != 0 || verification.failed() != 0 || verification.exitCode() != 0
                    || verification.timedOut()) throw blocked();
            // Only validated immutable identities enter the fixed renderer. No ticket/prompt/Markdown input.
            return new Prepared(request, render(request));
        } catch (RuntimeException unavailable) {
            throw blocked(); // Do not retain raw object bytes, receipt/store errors, paths or credentials.
        }
    }
    static Draft render(TestProjectDeliveryRequest request) {
        return new Draft("Draft: ASEP VN-T14 test " + request.operationId(),
                    "VN-T14 isolated test candidate: `" + request.candidateCommitSha() + "`\n"
                    + "Manifest: `" + request.manifestSha256() + "`\nOperation: `" + request.operationId() + "`\n"
                    + "Intent: `" + request.bodyHash() + "`\nHuman review and merge required.\nCI status is recorded separately.");
    }
    private boolean fresh(Instant issued, Instant expires, Instant now) {
        return issued != null && expires != null && !issued.isAfter(now) && expires.isAfter(now)
                && expires.isAfter(issued) && Duration.between(issued, now).compareTo(profile.maximumReceiptAge()) <= 0;
    }
    private void inspectObjects(TestProjectDeliveryRequest r) {
        CommitObject candidate = commit(object(ObjectType.COMMIT, r.candidateCommitSha(), 8192));
        CommitObject base = commit(object(ObjectType.COMMIT, r.baseSha(), 8192));
        if (!candidate.tree.equals(r.candidateTreeSha()) || !candidate.parents.equals(List.of(r.baseSha()))
                || !candidate.message.equals("VN-T14 isolated test " + r.operationId() + "\n")) throw blocked();
        List<Entry> original = tree(object(ObjectType.TREE, base.tree, 1048576));
        List<Entry> changed = tree(object(ObjectType.TREE, candidate.tree, 1048576));
        if (original.stream().anyMatch(e -> e.name.equals(r.probePath()))) throw blocked();
        Entry probe = new Entry("100644", r.probePath(), gitId(ObjectType.BLOB, probeContent(r.operationId())));
        var expected = new ArrayList<>(original); expected.add(probe); expected.sort(ENTRY_ORDER);
        if (!expected.equals(changed) || !Arrays.equals(probeContent(r.operationId()), object(ObjectType.BLOB, probe.sha, 256))
                || !manifestHash(r).equals(r.manifestSha256())) throw blocked();
    }
    private byte[] object(ObjectType type, String sha, int maximumBytes) {
        byte[] supplied = artifacts.read(type, sha, maximumBytes);
        if (supplied == null || supplied.length > maximumBytes) throw blocked();
        byte[] bytes = supplied.clone(); // Own the bytes before hashing; a caller cannot mutate them between hash and parse.
        if (!gitId(type, bytes).equals(sha)) throw blocked();
        return bytes;
    }
    /** Canonical changed-file manifest, not a seal or verification proof. All other base entries must be identical. */
    public static String manifestHash(TestProjectDeliveryRequest r) {
        byte[] probe = probeContent(r.operationId());
        return DeliveryIntent.hash(List.of("asep-vn-t14-single-probe-manifest-v1", Long.toString(r.projectId()),
                r.runId().toString(), r.attemptId().toString(), r.baseSha(), r.expectedTargetSha(),
                r.candidateCommitSha(), r.candidateTreeSha(), r.probePath(), "100644", Integer.toString(probe.length),
                gitId(ObjectType.BLOB, probe), digest("SHA-256", probe)));
    }
    public static byte[] probeContent(UUID operationId) {
        return ("VN-T14 isolated delivery probe\nOperation: " + Objects.requireNonNull(operationId) + "\n").getBytes(StandardCharsets.US_ASCII);
    }
    static String gitId(ObjectType type, byte[] bytes) {
        byte[] header = (type.name().toLowerCase(java.util.Locale.ROOT) + " " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII);
        byte[] encoded = Arrays.copyOf(header, header.length + bytes.length);
        System.arraycopy(bytes, 0, encoded, header.length, bytes.length);
        return digest("SHA-1", encoded);
    }
    private static String digest(String algorithm, byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("Required digest unavailable"); }
    }
    private static boolean sha256(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static CommitObject commit(byte[] bytes) {
        // Parse only Git headers; never execute filters, hooks, signatures, attributes or build code.
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        int boundary = text.indexOf("\n\n");
        if (boundary < 0) throw blocked();
        String tree = null;
        int authors = 0, committers = 0;
        var parents = new ArrayList<String>();
        if (!text.startsWith("tree ")) throw blocked();
        for (String line : text.substring(0, boundary).split("\n")) {
            if (line.startsWith("author ") || line.startsWith("committer ")) {
                if (!line.matches("(author|committer) [^<>\\p{Cntrl}]+ <[^<>\\p{Cntrl}]*> [0-9]{1,19} [+-][0-9]{4}")) throw blocked();
                if (line.startsWith("author ")) authors++; else committers++;
            }
            if (line.startsWith("tree ")) { if (tree != null) throw blocked(); tree = line.substring(5); }
            if (line.startsWith("parent ")) {
                String parent = line.substring(7);
                if (!parent.matches("[0-9a-f]{40}")) throw blocked();
                parents.add(parent);
            }
        }
        if (tree == null || !tree.matches("[0-9a-f]{40}") || authors != 1 || committers != 1) throw blocked();
        return new CommitObject(tree, List.copyOf(parents), text.substring(boundary + 2)); // Root/merge bases are supported; candidate must have exactly the base parent.
    }
    private static final Comparator<Entry> ENTRY_ORDER = Comparator.comparing(e -> e.name + (e.mode.equals("40000") ? "/" : ""));
    private static List<Entry> tree(byte[] bytes) {
        var entries = new ArrayList<Entry>();
        int offset = 0;
        while (offset < bytes.length) {
            int nul = offset;
            while (nul < bytes.length && bytes[nul] != 0) nul++;
            if (nul == bytes.length || nul + 21 > bytes.length || entries.size() >= 4096) throw blocked();
            String header = new String(bytes, offset, nul - offset, StandardCharsets.US_ASCII);
            if (!header.matches("(100644|100755|120000|160000|40000) [A-Za-z0-9._-]{1,255}")) throw blocked();
            String[] parts = header.split(" ", 2);
            if (parts[1].equals(".") || parts[1].equals("..")) throw blocked();
            var entry = new Entry(parts[0], parts[1], HexFormat.of().formatHex(bytes, nul + 1, nul + 21));
            if (!entries.isEmpty() && (entries.getLast().name.equals(entry.name) || ENTRY_ORDER.compare(entries.getLast(), entry) >= 0)) throw blocked();
            if (entries.stream().anyMatch(e -> e.name.equals(entry.name))) throw blocked();
            entries.add(entry); offset = nul + 21;
        }
        return List.copyOf(entries);
    }
    private static IllegalStateException blocked() { return new IllegalStateException("VN-T14 candidate preparation blocked"); }
}
