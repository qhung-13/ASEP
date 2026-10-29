package io.github.qhung13.asep.codedelivery;

import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Issuer, revocation and audit integration tests.
 * Disposable PostgreSQL only; never connects to GitLab.
 */
class OwnerGrantAuthorityPostgresTest {

    private static final long PROJECT_ID = 87786140L;
    private PGSimpleDataSource runtime;
    private PGSimpleDataSource issuer;

    @BeforeEach
    void requireOwnedDisposableDatabaseAndDistinctRoles() throws Exception {
        assumeTrue(Boolean.getBoolean("asep.delivery.postgres"));

        assertEquals("true", System.getenv("ASEP_DELIVERY_DISPOSABLE"));
        assertEquals("asep_delivery_runtime",
                System.getenv("ASEP_DELIVERY_DB_USER"));

        String url = System.getenv("ASEP_DELIVERY_JDBC_URL");
        assertNotNull(url);
        assertTrue(url.matches(
                "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/"
                + "asep_delivery_test_[0-9a-f]{32}"));

        String nonce = System.getenv("ASEP_DELIVERY_NONCE");
        assertNotNull(nonce);
        assertTrue(nonce.matches("[0-9a-f]{64}"));

        String runtimePassword = System.getenv("ASEP_DELIVERY_DB_PASSWORD");
        String issuerPassword =
                System.getenv("ASEP_DELIVERY_TEST_ISSUER_PASSWORD");

        assertNotNull(runtimePassword);
        assertNotNull(issuerPassword);
        assertNotEquals(runtimePassword, issuerPassword);

        runtime = source(url, "asep_delivery_runtime", runtimePassword);
        issuer = source(url, "asep_delivery_issuer", issuerPassword);

        try (var db = runtime.getConnection();
             var stmt = db.prepareStatement("""
                 SELECT current_user='asep_delivery_runtime'
                     AND database_name=current_database()
                     AND server_started_at=pg_postmaster_start_time()
                     AND nonce=?
                 FROM asep_delivery_test_proof.instance
                 WHERE singleton
                 """)) {
            stmt.setString(1, nonce);
            try (var rows = stmt.executeQuery()) {
                assertTrue(rows.next());
                assertTrue(rows.getBoolean(1));
                assertFalse(rows.next());
            }
        }

        try (var db = issuer.getConnection();
             var stmt = db.createStatement();
             var rows = stmt.executeQuery(
                     "SELECT current_user='asep_delivery_issuer'")) {
            assertTrue(rows.next());
            assertTrue(rows.getBoolean(1));
        }
    }

    private static PGSimpleDataSource source(
            String url, String user, String password) {
        var ds = new PGSimpleDataSource();
        ds.setUrl(url);
        ds.setUser(user);
        ds.setPassword(password);
        ds.setConnectTimeout(3);
        ds.setSocketTimeout(5);
        ds.setOptions(
                "-c statement_timeout=4000 -c lock_timeout=3000");
        return ds;
    }

    private UUID issue(
            PGSimpleDataSource role, long projectId, String sourceOverride)
            throws SQLException {

        UUID grant = UUID.randomUUID();
        UUID operation = UUID.randomUUID();
        String branch = sourceOverride == null
                ? "test/vn-t14/" + operation
                : sourceOverride;

        try (var db = role.getConnection();
             var stmt = db.prepareStatement("""
                 INSERT INTO code_delivery.owner_grants
                     (grant_id, operation_id, request_hash,
                      project_id, repository_path, source_branch,
                      target_branch, issued_by, not_before, expires_at)
                 VALUES (?, ?, ?, ?,
                         'qhung-13/asep-m01-sandbox', ?,
                         'main', 'test-operator',
                         clock_timestamp() - interval '1 minute',
                         clock_timestamp() + interval '30 minutes')
                 """)) {
            stmt.setObject(1, grant);
            stmt.setObject(2, operation);
            stmt.setString(3, "a".repeat(64));
            stmt.setLong(4, projectId);
            stmt.setString(5, branch);
            assertEquals(1, stmt.executeUpdate());
        }

        return grant;
    }

    private void revoke(PGSimpleDataSource role, UUID grant)
            throws SQLException {
        try (var db = role.getConnection();
             var stmt = db.prepareStatement("""
                 UPDATE code_delivery.owner_grants
                 SET revoked_at=clock_timestamp(),
                     revoked_by='test-operator'
                 WHERE grant_id=?
                 """)) {
            stmt.setObject(1, grant);
            assertEquals(1, stmt.executeUpdate());
        }
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }

    private static void sqlState(String expected, SqlAction action) {
        SQLException error =
                assertThrows(SQLException.class, action::run);
        assertEquals(expected, error.getSQLState());
    }


    @Test
    void jdbcSourceReadsGrantUsingRuntimeRole() throws Exception {
        UUID grantId = issue(issuer, PROJECT_ID, null);

        var source = new JdbcOwnerGrantSource(runtime);
        var record = source.find(grantId);

        assertNotNull(record);
        assertNotNull(record.grant());
        assertEquals(grantId, record.grant().id());
        assertEquals(PROJECT_ID, record.grant().projectId());
        assertEquals("a".repeat(64), record.grant().requestHash());
        assertEquals("qhung-13/asep-m01-sandbox",
                record.grant().repositoryPath());
        assertEquals("main", record.grant().targetBranch());
        assertEquals(
                "test/vn-t14/" + record.grant().operationId(),
                record.grant().sourceBranch());

        assertFalse(record.revoked());
        assertTrue(record.expiresAt().isAfter(record.notBefore()));
    }

    @Test
    void jdbcSourceObservesRevocationOnSubsequentRead()
            throws Exception {

        UUID grantId = issue(issuer, PROJECT_ID, null);
        var source = new JdbcOwnerGrantSource(runtime);

        var before = source.find(grantId);
        assertNotNull(before);
        assertFalse(before.revoked());

        revoke(issuer, grantId);

        var after = source.find(grantId);
        assertNotNull(after);
        assertTrue(after.revoked());
        assertEquals(before.grant(), after.grant());
        assertEquals(before.expiresAt(), after.expiresAt());
    }

    @Test
    void jdbcSourceFailsClosedForMissingAndUnavailableStore()
            throws Exception {

        var source = new JdbcOwnerGrantSource(runtime);

        assertNull(source.find(UUID.randomUUID()));
        assertNull(source.find(null));

        var unavailable = new JdbcOwnerGrantSource(
                source(
                        System.getenv("ASEP_DELIVERY_JDBC_URL"),
                        "asep_delivery_runtime",
                        "intentionally-invalid-test-password"
                )
        );

        assertThrows(
                IllegalStateException.class,
                () -> unavailable.find(UUID.randomUUID()));
    }


    @Test
    void issuerCannotCreateGrantValidForTwoHours() throws Exception {
        UUID operation = UUID.randomUUID();

        sqlState("23514", () -> {
            try (var db = issuer.getConnection();
                 var stmt = db.prepareStatement("""
                     INSERT INTO code_delivery.owner_grants
                         (grant_id, operation_id, request_hash,
                          project_id, repository_path, source_branch,
                          target_branch, issued_by,
                          not_before, expires_at)
                     VALUES (?, ?, ?, 87786140,
                             'qhung-13/asep-m01-sandbox', ?,
                             'main', 'test-operator',
                             clock_timestamp() - interval '1 minute',
                             clock_timestamp() + interval '2 hours')
                     """)) {

                stmt.setObject(1, UUID.randomUUID());
                stmt.setObject(2, operation);
                stmt.setString(3, "a".repeat(64));
                stmt.setString(4, "test/vn-t14/" + operation);
                stmt.executeUpdate();
            }
        });
    }

    @Test
    void issuerCanIssueGrantAndAuditIsRecorded() throws Exception {
        UUID grant = issue(issuer, PROJECT_ID, null);

        try (var db = runtime.getConnection();
             var stmt = db.prepareStatement("""
                 SELECT project_id, issued_by, revoked_at IS NULL
                 FROM code_delivery.owner_grants
                 WHERE grant_id=?
                 """)) {
            stmt.setObject(1, grant);
            try (var rows = stmt.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(PROJECT_ID, rows.getLong(1));
                assertEquals("test-operator", rows.getString(2));
                assertTrue(rows.getBoolean(3));
                assertFalse(rows.next());
            }
        }

        try (var db = issuer.getConnection();
             var stmt = db.prepareStatement("""
                 SELECT event_kind, actor_id
                 FROM code_delivery.owner_grant_events
                 WHERE grant_id=?
                 """)) {
            stmt.setObject(1, grant);
            try (var rows = stmt.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("ISSUED", rows.getString(1));
                assertEquals("test-operator", rows.getString(2));
                assertFalse(rows.next());
            }
        }
    }

    @Test
    void revocationIsAuditedAndCannotBeReversed() throws Exception {
        UUID grant = issue(issuer, PROJECT_ID, null);

        revoke(issuer, grant);
        sqlState("23514", () -> revoke(issuer, grant));

        try (var db = issuer.getConnection();
             var stmt = db.prepareStatement("""
                 SELECT revoked_at IS NOT NULL, revoked_by
                 FROM code_delivery.owner_grants
                 WHERE grant_id=?
                 """)) {
            stmt.setObject(1, grant);
            try (var rows = stmt.executeQuery()) {
                assertTrue(rows.next());
                assertTrue(rows.getBoolean(1));
                assertEquals("test-operator", rows.getString(2));
            }
        }

        try (var db = issuer.getConnection();
             var stmt = db.prepareStatement("""
                 SELECT event_kind
                 FROM code_delivery.owner_grant_events
                 WHERE grant_id=?
                 ORDER BY event_kind
                 """)) {
            stmt.setObject(1, grant);
            try (var rows = stmt.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("ISSUED", rows.getString(1));
                assertTrue(rows.next());
                assertEquals("REVOKED", rows.getString(1));
                assertFalse(rows.next());
            }
        }
    }

    @Test
    void deliveryRuntimeCannotIssueRevokeOrBecomeIssuer()
            throws Exception {

        UUID grant = issue(issuer, PROJECT_ID, null);

        sqlState("42501",
                () -> issue(runtime, PROJECT_ID, null));

        sqlState("42501",
                () -> revoke(runtime, grant));

        sqlState("42501", () -> {
            try (var db = runtime.getConnection();
                 var stmt = db.createStatement()) {
                stmt.execute("SET ROLE asep_delivery_issuer");
            }
        });

        sqlState("42501", () -> {
            try (var db = runtime.getConnection();
                 var stmt = db.createStatement()) {
                stmt.executeQuery(
                        "SELECT * FROM code_delivery.owner_grant_events");
            }
        });
    }

    @Test
    void issuerCannotEscapeScopeOrRewriteAudit() throws Exception {
        UUID grant = issue(issuer, PROJECT_ID, null);

        sqlState("23514",
                () -> issue(issuer, 42L, null));

        sqlState("23514",
                () -> issue(issuer, PROJECT_ID, "wrong-branch"));

        sqlState("42501", () -> {
            try (var db = issuer.getConnection();
                 var stmt = db.prepareStatement("""
                     UPDATE code_delivery.owner_grants
                     SET issued_by='forged-operator'
                     WHERE grant_id=?
                     """)) {
                stmt.setObject(1, grant);
                stmt.executeUpdate();
            }
        });

        sqlState("42501", () -> {
            try (var db = issuer.getConnection();
                 var stmt = db.prepareStatement("""
                     INSERT INTO code_delivery.owner_grant_events
                         (grant_id, event_kind, actor_id)
                     VALUES (?, 'REVOKED', 'forged-operator')
                     """)) {
                stmt.setObject(1, grant);
                stmt.executeUpdate();
            }
        });

        sqlState("42501", () -> {
            try (var db = issuer.getConnection();
                 var stmt = db.prepareStatement("""
                     DELETE FROM code_delivery.owner_grant_events
                     WHERE grant_id=?
                     """)) {
                stmt.setObject(1, grant);
                stmt.executeUpdate();
            }
        });
    }
}
