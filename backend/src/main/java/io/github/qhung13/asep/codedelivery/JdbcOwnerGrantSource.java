package io.github.qhung13.asep.codedelivery;

import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

import static io.github.qhung13.asep.codedelivery.TestProjectDeliveryGate.OwnerGrant;

/**
 * Reads current Owner Grant state from the trusted PostgreSQL store.
 *
 * The injected DataSource must use the read-only delivery runtime role.
 * This class cannot issue, revoke, or approve grants.
 */
public final class JdbcOwnerGrantSource
        implements TestProjectOwnerGrantVerifier.Source {

    private static final String FIND_SQL = """
            SELECT grant_id, operation_id, request_hash,
                   project_id, repository_path, source_branch,
                   target_branch, not_before, expires_at, revoked_at
            FROM code_delivery.owner_grants
            WHERE grant_id = ?
            """;

    private final DataSource database;

    public JdbcOwnerGrantSource(DataSource database) {
        this.database = Objects.requireNonNull(database);
    }

    @Override
    public TestProjectOwnerGrantVerifier.GrantRecord find(UUID grantId) {
        if (grantId == null) {
            return null;
        }

        try (var connection = database.getConnection();
             var statement = connection.prepareStatement(FIND_SQL)) {

            statement.setObject(1, grantId);

            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }

                var notBefore = rows.getTimestamp(8);
                var expiresAt = rows.getTimestamp(9);
                var revokedAt = rows.getTimestamp(10);

                if (notBefore == null || expiresAt == null) {
                    return null;
                }

                var grant = new OwnerGrant(
                        rows.getObject(1, UUID.class),
                        rows.getObject(2, UUID.class),
                        rows.getString(3),
                        rows.getLong(4),
                        rows.getString(5),
                        rows.getString(6),
                        rows.getString(7)
                );

                var record =
                        new TestProjectOwnerGrantVerifier.GrantRecord(
                                grant,
                                notBefore.toInstant(),
                                expiresAt.toInstant(),
                                revokedAt != null
                        );

                if (rows.next()) {
                    throw new IllegalStateException(
                            "Owner Grant lookup returned duplicate records");
                }

                return record;
            }

        } catch (SQLException unavailable) {
            throw new IllegalStateException(
                    "Owner Grant store unavailable");
        }
    }
}
