//go:build integration

// Package testsupport is compiled only for integration verification. It never
// participates in the production orchestrator or provisions database objects.
package testsupport

import (
	"context"
	"crypto/subtle"
	"database/sql"
	"errors"
	"net/url"
	"os"
	"regexp"
	"strconv"
	"testing"
	"time"

	_ "github.com/jackc/pgx/v5/stdlib"
)

var ErrUnsafeDatabase = errors.New("disposable PostgreSQL proof missing or invalid; use scripts/ci_verify.sh")

var noncePattern = regexp.MustCompile(`^[0-9a-f]{64}$`)
var prefixPattern = regexp.MustCompile(`^synthetic:vn-t10-it-([0-9a-f]{32})$`)

// Context bounds a test operation and also ends it when its owning test finishes.
func Context(t testing.TB) context.Context {
	t.Helper()
	ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
	t.Cleanup(cancel)
	return ctx
}

// Connect performs read-only verification before returning a usable connection.
// A DSN, opt-in flag, or synthetic label alone is never proof of a safe target.
func Connect(ctx context.Context, databaseURL, nonce string) (*sql.DB, error) {
	match := prefixPattern.FindStringSubmatch(os.Getenv("ASEP_VN_T10_TEST_PREFIX"))
	if os.Getenv("ASEP_VN_T10_DISPOSABLE_DB") != "true" || !noncePattern.MatchString(nonce) || len(match) != 2 {
		return nil, ErrUnsafeDatabase
	}
	// Reject missing/default DSNs before connecting, and accept only the harness
	// loopback URL form. Query-string host/service overrides are not permitted.
	parsed, err := url.Parse(databaseURL)
	if err != nil {
		return nil, ErrUnsafeDatabase
	}
	port, err := strconv.Atoi(parsed.Port())
	if err != nil || port < 1 || port > 65535 || parsed.Scheme != "postgres" || parsed.Hostname() != "127.0.0.1" ||
		parsed.User == nil || parsed.RawQuery != "sslmode=disable" || parsed.Fragment != "" ||
		(parsed.Path != "/asep_vnt11_"+match[1] && parsed.Path != "/asep_vnt11_unmarked_"+match[1]) {
		return nil, ErrUnsafeDatabase
	}
	db, err := sql.Open("pgx", databaseURL)
	if err != nil {
		return nil, ErrUnsafeDatabase
	}
	db.SetMaxOpenConns(2)
	db.SetMaxIdleConns(2)
	var actualNonce, databaseName, role string
	var sameServer, readOnly, schemaProtected bool
	var version int
	err = db.QueryRowContext(ctx, `
        SELECT p.nonce, current_database(), current_user,
            p.server_started_at = pg_postmaster_start_time(),
            has_table_privilege(current_user, 'asep_test_harness.instance', 'SELECT')
              AND NOT has_table_privilege(current_user, 'asep_test_harness.instance', 'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER'),
            NOT has_schema_privilege(current_user, 'asep_test_harness', 'CREATE'),
            current_setting('server_version_num')::int
        FROM asep_test_harness.instance p
        WHERE p.singleton AND p.database_name = current_database()
    `).Scan(&actualNonce, &databaseName, &role, &sameServer, &readOnly, &schemaProtected, &version)
	if err != nil || databaseName != "asep_vnt11_"+match[1] || role != "asep_orch_runtime" ||
		!sameServer || !readOnly || !schemaProtected || version < 170000 || version >= 180000 ||
		subtle.ConstantTimeCompare([]byte(actualNonce), []byte(nonce)) != 1 {
		_ = db.Close()
		// Never retain driver errors: they can contain connection credentials.
		return nil, ErrUnsafeDatabase
	}
	return db, nil
}

// DatabaseURL verifies the actual instance before any Store or fixture can write.
func DatabaseURL(t testing.TB) string {
	t.Helper()
	url := os.Getenv("ASEP_ORCHESTRATOR_DATABASE_URL")
	db, err := Connect(Context(t), url, os.Getenv("ASEP_VN_T11_DISPOSABLE_NONCE"))
	if err != nil {
		t.Fatal(ErrUnsafeDatabase)
	}
	if err := db.Close(); err != nil {
		t.Fatal("disposable database verification connection did not close")
	}
	return url
}

func Database(t testing.TB) *sql.DB {
	t.Helper()
	db, err := Connect(Context(t), os.Getenv("ASEP_ORCHESTRATOR_DATABASE_URL"), os.Getenv("ASEP_VN_T11_DISPOSABLE_NONCE"))
	if err != nil {
		t.Fatal(ErrUnsafeDatabase)
	}
	t.Cleanup(func() { _ = db.Close() })
	return db
}

// WaitForLeaseExpiry uses the same clock as production lease predicates.
func WaitForLeaseExpiry(t testing.TB, db *sql.DB, taskID string) {
	t.Helper()
	ctx := Context(t)
	ticker := time.NewTicker(20 * time.Millisecond)
	defer ticker.Stop()
	for {
		var expired bool
		if err := db.QueryRowContext(ctx, `SELECT lease_expires_at <= clock_timestamp()
            FROM orchestration.task_deliveries WHERE task_id=$1::uuid`, taskID).Scan(&expired); err != nil {
			t.Fatal("database-time lease expiry observation failed")
		}
		if expired {
			return
		}
		select {
		case <-ctx.Done():
			t.Fatal("database-time lease expiry deadline exceeded")
		case <-ticker.C:
		}
	}
}
