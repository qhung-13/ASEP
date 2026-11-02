package io.github.qhung13.asep.codedelivery;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import static io.github.qhung13.asep.codedelivery.DeliveryRecord.State;
import static io.github.qhung13.asep.codedelivery.TestProjectRemoteReconciliation.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real disposable PostgreSQL fixtures. There is no GitLab transport, credential or receipt issuer. */
class TestProjectDeliveryLedgerPostgresTest {
    private static final String BASE = "a".repeat(40);
    private static final String CANDIDATE = "b".repeat(40);
    private PGSimpleDataSource database;
    private JdbcTestProjectDeliveryLedger ledger;
    private TestProjectDeliveryRequest request;

    @BeforeEach void requireOwnedDatabase() throws Exception {
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
        // Never mutate an arbitrary database merely because the caller set an environment variable.
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
        ledger = new JdbcTestProjectDeliveryLedger(database);
        request = fixture(UUID.randomUUID(), UUID.randomUUID(), "d".repeat(64));
    }

    private TestProjectDeliveryRequest fixture(UUID operation, UUID run, String manifest) {
        return new TestProjectDeliveryRequest(operation, run, UUID.randomUUID(), 1,
                UUID.randomUUID(), "local-fixture", 1, BASE, BASE, CANDIDATE, "c".repeat(40), manifest,
                UUID.randomUUID(), UUID.randomUUID(), "e".repeat(64), "f".repeat(64));
    }

    private JdbcTestProjectDeliveryLedger restarted() { return new JdbcTestProjectDeliveryLedger(database); }
    private Branch branch(String head) { return new Branch(request.projectId(), request.sourceBranch(), head); }
    private Observation observed(String branchHead, List<MergeRequest> mrs) {
        return new Observation(true, true, branchHead == null ? null : branch(branchHead), true, mrs, BASE);
    }
    private MergeRequest mr(long iid, String head) {
        var text = TestProjectDeliveryPreparation.render(request);
        return new MergeRequest(request.projectId(), request.projectId(), request.projectId(), iid,
                request.sourceBranch(), request.targetBranch(), head, true, text.title(), text.body());
    }
    private TestProjectDeliveryRecord pushClaimed() {
        var admitted = ledger.admit(request);
        var claim = ledger.claimPush(admitted);
        assertTrue(claim.applied());
        return claim.record();
    }
    private TestProjectDeliveryRecord pushConfirmed() {
        var claimed = pushClaimed();
        var confirmed = restarted().observe(claimed, observed(CANDIDATE, List.of()));
        assertTrue(confirmed.applied());
        assertEquals(State.CONFIRMED, confirmed.record().push());
        return confirmed.record();
    }
    private TestProjectDeliveryRecord mrClaimed() {
        var claim = restarted().claimMr(pushConfirmed());
        assertTrue(claim.applied());
        return claim.record();
    }
    private int callbackCount(UUID operationId) throws Exception {
        try (var db = database.getConnection(); var query = db.prepareStatement("""
                SELECT count(*) FROM code_delivery.vn_t14_callbacks WHERE operation_id=?
                """)) {
            query.setObject(1, operationId);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    @Test void admissionReplaysExactlyAndConflictingRequestOrRunCannotReplaceIt() throws Exception {
        var first = ledger.admit(request);
        assertEquals(State.PLANNED, first.push());
        assertEquals(1, first.revision());
        assertEquals(first, restarted().admit(request));
        assertEquals(first, restarted().find(request.operationId()));
        assertEquals(1, callbackCount(request.operationId()));
        assertEquals(DeliveryException.Kind.CONFLICT, assertThrows(DeliveryException.class,
                () -> ledger.admit(fixture(request.operationId(), request.runId(), "9".repeat(64)))).kind());
        assertEquals(DeliveryException.Kind.CONFLICT, assertThrows(DeliveryException.class,
                () -> ledger.admit(fixture(UUID.randomUUID(), request.runId(), "d".repeat(64)))).kind());
        assertEquals(first, ledger.find(request.operationId()));
    }

    @Test void claimsAreDurableOneWayAndMrCannotBeClaimedBeforeExactPushConfirmation() throws Exception {
        var admitted = ledger.admit(request);
        assertFalse(ledger.claimMr(admitted).applied());
        var push = ledger.claimPush(admitted);
        assertTrue(push.applied());
        assertEquals(State.IN_PROGRESS, push.record().push());
        assertTrue(push.record().pushClaimed());
        assertEquals(2, push.record().revision());
        assertFalse(restarted().claimPush(push.record()).applied());
        assertFalse(restarted().claimMr(push.record()).applied());
        assertEquals(push.record(), restarted().find(request.operationId()));
        var confirmed = restarted().observe(push.record(), observed(CANDIDATE, List.of()));
        assertTrue(confirmed.applied());
        assertEquals(CANDIDATE, confirmed.record().lastConfirmedPushHead());
        var mr = restarted().claimMr(confirmed.record());
        assertTrue(mr.applied());
        assertEquals(State.IN_PROGRESS, mr.record().mergeRequest());
        assertTrue(mr.record().mrClaimed());
        assertFalse(restarted().claimMr(mr.record()).applied());
        assertEquals(4, callbackCount(request.operationId()));
    }

    @Test void crashAfterClaimWithoutRemoteProofRemainsUnknownWithoutNewSendClaim() throws Exception {
        var claimed = pushClaimed();
        var afterRestart = restarted().find(request.operationId());
        assertEquals(claimed, afterRestart);
        var recovery = restarted().observe(afterRestart, observed(null, List.of()));
        assertTrue(recovery.applied());
        assertEquals(State.UNKNOWN, recovery.record().push());
        assertTrue(recovery.record().pushClaimed());
        assertFalse(restarted().claimPush(recovery.record()).applied());
        assertFalse(restarted().claimMr(recovery.record()).applied());
        assertFalse(restarted().observe(recovery.record(), observed(null, List.of())).applied());
        assertEquals(3, callbackCount(request.operationId()));
    }

    @Test void exactReadAfterLostReplyPersistsRemoteFactsAndAckSurvivesRestart() throws Exception {
        var claimed = mrClaimed();
        var observed = restarted().observe(claimed, observed(CANDIDATE, List.of(mr(7, CANDIDATE))));
        assertTrue(observed.applied());
        assertTrue(observed.record().exactRemoteCandidateObserved());
        assertEquals(Set.of(7L), observed.record().knownMrIids());
        assertEquals(observed.record(), restarted().find(request.operationId()));
        var pending = restarted().pendingCallbacks(request.operationId());
        assertEquals(5, pending.size());
        var last = pending.getLast();
        assertEquals(observed.record().revision(), last.revision());
        assertEquals(List.of(7L), last.knownMrIids());
        restarted().acknowledge(last);
        restarted().acknowledge(last); // Idempotent trusted ACK.
        assertEquals(4, restarted().pendingCallbacks(request.operationId()).size());
        var forged = new TestProjectDeliveryRecord.Callback(last.callbackId(), last.operationId(), last.runId(),
                last.expectedRunVersion(), last.requestHash(), last.revision(), last.push(), last.mergeRequest(),
                last.pushClaimed(), last.mrClaimed(), last.lastConfirmedPushHead(), List.of(999L), last.residualRace());
        assertEquals(DeliveryException.Kind.CONFLICT, assertThrows(DeliveryException.class,
                () -> restarted().acknowledge(forged)).kind());
    }

    @Test void remoteDriftAndRevokedReadRetainMrIdentityConfirmedHeadAndStickyRace() throws Exception {
        var claimed = mrClaimed();
        var changed = restarted().observe(claimed, observed("9".repeat(40), List.of(mr(21, "9".repeat(40)))));
        assertTrue(changed.applied());
        assertEquals(Set.of(21L), changed.record().knownMrIids());
        assertTrue(changed.record().residualRace());
        assertEquals(State.BLOCKED, changed.record().mergeRequest());
        var unavailable = new Observation(false, false, null, false, List.of(), null);
        var afterReadLoss = restarted().observe(changed.record(), unavailable);
        assertTrue(afterReadLoss.applied());
        assertEquals(State.MANUAL_RECONCILIATION_REQUIRED, afterReadLoss.record().mergeRequest());
        assertEquals(CANDIDATE, afterReadLoss.record().lastConfirmedPushHead());
        assertEquals(Set.of(21L), afterReadLoss.record().knownMrIids());
        assertTrue(afterReadLoss.record().residualRace());
        assertFalse(afterReadLoss.record().exactRemoteCandidateObserved());
        assertEquals(afterReadLoss.record(), restarted().find(request.operationId()));
    }

    @Test void duplicateAndOutOfOrderMrObservationsNeverLoseHistoryOrInventSuccess() throws Exception {
        var claimed = mrClaimed();
        var ambiguous = restarted().observe(claimed, observed(CANDIDATE, List.of(mr(7, CANDIDATE), mr(8, CANDIDATE))));
        assertTrue(ambiguous.applied());
        assertEquals(State.UNKNOWN, ambiguous.record().mergeRequest());
        assertEquals(Set.of(7L, 8L), ambiguous.record().knownMrIids());
        var smaller = restarted().observe(ambiguous.record(), observed(CANDIDATE, List.of(mr(7, CANDIDATE))));
        assertFalse(smaller.applied());
        assertEquals(Set.of(7L, 8L), smaller.record().knownMrIids());
        assertEquals(State.UNKNOWN, smaller.record().mergeRequest());
        assertEquals(5, callbackCount(request.operationId()));
    }

    @Test void staleSnapshotsCannotOverwriteNewerRemoteObservationsOrStartAnotherClaim() throws Exception {
        var admitted = ledger.admit(request);
        var claimed = restarted().claimPush(admitted);
        assertTrue(claimed.applied());
        assertFalse(restarted().observe(admitted, observed(CANDIDATE, List.of())).applied());
        assertFalse(restarted().claimPush(admitted).applied());
        assertEquals(claimed.record(), restarted().find(request.operationId()));
        var confirmed = restarted().observe(claimed.record(), observed(CANDIDATE, List.of()));
        assertTrue(confirmed.applied());
        assertFalse(restarted().observe(claimed.record(), observed(null, List.of())).applied());
        assertEquals(State.CONFIRMED, restarted().find(request.operationId()).push());
    }

    @Test void concurrentClaimersHaveExactlyOneDurableWinnerAndOneCallbackRevision() throws Exception {
        var admitted = ledger.admit(request);
        var ready = new CountDownLatch(8);
        var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<java.util.concurrent.Future<JdbcTestProjectDeliveryLedger.Update>>();
            for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> {
                ready.countDown();
                if (!go.await(5, TimeUnit.SECONDS)) throw new AssertionError("Claim gate timed out");
                return restarted().claimPush(admitted);
            }));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            int wins = 0;
            for (var future : futures) if (future.get(15, TimeUnit.SECONDS).applied()) wins++;
            assertEquals(1, wins);
        }
        var stored = restarted().find(request.operationId());
        assertEquals(2L, stored.revision());
        assertTrue(stored.pushClaimed());
        assertEquals(2, callbackCount(request.operationId()));
    }

    @Test void callbackInsertionFailureRollsBackClaimAndRevisionTogether() throws Exception {
        var admitted = ledger.admit(request);
        try (var db = database.getConnection(); var statement = db.prepareStatement("""
                INSERT INTO code_delivery.vn_t14_callbacks
                    (callback_id,operation_id,revision,payload_json,payload_hash)
                VALUES(?,?,2,'{}',?)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, request.operationId());
            statement.setString(3, "e".repeat(64));
            assertEquals(1, statement.executeUpdate());
        }
        var failure = assertThrows(DeliveryException.class, () -> restarted().claimPush(admitted));
        assertEquals(DeliveryException.Kind.CONFLICT, failure.kind());
        assertEquals(admitted, restarted().find(request.operationId()));
        assertEquals(1L, restarted().find(request.operationId()).revision());
        // The injected conflicting callback persists; the failed claim produced no new callback.
        assertEquals(2, callbackCount(request.operationId()));
    }

    @Test void callbackFailureAlsoRollsBackNewlyObservedMrHistory() throws Exception {
        var claimed = mrClaimed(); // Revision 4; no remote MR has been accepted yet.
        try (var db = database.getConnection(); var statement = db.prepareStatement("""
                INSERT INTO code_delivery.vn_t14_callbacks
                    (callback_id,operation_id,revision,payload_json,payload_hash)
                VALUES(?,?,5,'{}',?)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, request.operationId());
            statement.setString(3, "e".repeat(64));
            assertEquals(1, statement.executeUpdate());
        }
        var failure = assertThrows(DeliveryException.class,
                () -> restarted().observe(claimed, observed(CANDIDATE, List.of(mr(33, CANDIDATE)))));
        assertEquals(DeliveryException.Kind.CONFLICT, failure.kind());
        assertEquals(claimed, restarted().find(request.operationId()));
        assertTrue(restarted().find(request.operationId()).knownMrIids().isEmpty());
        try (var db = database.getConnection(); var statement = db.prepareStatement("""
                SELECT count(*) FROM code_delivery.vn_t14_observed_mrs WHERE operation_id=?
                """)) {
            statement.setObject(1, request.operationId());
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
        }
    }

    @Test void immutableRequestAndCallbackPayloadStillCannotBeRewrittenByRuntime() throws Exception {
        var admitted = ledger.admit(request);
        try (var db = database.getConnection(); var statement = db.prepareStatement("""
                UPDATE code_delivery.vn_t14_operations SET request_hash=? WHERE operation_id=?
                """)) {
            statement.setString(1, "e".repeat(64));
            statement.setObject(2, request.operationId());
            var error = assertThrows(SQLException.class, statement::executeUpdate);
            assertEquals("42501", error.getSQLState());
        }
        try (var db = database.getConnection(); var statement = db.prepareStatement("""
                UPDATE code_delivery.vn_t14_callbacks SET payload_json='{}' WHERE operation_id=?
                """)) {
            statement.setObject(1, request.operationId());
            var error = assertThrows(SQLException.class, statement::executeUpdate);
            assertEquals("42501", error.getSQLState());
        }
        assertEquals(admitted, restarted().find(request.operationId()));
    }
}
