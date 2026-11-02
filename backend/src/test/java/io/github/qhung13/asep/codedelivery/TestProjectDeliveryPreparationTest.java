package io.github.qhung13.asep.codedelivery;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static io.github.qhung13.asep.codedelivery.TestProjectDeliveryPreparation.*;
import static org.junit.jupiter.api.Assertions.*;

class TestProjectDeliveryPreparationTest {
    private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
    private static final UUID OP = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID RUN = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID ATTEMPT = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final UUID SEAL = UUID.fromString("10000000-0000-4000-8000-000000000004");
    private static final UUID VERIFIED = UUID.fromString("10000000-0000-4000-8000-000000000005");
    private final Map<String, byte[]> objects = new HashMap<>();
    private String base;
    private String tree;
    private String candidate;
    private Seal seal;
    private Verification verification;
    private final Profile profile = new Profile("1".repeat(64), "2".repeat(64), "3".repeat(64), 2, Duration.ofMinutes(10));
    private final Receipts receipts = new Receipts() {
        @Override public Seal seal(UUID id) { assertEquals(SEAL, id); return seal; }
        @Override public Verification verification(UUID id) { assertEquals(VERIFIED, id); return verification; }
    };
    private record Item(String mode, String name, String sha) {}
    private String put(ObjectType type, byte[] bytes) {
        String sha = gitId(type, bytes); objects.put(type + ":" + sha, bytes.clone()); return sha;
    }
    private byte[] treeBytes(List<Item> items) {
        var output = new ByteArrayOutputStream();
        items.stream().sorted(Comparator.comparing(i -> i.name + (i.mode.equals("40000") ? "/" : ""))).forEach(i -> {
            output.writeBytes((i.mode + " " + i.name + "\0").getBytes(StandardCharsets.US_ASCII));
            output.writeBytes(HexFormat.of().parseHex(i.sha));
        });
        return output.toByteArray();
    }
    private String commit(String root, String parent, String message) {
        return put(ObjectType.COMMIT, ("tree " + root + "\n" + (parent == null ? "" : "parent " + parent + "\n")
                + "author Fixture <fixture@invalid> 1793145600 +0000\ncommitter Fixture <fixture@invalid> 1793145600 +0000\n\n" + message).getBytes(StandardCharsets.US_ASCII));
    }
    private List<Item> baseEntries() {
        // Existing nested trees, executable files and CI remain byte-for-byte unchanged.
        return List.of(new Item("100644", ".gitlab-ci.yml", put(ObjectType.BLOB, "# existing CI, NOT safe-CI evidence\n".getBytes(StandardCharsets.US_ASCII))),
                new Item("100755", "existing.sh", put(ObjectType.BLOB, "existing fixture".getBytes(StandardCharsets.US_ASCII))),
                new Item("40000", "src", put(ObjectType.TREE, new byte[0])));
    }
    private TestProjectDeliveryRequest request(String manifest) {
        return new TestProjectDeliveryRequest(OP, RUN, ATTEMPT, 1, UUID.fromString("10000000-0000-4000-8000-000000000006"),
                "vn-t14-local-fixture", 1, base, base, candidate, tree, manifest, SEAL, VERIFIED, profile.profileSha256(), "4".repeat(64));
    }
    private TestProjectDeliveryRequest fixture(String fault) {
        var originals = baseEntries();
        if (fault.equals("collision")) {
            originals = new ArrayList<>(originals); originals.add(new Item("100644", "vn-t14-probe-" + OP + ".txt", put(ObjectType.BLOB, new byte[0])));
        }
        String originalTree = put(ObjectType.TREE, treeBytes(originals));
        base = commit(originalTree, null, "Base fixture\n");
        if (fault.equals("base-merge")) base = put(ObjectType.COMMIT, ("tree " + originalTree + "\nparent " + "a".repeat(40) + "\nparent " + "b".repeat(40) + "\nauthor Fixture <fixture@invalid> 1793145600 +0000\ncommitter Fixture <fixture@invalid> 1793145600 +0000\n\nBase fixture\n").getBytes(StandardCharsets.US_ASCII));
        var changed = new ArrayList<>(originals);
        String blob = put(ObjectType.BLOB, fault.equals("wrong-content") ? "/merge\n".getBytes(StandardCharsets.US_ASCII) : probeContent(OP));
        String mode = fault.equals("symlink") ? "120000" : fault.equals("executable") ? "100755" : "100644";
        changed.add(new Item(mode, fault.equals("path") ? ".gitlab-ci-override.yml" : "vn-t14-probe-" + OP + ".txt", blob));
        if (fault.equals("ci-change")) changed.set(0, new Item("100644", ".gitlab-ci.yml", put(ObjectType.BLOB, "script: deploy".getBytes(StandardCharsets.US_ASCII))));
        if (fault.equals("subtree-change")) changed.set(2, new Item("40000", "src", put(ObjectType.TREE, treeBytes(List.of(new Item("100644", "extra", blob))))));
        byte[] changedTree = treeBytes(changed);
        if (fault.equals("truncated-tree")) changedTree = java.util.Arrays.copyOf(changedTree, changedTree.length - 1);
        if (fault.equals("traversal-tree")) changedTree = treeBytes(List.of(new Item("100644", "../probe", blob)));
        if (fault.equals("duplicate-tree-entry")) {
            var duplicate = new ArrayList<>(changed); duplicate.add(changed.getFirst()); changedTree = treeBytes(duplicate);
        }
        tree = put(ObjectType.TREE, changedTree);
        candidate = commit(tree, fault.equals("wrong-parent") ? "f".repeat(40) : base,
                fault.equals("message") ? "[skip ci]\n/merge\n" : "VN-T14 isolated test " + OP + "\n");
        if (fault.equals("merge-candidate")) candidate = put(ObjectType.COMMIT, ("tree " + tree + "\nparent " + base + "\nparent " + "a".repeat(40) + "\nauthor Fixture <fixture@invalid> 1793145600 +0000\ncommitter Fixture <fixture@invalid> 1793145600 +0000\n\nVN-T14 isolated test " + OP + "\n").getBytes(StandardCharsets.US_ASCII));
        if (fault.equals("duplicate-tree-header")) candidate = put(ObjectType.COMMIT, ("tree " + tree + "\ntree " + tree + "\nparent " + base + "\nauthor Fixture <fixture@invalid> 1793145600 +0000\ncommitter Fixture <fixture@invalid> 1793145600 +0000\n\nVN-T14 isolated test " + OP + "\n").getBytes(StandardCharsets.US_ASCII));
        if (fault.equals("root-candidate")) candidate = commit(tree, null, "VN-T14 isolated test " + OP + "\n");
        if (fault.equals("missing-author")) candidate = put(ObjectType.COMMIT, ("tree " + tree + "\nparent " + base + "\ncommitter Fixture <fixture@invalid> 1793145600 +0000\n\nVN-T14 isolated test " + OP + "\n").getBytes(StandardCharsets.US_ASCII));
        var r = request("0".repeat(64));
        r = request(manifestHash(r));
        seal = new Seal(SEAL, RUN, ATTEMPT, base, candidate, tree, r.manifestSha256(), NOW.minusSeconds(5), NOW.minusSeconds(4), NOW.plusSeconds(300));
        verification = new Verification(VERIFIED, RUN, ATTEMPT, SEAL, r.manifestSha256(), profile.profileSha256(), profile.harnessSha256(),
                profile.imageSha256(), "5".repeat(64), 2, 2, 0, 0, 0, false, NOW.minusSeconds(1), NOW.plusSeconds(300));
        return r;
    }
    private TestProjectDeliveryPreparation preparation(Receipts store) {
        return new TestProjectDeliveryPreparation((type, sha, bound) -> {
            byte[] bytes = objects.get(type + ":" + sha);
            return bytes == null ? null : bytes.clone();
        }, store, profile, Clock.fixed(NOW, ZoneOffset.UTC));
    }
    @Test void exactObjectsAndControllerReceiptsProduceOnlyALocalControlledPlan() {
        var r = fixture("");
        var prepared = preparation(receipts).prepare(r);
        assertEquals(87786140L, r.projectId()); assertEquals("main", r.targetBranch());
        assertEquals("test/vn-t14/" + OP, r.sourceBranch());
        assertNotEquals("asep/delivery/" + OP, r.sourceBranch());
        assertEquals("Draft: ASEP VN-T14 test " + OP, prepared.draft().title());
        assertTrue(prepared.draft().body().contains(r.candidateCommitSha()));
        assertTrue(prepared.draft().body().contains(r.bodyHash()));
        assertFalse(prepared.draft().body().lines().anyMatch(line -> line.stripLeading().startsWith("/")));
        assertEquals("PreparedTestProjectCandidate[not write authority]", prepared.toString());
        assertEquals(0, TestProjectDeliveryPreparation.class.getAnnotations().length);
    }
    @Test void existingMergeBaseIsSupportedButCandidateRemainsOneCommitOnThatExactBase() {
        var r = fixture("base-merge");
        assertEquals(r, preparation(receipts).prepare(r).request());
    }
    @Test void contentHashesMatchKnownGitObjects() {
        assertEquals("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", gitId(ObjectType.BLOB, new byte[0]));
        assertEquals("4b825dc642cb6eb9a060e54bf8d69288fbee4904", gitId(ObjectType.TREE, new byte[0]));
    }
    @ParameterizedTest @ValueSource(strings = {"collision", "wrong-content", "symlink", "executable", "path", "ci-change", "subtree-change", "wrong-parent", "message", "truncated-tree", "traversal-tree", "duplicate-tree-entry", "merge-candidate", "duplicate-tree-header", "root-candidate", "missing-author"})
    void rejectsAnythingExceptTheExactSingleRegularProbeOnTheBase(String fault) {
        var r = fixture(fault);
        assertThrows(IllegalStateException.class, () -> preparation(receipts).prepare(r));
    }
    @ParameterizedTest @ValueSource(strings = {"commit-tamper", "tree-tamper", "blob-tamper", "missing", "oversize"})
    void rehashesEverySuppliedObjectInsteadOfTrustingManifestOrStoreFilename(String fault) {
        var r = fixture("");
        String key = fault.startsWith("tree") ? ObjectType.TREE + ":" + tree
                : fault.startsWith("blob") ? ObjectType.BLOB + ":" + gitId(ObjectType.BLOB, probeContent(OP)) : ObjectType.COMMIT + ":" + candidate;
        if (fault.equals("missing")) objects.remove(key);
        else objects.put(key, fault.equals("oversize") ? new byte[8193] : "PRIVATE_UNTRUSTED_OBJECT_CONTENT".getBytes(StandardCharsets.US_ASCII));
        var failure = assertThrows(IllegalStateException.class, () -> preparation(receipts).prepare(r));
        assertFalse(failure.toString().contains("PRIVATE")); assertNull(failure.getCause());
    }
    @Test void intentReceiptIdsAndSuccessfulObjectValidationAreNotEvidence() {
        var r = fixture("");
        assertThrows(IllegalStateException.class, () -> preparation(Receipts.unavailable()).prepare(r));
        var hostile = new Receipts() {
            @Override public Seal seal(UUID id) { throw new IllegalStateException("PRIVATE_STORE_ERROR_OR_TOKEN"); }
            @Override public Verification verification(UUID id) { fail("should stop on store failure"); return null; }
        };
        var failure = assertThrows(IllegalStateException.class, () -> preparation(hostile).prepare(r));
        assertNull(failure.getCause()); assertFalse(failure.toString().contains("PRIVATE"));
    }
    @ParameterizedTest @ValueSource(strings = {"receipt", "run", "attempt", "base", "commit", "tree", "manifest", "writer-active", "writer-after-seal", "expired", "future", "old"})
    void rejectsUnboundOrStaleSeal(String fault) {
        var r = fixture("");
        seal = new Seal(fault.equals("receipt") ? UUID.randomUUID() : SEAL, fault.equals("run") ? UUID.randomUUID() : RUN,
                fault.equals("attempt") ? UUID.randomUUID() : ATTEMPT, fault.equals("base") ? "f".repeat(40) : base,
                fault.equals("commit") ? "f".repeat(40) : candidate, fault.equals("tree") ? "f".repeat(40) : tree,
                fault.equals("manifest") ? "f".repeat(64) : r.manifestSha256(),
                fault.equals("writer-active") ? null : fault.equals("writer-after-seal") ? NOW : NOW.minusSeconds(900),
                fault.equals("future") ? NOW.plusSeconds(1) : fault.equals("old") ? NOW.minusSeconds(601) : NOW.minusSeconds(4),
                fault.equals("expired") ? NOW : NOW.plusSeconds(300));
        assertThrows(IllegalStateException.class, () -> preparation(receipts).prepare(r));
    }
    @ParameterizedTest @ValueSource(strings = {"receipt", "run", "attempt", "seal", "manifest", "profile", "harness", "image", "logs", "zero", "partial", "skip", "failed", "exit", "timeout", "pre-seal", "expired", "future", "old"})
    void rejectsSpoofedWrongProfileOrNonPassingVerification(String fault) {
        var r = fixture("");
        verification = new Verification(fault.equals("receipt") ? UUID.randomUUID() : VERIFIED,
                fault.equals("run") ? UUID.randomUUID() : RUN, fault.equals("attempt") ? UUID.randomUUID() : ATTEMPT,
                fault.equals("seal") ? UUID.randomUUID() : SEAL, fault.equals("manifest") ? "f".repeat(64) : r.manifestSha256(),
                fault.equals("profile") ? "f".repeat(64) : profile.profileSha256(), fault.equals("harness") ? "f".repeat(64) : profile.harnessSha256(),
                fault.equals("image") ? "f".repeat(64) : profile.imageSha256(), fault.equals("logs") ? "not-a-digest" : "5".repeat(64),
                fault.equals("zero") ? 0 : 2, fault.equals("partial") ? 1 : fault.equals("zero") ? 0 : 2,
                fault.equals("skip") ? 1 : 0, fault.equals("failed") ? 1 : 0, fault.equals("exit") ? 1 : 0, fault.equals("timeout"),
                fault.equals("pre-seal") ? NOW.minusSeconds(5) : fault.equals("future") ? NOW.plusSeconds(1) : fault.equals("old") ? NOW.minusSeconds(601) : NOW.minusSeconds(1),
                fault.equals("expired") ? NOW : NOW.plusSeconds(300));
        assertThrows(IllegalStateException.class, () -> preparation(receipts).prepare(r));
    }
    @Test void requestManifestTamperingAndWrongTargetAreRejected() {
        fixture("");
        assertThrows(IllegalStateException.class, () -> preparation(receipts).prepare(request("f".repeat(64))));
        assertThrows(IllegalArgumentException.class, () -> new TestProjectDeliveryRequest(OP, RUN, ATTEMPT, 1, UUID.randomUUID(), "test", 1,
                base, "f".repeat(40), candidate, tree, "0".repeat(64), SEAL, VERIFIED, profile.profileSha256(), "4".repeat(64)));
    }
    @Test void domainSeparatedIdentityBindsCurrentOperationRunAndGrant() {
        var r = fixture("");
        var other = new TestProjectDeliveryRequest(OP, RUN, ATTEMPT, 2, r.ownerGrantId(), r.policyId(), r.policyEpoch(), base, base,
                candidate, tree, r.manifestSha256(), SEAL, VERIFIED, r.verifierProfileSha256(), r.safeCiProfileSha256());
        assertNotEquals(r.bodyHash(), other.bodyHash());
        assertNotEquals(CodeDeliveryContractTest.intent().bodyHash(), r.bodyHash());
        assertEquals(64, r.bodyHash().length());
    }
}
