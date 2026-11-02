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

    private void issueExact(UUID grant, UUID operation, String hash) throws SQLException {
        try (var db = issuer.getConnection(); var stmt = db.prepareStatement("""
                INSERT INTO code_delivery.owner_grants
                  (grant_id,operation_id,request_hash,project_id,repository_path,source_branch,
                   target_branch,issued_by,not_before,expires_at)
                VALUES (?,?,?,87786140,'qhung-13/asep-m01-sandbox',?,'main','test-operator',
                        clock_timestamp(),clock_timestamp()+interval '30 minutes')
                """)) {
            stmt.setObject(1, grant); stmt.setObject(2, operation); stmt.setString(3, hash);
            stmt.setString(4, "test/vn-t14/" + operation); stmt.executeUpdate();
        }
    }
    private void failAudit(UUID grant, String kind, SqlAction action) throws Exception {
        String password = System.getenv("ASEP_DELIVERY_TEST_MIGRATION_PASSWORD");
        assertNotNull(password);
        var migrator = source(System.getenv("ASEP_DELIVERY_JDBC_URL"), "asep_delivery_migrator", password);
        // Only the owned fixture's migration identity can inject this failure. Runtime/issuer cannot DDL.
        try (var db = migrator.getConnection(); var stmt = db.createStatement()) {
            stmt.execute("CREATE FUNCTION code_delivery.test_reject_grant_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.grant_id='"
                    + grant + "'::uuid AND NEW.event_kind='" + kind + "' THEN RAISE EXCEPTION 'injected audit failure' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");
            stmt.execute("CREATE TRIGGER test_reject_grant_audit BEFORE INSERT ON code_delivery.owner_grant_events FOR EACH ROW EXECUTE FUNCTION code_delivery.test_reject_grant_audit()");
            try { action.run(); }
            finally {
                stmt.execute("DROP TRIGGER test_reject_grant_audit ON code_delivery.owner_grant_events");
                stmt.execute("DROP FUNCTION code_delivery.test_reject_grant_audit()");
            }
        }
    }
    private int auditCount(UUID grant) throws SQLException {
        try (var db = issuer.getConnection(); var stmt = db.prepareStatement("SELECT count(*) FROM code_delivery.owner_grant_events WHERE grant_id=?")) {
            stmt.setObject(1, grant); try (var row = stmt.executeQuery()) { assertTrue(row.next()); return row.getInt(1); }
        }
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

        var failure=assertThrows(IllegalStateException.class, () -> unavailable.find(UUID.randomUUID()));
        assertNull(failure.getCause());
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
                assertEquals("asep_delivery_issuer", rows.getString(2));
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
                assertEquals("asep_delivery_issuer", rows.getString(2));
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
                assertEquals("asep_delivery_issuer", rows.getString(2));
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
    @Test void auditInsertionFailureRollsBackIssuance() throws Exception {
        UUID grant = UUID.randomUUID(), operation = UUID.randomUUID();
        failAudit(grant, "ISSUED", () -> sqlState("23514", () -> issueExact(grant, operation, "a".repeat(64))));
        assertNull(new JdbcOwnerGrantSource(runtime).find(grant)); assertEquals(0, auditCount(grant));
        // The failed transaction did not reserve the operation/grant identity.
        issueExact(grant, operation, "a".repeat(64)); assertEquals(1, auditCount(grant));
    }
    @Test void auditInsertionFailureRollsBackRevocation() throws Exception {
        UUID grant = issue(issuer, PROJECT_ID, null);
        failAudit(grant, "REVOKED", () -> sqlState("23514", () -> revoke(issuer, grant)));
        assertFalse(new JdbcOwnerGrantSource(runtime).find(grant).revoked()); assertEquals(1, auditCount(grant));
        revoke(issuer, grant);
        assertTrue(new JdbcOwnerGrantSource(runtime).find(grant).revoked()); assertEquals(2, auditCount(grant));
    }
    @Test void issuerCannotRewriteAnyImmutableGrantColumnOrAuditHistory() throws Exception {
        UUID grant = issue(issuer, PROJECT_ID, null);
        for (String column : java.util.List.of("grant_id", "operation_id", "request_hash", "project_id", "repository_path", "source_branch",
                "target_branch", "issued_by", "issued_at", "not_before", "expires_at")) {
            sqlState("42501", () -> {
                try (var db = issuer.getConnection(); var stmt = db.prepareStatement("UPDATE code_delivery.owner_grants SET " + column + "=" + column + " WHERE grant_id=?")) {
                    stmt.setObject(1, grant); stmt.executeUpdate();
                }
            });
        }
        for (String command : java.util.List.of("UPDATE code_delivery.owner_grant_events SET actor_id='forged'", "TRUNCATE code_delivery.owner_grant_events", "DELETE FROM code_delivery.owner_grants")) {
            sqlState("42501", () -> { try (var db = issuer.getConnection(); var stmt = db.createStatement()) { stmt.execute(command); } });
        }
        assertEquals(1, auditCount(grant));
    }
    @Test void expiredExactGrantCannotBeRevivedByRuntimeOrIssuerUpdates() throws Exception {
        var request = new TestProjectDeliveryRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID(), "operator-fixture", 1,
                "a".repeat(40), "a".repeat(40), "b".repeat(40), "c".repeat(40), "d".repeat(64), UUID.randomUUID(), UUID.randomUUID(), "e".repeat(64), "f".repeat(64));
        issueExact(request.ownerGrantId(), request.operationId(), request.bodyHash());
        var source = new JdbcOwnerGrantSource(runtime); var record = source.find(request.ownerGrantId());
        var expired = new TestProjectOwnerGrantVerifier(source, java.time.Clock.fixed(record.expiresAt(), java.time.ZoneOffset.UTC));
        assertTrue(expired.verify(request, null).verified().isEmpty());
        for (var role : java.util.List.of(runtime, issuer)) sqlState("42501", () -> {
            try (var db = role.getConnection(); var stmt = db.prepareStatement("UPDATE code_delivery.owner_grants SET expires_at=clock_timestamp()+interval '1 hour' WHERE grant_id=?")) {
                stmt.setObject(1, request.ownerGrantId()); stmt.executeUpdate();
            }
        });
        assertEquals(record.expiresAt(), source.find(request.ownerGrantId()).expiresAt());
        assertTrue(expired.verify(request, null).verified().isEmpty());
    }
    @Test void competingGrantIdsCannotCreateTwoGrantsForOneOperation() throws Exception {
        UUID operation = UUID.randomUUID();
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var tasks = java.util.stream.Stream.of(UUID.randomUUID(), UUID.randomUUID()).map(grant -> pool.submit(() -> {
                start.await();
                try { issueExact(grant, operation, "a".repeat(64)); return true; }
                catch (SQLException conflict) { assertEquals("23505", conflict.getSQLState()); return false; }
            })).toList();
            start.countDown(); int wins = 0;
            for (var task : tasks) if (task.get(10, java.util.concurrent.TimeUnit.SECONDS)) wins++;
            assertEquals(1, wins);
        } finally { start.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)); }
        try (var db = issuer.getConnection(); var stmt = db.prepareStatement("SELECT count(*),count(e.event_id) FROM code_delivery.owner_grants g JOIN code_delivery.owner_grant_events e USING(grant_id) WHERE g.operation_id=?")) {
            stmt.setObject(1, operation); try (var row = stmt.executeQuery()) { assertTrue(row.next()); assertEquals(1, row.getInt(1)); assertEquals(1, row.getInt(2)); }
        }
    }
    @Test void issuerCannotEscalateToMigrationOrWriteGoDomain() throws Exception {
        for (String command : java.util.List.of("SET ROLE asep_delivery_migrator", "SELECT * FROM orchestration.delivery_owner_guard", "INSERT INTO orchestration.delivery_owner_guard VALUES(1)")) {
            sqlState("42501", () -> { try (var db = issuer.getConnection(); var stmt = db.createStatement()) { stmt.execute(command); } });
        }
    }

    private TestProjectDeliveryRequest exactRequest() {
        return new TestProjectDeliveryRequest(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,UUID.randomUUID(),"restricted-operator-test",1,
                "a".repeat(40),"a".repeat(40),"b".repeat(40),"c".repeat(40),"d".repeat(64),UUID.randomUUID(),UUID.randomUUID(),"e".repeat(64),"f".repeat(64));
    }
    @Test void restrictedAuthorityIssuesExactGrantFromAuthenticatedDatabasePrincipal() throws Exception {
        var request=exactRequest();var authority=new OwnerGrantAuthority(issuer,"asep_delivery_issuer");
        var receipt=authority.approve(request,300);
        assertEquals(request.ownerGrantId(),receipt.grantId());assertEquals(request.bodyHash(),receipt.requestHash());
        assertEquals("asep_delivery_issuer",receipt.actor());assertEquals(1,auditCount(receipt.grantId()));
        var verifier=new TestProjectOwnerGrantVerifier(new JdbcOwnerGrantSource(runtime),java.time.Clock.systemUTC());
        assertEquals(java.util.Set.of(TestProjectDeliveryGate.Requirement.OWNER_WRITE_AUTHORIZATION),verifier.verify(request,null).verified());
    }
    @Test void approvalReplayReturnsOriginalGrantWithoutExpiryExtensionOrAuditDuplication() throws Exception {
        var request=exactRequest();var authority=new OwnerGrantAuthority(issuer,"asep_delivery_issuer");
        var first=authority.approve(request,60);var replay=authority.approve(request,3600);
        assertEquals(first,replay);assertEquals(1,auditCount(request.ownerGrantId()));
    }
    @Test void authorityRevocationIsIdempotentButNeverRestoresTheGrant() throws Exception {
        var request=exactRequest();var authority=new OwnerGrantAuthority(issuer,"asep_delivery_issuer");
        authority.approve(request,300);var revoked=authority.revoke(request);
        assertNotNull(revoked.revokedAt());assertEquals(revoked,authority.revoke(request));
        assertEquals(2,auditCount(request.ownerGrantId()));
        assertThrows(DeliveryException.class,()->authority.approve(request,300));
        assertTrue(new JdbcOwnerGrantSource(runtime).find(request.ownerGrantId()).revoked());
    }
    @Test void changedRequestCannotReuseAnApprovedOperation() throws Exception {
        var request=exactRequest();var authority=new OwnerGrantAuthority(issuer,"asep_delivery_issuer");authority.approve(request,300);
        var other=new TestProjectDeliveryRequest(request.operationId(),request.runId(),request.attemptId(),2,request.ownerGrantId(),request.policyId(),request.policyEpoch(),
                request.baseSha(),request.expectedTargetSha(),request.candidateCommitSha(),request.candidateTreeSha(),request.manifestSha256(),request.sealReceiptId(),request.verificationReceiptId(),request.verifierProfileSha256(),request.safeCiProfileSha256());
        assertThrows(DeliveryException.class,()->authority.approve(other,300));assertEquals(1,auditCount(request.ownerGrantId()));
    }
    @Test void restrictedAuthorityRejectsWrongPrincipalRuntimeAndInvalidTtlBeforeIssuance() throws Exception {
        var request=exactRequest();
        assertThrows(DeliveryException.class,()->new OwnerGrantAuthority(runtime,"asep_delivery_runtime"));
        assertThrows(DeliveryException.class,()->new OwnerGrantAuthority(issuer,"untrusted_actor").approve(request,300));
        var authority=new OwnerGrantAuthority(issuer,"asep_delivery_issuer");
        assertThrows(DeliveryException.class,()->authority.approve(request,0));assertThrows(DeliveryException.class,()->authority.approve(request,3601));
        assertNull(new JdbcOwnerGrantSource(runtime).find(request.ownerGrantId()));
    }
    @Test void expiredApprovalCannotBeRenewedByAnExactReplay() throws Exception {
        var request=exactRequest();var authority=new OwnerGrantAuthority(issuer,"asep_delivery_issuer");var first=authority.approve(request,1);
        try(var db=issuer.getConnection();var stmt=db.prepareStatement("SELECT pg_sleep(GREATEST(0,extract(epoch FROM (?::timestamptz-clock_timestamp())))+0.02)")) {
            stmt.setTimestamp(1,java.sql.Timestamp.from(first.expiresAt()));stmt.execute();
        }
        assertThrows(DeliveryException.class,()->authority.approve(request,300));
        assertEquals(first.expiresAt(),new JdbcOwnerGrantSource(runtime).find(request.ownerGrantId()).expiresAt());assertEquals(1,auditCount(request.ownerGrantId()));
    }
    @Test void restrictedAuthorityRollsBackWhenItsAuditAppendFails() throws Exception {
        var request=exactRequest();var authority=new OwnerGrantAuthority(issuer,"asep_delivery_issuer");
        failAudit(request.ownerGrantId(),"ISSUED",()->assertThrows(DeliveryException.class,()->authority.approve(request,300)));
        assertNull(new JdbcOwnerGrantSource(runtime).find(request.ownerGrantId()));
        authority.approve(request,300);
        failAudit(request.ownerGrantId(),"REVOKED",()->assertThrows(DeliveryException.class,()->authority.revoke(request)));
        assertFalse(new JdbcOwnerGrantSource(runtime).find(request.ownerGrantId()).revoked());assertEquals(1,auditCount(request.ownerGrantId()));
    }
    @Test void arbitraryIssuerLabelCannotOverrideAuthenticatedAuditActor() throws Exception {
        UUID grant=issue(issuer,PROJECT_ID,null);
        try(var db=issuer.getConnection();var stmt=db.prepareStatement("SELECT issued_by FROM code_delivery.owner_grants WHERE grant_id=?")) {
            stmt.setObject(1,grant);try(var rows=stmt.executeQuery()) { assertTrue(rows.next());assertEquals("asep_delivery_issuer",rows.getString(1)); }
        }
        revoke(issuer,grant);
        try(var db=issuer.getConnection();var stmt=db.prepareStatement("SELECT actor_id FROM code_delivery.owner_grant_events WHERE grant_id=?")) {
            stmt.setObject(1,grant);try(var rows=stmt.executeQuery()) { int count=0;while(rows.next()) { assertEquals("asep_delivery_issuer",rows.getString(1));count++; }assertEquals(2,count); }
        }
    }

}
