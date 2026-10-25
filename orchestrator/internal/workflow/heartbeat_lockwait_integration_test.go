//go:build integration

package workflow

import (
	"context"
	"database/sql"
	"errors"
	"os"
	"testing"
	"time"

	"github.com/qhung-13/ASEP/orchestrator/internal/testsupport"
)

// F08 investigation: a real lock-only blocker must not allow a previously
// evaluated lease predicate to renew authority after database-observed expiry.
// If this fails, preserve the regression for separate production-fix review.
func TestHeartbeatRejectsLeaseExpiredWhileWaitingForRowLock(t *testing.T) {
	store := storeForTest(t)
	_, run := admitForTest(t, store, "heartbeat-lockwait")
	lease, err := store.ClaimForRun(testContext(t), run.RunID, "synthetic-lockwait-worker", 5)
	if err != nil {
		t.Fatal("lock-wait fixture claim failed")
	}
	probeDB, err := testsupport.Connect(testContext(t), testsupport.DatabaseURL(t), os.Getenv("ASEP_VN_T11_DISPOSABLE_NONCE"))
	if err != nil {
		t.Fatal("verified lock-wait probe unavailable")
	}
	t.Cleanup(func() { _ = probeDB.Close() })
	probeDB.SetMaxOpenConns(1)
	probeDB.SetMaxIdleConns(1)
	var probePID int
	if err := probeDB.QueryRowContext(testContext(t), "SELECT pg_backend_pid()").Scan(&probePID); err != nil {
		t.Fatal("lock-wait probe identity unavailable")
	}
	blocker, err := store.db.BeginTx(testContext(t), &sql.TxOptions{Isolation: sql.LevelReadCommitted})
	if err != nil {
		t.Fatal("controlled blocker transaction unavailable")
	}
	t.Cleanup(func() { _ = blocker.Rollback() })
	var taskID string
	if err := blocker.QueryRowContext(testContext(t), `SELECT task_id::text FROM orchestration.task_deliveries
        WHERE task_id=$1::uuid FOR UPDATE`, run.TaskID).Scan(&taskID); err != nil {
		t.Fatal("controlled task row lock unavailable")
	}
	ctx, cancel := context.WithTimeout(t.Context(), 9*time.Second)
	defer cancel()
	type result struct {
		expires time.Time
		err     error
	}
	reply := make(chan result, 1)
	go func() {
		expires, err := (&Store{db: probeDB}).Heartbeat(ctx, lease, 30)
		reply <- result{expires, err}
	}()
	ticker := time.NewTicker(10 * time.Millisecond)
	defer ticker.Stop()
	for {
		var waiting bool
		if err := store.db.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM pg_stat_activity
            WHERE pid=$1 AND wait_event_type='Lock' AND cardinality(pg_blocking_pids(pid)) > 0)`, probePID).Scan(&waiting); err != nil {
			t.Fatal("could not observe the controlled heartbeat lock wait")
		}
		if waiting {
			break
		}
		select {
		case <-ctx.Done():
			t.Fatal("heartbeat lock-wait barrier deadline exceeded")
		case <-reply:
			t.Fatal("heartbeat finished before reaching the controlled lock barrier")
		case <-ticker.C:
		}
	}
	testsupport.WaitForLeaseExpiry(t, store.db, run.TaskID)
	before := snapshotFor(t, store, run)
	if err := blocker.Commit(); err != nil {
		t.Fatal("controlled lock release failed")
	}
	var actual result
	select {
	case actual = <-reply:
	case <-ctx.Done():
		t.Fatal("heartbeat did not finish after controlled lock release")
	}
	after := snapshotFor(t, store, run)
	t.Logf("RF-05/F08 checkpoint: heartbeat blocked on unchanged task row; DB observed lease expired before lock release; rejected=%t expiry_changed=%t epoch=%d deliveries=%d semantic_attempt=%d budget=%d version=%d tasks=%d outbox=%d",
		errors.Is(actual.err, ErrLeaseLost), after.firstTaskExpiry != before.firstTaskExpiry,
		after.firstTaskEpoch, after.firstTaskDeliveries, after.firstAttemptNo, after.used, after.version, after.tasks, after.outbox)
	if !errors.Is(actual.err, ErrLeaseLost) || after != before {
		t.Fatalf("F08 reproduced: expected ErrLeaseLost and unchanged persisted lease; accepted=%t expiry_extended=%t; production unchanged pending review",
			actual.err == nil, after.firstTaskExpiry.Valid && after.firstTaskExpiry.Time.After(before.firstTaskExpiry.Time))
	}
}
