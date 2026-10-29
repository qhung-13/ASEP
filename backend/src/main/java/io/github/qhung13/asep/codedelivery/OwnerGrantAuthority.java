package io.github.qhung13.asep.codedelivery;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import javax.sql.DataSource;

/** Restricted issuer capability, never a worker bean or agent-facing endpoint.
 * The operator boundary supplies a separately authenticated, isolated issuer datasource.
 * Actor identity/time are database-derived, never caller fields. Commit errors are UNKNOWN;
 * repeat only the same request to read its original immutable grant, never extend its expiry.
 */
public final class OwnerGrantAuthority {
    public record Receipt(java.util.UUID grantId, java.util.UUID operationId, String requestHash,
            String actor, Instant issuedAt, Instant notBefore, Instant expiresAt, Instant revokedAt) {
        @Override public String toString() { return "OwnerGrantReceipt[identity and timestamps redacted]"; }
    }
    private final DataSource database;
    private final String expectedPrincipal;
    public OwnerGrantAuthority(DataSource database, String expectedPrincipal) {
        this.database = Objects.requireNonNull(database);
        if (expectedPrincipal == null || !expectedPrincipal.matches("[a-z_][a-z0-9_]{0,62}")
                || expectedPrincipal.equals("asep_delivery_runtime")) throw denied();
        this.expectedPrincipal = expectedPrincipal;
    }
    public Receipt approve(TestProjectDeliveryRequest request, int validitySeconds) {
        if (request == null || validitySeconds < 1 || validitySeconds > 3600) throw denied();
        return change(request, validitySeconds, false);
    }
    public Receipt revoke(TestProjectDeliveryRequest request) {
        if (request == null) throw denied();
        return change(request, 0, true);
    }
    private Receipt change(TestProjectDeliveryRequest request, int seconds, boolean revoke) {
        try (var db = database.getConnection()) {
            db.setAutoCommit(false);
            try {
                String principal = principal(db);
                if (!revoke) {
                    try (var statement = db.prepareStatement("""
                            INSERT INTO code_delivery.owner_grants
                              (grant_id,operation_id,request_hash,project_id,repository_path,source_branch,
                               target_branch,not_before,expires_at)
                            VALUES (?,?,?,87786140,'qhung-13/asep-m01-sandbox',?,'main',
                                    clock_timestamp(),statement_timestamp() + (? * interval '1 second'))
                            ON CONFLICT DO NOTHING
                            """)) {
                        statement.setObject(1, request.ownerGrantId()); statement.setObject(2, request.operationId());
                        statement.setString(3, request.bodyHash()); statement.setString(4, request.sourceBranch());
                        statement.setInt(5, seconds); statement.executeUpdate();
                    }
                }
                Receipt receipt;
                try (var statement = db.prepareStatement("""
                        SELECT grant_id,operation_id,request_hash,issued_by,issued_at,not_before,expires_at,revoked_at,
                               project_id,repository_path,source_branch,target_branch,clock_timestamp()
                        FROM code_delivery.owner_grants WHERE operation_id=? FOR UPDATE
                        """)) {
                    statement.setObject(1, request.operationId());
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next()) throw denied();
                        receipt = receipt(rows);
                        if (!request.ownerGrantId().equals(receipt.grantId()) || !request.bodyHash().equals(receipt.requestHash())
                                || rows.getLong(9) != request.projectId() || !"qhung-13/asep-m01-sandbox".equals(rows.getString(10))
                                || !request.sourceBranch().equals(rows.getString(11)) || !request.targetBranch().equals(rows.getString(12))) throw conflict();
                        Instant now = rows.getTimestamp(13).toInstant();
                        if (!revoke && (receipt.revokedAt() != null || receipt.notBefore().isAfter(now) || !receipt.expiresAt().isAfter(now))) throw denied();
                    }
                }
                if (revoke && receipt.revokedAt() == null) {
                    try (var statement = db.prepareStatement("""
                            UPDATE code_delivery.owner_grants SET revoked_at=clock_timestamp(),revoked_by=session_user
                            WHERE grant_id=? AND revoked_at IS NULL RETURNING revoked_at,revoked_by
                            """)) {
                        statement.setObject(1, request.ownerGrantId());
                        try (var row = statement.executeQuery()) {
                            if (!row.next() || !principal.equals(row.getString(2))) throw denied();
                            receipt = new Receipt(receipt.grantId(),receipt.operationId(),receipt.requestHash(),receipt.actor(),
                                    receipt.issuedAt(),receipt.notBefore(),receipt.expiresAt(),row.getTimestamp(1).toInstant());
                        }
                    }
                }
                db.commit();
                return receipt;
            } catch (SQLException | RuntimeException failure) {
                try { db.rollback(); } catch (SQLException ignored) { /* No rollback success or no-effect claim. */ }
                throw failure;
            }
        } catch (SQLException unavailable) { throw new DeliveryException(DeliveryException.Kind.UNAVAILABLE); }
    }
    private String principal(Connection db) throws SQLException {
        try (var statement = db.createStatement(); var row = statement.executeQuery("""
                SELECT session_user,current_user,pg_has_role(session_user,'asep_delivery_issuer','USAGE'),
                       rolsuper,rolcreaterole,rolcreatedb,rolbypassrls
                FROM pg_roles WHERE rolname=session_user
                """)) {
            if (!row.next() || !expectedPrincipal.equals(row.getString(1)) || !expectedPrincipal.equals(row.getString(2))
                    || !row.getBoolean(3) || row.getBoolean(4) || row.getBoolean(5) || row.getBoolean(6) || row.getBoolean(7)) throw denied();
            return row.getString(1);
        }
    }
    private static Receipt receipt(ResultSet row) throws SQLException {
        var revoked = row.getTimestamp(8);
        return new Receipt(row.getObject(1,java.util.UUID.class),row.getObject(2,java.util.UUID.class),row.getString(3),row.getString(4),
                row.getTimestamp(5).toInstant(),row.getTimestamp(6).toInstant(),row.getTimestamp(7).toInstant(),revoked == null ? null : revoked.toInstant());
    }
    private static DeliveryException denied() { return new DeliveryException(DeliveryException.Kind.INVALID); }
    private static DeliveryException conflict() { return new DeliveryException(DeliveryException.Kind.CONFLICT); }
}
