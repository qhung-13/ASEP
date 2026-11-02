package io.github.qhung13.asep.codedelivery;

import io.github.qhung13.asep.integration.GitLabTestProjectReader.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import static io.github.qhung13.asep.codedelivery.DeliveryRecord.State;
import static io.github.qhung13.asep.codedelivery.TestProjectDeliveryPreparation.*;
import static io.github.qhung13.asep.codedelivery.TestProjectRemoteReconciliation.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * VN-T14 fake-only integration scenarios against an OWNED disposable PostgreSQL instance.
 * The synthetic "all-verified" grant/receipt/CI fixtures below NEVER authorize real GitLab I/O.
 * This test has no HTTP or Git client, credential provider, Spring bean or live executor.
 */
class TestProjectSyntheticFlowPostgresTest {
    private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
    private static final String OTHER_HEAD = "9".repeat(40);
    private PGSimpleDataSource database;
    private Fixture fixture;

    @BeforeEach void requireOwnedDisposableDatabase() throws Exception {
        assumeTrue(Boolean.getBoolean("asep.delivery.postgres"), "Use scripts/ci_verify_delivery.sh");
        String url = System.getenv("ASEP_DELIVERY_JDBC_URL");
        assertEquals("true", System.getenv("ASEP_DELIVERY_DISPOSABLE"));
        assertNotNull(url);
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/asep_delivery_test_[0-9a-f]{32}"));
        assertEquals("asep_delivery_runtime", System.getenv("ASEP_DELIVERY_DB_USER"));
        String nonce = System.getenv("ASEP_DELIVERY_NONCE");
        assertNotNull(nonce);
        assertTrue(nonce.matches("[0-9a-f]{64}"));
        database = new PGSimpleDataSource();
        database.setUrl(url);
        database.setUser(System.getenv("ASEP_DELIVERY_DB_USER"));
        database.setPassword(System.getenv("ASEP_DELIVERY_DB_PASSWORD"));
        database.setConnectTimeout(3);
        database.setSocketTimeout(5);
        database.setOptions("-c statement_timeout=4000 -c lock_timeout=3000");
        try (var db = database.getConnection(); var query = db.prepareStatement("""
                SELECT current_user='asep_delivery_runtime' AND database_name=current_database()
                    AND server_started_at=pg_postmaster_start_time() AND nonce=?
                FROM asep_delivery_test_proof.instance WHERE singleton
                """)) {
            query.setString(1, nonce);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                assertTrue(rows.getBoolean(1));
                assertFalse(rows.next());
            }
        }
        fixture = new Fixture();
    }

    private final class Fixture {
        final UUID operationId = UUID.randomUUID();
        final UUID runId = UUID.randomUUID();
        final UUID attemptId = UUID.randomUUID();
        final UUID sealId = UUID.randomUUID();
        final UUID verifierId = UUID.randomUUID();
        final UUID grantId = UUID.randomUUID();
        final Profile profile = new Profile("1".repeat(64), "2".repeat(64), "3".repeat(64), 2, Duration.ofMinutes(5));
        final Map<String, byte[]> objects = new HashMap<>();
        final TestProjectDeliveryRequest request;
        final TestProjectDeliveryPreparation preparation;
        final TestProjectDeliveryGate gate;
        final FakeRemote remote;
        boolean allowGrant = true;
        Runnable afterPushClaim = () -> {};
        Runnable afterMrClaim = () -> {};

        Fixture() {
            String root = put(ObjectType.TREE, new byte[0]);
            String base = commit(root, null, "Base test fixture\n");
            String blob = put(ObjectType.BLOB, probeContent(operationId));
            String path = "vn-t14-probe-" + operationId + ".txt";
            byte[] candidateTree = ("100644 " + path + "\0").getBytes(StandardCharsets.US_ASCII);
            byte[] entry = new byte[candidateTree.length + 20];
            System.arraycopy(candidateTree, 0, entry, 0, candidateTree.length);
            System.arraycopy(HexFormat.of().parseHex(blob), 0, entry, candidateTree.length, 20);
            String tree = put(ObjectType.TREE, entry);
            String candidate = commit(tree, base, "VN-T14 isolated test " + operationId + "\n");
            var preliminary = new TestProjectDeliveryRequest(operationId, runId, attemptId, 1,
                    grantId, "synthetic-only", 1, base, base, candidate, tree, "0".repeat(64), sealId,
                    verifierId, profile.profileSha256(), "4".repeat(64));
            request = new TestProjectDeliveryRequest(operationId, runId, attemptId, 1,
                    grantId, "synthetic-only", 1, base, base, candidate, tree, manifestHash(preliminary), sealId,
                    verifierId, profile.profileSha256(), "4".repeat(64));
            var seal = new Seal(sealId, runId, attemptId, base, candidate, tree, request.manifestSha256(),
                    NOW.minusSeconds(5), NOW.minusSeconds(4), NOW.plusSeconds(60));
            var verification = new Verification(verifierId, runId, attemptId, sealId, request.manifestSha256(),
                    profile.profileSha256(), profile.harnessSha256(), profile.imageSha256(), "5".repeat(64),
                    2, 2, 0, 0, 0, false, NOW.minusSeconds(1), NOW.plusSeconds(60));
            preparation = new TestProjectDeliveryPreparation((type, sha, bound) -> {
                byte[] bytes = objects.get(type + ":" + sha);
                return bytes == null ? null : bytes.clone();
            }, new Receipts() {
                @Override public Seal seal(UUID id) { return sealId.equals(id) ? seal : null; }
                @Override public Verification verification(UUID id) { return verifierId.equals(id) ? verification : null; }
            }, profile, Clock.fixed(NOW, ZoneOffset.UTC));
            gate = new TestProjectDeliveryGate((r, inspection) -> {
                if (!allowGrant) return new TestProjectDeliveryGate.Evidence(EnumSet.noneOf(TestProjectDeliveryGate.Requirement.class), null);
                var grant = new TestProjectDeliveryGate.OwnerGrant(grantId, operationId, request.bodyHash(),
                        request.projectId(), "qhung-13/asep-m01-sandbox", request.sourceBranch(), request.targetBranch());
                return new TestProjectDeliveryGate.Evidence(EnumSet.allOf(TestProjectDeliveryGate.Requirement.class), grant);
            }, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30));
            remote = new FakeRemote(request);
        }

        String put(ObjectType type, byte[] bytes) {
            String sha = gitId(type, bytes);
            objects.put(type + ":" + sha, bytes.clone());
            return sha;
        }
        String commit(String tree, String parent, String message) {
            return put(ObjectType.COMMIT, ("tree " + tree + "\n"
                    + (parent == null ? "" : "parent " + parent + "\n")
                    + "author Fixture <fixture@invalid> 1793145600 +0000\n"
                    + "committer Fixture <fixture@invalid> 1793145600 +0000\n\n" + message)
                    .getBytes(StandardCharsets.US_ASCII));
        }
        JdbcTestProjectDeliveryLedger ledger() { return new JdbcTestProjectDeliveryLedger(database); }
        void validate() {
            gate.requireBeforeMutation(request, remote.inspection());
            assertEquals(request, preparation.prepare(request).request());
        }
        TestProjectDeliveryRecord inspect(JdbcTestProjectDeliveryLedger ledger, TestProjectDeliveryRecord record) {
            return ledger.observe(record, remote.observe()).record();
        }

        /** The only "writer" in this class is the in-memory FakeRemote. */
        TestProjectDeliveryRecord execute() {
            JdbcTestProjectDeliveryLedger ledger = ledger(); // New Java instance emulates a process restart.
            var record = inspect(ledger, ledger.admit(request));
            // Missing/incomplete preclaim remote reads cannot prove absence of a collision.
            if (!completeRead()) return record;
            if (!record.pushClaimed() && record.push() == State.PLANNED
                    && record.mergeRequest() == State.PLANNED && !record.residualRace()) {
                validate();
                var claimed = ledger.claimPush(record);
                record = claimed.record();
                if (claimed.applied()) {
                    afterPushClaim.run(); // Intentional crash/fault boundary AFTER committed claim.
                    validate(); // A committed claim NEVER skips current gates before send.
                    if (!completeRead() || remote.branch != null) return inspect(ledger, record);
                    try { remote.push(request); }
                    catch (FakeRemote.LostReply lost) { /* Unknown reply; read instead of retry. */ }
                    record = inspect(ledger, record);
                }
            }
            if (record.pushClaimed()) record = inspect(ledger, record);
            if (record.push() != State.CONFIRMED || record.residualRace() || !completeRead()) return record;
            if (!record.mrClaimed() && record.mergeRequest() == State.PLANNED) {
                validate();
                var claimed = ledger.claimMr(record);
                record = claimed.record();
                if (claimed.applied()) {
                    afterMrClaim.run(); // Intentional crash/fault boundary AFTER committed claim.
                    validate();
                    if (!completeRead() || remote.branch == null || !request.candidateCommitSha().equals(remote.branch.head())
                            || !remote.requests.isEmpty()) return inspect(ledger, record);
                    try { remote.createDraft(request, TestProjectDeliveryPreparation.render(request)); }
                    catch (FakeRemote.LostReply lost) { /* Unknown reply; read instead of retry. */ }
                    record = inspect(ledger, record);
                }
            }
            if (record.mrClaimed()) record = inspect(ledger, record);
            return record;
        }
        private boolean completeRead() {
            var read = remote.observe();
            return read.readable() && read.branchComplete() && read.mrListComplete()
                    && read.targetHead() != null;
        }
    }

    /** Deterministic and in-memory: no GitLab client, credentials, hooks or remote mutation. */
    private static final class FakeRemote {
        static final class LostReply extends RuntimeException {}
        final TestProjectDeliveryRequest request;
        String targetHead;
        Branch branch;
        final List<MergeRequest> requests = new ArrayList<>();
        boolean readable = true;
        boolean branchComplete = true;
        boolean mrListComplete = true;
        boolean losePushReply;
        boolean loseMrReply;
        Runnable afterPush = () -> {};
        Runnable afterMr = () -> {};
        int pushWrites;
        int mrWrites;
        FakeRemote(TestProjectDeliveryRequest request) {
            this.request = request;
            targetHead = request.expectedTargetSha();
        }
        Inspection inspection() {
            var empty = new CollectionObservation(0, true);
            return new Inspection(request.projectId(), "main",
                    new Project(request.projectId(), "qhung-13/asep-m01-sandbox", "private", false,
                            "main", "disabled", false, 30),
                    new Target("main", targetHead, true, false), empty, empty, empty, empty, empty,
                    List.of(), java.util.Set.of(), NOW, NOW);
        }
        Observation observe() {
            return new Observation(readable, branchComplete && readable, readable ? branch : null,
                    mrListComplete && readable, readable ? List.copyOf(requests) : List.of(),
                    readable ? targetHead : null);
        }
        void push(TestProjectDeliveryRequest r) {
            if (branch != null) throw new IllegalStateException("Fake branch collision");
            branch = new Branch(r.projectId(), r.sourceBranch(), r.candidateCommitSha());
            pushWrites++;
            afterPush.run();
            if (losePushReply) throw new LostReply();
        }
        void createDraft(TestProjectDeliveryRequest r, Draft draft) {
            if (branch == null || !r.candidateCommitSha().equals(branch.head()) || !render(r).equals(draft))
                throw new IllegalStateException("Fake precondition");
            requests.add(new MergeRequest(r.projectId(), r.projectId(), r.projectId(), 1000 + mrWrites,
                    r.sourceBranch(), r.targetBranch(), branch.head(), true, draft.title(), draft.body()));
            mrWrites++;
            afterMr.run();
            if (loseMrReply) throw new LostReply();
        }
    }

    @Test void completeSyntheticDeliveryHasOnePushAndDraftAcrossJavaRestarts() {
        var first = fixture.execute();
        assertTrue(first.exactRemoteCandidateObserved());
        assertEquals(1, fixture.remote.pushWrites);
        assertEquals(1, fixture.remote.mrWrites);
        assertEquals(first, fixture.execute());
        assertEquals(first, fixture.ledger().find(fixture.operationId));
        assertEquals(1, fixture.remote.pushWrites);
        assertEquals(1, fixture.remote.mrWrites);
        assertEquals(java.util.Set.of(1000L), first.knownMrIids());
        assertEquals(5, fixture.ledger().pendingCallbacks(fixture.operationId).size());
    }

    @Test void crashAfterPushClaimNeverBlindlyRetriesMissingBranch() {
        fixture.afterPushClaim = () -> { throw new IllegalStateException("SIMULATED_CRASH_AFTER_PUSH_CLAIM"); };
        assertThrows(IllegalStateException.class, fixture::execute);
        var claimed = fixture.ledger().find(fixture.operationId);
        assertTrue(claimed.pushClaimed());
        assertEquals(State.IN_PROGRESS, claimed.push());
        assertEquals(0, fixture.remote.pushWrites);
        fixture.afterPushClaim = () -> {};
        var recovered = fixture.execute();
        assertEquals(State.UNKNOWN, recovered.push());
        assertEquals(0, fixture.remote.pushWrites);
        assertFalse(fixture.ledger().claimPush(recovered).applied());
    }

    @Test void lostPushResponseAndUnreadableLookupAreReconciledWithoutResend() {
        fixture.remote.losePushReply = true;
        fixture.remote.afterPush = () -> fixture.remote.readable = false;
        var unresolved = fixture.execute();
        assertEquals(State.MANUAL_RECONCILIATION_REQUIRED, unresolved.push());
        assertEquals(1, fixture.remote.pushWrites);
        assertEquals(0, fixture.remote.mrWrites);
        fixture.remote.readable = true;
        fixture.remote.afterPush = () -> {};
        var recovered = fixture.execute();
        assertTrue(recovered.exactRemoteCandidateObserved());
        assertEquals(1, fixture.remote.pushWrites);
        assertEquals(1, fixture.remote.mrWrites);
    }

    @Test void crashAfterMrClaimCannotStartSecondMrCreate() {
        fixture.afterMrClaim = () -> { throw new IllegalStateException("SIMULATED_CRASH_AFTER_MR_CLAIM"); };
        assertThrows(IllegalStateException.class, fixture::execute);
        var claimed = fixture.ledger().find(fixture.operationId);
        assertTrue(claimed.mrClaimed());
        assertEquals(State.IN_PROGRESS, claimed.mergeRequest());
        assertEquals(1, fixture.remote.pushWrites);
        assertEquals(0, fixture.remote.mrWrites);
        fixture.afterMrClaim = () -> {};
        var recovered = fixture.execute();
        assertEquals(State.UNKNOWN, recovered.mergeRequest());
        assertEquals(0, fixture.remote.mrWrites);
        assertFalse(fixture.ledger().claimMr(recovered).applied());
    }

    @Test void lostMrReplyAndUnreadableLookupPreserveIidThenRecoverWithoutDuplicate() {
        fixture.remote.loseMrReply = true;
        fixture.remote.afterMr = () -> fixture.remote.readable = false;
        var unresolved = fixture.execute();
        assertEquals(State.MANUAL_RECONCILIATION_REQUIRED, unresolved.mergeRequest());
        assertEquals(1, fixture.remote.mrWrites);
        fixture.remote.readable = true;
        fixture.remote.afterMr = () -> {};
        var recovered = fixture.execute();
        assertTrue(recovered.exactRemoteCandidateObserved());
        assertEquals(java.util.Set.of(1000L), recovered.knownMrIids());
        assertEquals(1, fixture.remote.pushWrites);
        assertEquals(1, fixture.remote.mrWrites);
    }

    @Test void preexistingWrongSourceBranchIsBlockedBeforeAnySend() {
        fixture.remote.branch = new Branch(fixture.request.projectId(), fixture.request.sourceBranch(), OTHER_HEAD);
        var blocked = fixture.execute();
        assertEquals(State.BLOCKED, blocked.push());
        assertFalse(blocked.residualRace()); // No previously claimed send can produce post-send race.
        assertEquals(0, fixture.remote.pushWrites);
        assertEquals(0, fixture.remote.mrWrites);
    }

    @Test void unavailableTrustedGrantFailsBeforeClaimAndWithoutAnyFakeWrite() {
        fixture.allowGrant = false;
        assertThrows(DeliveryException.class, fixture::execute);
        assertFalse(fixture.ledger().find(fixture.operationId).pushClaimed());
        assertEquals(0, fixture.remote.pushWrites);
        assertEquals(0, fixture.remote.mrWrites);
    }

    @Test void grantRevocationAfterCommittedClaimDeniesSendAndKeepsHistory() {
        fixture.afterPushClaim = () -> fixture.allowGrant = false;
        assertThrows(DeliveryException.class, fixture::execute);
        var committed = fixture.ledger().find(fixture.operationId);
        assertTrue(committed.pushClaimed());
        assertEquals(State.IN_PROGRESS, committed.push());
        assertEquals(0, fixture.remote.pushWrites);
        fixture.allowGrant = true;
        assertEquals(State.UNKNOWN, fixture.execute().push());
        assertEquals(0, fixture.remote.pushWrites);
    }

    @Test void targetMovesAfterClaimAndNoFakeSendOccurs() {
        fixture.afterPushClaim = () -> fixture.remote.targetHead = OTHER_HEAD;
        assertThrows(DeliveryException.class, fixture::execute);
        assertTrue(fixture.ledger().find(fixture.operationId).pushClaimed());
        assertEquals(0, fixture.remote.pushWrites);
        fixture.afterPushClaim = () -> {};
        var observed = fixture.execute();
        assertTrue(observed.residualRace());
        assertEquals(0, fixture.remote.pushWrites);
    }

    @Test void postSendSourceDriftRetainsMrFactAndStickyResidualRace() {
        fixture.remote.afterMr = () -> fixture.remote.branch = new Branch(
                fixture.request.projectId(), fixture.request.sourceBranch(), OTHER_HEAD);
        var blocked = fixture.execute();
        assertTrue(blocked.residualRace());
        assertEquals(java.util.Set.of(1000L), blocked.knownMrIids());
        assertEquals(1, fixture.remote.mrWrites);
        fixture.remote.readable = false;
        var unknown = fixture.execute();
        assertTrue(unknown.residualRace());
        assertEquals(java.util.Set.of(1000L), unknown.knownMrIids());
        assertEquals(1, fixture.remote.mrWrites);
    }

    @Test void duplicateRemoteMrResultsRemainUnknownWithBothIidsAndNoDuplicateCreate() {
        fixture.remote.afterMr = () -> {
            var first = fixture.remote.requests.getFirst();
            fixture.remote.requests.add(new MergeRequest(first.projectId(), first.sourceProjectId(),
                    first.targetProjectId(), 2000, first.source(), first.target(), first.head(),
                    first.draft(), first.title(), first.body()));
        };
        var ambiguous = fixture.execute();
        assertEquals(State.UNKNOWN, ambiguous.mergeRequest());
        assertEquals(java.util.Set.of(1000L, 2000L), ambiguous.knownMrIids());
        assertEquals(1, fixture.remote.mrWrites);
        fixture.execute();
        assertEquals(1, fixture.remote.mrWrites);
    }

    @Test void unreadableRemoteBeforeAnyClaimCannotAuthorizeFakePush() {
        fixture.remote.readable = false;
        var unavailable = fixture.execute();
        assertEquals(State.PLANNED, unavailable.push());
        assertFalse(unavailable.pushClaimed());
        assertEquals(0, fixture.remote.pushWrites);
        fixture.remote.readable = true;
        assertTrue(fixture.execute().exactRemoteCandidateObserved());
        assertEquals(1, fixture.remote.pushWrites);
    }

    @Test void sourceCollisionAfterClaimIsNeverOverwrittenOrBlindlyRetried() {
        fixture.afterPushClaim = () -> fixture.remote.branch = new Branch(
                fixture.request.projectId(), fixture.request.sourceBranch(), OTHER_HEAD);
        var blocked = fixture.execute();
        assertTrue(blocked.pushClaimed());
        assertEquals(State.BLOCKED, blocked.push());
        assertTrue(blocked.residualRace());
        assertEquals(0, fixture.remote.pushWrites);
        fixture.execute();
        assertEquals(0, fixture.remote.pushWrites);
    }

    @Test void incompleteReadDoesNotTurnAbsentRemoteStateIntoPermissionToWrite() {
        fixture.remote.mrListComplete = false;
        var unknown = fixture.execute();
        assertEquals(State.MANUAL_RECONCILIATION_REQUIRED, unknown.mergeRequest());
        assertEquals(0, fixture.remote.pushWrites);
        assertEquals(0, fixture.remote.mrWrites);
    }
}
