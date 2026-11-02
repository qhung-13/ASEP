package io.github.qhung13.asep.codedelivery;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** VN-T14 V4 schema/ACL tests only. No live GitLab, credentials or trusted receipt issuer. */
class TestProjectDeliverySchemaPostgresTest {
    private static final long PROJECT_ID = 87786140L;
    private static final String CANDIDATE = "a".repeat(40);
    private PGSimpleDataSource database;

    @BeforeEach void requireOwnedDisposableDatabase() throws Exception {
        assumeTrue(Boolean.getBoolean("asep.delivery.postgres"),
                "Use scripts/ci_verify_delivery.sh with its owned disposable PostgreSQL instance");
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
        // The same read-only identity/nonce/server-start proof used by VN-T13.
        try (var db = database.getConnection(); var query = db.prepareStatement("""
                SELECT current_user='asep_delivery_runtime' AND database_name=current_database()
                    AND server_started_at=pg_postmaster_start_time() AND nonce=?
                FROM asep_delivery_test_proof.instance WHERE singleton
                """)) {
            query.setString(1, nonce);
            try (var row = query.executeQuery()) {
                assertTrue(row.next());
                assertTrue(row.getBoolean(1));
                assertFalse(row.next());
            }
        }
    }

    private UUID create(UUID runId) throws SQLException {
        UUID operationId = UUID.randomUUID();
        insert(operationId, runId, PROJECT_ID, "test/vn-t14/" + operationId, "main");
        return operationId;
    }

    private void insert(UUID operationId, UUID runId, long projectId, String source, String target)
            throws SQLException {
        try (var db = database.getConnection(); var stmt = db.prepareStatement("""
                INSERT INTO code_delivery.vn_t14_operations
                    (operation_id,run_id,attempt_id,project_id,source_branch,target_branch,
                     candidate_commit_sha,request_hash,request_json)
                VALUES (?,?,?,?,?,?,?,?,?)
                """)) {
            stmt.setObject(1, operationId);
            stmt.setObject(2, runId);
            stmt.setObject(3, UUID.randomUUID());
            stmt.setLong(4, projectId);
            stmt.setString(5, source);
            stmt.setString(6, target);
            stmt.setString(7, CANDIDATE);
            stmt.setString(8, "b".repeat(64));
            stmt.setString(9, "{\"fixture\":true}");
            assertEquals(1, stmt.executeUpdate());
        }
    }

    private int update(UUID operationId, String command) throws SQLException {
        try (var db = database.getConnection(); var stmt = db.prepareStatement(
                "UPDATE code_delivery.vn_t14_operations SET " + command + " WHERE operation_id=?")) {
            stmt.setObject(1, operationId);
            return stmt.executeUpdate();
        }
    }

    private static void sqlState(String expected, ThrowingSql action) {
        SQLException error = assertThrows(SQLException.class, action::run);
        assertEquals(expected, error.getSQLState());
    }
    private interface ThrowingSql { void run() throws SQLException; }

    @Test void fixedProjectSourceAndMainCannotBeChangedByCaller() throws Exception {
        UUID operation = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        insert(operation, run, PROJECT_ID, "test/vn-t14/" + operation, "main");
        UUID wrongProject = UUID.randomUUID();
        sqlState("23514", () -> insert(wrongProject, UUID.randomUUID(), 42,
                "test/vn-t14/" + wrongProject, "main"));
        sqlState("23514", () -> insert(UUID.randomUUID(), UUID.randomUUID(), PROJECT_ID,
                "test/vn-t14/other", "main"));
        UUID wrongTarget = UUID.randomUUID();
        sqlState("23514", () -> insert(wrongTarget, UUID.randomUUID(), PROJECT_ID,
                "test/vn-t14/" + wrongTarget, "release"));
        sqlState("23505", () -> create(run)); // Distinct command, same active run/project.
    }

    @Test void cannotClaimMrBeforeConfirmedPushAndCannotConfirmWithoutFacts() throws Exception {
        UUID operation = create(UUID.randomUUID());
        sqlState("23514", () -> update(operation,
                "mr_claimed=true,mr_state='IN_PROGRESS',revision=revision+1"));
        sqlState("23514", () -> update(operation,
                "push_state='CONFIRMED',push_claimed=true,revision=revision+1"));
        assertEquals(1, update(operation,
                "push_claimed=true,push_state='IN_PROGRESS',revision=revision+1"));
        assertEquals(1, update(operation,
                "push_state='CONFIRMED',last_confirmed_push_head='" + CANDIDATE + "',revision=revision+1"));
        assertEquals(1, update(operation,
                "mr_claimed=true,mr_state='IN_PROGRESS',revision=revision+1"));
        assertEquals(1, update(operation,
                "mr_state='UNKNOWN',revision=revision+1"));
        try (var db = database.getConnection(); var stmt = db.prepareStatement("""
                SELECT push_claimed,mr_claimed,last_confirmed_push_head,revision
                FROM code_delivery.vn_t14_operations WHERE operation_id=?
                """)) {
            stmt.setObject(1, operation);
            try (var row = stmt.executeQuery()) {
                assertTrue(row.next());
                assertTrue(row.getBoolean(1));
                assertTrue(row.getBoolean(2));
                assertEquals(CANDIDATE, row.getString(3));
                assertEquals(5L, row.getLong(4));
            }
        }
    }

    @Test void claimedSendAndRemoteFactsAreMonotonicAcrossTransactions() throws Exception {
        UUID operation = create(UUID.randomUUID());
        assertEquals(1, update(operation,
                "push_claimed=true,push_state='IN_PROGRESS',revision=revision+1"));
        sqlState("23514", () -> update(operation, "push_state='UNKNOWN'")); // No revision increment.
        sqlState("23514", () -> update(operation,
                "push_claimed=false,push_state='BLOCKED',revision=revision+1"));
        assertEquals(1, update(operation,
                "last_confirmed_push_head='" + CANDIDATE + "',push_state='CONFIRMED',revision=revision+1"));
        sqlState("23514", () -> update(operation,
                "last_confirmed_push_head=NULL,push_state='BLOCKED',revision=revision+1"));
        assertEquals(1, update(operation, "residual_race=true,revision=revision+1"));
        sqlState("23514", () -> update(operation,
                "residual_race=false,revision=revision+1"));
        // A history-preserving downgrade is permitted after observing remote drift.
        assertEquals(1, update(operation, "push_state='BLOCKED',revision=revision+1"));
    }

    @Test void unclaimedCollisionBlockCannotBeRearmedThroughRuntimeSql() throws Exception {
        UUID pushOperation = create(UUID.randomUUID());

        assertEquals(1, update(pushOperation,
                "push_state='BLOCKED',revision=revision+1"));

        sqlState("23514", () -> update(pushOperation,
                "push_state='PLANNED',revision=revision+1"));

        sqlState("23514", () -> update(pushOperation,
                "push_claimed=true,push_state='IN_PROGRESS',revision=revision+1"));

        // Establish a valid confirmed push before testing MR claim bypass.
        UUID mrOperation = create(UUID.randomUUID());

        assertEquals(1, update(mrOperation,
                "push_claimed=true,push_state='IN_PROGRESS',revision=revision+1"));

        assertEquals(1, update(mrOperation,
                "push_state='CONFIRMED',last_confirmed_push_head='"
                        + CANDIDATE + "',revision=revision+1"));

        assertEquals(1, update(mrOperation,
                "mr_state='BLOCKED',revision=revision+1"));

        sqlState("23514", () -> update(mrOperation,
                "mr_state='PLANNED',revision=revision+1"));

        sqlState("23514", () -> update(mrOperation,
                "mr_claimed=true,mr_state='IN_PROGRESS',revision=revision+1"));
    }

    @Test void observedMrIidsAreAppendOnlyAndScopedToTheOperation() throws Exception {
        UUID operation = create(UUID.randomUUID());
        try (var db = database.getConnection(); var statement = db.prepareStatement("""
                INSERT INTO code_delivery.vn_t14_observed_mrs(operation_id,mr_iid) VALUES (?,?)
                ON CONFLICT DO NOTHING
                """)) {
            statement.setObject(1, operation);
            statement.setLong(2, 123);
            assertEquals(1, statement.executeUpdate());
            assertEquals(0, statement.executeUpdate());
        }
        sqlState("23503", () -> {
            try (var db = database.getConnection(); var statement = db.prepareStatement("""
                    INSERT INTO code_delivery.vn_t14_observed_mrs(operation_id,mr_iid) VALUES (?,123)
                    """)) {
                statement.setObject(1, UUID.randomUUID());
                statement.executeUpdate();
            }
        });
        sqlState("42501", () -> {
            try (var db = database.getConnection(); var statement = db.createStatement()) {
                statement.executeUpdate("UPDATE code_delivery.vn_t14_observed_mrs SET mr_iid=124");
            }
        });
        sqlState("42501", () -> {
            try (var db = database.getConnection(); var statement = db.createStatement()) {
                statement.executeUpdate("DELETE FROM code_delivery.vn_t14_observed_mrs");
            }
        });
    }

    @Test void callbackPayloadIsImmutableAndAcknowledgementCannotBeReversed() throws Exception {
        UUID operation = create(UUID.randomUUID());
        UUID callback = UUID.randomUUID();
        try (var db = database.getConnection(); var stmt = db.prepareStatement("""
                INSERT INTO code_delivery.vn_t14_callbacks
                    (callback_id,operation_id,revision,payload_json,payload_hash)
                VALUES (?,?,1,'{"fixture":true}',?)
                """)) {
            stmt.setObject(1, callback);
            stmt.setObject(2, operation);
            stmt.setString(3, "c".repeat(64));
            assertEquals(1, stmt.executeUpdate());
        }
        try (var db = database.getConnection(); var stmt = db.prepareStatement("""
                UPDATE code_delivery.vn_t14_callbacks SET acknowledged=true WHERE callback_id=?
                """)) {
            stmt.setObject(1, callback);
            assertEquals(1, stmt.executeUpdate());
            assertEquals(1, stmt.executeUpdate()); // Idempotent ACK.
        }
        sqlState("23514", () -> {
            try (var db = database.getConnection(); var stmt = db.prepareStatement("""
                    UPDATE code_delivery.vn_t14_callbacks SET acknowledged=false WHERE callback_id=?
                    """)) {
                stmt.setObject(1, callback);
                stmt.executeUpdate();
            }
        });
        sqlState("42501", () -> {
            try (var db = database.getConnection(); var stmt = db.createStatement()) {
                stmt.executeUpdate("UPDATE code_delivery.vn_t14_callbacks SET payload_json='{}'");
            }
        });
    }

    @Test void runtimeCannotRewriteRequestsOrReadMigrationHistory() throws Exception {
        UUID operation = create(UUID.randomUUID());
        sqlState("42501", () -> update(operation, "request_hash='" + "f".repeat(64) + "'"));
        sqlState("42501", () -> {
            try (var db = database.getConnection(); var stmt = db.createStatement()) {
                stmt.execute("SELECT count(*) FROM code_delivery.flyway_schema_history");
            }
        });
        sqlState("42501", () -> {
            try (var db = database.getConnection(); var stmt = db.createStatement()) {
                stmt.execute("DELETE FROM code_delivery.vn_t14_operations");
            }
        });
        sqlState("42501", () -> {
            try (var db = database.getConnection(); var stmt = db.createStatement()) {
                stmt.execute("CREATE TABLE code_delivery.vn_t14_forbidden(id integer)");
            }
        });
    }

    @Test void competingDatabaseClaimersHaveOneCasWinner() throws Exception {
        UUID operation = create(UUID.randomUUID());
        var gate = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var claims = List.of(
                    workers.submit(() -> { gate.await(); return claimPush(operation); }),
                    workers.submit(() -> { gate.await(); return claimPush(operation); }));
            gate.countDown();
            int applied = 0;
            for (var claim : claims) applied += claim.get(15, TimeUnit.SECONDS);
            assertEquals(1, applied);
        }
        try (var db = database.getConnection(); var stmt = db.prepareStatement("""
                SELECT push_claimed,push_state,revision FROM code_delivery.vn_t14_operations WHERE operation_id=?
                """)) {
            stmt.setObject(1, operation);
            try (var row = stmt.executeQuery()) {
                assertTrue(row.next());
                assertTrue(row.getBoolean(1));
                assertEquals("IN_PROGRESS", row.getString(2));
                assertEquals(2L, row.getLong(3));
            }
        }
    }

    private int claimPush(UUID operationId) throws SQLException {
        try (Connection db = database.getConnection(); var stmt = db.prepareStatement("""
                UPDATE code_delivery.vn_t14_operations
                SET push_claimed=true,push_state='IN_PROGRESS',revision=revision+1,updated_at=clock_timestamp()
                WHERE operation_id=? AND revision=1 AND NOT push_claimed AND push_state='PLANNED'
                """)) {
            stmt.setObject(1, operationId);
            return stmt.executeUpdate();
        }
    }
}
