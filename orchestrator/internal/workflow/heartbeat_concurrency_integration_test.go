//go:build integration

package workflow

import (
	"context"
	"database/sql"
	"errors"
	"os"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgconn"
	"github.com/qhung-13/ASEP/orchestrator/internal/testsupport"
)

// Pin a separate pool to one backend so pg_blocking_pids can prove the ordering
// of real production calls. No test hook is added to Store or the HTTP API.
func heartbeatProbe(t *testing.T) (*Store, int) {
	t.Helper()
	db, err := testsupport.Connect(testContext(t), testsupport.DatabaseURL(t), os.Getenv("ASEP_VN_T11_DISPOSABLE_NONCE"))
	if err != nil {
		t.Fatal("verified heartbeat probe unavailable")
	}
	t.Cleanup(func() { _ = db.Close() })
	db.SetMaxOpenConns(1)
	db.SetMaxIdleConns(1)
	var pid int
	if err := db.QueryRowContext(testContext(t), "SELECT pg_backend_pid()").Scan(&pid); err != nil {
		t.Fatal("heartbeat probe backend identity unavailable")
	}
	return &Store{db: db}, pid
}

// The controller never requests another row after taking this lock. Production
// operations acquire their own run/task locks while this fixture supplies a gate.
func holdHeartbeatRow(t *testing.T, store *Store, query, id string) (*sql.Tx, int) {
	t.Helper()
	tx, err := store.db.BeginTx(testContext(t), &sql.TxOptions{Isolation: sql.LevelReadCommitted})
	if err != nil {
		t.Fatal("controlled heartbeat blocker unavailable")
	}
	t.Cleanup(func() { _ = tx.Rollback() })
	var lockedID string
	if err := tx.QueryRowContext(testContext(t), query, id).Scan(&lockedID); err != nil || lockedID != id {
		t.Fatal("controlled heartbeat fixture row could not be locked")
	}
	var pid int
	if err := tx.QueryRowContext(testContext(t), "SELECT pg_backend_pid()").Scan(&pid); err != nil {
		t.Fatal("controlled blocker identity unavailable")
	}
	return tx, pid
}

func waitHeartbeatBlockedBy(t *testing.T, store *Store, waiter, blocker int) {
	t.Helper()
	ctx := testContext(t)
	ticker := time.NewTicker(10 * time.Millisecond)
	defer ticker.Stop()
	for {
		var waiting bool
		if err := store.db.QueryRowContext(ctx, "SELECT $2::int = ANY(pg_blocking_pids($1::int))", waiter, blocker).Scan(&waiting); err != nil {
			t.Fatal("controlled lock ordering could not be observed")
		}
		if waiting {
			return
		}
		select {
		case <-ctx.Done():
			t.Fatal("controlled lock-order barrier deadline exceeded")
		case <-ticker.C:
		}
	}
}

type heartbeatResult struct {
	expires time.Time
	err     error
}

func startHeartbeat(t *testing.T, store *Store, lease TaskLease) <-chan heartbeatResult {
	t.Helper()
	ctx := testContext(t)
	reply := make(chan heartbeatResult, 1)
	go func() {
		expires, err := store.Heartbeat(ctx, lease, 60)
		reply <- heartbeatResult{expires, err}
	}()
	return reply
}

func awaitHeartbeat(t *testing.T, reply <-chan heartbeatResult) heartbeatResult {
	t.Helper()
	select {
	case result := <-reply:
		return result
	case <-testContext(t).Done():
		t.Fatal("heartbeat result deadline exceeded")
		return heartbeatResult{}
	}
}

func TestHeartbeatTransitionLockOrdering(t *testing.T) {
	for _, first := range []string{"transition", "heartbeat"} {
		t.Run(first+"-first", func(t *testing.T) {
			store := storeForTest(t)
			command, run := admitForTest(t, store, "heartbeat-transition")
			lease, err := store.ClaimForRun(testContext(t), run.RunID, "synthetic-heartbeat-transition", 30)
			if err != nil {
				t.Fatal("heartbeat/transition fixture claim failed")
			}
			before := snapshotFor(t, store, run)
			hb, heartbeatPID := heartbeatProbe(t)
			advance, advancePID := heartbeatProbe(t)
			blocker, blockerPID := holdHeartbeatRow(t, store, `SELECT task_id::text
                FROM orchestration.task_deliveries WHERE task_id=$1::uuid FOR UPDATE`, run.TaskID)
			type advanceResult struct {
				receipt advanceReceipt
				err     error
			}
			transitionReply := make(chan advanceResult, 1)
			advanceCommand := validAdvance(t, command, lease)
			advanceContext := testContext(t)
			startTransition := func() {
				go func() {
					receipt, err := advance.advanceInvestigation(advanceContext, advanceCommand)
					transitionReply <- advanceResult{receipt, err}
				}()
			}
			var heartbeatReply <-chan heartbeatResult
			if first == "transition" {
				startTransition()
				waitHeartbeatBlockedBy(t, store, advancePID, blockerPID)
				heartbeatReply = startHeartbeat(t, hb, lease)
				waitHeartbeatBlockedBy(t, store, heartbeatPID, advancePID)
			} else {
				heartbeatReply = startHeartbeat(t, hb, lease)
				waitHeartbeatBlockedBy(t, store, heartbeatPID, blockerPID)
				startTransition()
				waitHeartbeatBlockedBy(t, store, advancePID, heartbeatPID)
			}
			if err := blocker.Commit(); err != nil {
				t.Fatal("controlled heartbeat/transition gate release failed")
			}
			heartbeat := awaitHeartbeat(t, heartbeatReply)
			var transition advanceResult
			select {
			case transition = <-transitionReply:
			case <-testContext(t).Done():
				t.Fatal("transition result deadline exceeded")
			}
			if transition.err != nil {
				t.Fatalf("valid competing transition failed: %v", transition.err)
			}
			checkAdvanced(t, store, run, transition.receipt)
			after := snapshotFor(t, store, run)
			if first == "transition" {
				if !errors.Is(heartbeat.err, ErrLeaseLost) || after.firstTaskExpiry != before.firstTaskExpiry {
					t.Fatal("heartbeat renewed a task after the transition won")
				}
			} else if heartbeat.err != nil || !heartbeat.expires.After(before.firstTaskExpiry.Time) || !after.firstTaskExpiry.Time.Equal(heartbeat.expires) {
				t.Fatalf("heartbeat that won the lock order did not renew correctly: %v", heartbeat.err)
			}
			if after.firstTaskClaimant != before.firstTaskClaimant || after.firstTaskEpoch != before.firstTaskEpoch || after.firstTaskDeliveries != before.firstTaskDeliveries {
				t.Fatal("heartbeat/transition race changed lease ownership")
			}
			if _, err := store.Heartbeat(testContext(t), lease, 60); !errors.Is(err, ErrLeaseLost) || snapshotFor(t, store, run) != after {
				t.Fatal("completed task retained heartbeat authority")
			}
			t.Logf("observed %s-first: completed task; version=%d tasks=%d attempts=%d transitions=%d outbox=%d budget=%d; completed heartbeat rejected; no deadlock", first, after.version, after.tasks, after.attempts, after.transitions, after.outbox, after.used)
		})
	}
}

func TestHeartbeatConcurrentClaim(t *testing.T) {
	t.Run("active-heartbeat-first", func(t *testing.T) {
		store := storeForTest(t)
		_, run := admitForTest(t, store, "heartbeat-claim-active")
		lease, err := store.ClaimForRun(testContext(t), run.RunID, "synthetic-active-worker", 30)
		if err != nil {
			t.Fatal("active heartbeat fixture claim failed")
		}
		before := snapshotFor(t, store, run)
		hb, pid := heartbeatProbe(t)
		blocker, blockerPID := holdHeartbeatRow(t, store, `SELECT task_id::text
            FROM orchestration.task_deliveries WHERE task_id=$1::uuid FOR UPDATE`, run.TaskID)
		reply := startHeartbeat(t, hb, lease)
		waitHeartbeatBlockedBy(t, store, pid, blockerPID)
		if _, err := store.ClaimForRun(testContext(t), run.RunID, "synthetic-competing-worker", 30); !errors.Is(err, ErrNoTask) || snapshotFor(t, store, run) != before {
			t.Fatal("competing claim modified the current valid lease")
		}
		if err := blocker.Commit(); err != nil {
			t.Fatal("active heartbeat gate release failed")
		}
		result := awaitHeartbeat(t, reply)
		expected := before
		expected.firstTaskExpiry.Time = result.expires
		if result.err != nil || !result.expires.After(before.firstTaskExpiry.Time) || snapshotFor(t, store, run) != expected {
			t.Fatal("valid heartbeat/claim race changed authoritative state")
		}
		t.Log("active heartbeat/claim observed: losing claimant ErrNoTask; epoch=1 deliveries=1 attempt=1 budget=0; expiry renewed; no deadlock")
	})
	t.Run("expired-reclaim-first", func(t *testing.T) {
		store := storeForTest(t)
		_, run := admitForTest(t, store, "heartbeat-claim-expired")
		old, err := store.ClaimForRun(testContext(t), run.RunID, "synthetic-expired-worker", 1)
		if err != nil {
			t.Fatal("expired heartbeat fixture claim failed")
		}
		waitForLeaseExpiry(t, store, run.TaskID)
		hb, heartbeatPID := heartbeatProbe(t)
		claim, claimPID := heartbeatProbe(t)
		blocker, blockerPID := holdHeartbeatRow(t, store, `SELECT attempt_id::text
            FROM orchestration.stage_attempts WHERE attempt_id=$1::uuid FOR UPDATE`, run.AttemptID)
		type claimResult struct {
			lease TaskLease
			err   error
		}
		reply := make(chan claimResult, 1)
		ctx := testContext(t)
		go func() {
			lease, err := claim.ClaimForRun(ctx, run.RunID, "synthetic-reclaimed-worker", 30)
			reply <- claimResult{lease, err}
		}()
		waitHeartbeatBlockedBy(t, store, claimPID, blockerPID)
		heartbeatReply := startHeartbeat(t, hb, old)
		waitHeartbeatBlockedBy(t, store, heartbeatPID, claimPID)
		if err := blocker.Commit(); err != nil {
			t.Fatal("reclaim gate release failed")
		}
		var owner claimResult
		select {
		case owner = <-reply:
		case <-ctx.Done():
			t.Fatal("reclaim completion deadline exceeded")
		}
		if result := awaitHeartbeat(t, heartbeatReply); !errors.Is(result.err, ErrLeaseLost) || owner.err != nil {
			t.Fatalf("reclaim/heartbeat outcome invalid: claim=%v heartbeat=%v", owner.err, result.err)
		}
		after := snapshotFor(t, store, run)
		if after.firstTaskClaimant != owner.lease.ClaimantID || after.firstTaskEpoch != 2 || after.firstTaskDeliveries != 2 ||
			!after.firstTaskExpiry.Time.Equal(owner.lease.ExpiresAt) || after.firstAttemptNo != 1 || after.used != 0 ||
			after.version != 1 || after.tasks != 1 || after.attempts != 1 || after.transitions != 1 || after.outbox != 1 ||
			owner.lease.TaskID != run.TaskID || owner.lease.AttemptID != run.AttemptID {
			t.Fatalf("reclaim/heartbeat changed durable work identity or counters: %+v", after)
		}
		expires, err := store.Heartbeat(testContext(t), owner.lease, 60)
		expected := after
		expected.firstTaskExpiry.Time = expires
		if err != nil || !expires.After(after.firstTaskExpiry.Time) || snapshotFor(t, store, run) != expected {
			t.Fatal("new owner could not renew after fencing the old heartbeat")
		}
		t.Log("reclaim-first observed: old heartbeat ErrLeaseLost; same task/attempt; epoch=2 deliveries=2 attempt=1 budget=0; new owner renews; no deadlock")
	})
}

func TestHeartbeatDatabaseErrorsRemainDistinctFromLeaseLoss(t *testing.T) {
	for _, row := range []string{"run", "task"} {
		t.Run(row+"-lock-timeout", func(t *testing.T) {
			store := storeForTest(t)
			_, run := admitForTest(t, store, "heartbeat-db-error")
			lease, err := store.ClaimForRun(testContext(t), run.RunID, "synthetic-error-worker", 30)
			if err != nil {
				t.Fatal("heartbeat error fixture claim failed")
			}
			before := snapshotFor(t, store, run)
			probe, _ := heartbeatProbe(t)
			if _, err := probe.db.ExecContext(testContext(t), "SET lock_timeout='100ms'"); err != nil {
				t.Fatal("bounded lock timeout could not be configured")
			}
			query, id := "SELECT run_id::text FROM orchestration.workflow_runs WHERE run_id=$1::uuid FOR UPDATE", run.RunID
			if row == "task" {
				query, id = "SELECT task_id::text FROM orchestration.task_deliveries WHERE task_id=$1::uuid FOR UPDATE", run.TaskID
			}
			blocker, _ := holdHeartbeatRow(t, store, query, id)
			_, err = probe.Heartbeat(testContext(t), lease, 60)
			var pgErr *pgconn.PgError
			if errors.Is(err, ErrLeaseLost) || !errors.As(err, &pgErr) || pgErr.Code != "55P03" {
				t.Fatalf("real PostgreSQL lock error was lost or misclassified: %v", err)
			}
			if err := blocker.Rollback(); err != nil {
				t.Fatal("controlled error blocker did not roll back")
			}
			if snapshotFor(t, store, run) != before {
				t.Fatal("failed heartbeat transaction changed persisted state")
			}
			if _, err := probe.db.ExecContext(testContext(t), "RESET lock_timeout"); err != nil {
				t.Fatal("heartbeat error connection did not recover")
			}
			if _, err := probe.Heartbeat(testContext(t), lease, 60); err != nil {
				t.Fatal("valid heartbeat failed after transaction rollback")
			}
			t.Logf("%s lock timeout observed: SQLSTATE=55P03 propagated; snapshot unchanged; subsequent heartbeat succeeded", row)
		})
	}
	t.Run("canceled-context", func(t *testing.T) {
		store := storeForTest(t)
		_, run := admitForTest(t, store, "heartbeat-context-error")
		lease, err := store.ClaimForRun(testContext(t), run.RunID, "synthetic-context-worker", 30)
		if err != nil {
			t.Fatal("context fixture claim failed")
		}
		before := snapshotFor(t, store, run)
		ctx, cancel := context.WithCancel(t.Context())
		cancel()
		if _, err := store.Heartbeat(ctx, lease, 60); !errors.Is(err, context.Canceled) || errors.Is(err, ErrLeaseLost) {
			t.Fatalf("canceled transaction was misclassified: %v", err)
		}
		if snapshotFor(t, store, run) != before {
			t.Fatal("canceled heartbeat changed persisted state")
		}
	})
}
