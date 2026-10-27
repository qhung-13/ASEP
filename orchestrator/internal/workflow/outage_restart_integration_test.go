//go:build integration && postgresrestart

package workflow

import (
	"database/sql"
	"errors"
	"net"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgconn"
	"github.com/qhung-13/ASEP/orchestrator/internal/testsupport"
)

// Only the disposable runner controls PostgreSQL lifecycle. This trusted test
// process receives no Docker capability and cannot refresh the B0 marker.
// A separate build tag prevents silently skipping this externally orchestrated
// scenario in the ordinary integration pass; ci_verify runs it explicitly.
func TestOutageDatabaseRestart(t *testing.T) {
	dir := os.Getenv("ASEP_VN_T11_OUTAGE_CONTROL")
	info, err := os.Lstat(dir)
	if err != nil || !info.IsDir() || info.Mode().Perm() != 0700 || !filepath.IsAbs(dir) || filepath.Base(dir) != "outage-control" || !strings.HasPrefix(filepath.Base(filepath.Dir(dir)), "asep-vnt11.") {
		t.Fatal("private disposable restart control unavailable")
	}
	owner, ok := info.Sys().(*syscall.Stat_t)
	if !ok || int(owner.Uid) != os.Getuid() {
		t.Fatal("restart control not owned by trusted runner")
	}
	write := func(name string) {
		t.Helper()
		f, err := os.OpenFile(filepath.Join(dir, name), os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
		if err != nil {
			t.Fatal("restart checkpoint write failed")
		}
		if _, err := f.WriteString("ready\n"); err != nil {
			_ = f.Close()
			t.Fatal("restart checkpoint write failed")
		}
		if f.Close() != nil {
			t.Fatal("restart checkpoint close failed")
		}
	}
	await := func(name string) {
		t.Helper()
		ticker := time.NewTicker(10 * time.Millisecond)
		defer ticker.Stop()
		ctx := testContext(t)
		for {
			info, err := os.Lstat(filepath.Join(dir, name))
			if err == nil {
				if !info.Mode().IsRegular() || info.Mode().Perm() != 0600 {
					t.Fatal("untrusted restart checkpoint")
				}
				return
			}
			if !errors.Is(err, os.ErrNotExist) {
				t.Fatal("restart checkpoint read failed")
			}
			select {
			case <-ticker.C:
			case <-ctx.Done():
				t.Fatal("restart checkpoint deadline", name)
			}
		}
	}
	s, a, run, accepted := planningRetry(t, syntheticProfile{2, 2, 2, true}, 2)
	attestRetry(t, s, accepted)
	original, err := s.decideRetry(testContext(t), accepted)
	if err != nil {
		t.Fatal(err)
	}
	l, err := s.ClaimForRun(testContext(t), run.RunID, "synthetic-database-restart", 60)
	if err != nil {
		t.Fatal(err)
	}
	pending := retryForTest(t, a, l, 3, "REPLAN")
	attestRetry(t, s, pending)
	before := snapshotFor(t, s, run)
	write("ready")
	await("offline")
	if r, err := s.decideRetry(testContext(t), pending); err == nil || r != (retryReceipt{}) {
		t.Fatal("stopped PostgreSQL acknowledged a new decision")
	}
	if r, err := s.reconcileRetry(testContext(t), accepted); !errors.Is(err, ErrResultUnknown) || r != (retryReceipt{}) {
		t.Fatal("stopped PostgreSQL fabricated historical acceptance")
	}
	write("observed")
	await("server-up")
	portBytes, err := os.ReadFile(filepath.Join(dir, "server-port"))
	if err != nil {
		t.Fatal("restored endpoint port unavailable")
	}
	port, err := strconv.Atoi(strings.TrimSpace(string(portBytes)))
	if err != nil || port < 1 || port > 65535 {
		t.Fatal("restored endpoint port invalid")
	}
	endpoint, err := url.Parse(s.databaseURL)
	if err != nil {
		t.Fatal("original endpoint invalid")
	}
	endpoint.Host = net.JoinHostPort("127.0.0.1", strconv.Itoa(port))
	restoredURL := endpoint.String()
	// Old pooled connections were deliberately killed. A fresh connection is
	// used only for read-only restart evidence and the expected-denied UPDATE;
	// no successful orchestration mutation precedes fresh B0 proof below.
	probe, err := sql.Open("pgx", restoredURL)
	if err != nil {
		t.Fatal("restart evidence connection unavailable")
	}
	defer probe.Close()
	var stale bool
	if err := probe.QueryRowContext(testContext(t), `SELECT server_started_at<>pg_postmaster_start_time() FROM asep_test_harness.instance WHERE singleton`).Scan(&stale); err != nil || !stale {
		t.Fatal("actual database restart/stale marker not independently observed")
	}
	db, err := testsupport.Connect(testContext(t), restoredURL, os.Getenv("ASEP_VN_T11_DISPOSABLE_NONCE"))
	if db != nil {
		_ = db.Close()
	}
	if !errors.Is(err, testsupport.ErrUnsafeDatabase) {
		t.Fatal("B0 accepted stale server-start marker")
	}
	_, err = probe.ExecContext(testContext(t), `UPDATE asep_test_harness.instance SET server_started_at=pg_postmaster_start_time()`)
	var pg *pgconn.PgError
	if !errors.As(err, &pg) || pg.Code != "42501" {
		t.Fatal("runtime could refresh B0 marker")
	}
	write("stale-proof-rejected")
	await("restored")
	t.Setenv("ASEP_ORCHESTRATOR_DATABASE_URL", restoredURL)
	reconnected := storeForTest(t)
	if snapshotFor(t, reconnected, run) != before {
		t.Fatal("database restart changed accepted history/accounting/lease identity")
	}
	for range 3 {
		r, err := reconnected.reconcileRetry(testContext(t), accepted)
		if err != nil || r != original {
			t.Fatal("restart lost original durable receipt", err)
		}
	}
	if _, err := reconnected.reconcileRetry(testContext(t), pending); !errors.Is(err, ErrResultUnknown) {
		t.Fatal("offline operation gained a fabricated receipt")
	}
	r, err := reconnected.decideRetry(testContext(t), pending)
	if err != nil {
		t.Fatal("restored authorized operation failed", err)
	}
	if again, err := reconnected.decideRetry(testContext(t), pending); err != nil || again != r {
		t.Fatal("restored operation duplicated progress")
	}
	after := snapshotFor(t, reconnected, run)
	if after.used != 2 || after.version != 4 || after.tasks != 4 || after.outbox != 4 {
		t.Fatal("restart reset or duplicated durable budget/progress")
	}
	t.Log("RF-10 actual owned PostgreSQL restart: offline operations had no ACK; stale B0 marker rejected; runtime refresh denied; trusted harness re-proved instance; exact receipts/budgets retained; restored operation accepted once")
}
