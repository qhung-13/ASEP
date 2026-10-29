package io.github.qhung13.asep.codedelivery;

import java.sql.Connection;
import java.sql.SQLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Owner Grant schema and least-privilege tests.
 * Executes only against the owned disposable PostgreSQL fixture.
 */
class OwnerGrantSchemaPostgresTest {

    private PGSimpleDataSource database;

    @BeforeEach
    void requireOwnedDisposableDatabase() throws Exception {
        assumeTrue(Boolean.getBoolean("asep.delivery.postgres"));

        assertEquals("true",
                System.getenv("ASEP_DELIVERY_DISPOSABLE"));

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

        database = new PGSimpleDataSource();
        database.setUrl(url);
        database.setUser(
                System.getenv("ASEP_DELIVERY_DB_USER"));
        database.setPassword(
                System.getenv("ASEP_DELIVERY_DB_PASSWORD"));
        database.setConnectTimeout(3);
        database.setSocketTimeout(5);
        database.setOptions(
                "-c statement_timeout=4000 -c lock_timeout=3000");

        try (var db = database.getConnection();
             var stmt = db.prepareStatement("""
                 SELECT current_user='asep_delivery_runtime'
                     AND database_name=current_database()
                     AND server_started_at=pg_postmaster_start_time()
                     AND nonce=?
                 FROM asep_delivery_test_proof.instance
                 WHERE singleton
                 """)) {

            stmt.setString(1, nonce);

            try (var row = stmt.executeQuery()) {
                assertTrue(row.next());
                assertTrue(row.getBoolean(1));
                assertFalse(row.next());
            }
        }
    }

    private static void assertTablePresent(
            Connection db, String qualifiedName)
            throws SQLException {

        try (var stmt = db.prepareStatement(
                "SELECT to_regclass(?) IS NOT NULL")) {
            stmt.setString(1, qualifiedName);

            try (var row = stmt.executeQuery()) {
                assertTrue(row.next());
                assertTrue(row.getBoolean(1),
                        "Required table is missing: " + qualifiedName);
            }
        }
    }

    private static boolean hasPrivilege(
            Connection db, String table, String privilege)
            throws SQLException {

        try (var stmt = db.prepareStatement(
                "SELECT has_table_privilege(current_user, ?, ?)")) {
            stmt.setString(1, table);
            stmt.setString(2, privilege);

            try (var row = stmt.executeQuery()) {
                assertTrue(row.next());
                return row.getBoolean(1);
            }
        }
    }

    @Test
    void deliveryRuntimeCanOnlyReadOwnerGrants()
            throws Exception {

        try (var db = database.getConnection()) {
            String table = "code_delivery.owner_grants";

            assertTablePresent(db, table);

            assertTrue(hasPrivilege(db, table, "SELECT"));
            assertFalse(hasPrivilege(db, table, "INSERT"));
            assertFalse(hasPrivilege(db, table, "UPDATE"));
            assertFalse(hasPrivilege(db, table, "DELETE"));
            assertFalse(hasPrivilege(db, table, "TRUNCATE"));
        }
    }

    @Test
    void deliveryRuntimeCannotModifyGrantAudit()
            throws Exception {

        try (var db = database.getConnection()) {
            String table = "code_delivery.owner_grant_events";

            assertTablePresent(db, table);

            assertFalse(hasPrivilege(db, table, "INSERT"));
            assertFalse(hasPrivilege(db, table, "UPDATE"));
            assertFalse(hasPrivilege(db, table, "DELETE"));
            assertFalse(hasPrivilege(db, table, "TRUNCATE"));
        }
    }
}
