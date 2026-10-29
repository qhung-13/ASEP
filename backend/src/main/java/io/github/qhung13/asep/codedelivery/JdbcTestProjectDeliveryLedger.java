package io.github.qhung13.asep.codedelivery;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import static io.github.qhung13.asep.codedelivery.DeliveryRecord.State;
import static io.github.qhung13.asep.codedelivery.TestProjectDeliveryRecord.Callback;
import static io.github.qhung13.asep.codedelivery.TestProjectRemoteReconciliation.*;

/**
 * Java-owned, local-only durable VN-T14 state machine. This class has no HTTP/GitLab transport,
 * token provider, trusted grant issuer or Go state-write authority.
 *
 * A future trusted executor MUST verify current Owner grant, CI enrollment, exact independently
 * issued candidate/verification receipts, target head and live credentials before EACH send.
 * A committed claim is NEVER a standalone permission to send, or permission to blindly retry.
 */
public final class JdbcTestProjectDeliveryLedger {
    private final DataSource database;

    public JdbcTestProjectDeliveryLedger(DataSource database) { this.database = Objects.requireNonNull(database); }

    public TestProjectDeliveryRecord admit(TestProjectDeliveryRequest request) {
        Objects.requireNonNull(request);
        try (Connection db = database.getConnection()) {
            db.setAutoCommit(false);
            try {
                int inserted;
                try (var statement = db.prepareStatement("""
                        INSERT INTO code_delivery.vn_t14_operations
                            (operation_id,run_id,attempt_id,project_id,source_branch,target_branch,
                             candidate_commit_sha,request_hash,request_json)
                        VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
                        """)) {
                    statement.setObject(1, request.operationId());
                    statement.setObject(2, request.runId());
                    statement.setObject(3, request.attemptId());
                    statement.setLong(4, request.projectId());
                    statement.setString(5, request.sourceBranch());
                    statement.setString(6, request.targetBranch());
                    statement.setString(7, request.candidateCommitSha());
                    statement.setString(8, request.bodyHash());
                    statement.setString(9, JdbcDeliveryLedger.JSON.writeValueAsString(request));
                    inserted = statement.executeUpdate();
                }
                var stored = read(db, request.operationId());
                if (stored == null || !stored.request().equals(request))
                    throw new DeliveryException(DeliveryException.Kind.CONFLICT);
                if (inserted == 1) insertCallback(db, stored);
                db.commit();
                return stored;
            } catch (SQLException | RuntimeException failure) {
                rollback(db);
                throw failure;
            }
        } catch (SQLException failure) { throw sqlFailure(failure); }
    }

    public TestProjectDeliveryRecord find(UUID operationId) {
        Objects.requireNonNull(operationId);
        try (Connection db = database.getConnection()) {
            var stored = read(db, operationId);
            if (stored == null) throw new DeliveryException(DeliveryException.Kind.UNAVAILABLE);
            return stored;
        } catch (SQLException failure) { throw sqlFailure(failure); }
    }

    /** A false application means stale CAS or an illegal state edge: no new send is permitted. */
    public record Update(TestProjectDeliveryRecord record, boolean applied) {}
    private enum Action { CLAIM_PUSH, CLAIM_MR, OBSERVE }

    public Update claimPush(TestProjectDeliveryRecord expected) { return change(expected, Action.CLAIM_PUSH, null); }
    public Update claimMr(TestProjectDeliveryRecord expected) { return change(expected, Action.CLAIM_MR, null); }

    /** The observation must come from a trusted scoped reader, not an agent/API body. */
    public Update observe(TestProjectDeliveryRecord expected, Observation observation) {
        return change(expected, Action.OBSERVE, observation);
    }

    private Update change(TestProjectDeliveryRecord expected, Action action, Observation observation) {
        Objects.requireNonNull(expected);
        try (Connection db = database.getConnection()) {
            db.setAutoCommit(false);
            try {
                var stored = read(db, expected.request().operationId());
                if (stored == null) throw new DeliveryException(DeliveryException.Kind.UNAVAILABLE);
                if (!stored.equals(expected)) {
                    db.commit();
                    return new Update(stored, false);
                }
                TestProjectDeliveryRecord next = switch (action) {
                    case CLAIM_PUSH -> claimPushResult(stored);
                    case CLAIM_MR -> claimMrResult(stored);
                    case OBSERVE -> observationResult(stored, observation);
                };
                if (next == null || sameFacts(stored, next)) {
                    db.commit();
                    return new Update(stored, false);
                }
                int updated;
                try (var statement = db.prepareStatement("""
                        UPDATE code_delivery.vn_t14_operations SET push_state=?,mr_state=?,
                            push_claimed=?,mr_claimed=?,last_confirmed_push_head=?,residual_race=?,
                            revision=revision+1,updated_at=clock_timestamp()
                        WHERE operation_id=? AND revision=? AND request_hash=?
                        """)) {
                    statement.setString(1, next.push().name());
                    statement.setString(2, next.mergeRequest().name());
                    statement.setBoolean(3, next.pushClaimed());
                    statement.setBoolean(4, next.mrClaimed());
                    statement.setString(5, next.lastConfirmedPushHead());
                    statement.setBoolean(6, next.residualRace());
                    statement.setObject(7, expected.request().operationId());
                    statement.setLong(8, expected.revision());
                    statement.setString(9, expected.request().bodyHash());
                    updated = statement.executeUpdate();
                }
                if (updated == 0) {
                    var winner = read(db, expected.request().operationId());
                    if (winner == null) throw new DeliveryException(DeliveryException.Kind.UNAVAILABLE);
                    db.commit();
                    return new Update(winner, false);
                }
                if (action == Action.OBSERVE) insertObservedMrs(db, stored.request().operationId(), next.knownMrIids());
                var committed = read(db, stored.request().operationId());
                if (!committed.equals(next)) throw new DeliveryException(DeliveryException.Kind.CONFLICT);
                insertCallback(db, committed); // SAME transaction as state update and MR history.
                db.commit();
                return new Update(committed, true);
            } catch (SQLException | RuntimeException failure) {
                rollback(db);
                throw failure;
            }
        } catch (SQLException failure) { throw sqlFailure(failure); }
    }

    private static boolean sameFacts(TestProjectDeliveryRecord current, TestProjectDeliveryRecord next) {
        return current.request().equals(next.request()) && current.push() == next.push()
                && current.mergeRequest() == next.mergeRequest()
                && current.pushClaimed() == next.pushClaimed() && current.mrClaimed() == next.mrClaimed()
                && Objects.equals(current.lastConfirmedPushHead(), next.lastConfirmedPushHead())
                && current.knownMrIids().equals(next.knownMrIids())
                && current.residualRace() == next.residualRace();
    }

    private static TestProjectDeliveryRecord claimPushResult(TestProjectDeliveryRecord s) {
        if (s.pushClaimed() || s.push() != State.PLANNED || s.mrClaimed()
                || s.mergeRequest() != State.PLANNED || s.lastConfirmedPushHead() != null || s.residualRace()) return null;
        return new TestProjectDeliveryRecord(s.request(), State.IN_PROGRESS, s.mergeRequest(), true,
                false, null, s.knownMrIids(), false, s.revision() + 1);
    }

    private static TestProjectDeliveryRecord claimMrResult(TestProjectDeliveryRecord s) {
        if (!s.pushClaimed() || s.push() != State.CONFIRMED || !s.request().candidateCommitSha().equals(s.lastConfirmedPushHead())
                || s.mrClaimed() || s.mergeRequest() != State.PLANNED || s.residualRace()) return null;
        return new TestProjectDeliveryRecord(s.request(), s.push(), State.IN_PROGRESS, true,
                true, s.lastConfirmedPushHead(), s.knownMrIids(), false, s.revision() + 1);
    }

    private static TestProjectDeliveryRecord observationResult(TestProjectDeliveryRecord s, Observation observed) {
        var result = TestProjectRemoteReconciliation.reconcile(s.request(), s.recordedSend(), observed);
        // Remote read classifications are never proof of an eligible current Owner grant.
        // An unclaimed collision BLOCK is terminal for this operation.
        // Remote deletion must not silently reauthorize push or Draft MR creation.
        // Claimed steps can still reconcile remote facts, but never resend.
        State push = !s.pushClaimed() && s.push() == State.BLOCKED
                ? State.BLOCKED : result.branch();
        State mr = !s.mrClaimed() && s.mergeRequest() == State.BLOCKED
                ? State.BLOCKED : result.mergeRequest();
        return new TestProjectDeliveryRecord(s.request(), push, mr,
                s.pushClaimed(), s.mrClaimed(), result.lastConfirmedPushHead(), result.retainedMrIids(),
                s.residualRace() || result.residualRace(), s.revision() + 1);
    }

    private static void insertObservedMrs(Connection db, UUID operationId, Set<Long> iids) throws SQLException {
        try (var statement = db.prepareStatement("""
                INSERT INTO code_delivery.vn_t14_observed_mrs(operation_id,mr_iid)
                VALUES(?,?) ON CONFLICT DO NOTHING
                """)) {
            for (long iid : iids) {
                statement.setObject(1, operationId);
                statement.setLong(2, iid);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static TestProjectDeliveryRecord read(Connection db, UUID operationId) throws SQLException {
        // One SQL statement = one PostgreSQL MVCC snapshot for the operation and its append-only
        // MR history. Two separate SELECTs under READ COMMITTED could return mixed revisions.
        try (var statement = db.prepareStatement("""
                SELECT o.operation_id,o.run_id,o.attempt_id,o.project_id,o.source_branch,
                    o.target_branch,o.candidate_commit_sha,o.request_hash,o.request_json,
                    o.push_state,o.mr_state,o.push_claimed,o.mr_claimed,
                    o.last_confirmed_push_head,o.residual_race,o.revision,
                    m.mr_iid AS observed_iid
                FROM code_delivery.vn_t14_operations o
                LEFT JOIN code_delivery.vn_t14_observed_mrs m ON m.operation_id=o.operation_id
                WHERE o.operation_id=? ORDER BY m.mr_iid
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                TestProjectDeliveryRequest request;
                try { request = JdbcDeliveryLedger.JSON.readValue(rows.getString("request_json"), TestProjectDeliveryRequest.class); }
                catch (RuntimeException invalid) { throw new DeliveryException(DeliveryException.Kind.CONFLICT); }
                if (!request.operationId().equals(operationId)
                        || !request.runId().equals(rows.getObject("run_id", UUID.class))
                        || !request.attemptId().equals(rows.getObject("attempt_id", UUID.class))
                        || request.projectId() != rows.getLong("project_id")
                        || !request.sourceBranch().equals(rows.getString("source_branch"))
                        || !request.targetBranch().equals(rows.getString("target_branch"))
                        || !request.candidateCommitSha().equals(rows.getString("candidate_commit_sha"))
                        || !request.bodyHash().equals(rows.getString("request_hash")))
                    throw new DeliveryException(DeliveryException.Kind.CONFLICT);
                State push = State.valueOf(rows.getString("push_state"));
                State mr = State.valueOf(rows.getString("mr_state"));
                boolean pushClaimed = rows.getBoolean("push_claimed");
                boolean mrClaimed = rows.getBoolean("mr_claimed");
                String confirmedHead = rows.getString("last_confirmed_push_head");
                boolean race = rows.getBoolean("residual_race");
                long revision = rows.getLong("revision");
                Set<Long> iids = new HashSet<>();
                do {
                    Long iid = rows.getObject("observed_iid", Long.class);
                    if (iid != null) iids.add(iid);
                } while (rows.next());
                return new TestProjectDeliveryRecord(request, push, mr, pushClaimed, mrClaimed,
                        confirmedHead, Set.copyOf(iids), race, revision);
            }
        }
    }

    private static void insertCallback(Connection db, TestProjectDeliveryRecord record) throws SQLException {
        Callback callback = callback(record);
        try (var statement = db.prepareStatement("""
                INSERT INTO code_delivery.vn_t14_callbacks
                    (callback_id,operation_id,revision,payload_json,payload_hash) VALUES(?,?,?,?,?)
                """)) {
            statement.setObject(1, callback.callbackId());
            statement.setObject(2, callback.operationId());
            statement.setLong(3, callback.revision());
            statement.setString(4, JdbcDeliveryLedger.JSON.writeValueAsString(callback));
            statement.setString(5, callback.bodyHash());
            statement.executeUpdate();
        }
    }

    private static Callback callback(TestProjectDeliveryRecord record) {
        var request = record.request();
        var iids = record.knownMrIids().stream().sorted(Comparator.naturalOrder()).toList();
        return new Callback(Callback.id(request.operationId(), record.revision()), request.operationId(),
                request.runId(), request.runVersion(), request.bodyHash(), record.revision(), record.push(),
                record.mergeRequest(), record.pushClaimed(), record.mrClaimed(), record.lastConfirmedPushHead(),
                iids, record.residualRace());
    }

    public List<Callback> pendingCallbacks(UUID operationId) {
        Objects.requireNonNull(operationId);
        try (Connection db = database.getConnection(); var statement = db.prepareStatement("""
                SELECT callback_id,revision,payload_json,payload_hash
                FROM code_delivery.vn_t14_callbacks
                WHERE operation_id=? AND NOT acknowledged ORDER BY revision
                """)) {
            statement.setObject(1, operationId);
            try (var rows = statement.executeQuery()) {
                var pending = new ArrayList<Callback>();
                while (rows.next()) {
                    Callback callback = parseCallback(rows.getString("payload_json"), rows.getString("payload_hash"));
                    if (!callback.operationId().equals(operationId)
                            || !callback.callbackId().equals(rows.getObject("callback_id", UUID.class))
                            || callback.revision() != rows.getLong("revision"))
                        throw new DeliveryException(DeliveryException.Kind.CONFLICT);
                    pending.add(callback);
                }
                return List.copyOf(pending);
            }
        } catch (SQLException failure) { throw sqlFailure(failure); }
    }

    /** Only a trusted callback consumer may call this; it does NOT advance Go workflow state. */
    void acknowledge(Callback expected) {
        Objects.requireNonNull(expected);
        try (Connection db = database.getConnection()) {
            db.setAutoCommit(false);
            try (var query = db.prepareStatement("""
                    SELECT payload_json,payload_hash FROM code_delivery.vn_t14_callbacks
                    WHERE callback_id=? AND operation_id=? FOR UPDATE
                    """)) {
                query.setObject(1, expected.callbackId());
                query.setObject(2, expected.operationId());
                try (var rows = query.executeQuery()) {
                    if (!rows.next() || !parseCallback(rows.getString("payload_json"), rows.getString("payload_hash")).equals(expected))
                        throw new DeliveryException(DeliveryException.Kind.CONFLICT);
                }
                try (var update = db.prepareStatement("""
                        UPDATE code_delivery.vn_t14_callbacks SET acknowledged=true
                        WHERE callback_id=? AND operation_id=? AND payload_hash=?
                        """)) {
                    update.setObject(1, expected.callbackId());
                    update.setObject(2, expected.operationId());
                    update.setString(3, expected.bodyHash());
                    if (update.executeUpdate() != 1) throw new DeliveryException(DeliveryException.Kind.CONFLICT);
                }
                db.commit();
            } catch (SQLException | RuntimeException failure) {
                rollback(db);
                throw failure;
            }
        } catch (SQLException failure) { throw sqlFailure(failure); }
    }

    private static Callback parseCallback(String json, String hash) {
        try {
            Callback callback = JdbcDeliveryLedger.JSON.readValue(json, Callback.class);
            if (callback == null || !callback.bodyHash().equals(hash)
                    || !callback.callbackId().equals(Callback.id(callback.operationId(), callback.revision())))
                throw new DeliveryException(DeliveryException.Kind.CONFLICT);
            return callback;
        } catch (RuntimeException invalid) { throw new DeliveryException(DeliveryException.Kind.CONFLICT); }
    }

    private static void rollback(Connection db) {
        try { db.rollback(); } catch (SQLException ignored) { /* original sanitized failure remains */ }
    }
    private static DeliveryException sqlFailure(SQLException failure) {
        return new DeliveryException("23505".equals(failure.getSQLState())
                ? DeliveryException.Kind.CONFLICT : DeliveryException.Kind.UNAVAILABLE);
    }
}
