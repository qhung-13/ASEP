package io.github.qhung13.asep.codedelivery;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import static io.github.qhung13.asep.codedelivery.DeliveryRecord.*;

/** Explicitly wired Java-owned JDBC store. No bean, datasource configuration or Go SQL access. */
public final class JdbcDeliveryLedger {
    static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final DataSource database;
    public JdbcDeliveryLedger(DataSource database) { this.database = java.util.Objects.requireNonNull(database); }

    DeliveryRecord admit(DeliveryIntent intent) {
        try (Connection db = database.getConnection()) {
            db.setAutoCommit(false);
            try {
                int created;
                try (var statement = db.prepareStatement("""
                        INSERT INTO code_delivery.operations(operation_id,run_id,repository_id,request_hash,intent_json,
                            push_state,mr_state,reason,revision) VALUES(?,?,?,?,?,'PLANNED','PLANNED','NONE',1)
                        ON CONFLICT DO NOTHING
                        """)) {
                    statement.setObject(1, intent.operationId()); statement.setObject(2, intent.runId());
                    statement.setString(3, intent.repositoryId()); statement.setString(4, intent.bodyHash());
                    statement.setString(5, JSON.writeValueAsString(intent));
                    created = statement.executeUpdate();
                }
                DeliveryRecord stored = read(db, intent.operationId());
                if (stored == null || !stored.intent().equals(intent)) throw new DeliveryException(DeliveryException.Kind.CONFLICT);
                if (created == 1) callback(db, stored);
                db.commit(); // If this ACK is lost, caller retries ONLY the same operation/body.
                return stored;
            } catch (SQLException | RuntimeException failure) {
                db.rollback();
                throw failure;
            }
        } catch (SQLException failure) { throw sqlFailure(failure); }
    }

    public DeliveryRecord find(UUID operationId) {
        try (Connection db = database.getConnection()) {
            DeliveryRecord stored = read(db, operationId);
            if (stored == null) throw new DeliveryException(DeliveryException.Kind.UNAVAILABLE);
            return stored;
        }
        catch (SQLException failure) { throw sqlFailure(failure); }
    }

    private static DeliveryRecord read(Connection db, UUID operationId) throws SQLException {
        try (var statement = db.prepareStatement("SELECT intent_json,request_hash,push_state,mr_state,mr_iid,residual_race,reason,revision FROM code_delivery.operations WHERE operation_id=?")) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                DeliveryIntent intent = JSON.readValue(row.getString(1), DeliveryIntent.class);
                if (!intent.operationId().equals(operationId) || !intent.bodyHash().equals(row.getString(2))) {
                    throw new DeliveryException(DeliveryException.Kind.CONFLICT);
                }
                return new DeliveryRecord(intent, State.valueOf(row.getString(3)), State.valueOf(row.getString(4)),
                        row.getObject(5, Long.class), row.getBoolean(6), Reason.valueOf(row.getString(7)), row.getLong(8));
            }
        }
    }

    // Compare-and-set, then callback in the SAME local transaction. No remote I/O under a DB lock.
    record Update(DeliveryRecord record, boolean applied) {}

    Update change(DeliveryRecord previous, State push, State mr, Long iid, boolean race, Reason reason) {
        try (Connection db = database.getConnection()) {
            db.setAutoCommit(false);
            try {
                int changed;
                try (var statement = db.prepareStatement("""
                        UPDATE code_delivery.operations SET push_state=?,mr_state=?,mr_iid=?,residual_race=?,reason=?,
                            revision=revision+1,updated_at=clock_timestamp() WHERE operation_id=? AND revision=?
                        """)) {
                    statement.setString(1, push.name()); statement.setString(2, mr.name()); statement.setObject(3, iid);
                    statement.setBoolean(4, race); statement.setString(5, reason.name());
                    statement.setObject(6, previous.intent().operationId()); statement.setLong(7, previous.revision());
                    changed = statement.executeUpdate();
                }
                DeliveryRecord stored = read(db, previous.intent().operationId());
                if (stored == null) throw new DeliveryException(DeliveryException.Kind.UNAVAILABLE);
                if (changed == 1) callback(db, stored);
                db.commit();
                return new Update(stored, changed == 1);
            } catch (SQLException | RuntimeException failure) {
                db.rollback();
                throw failure;
            }
        } catch (SQLException failure) { throw sqlFailure(failure); }
    }

    private static void callback(Connection db, DeliveryRecord record) throws SQLException {
        DeliveryIntent intent = record.intent();
        UUID callbackId = UUID.nameUUIDFromBytes((intent.operationId() + ":" + record.revision()).getBytes(StandardCharsets.UTF_8));
        Callback callback = new Callback(callbackId, intent.operationId(), intent.runId(), intent.runVersion(),
                intent.bodyHash(), record.revision(), record.push(), record.mergeRequest(), record.mrIid(), record.residualRace());
        try (var statement = db.prepareStatement("INSERT INTO code_delivery.callbacks(callback_id,operation_id,revision,payload_json,payload_hash) VALUES(?,?,?,?,?)")) {
            statement.setObject(1, callbackId); statement.setObject(2, intent.operationId());
            statement.setLong(3, record.revision()); statement.setString(4, JSON.writeValueAsString(callback));
            statement.setString(5, callback.bodyHash()); statement.executeUpdate();
        }
    }

    public List<Callback> pendingCallbacks(UUID operationId) {
        try (Connection db = database.getConnection(); var statement = db.prepareStatement(
                "SELECT payload_json,payload_hash FROM code_delivery.callbacks WHERE operation_id=? AND NOT acknowledged ORDER BY revision")) {
            statement.setObject(1, operationId);
            try (var rows = statement.executeQuery()) {
                var result = new ArrayList<Callback>();
                while (rows.next()) {
                    Callback callback = JSON.readValue(rows.getString(1), Callback.class);
                    if (!callback.bodyHash().equals(rows.getString(2))) throw new DeliveryException(DeliveryException.Kind.CONFLICT);
                    result.add(callback);
                }
                return List.copyOf(result);
            }
        } catch (SQLException failure) { throw sqlFailure(failure); }
    }

    void acknowledge(Callback callback) {
        try (Connection db = database.getConnection(); var statement = db.prepareStatement(
                "UPDATE code_delivery.callbacks SET acknowledged=true WHERE callback_id=? AND operation_id=? AND payload_hash=?")) {
            statement.setObject(1, callback.callbackId()); statement.setObject(2, callback.operationId());
            statement.setString(3, callback.bodyHash());
            if (statement.executeUpdate() != 1) throw new DeliveryException(DeliveryException.Kind.CONFLICT);
        } catch (SQLException failure) { throw sqlFailure(failure); }
    }

    private static DeliveryException sqlFailure(SQLException failure) {
        return new DeliveryException("23505".equals(failure.getSQLState()) ? DeliveryException.Kind.CONFLICT : DeliveryException.Kind.UNAVAILABLE);
    }
}
