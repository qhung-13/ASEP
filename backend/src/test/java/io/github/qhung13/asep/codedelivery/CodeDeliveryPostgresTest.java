package io.github.qhung13.asep.codedelivery;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;
import static io.github.qhung13.asep.codedelivery.CodeDeliveryContractTest.*;
import static io.github.qhung13.asep.codedelivery.DeliveryChecks.*;
import static io.github.qhung13.asep.codedelivery.DeliveryRecord.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CodeDeliveryPostgresTest {
    private PGSimpleDataSource database;
    private JdbcDeliveryLedger ledger;
    private DeliveryIntent intent;
    private MutableChecks checks;
    private FakeGitLab remote;
    private CodeDeliveryExecutor executor;
    private static final class MutableChecks implements DeliveryChecks {
        volatile Current value;
        volatile Access access = new Access(true, true);
        volatile boolean unavailable, callbackAuthority = true;
        @Override public Current current(UUID operationId) {
            if (unavailable) throw new IllegalStateException("synthetic private policy detail");
            return value;
        }
        @Override public Access access(String repositoryId) { return access; }
        @Override public boolean trustedCallbackAcknowledgement() { return callbackAuthority; }
    }
    private static Current approved(DeliveryIntent i) {
        return new Current(i, true, true,
                new VerifiedCandidate(i.runId(), i.attemptId(), i.baseSha(), i.candidateCommitSha(), i.candidateTreeSha(), i.manifestSha256(), i.sealReceiptId(), true),
                new Verification(i.runId(), i.attemptId(), i.manifestSha256(), i.verificationReceiptId(), i.verifierProfileSha256(), true, 3, 3, 0, 0, false),
                i.expectedTargetSha(), i.safeCiProfileSha256(), true);
    }
    @BeforeEach void setUp() throws Exception {
        assumeTrue(Boolean.getBoolean("asep.delivery.postgres"), "Run scripts/ci_verify_delivery.sh for the required disposable PostgreSQL suite");
        String url = System.getenv("ASEP_DELIVERY_JDBC_URL");
        assertEquals("true", System.getenv("ASEP_DELIVERY_DISPOSABLE"));
        assertNotNull(url);
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/asep_delivery_test_[0-9a-f]{32}"));
        assertEquals("asep_delivery_runtime", System.getenv("ASEP_DELIVERY_DB_USER"));
        String nonce = System.getenv("ASEP_DELIVERY_NONCE");
        assertNotNull(nonce); assertTrue(nonce.matches("[0-9a-f]{64}"));
        database = new PGSimpleDataSource();
        database.setUrl(url); database.setUser(System.getenv("ASEP_DELIVERY_DB_USER"));
        database.setPassword(System.getenv("ASEP_DELIVERY_DB_PASSWORD"));
        database.setConnectTimeout(3); database.setSocketTimeout(5);
        database.setOptions("-c statement_timeout=4000 -c lock_timeout=3000");
        // Read-only instance/role/server-start proof BEFORE any mutation or executor creation.
        try (var db = database.getConnection(); var query = db.prepareStatement("""
                SELECT current_user='asep_delivery_runtime' AND database_name=current_database()
                    AND server_started_at=pg_postmaster_start_time() AND nonce=?
                FROM asep_delivery_test_proof.instance WHERE singleton
                """)) {
            query.setString(1, nonce);
            try (var row = query.executeQuery()) { assertTrue(row.next()); assertTrue(row.getBoolean(1)); assertFalse(row.next()); }
        }
        ledger = new JdbcDeliveryLedger(database);
        intent = intent();
        checks = new MutableChecks(); checks.value = approved(intent);
        remote = new FakeGitLab(); remote.enrollSyntheticTarget(intent.repositoryId(), intent.expectedTargetSha());
        executor = new CodeDeliveryExecutor(ledger, checks, remote);
    }
    private void persisted(DeliveryRecord expected, int pushes, int mrs) throws Exception {
        assertEquals(expected, new JdbcDeliveryLedger(database).find(intent.operationId()));
        assertEquals(pushes, remote.pushMutations()); assertEquals(mrs, remote.mrMutations());
        try (var db = database.getConnection(); var statement = db.prepareStatement("SELECT count(*) FROM code_delivery.operations WHERE run_id=? AND repository_id=?")) {
            statement.setObject(1, intent.runId()); statement.setString(2, intent.repositoryId());
            try (var rows = statement.executeQuery()) { assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); }
        }
    }
    private Current withApproved(DeliveryIntent approved) {
        Current c = checks.value;
        return new Current(approved, c.committedGoIntent(), c.ownerAuthorized(), c.candidate(), c.verification(), c.observedTargetSha(), c.safeCiProfileSha256(), c.safeCi());
    }
    private void invalidate(String mode) {
        Current c = checks.value;
        switch (mode) {
            case "jira" -> checks.value = withApproved(change(intent, "jiraSnapshotSha256", "e".repeat(64)));
            case "base" -> checks.value = withApproved(change(intent, "baseSha", "4".repeat(40)));
            case "policy" -> checks.value = withApproved(change(intent, "policyId", "synthetic-revoked-policy"));
            case "candidate" -> checks.value = withApproved(change(intent, "candidateCommitSha", "5".repeat(40)));
            case "manifest" -> checks.value = new Current(intent, true, true, new VerifiedCandidate(intent.runId(), intent.attemptId(), intent.baseSha(), intent.candidateCommitSha(), intent.candidateTreeSha(), "e".repeat(64), intent.sealReceiptId(), true), c.verification(), c.observedTargetSha(), c.safeCiProfileSha256(), true);
            case "verification" -> checks.value = new Current(intent, true, true, c.candidate(), new Verification(intent.runId(), intent.attemptId(), "e".repeat(64), intent.verificationReceiptId(), intent.verifierProfileSha256(), true, 3, 3, 0, 0, false), c.observedTargetSha(), c.safeCiProfileSha256(), true);
            case "empty-tests" -> checks.value = new Current(intent, true, true, c.candidate(), new Verification(intent.runId(), intent.attemptId(), intent.manifestSha256(), intent.verificationReceiptId(), intent.verifierProfileSha256(), true, 0, 0, 0, 0, false), c.observedTargetSha(), c.safeCiProfileSha256(), true);
            case "agent-evidence" -> checks.value = new Current(intent, true, true, c.candidate(), new Verification(intent.runId(), intent.attemptId(), intent.manifestSha256(), intent.verificationReceiptId(), intent.verifierProfileSha256(), false, 3, 3, 0, 0, false), c.observedTargetSha(), c.safeCiProfileSha256(), true);
            case "writer-active" -> checks.value = new Current(intent, true, true, new VerifiedCandidate(intent.runId(), intent.attemptId(), intent.baseSha(), intent.candidateCommitSha(), intent.candidateTreeSha(), intent.manifestSha256(), intent.sealReceiptId(), false), c.verification(), c.observedTargetSha(), c.safeCiProfileSha256(), true);
            case "authorization" -> checks.value = new Current(intent, true, false, c.candidate(), c.verification(), c.observedTargetSha(), c.safeCiProfileSha256(), true);
            case "uncommitted" -> checks.value = new Current(intent, false, true, c.candidate(), c.verification(), c.observedTargetSha(), c.safeCiProfileSha256(), true);
            case "unsafe-ci" -> checks.value = new Current(intent, true, true, c.candidate(), c.verification(), c.observedTargetSha(), c.safeCiProfileSha256(), false);
            case "ci-profile" -> checks.value = new Current(intent, true, true, c.candidate(), c.verification(), c.observedTargetSha(), "e".repeat(64), true);
            case "target" -> remote.enrollSyntheticTarget(intent.repositoryId(), "6".repeat(40));
            case "unavailable" -> checks.unavailable = true;
            case "revoked-access" -> checks.access = new Access(true, false);
            case "default-deny" -> executor = new CodeDeliveryExecutor(ledger, DeliveryChecks.unavailable(), remote);
            default -> throw new IllegalArgumentException("unknown fixture mode");
        }
    }

    @Test void exactIntentReplayAndDuplicateOutOfOrderCallbackAcksDoNotMutateAgain() throws Exception {
        var result = executor.execute(intent);
        assertTrue(result.draftMrComplete());
        var callbacks = ledger.pendingCallbacks(intent.operationId());
        assertEquals(5, callbacks.size());
        assertEquals(result, executor.execute(intent));
        assertEquals(callbacks, ledger.pendingCallbacks(intent.operationId()));
        var reversed = new ArrayList<>(callbacks); Collections.reverse(reversed);
        for (var callback : reversed) { executor.acknowledge(callback); executor.acknowledge(callback); }
        assertTrue(ledger.pendingCallbacks(intent.operationId()).isEmpty());
        var c = callbacks.getFirst();
        var forged = new Callback(c.callbackId(), c.operationId(), c.runId(), c.expectedRunVersion(), "e".repeat(64), c.revision(), c.push(), c.mergeRequest(), c.mrIid(), c.residualRace());
        assertEquals(DeliveryException.Kind.CONFLICT, assertThrows(DeliveryException.class, () -> executor.acknowledge(forged)).kind());
        checks.callbackAuthority = false;
        assertEquals(DeliveryException.Kind.INVALID, assertThrows(DeliveryException.class, () -> executor.acknowledge(c)).kind());
        persisted(result, 1, 1);
    }

    @ParameterizedTest @ValueSource(strings = {"jira", "base", "policy", "candidate", "manifest", "verification", "empty-tests", "agent-evidence", "writer-active", "authorization", "uncommitted", "unsafe-ci", "ci-profile", "target", "unavailable", "revoked-access", "default-deny"})
    void rf12PreWriteChangesOrMissingGatesBlockWithZeroWriteCalls(String mode) throws Exception {
        invalidate(mode);
        var result = executor.execute(intent);
        assertEquals(State.BLOCKED, result.push()); assertFalse(result.draftMrComplete());
        assertEquals(0, remote.pushCalls()); assertEquals(0, remote.mrCalls());
        persisted(result, 0, 0);
    }

    @ParameterizedTest @ValueSource(strings = {"jira", "base", "policy", "candidate", "manifest", "verification", "unsafe-ci", "target", "authorization", "unavailable"})
    void rf12RevalidatesBeforeMrEvenAfterPushWasConfirmed(String mode) throws Exception {
        remote.pushReply = FakeGitLab.Reply.LOST;
        assertEquals(State.UNKNOWN, executor.execute(intent).push());
        invalidate(mode);
        var result = executor.execute(intent);
        assertEquals(State.CONFIRMED, result.push()); assertEquals(State.BLOCKED, result.mergeRequest());
        assertEquals(1, remote.pushCalls()); assertEquals(0, remote.mrCalls());
        persisted(result, 1, 0);
    }

    @Test void rf13LostPushReplyReconcilesAfterExecutorRestartBeforeAnyFurtherSend() throws Exception {
        remote.pushReply = FakeGitLab.Reply.LOST;
        var unknown = executor.execute(intent);
        assertEquals(State.UNKNOWN, unknown.push()); assertEquals(State.PLANNED, unknown.mergeRequest());
        persisted(unknown, 1, 0);
        var restarted = new CodeDeliveryExecutor(new JdbcDeliveryLedger(database), checks, remote);
        var result = restarted.execute(intent);
        assertTrue(result.draftMrComplete()); assertEquals(1, remote.pushCalls());
        persisted(result, 1, 1);
    }

    @ParameterizedTest @ValueSource(strings = {"owner", "head", "hash"})
    void rf13BranchCollisionBlocksWithoutOverwrite(String mode) throws Exception {
        remote.seed(new FakeGitLab.Branch(intent.repositoryId(), intent.sourceBranch(), mode.equals("head") ? "9".repeat(40) : intent.candidateCommitSha(),
                mode.equals("owner") ? UUID.randomUUID() : intent.operationId(), mode.equals("hash") ? "f".repeat(64) : intent.bodyHash()));
        var result = executor.execute(intent);
        assertEquals(State.BLOCKED, result.push());
        assertEquals(0, remote.pushCalls()); assertEquals(0, remote.mrCalls());
        persisted(result, 0, 0);
    }

    @Test void rf14LostMrReplyHasSeparateUnknownStateAndReconcilesUniqueOwnership() throws Exception {
        remote.mrReply = FakeGitLab.Reply.LOST;
        var unknown = executor.execute(intent);
        assertEquals(State.CONFIRMED, unknown.push()); assertEquals(State.UNKNOWN, unknown.mergeRequest());
        assertFalse(unknown.draftMrComplete()); assertNull(unknown.mrIid());
        persisted(unknown, 1, 1);
        var result = new CodeDeliveryExecutor(new JdbcDeliveryLedger(database), checks, remote).execute(intent);
        assertTrue(result.draftMrComplete()); assertNotNull(result.mrIid()); assertEquals(1, remote.mrCalls());
        persisted(result, 1, 1);
    }

    @ParameterizedTest @ValueSource(strings = {"NORMAL", "LOST", "LOST-RESTORED"})
    void sourceBranchMovementDuringMrCreationNeverConfirmsStaleCandidate(String reply) throws Exception {
        String movedHead = "9".repeat(40);
        remote.mrReply = reply.equals("NORMAL") ? FakeGitLab.Reply.NORMAL : FakeGitLab.Reply.LOST;
        remote.beforeMr = () -> {
            // Observe the committed exact push before changing the remote source,
            // after the executor's last pre-create branch check.
            assertEquals(State.CONFIRMED, new JdbcDeliveryLedger(database).find(intent.operationId()).push());
            assertEquals(intent.candidateCommitSha(), remote.branch(intent).head());
            remote.seed(new FakeGitLab.Branch(intent.repositoryId(), intent.sourceBranch(), movedHead,
                    intent.operationId(), intent.bodyHash()));
        };

        var result = executor.execute(intent);
        assertFalse(result.draftMrComplete());
        assertNotEquals(State.CONFIRMED, result.mergeRequest());
        assertTrue(result.residualRace());
        assertEquals(State.CONFIRMED, result.push());
        assertEquals(reply.equals("NORMAL") ? State.BLOCKED : State.UNKNOWN, result.mergeRequest());
        var actual = remote.mergeRequests(intent).getFirst();
        assertEquals(movedHead, actual.head());
        assertEquals(intent.operationId(), actual.owner());
        assertEquals(intent.bodyHash(), actual.intentHash());
        if (reply.equals("NORMAL")) assertEquals(actual.iid(), result.mrIid());
        else assertNull(result.mrIid()); // A lost reply cannot supply a fabricated remote IID.
        persisted(result, 1, 1);

        if (reply.equals("LOST-RESTORED")) {
            // Returning the branch to the candidate must not hide the MR's actual
            // mismatched creation head during later read-based reconciliation.
            remote.seed(new FakeGitLab.Branch(intent.repositoryId(), intent.sourceBranch(), intent.candidateCommitSha(),
                    intent.operationId(), intent.bodyHash()));
        }
        for (int n = 0; n < 3; n++) {
            result = new CodeDeliveryExecutor(new JdbcDeliveryLedger(database), checks, remote).execute(intent);
            assertFalse(result.draftMrComplete());
            assertEquals(State.BLOCKED, result.mergeRequest());
            assertEquals(Reason.REMOTE_MISMATCH, result.reason());
            assertTrue(result.residualRace());
            assertEquals(actual.iid(), result.mrIid());
            assertEquals(List.of(actual), remote.mergeRequests(intent)); // No delete or replacement MR.
            assertEquals(reply.equals("LOST-RESTORED") ? intent.candidateCommitSha() : movedHead, remote.branch(intent).head());
            assertEquals(1, remote.pushCalls()); assertEquals(1, remote.mrCalls());
            persisted(result, 1, 1);
        }
        var callbacks = ledger.pendingCallbacks(intent.operationId());
        assertTrue(callbacks.stream().noneMatch(c -> c.mergeRequest() == State.CONFIRMED));
        assertEquals(actual.iid(), callbacks.getLast().mrIid());
        assertTrue(callbacks.getLast().residualRace());
        try (var db = database.getConnection(); var statement = db.prepareStatement("""
                SELECT push_state='CONFIRMED' AND mr_state='CONFIRMED' AND NOT residual_race,
                    mr_state,mr_iid,residual_race FROM code_delivery.operations WHERE operation_id=?
                """)) {
            statement.setObject(1, intent.operationId());
            try (var row = statement.executeQuery()) {
                assertTrue(row.next()); assertFalse(row.getBoolean(1));
                assertEquals("BLOCKED", row.getString(2)); assertEquals(actual.iid(), row.getLong(3));
                assertTrue(row.getBoolean(4)); assertFalse(row.next());
            }
        }
    }

    @Test void rf14AmbiguousMrsStayUnknownWithoutCreatingASecondMr() throws Exception {
        remote.mrReply = FakeGitLab.Reply.LOST;
        var unknown = executor.execute(intent);
        remote.seed(new FakeGitLab.MergeRequest(99, intent.repositoryId(), intent.sourceBranch(), intent.targetBranch(), intent.candidateCommitSha(), true, intent.operationId(), intent.bodyHash()));
        for (int n = 0; n < 3; n++) {
            var result = new CodeDeliveryExecutor(new JdbcDeliveryLedger(database), checks, remote).execute(intent);
            assertEquals(State.CONFIRMED, result.push()); assertEquals(State.UNKNOWN, result.mergeRequest());
            assertFalse(result.draftMrComplete()); persisted(result, 1, 1);
        }
        assertEquals(1, remote.mrCalls()); assertFalse(unknown.draftMrComplete());
    }

    @ParameterizedTest @ValueSource(strings = {"owner", "head", "target", "draft"})
    void rf14MismatchedMrCannotBeConfirmedByBranchOrTitleAlone(String mode) throws Exception {
        remote.pushReply = FakeGitLab.Reply.LOST; executor.execute(intent);
        remote.seed(new FakeGitLab.MergeRequest(99, intent.repositoryId(), intent.sourceBranch(), mode.equals("target") ? "other" : intent.targetBranch(),
                mode.equals("head") ? "9".repeat(40) : intent.candidateCommitSha(), !mode.equals("draft"), mode.equals("owner") ? UUID.randomUUID() : intent.operationId(), intent.bodyHash()));
        var result = executor.execute(intent);
        assertEquals(State.BLOCKED, result.mergeRequest()); assertFalse(result.draftMrComplete());
        assertEquals(0, remote.mrCalls()); persisted(result, 1, 0);
    }

    @Test void rf15PushConfirmedMrRejectedRemainsPartialWithoutCompensatingDelete() throws Exception {
        remote.mrReply = FakeGitLab.Reply.REJECTED;
        var result = executor.execute(intent);
        assertEquals(State.CONFIRMED, result.push()); assertEquals(State.BLOCKED, result.mergeRequest());
        assertFalse(result.draftMrComplete()); assertNotNull(remote.branch(intent));
        assertEquals(result, executor.execute(intent)); persisted(result, 1, 0);
    }

    @ParameterizedTest @ValueSource(strings = {"push-read", "push-no-read", "mr-read", "mr-no-read"})
    void rf16RevocationDuringUnknownPreventsEveryNewMutation(String mode) throws Exception {
        if (mode.startsWith("push")) remote.pushReply = FakeGitLab.Reply.LOST;
        else remote.mrReply = FakeGitLab.Reply.LOST;
        var initial = executor.execute(intent);
        int mrEffects = remote.mrMutations();
        checks.access = new Access(!mode.endsWith("no-read"), false);
        var result = new CodeDeliveryExecutor(new JdbcDeliveryLedger(database), checks, remote).execute(intent);
        if (mode.endsWith("no-read")) {
            assertEquals(State.MANUAL_RECONCILIATION_REQUIRED, mode.startsWith("push") ? result.push() : result.mergeRequest());
            assertFalse(result.draftMrComplete());
        } else if (mode.startsWith("push")) {
            assertEquals(State.CONFIRMED, result.push()); assertEquals(State.BLOCKED, result.mergeRequest());
        } else assertTrue(result.draftMrComplete()); // Read-only confirmation of an ALREADY existing exact MR.
        assertEquals(initial.intent(), result.intent());
        assertEquals(1, remote.pushCalls()); assertEquals(mrEffects, remote.mrMutations());
        persisted(result, 1, mrEffects);
    }

    @ParameterizedTest @ValueSource(strings = {"push", "mr"})
    void postSendRacePreservesActualEffectsAndNeverPretendsRemoteFencing(String step) throws Exception {
        if (step.equals("push")) remote.beforePush = () -> invalidate("jira");
        else remote.beforeMr = () -> invalidate("unsafe-ci");
        var result = executor.execute(intent);
        assertTrue(result.residualRace()); assertFalse(result.draftMrComplete());
        assertEquals(State.CONFIRMED, result.push());
        assertEquals(step.equals("push") ? State.PLANNED : State.CONFIRMED, result.mergeRequest());
        persisted(result, 1, step.equals("push") ? 0 : 1);
    }

    @Test void crashAfterClaimWithNoRemoteProofRemainsUnknownWithoutBlindRetry() throws Exception {
        var admitted = ledger.admit(intent);
        var claimed = ledger.change(admitted, State.IN_PROGRESS, State.PLANNED, null, false, Reason.NONE);
        assertTrue(claimed.applied());
        var result = new CodeDeliveryExecutor(new JdbcDeliveryLedger(database), checks, remote).execute(intent);
        assertEquals(State.UNKNOWN, result.push());
        assertEquals(result, executor.execute(intent)); assertEquals(0, remote.pushCalls());
        persisted(result, 0, 0);
    }

    @Test void crashAfterRemoteEffectBeforeLedgerConfirmationReconcilesInProgress() throws Exception {
        var admitted = ledger.admit(intent);
        ledger.change(admitted, State.IN_PROGRESS, State.PLANNED, null, false, Reason.NONE);
        remote.push(intent); // Remote effect survives a Java executor/connection restart.
        var result = new CodeDeliveryExecutor(new JdbcDeliveryLedger(database), checks, remote).execute(intent);
        assertTrue(result.draftMrComplete()); assertEquals(1, remote.pushCalls()); persisted(result, 1, 1);
    }

    @Test void changedPayloadAndReplacementOperationForSameRunConflictDurably() throws Exception {
        var original = executor.execute(intent);
        assertEquals(DeliveryException.Kind.CONFLICT, assertThrows(DeliveryException.class,
                () -> executor.execute(change(intent, "jiraSnapshotSha256", "e".repeat(64)))).kind());
        assertEquals(DeliveryException.Kind.CONFLICT, assertThrows(DeliveryException.class,
                () -> executor.execute(change(intent, "operationId", UUID.randomUUID().toString()))).kind());
        persisted(original, 1, 1);
    }

    @Test void concurrentExactIntentsHaveOneSendPerStep() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Callable<DeliveryRecord>>();
            for (int n = 0; n < 8; n++) tasks.add(() -> new CodeDeliveryExecutor(new JdbcDeliveryLedger(database), checks, remote).execute(intent));
            for (var task : pool.invokeAll(tasks, 10, TimeUnit.SECONDS)) task.get(1, TimeUnit.SECONDS);
        }
        var result = executor.execute(intent);
        assertTrue(result.draftMrComplete());
        assertEquals(1, remote.pushCalls()); assertEquals(1, remote.mrCalls()); persisted(result, 1, 1);
    }

    @Test void runtimeCannotRewriteIdentityCallbacksOrAnotherOwnersSchema() throws Exception {
        executor.execute(intent);
        for (String sql : new String[]{"UPDATE code_delivery.operations SET request_hash='" + "f".repeat(64) + "'",
                "UPDATE code_delivery.callbacks SET payload_json='{}'", "DELETE FROM code_delivery.operations",
                "CREATE TABLE code_delivery.unauthorized(id integer)", "INSERT INTO orchestration.delivery_owner_guard VALUES(1)"}) {
            try (Connection db = database.getConnection(); var statement = db.createStatement()) {
                var error = assertThrows(java.sql.SQLException.class, () -> statement.execute(sql));
                assertEquals("42501", error.getSQLState());
            }
        }
        try (var db = database.getConnection(); var statement = db.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM code_delivery.flyway_schema_history WHERE success")) {
            // Runtime must not read or own migration history either.
            fail("runtime unexpectedly read migration history");
        } catch (java.sql.SQLException expected) { assertEquals("42501", expected.getSQLState()); }
    }

    @Test void unknownBodyAndQuickActionsAreRejectedBeforeAnyLedgerMutation() throws Exception {
        var node = (tools.jackson.databind.node.ObjectNode) JdbcDeliveryLedger.JSON.valueToTree(intent);
        for (String text : new String[]{"/merge", "\n /approve", "untrusted ticket description"}) {
            node.put("body", text);
            assertEquals(DeliveryException.Kind.INVALID, assertThrows(DeliveryException.class,
                    () -> executor.executeJson(JdbcDeliveryLedger.JSON.writeValueAsBytes(node))).kind());
        }
        assertEquals(0, remote.pushCalls()); assertEquals(0, remote.mrCalls());
        try (var db = database.getConnection(); var statement = db.prepareStatement("SELECT count(*) FROM code_delivery.operations WHERE operation_id=?")) {
            statement.setObject(1, intent.operationId()); try (var rows = statement.executeQuery()) { assertTrue(rows.next()); assertEquals(0, rows.getInt(1)); }
        }
    }
}
