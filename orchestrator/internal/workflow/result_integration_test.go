//go:build integration

package workflow

import (
	"context"
	"database/sql"
	"errors"
	"net"
	"net/url"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgconn"
)

type resultReply struct {
	receipt advanceReceipt
	err     error
}

func startResult(t *testing.T, s *Store, c investigationAdvance) <-chan resultReply {
	t.Helper()
	reply := make(chan resultReply, 1)
	ctx := testContext(t)
	go func() { r, err := s.advanceInvestigation(ctx, c); reply <- resultReply{r, err} }()
	return reply
}

func awaitResult(t *testing.T, reply <-chan resultReply) resultReply {
	t.Helper()
	select {
	case result := <-reply:
		return result
	case <-testContext(t).Done():
		t.Fatal("result submission exceeded deadline")
		return resultReply{}
	}
}

func TestResultLockedAcceptance(t *testing.T) {
	for _, checkpoint := range []string{"expired-before-task-lock", "expired-during-task-lock", "accepted-before-expiry-committed-after"} {
		t.Run(checkpoint, func(t *testing.T) {
			s := storeForTest(t)
			admission, run := admitForTest(t, s, "result-acceptance")
			lease, err := s.ClaimForRun(testContext(t), run.RunID, "synthetic-acceptance-worker", 3)
			if err != nil {
				t.Fatal("acceptance fixture claim failed")
			}
			command := validAdvance(t, admission, lease)
			before := snapshotFor(t, s, run)
			if checkpoint == "expired-before-task-lock" {
				waitForLeaseExpiry(t, s, run.TaskID)
				_, err := s.advanceInvestigation(testContext(t), command)
				if !errors.Is(err, ErrLeaseLost) || snapshotFor(t, s, run) != before {
					t.Fatal("expiry before acceptance changed durable state")
				}
				t.Log("RF-05: expired before acceptance rejected; receipts=0; original bundle unchanged")
				return
			}
			probe, pid := heartbeatProbe(t)
			query, id := `SELECT task_id::text FROM orchestration.task_deliveries WHERE task_id=$1::uuid FOR UPDATE`, run.TaskID
			if checkpoint == "accepted-before-expiry-committed-after" {
				query, id = `SELECT attempt_id::text FROM orchestration.stage_attempts WHERE attempt_id=$1::uuid FOR UPDATE`, run.AttemptID
			}
			blocker, blockerPID := holdHeartbeatRow(t, s, query, id)
			reply := startResult(t, probe, command)
			waitHeartbeatBlockedBy(t, s, pid, blockerPID)
			// For the attempt gate, task completion already passed the fresh
			// acceptance check, but no row is visible until the eventual commit.
			if checkpoint == "accepted-before-expiry-committed-after" {
				var stillLive bool
				if err := s.db.QueryRowContext(testContext(t), `SELECT lease_expires_at>clock_timestamp()
                    FROM orchestration.task_deliveries WHERE task_id=$1::uuid`, run.TaskID).Scan(&stillLive); err != nil || !stillLive {
					t.Fatal("post-acceptance barrier was not reached before expiry")
				}
			}
			waitForLeaseExpiry(t, s, run.TaskID)
			if err := blocker.Commit(); err != nil {
				t.Fatal("acceptance barrier release failed")
			}
			actual := awaitResult(t, reply)
			if checkpoint == "expired-during-task-lock" {
				if !errors.Is(actual.err, ErrLeaseLost) || snapshotFor(t, s, run) != before {
					t.Fatal("F01: lease expired during lock wait was accepted")
				}
				t.Log("RF-05/F01: real task-lock wait crossed DB expiry; ErrLeaseLost; snapshot including receipts unchanged")
			} else {
				if actual.err != nil {
					t.Fatal("valid locked acceptance was invalidated by later expiry")
				}
				checkAdvanced(t, s, run, actual.receipt)
				var expiredAtCommit bool
				if err := s.db.QueryRowContext(testContext(t), `SELECT clock_timestamp()>$1::timestamptz`, lease.ExpiresAt).Scan(&expiredAtCommit); err != nil || !expiredAtCommit {
					t.Fatal("commit did not cross the controlled expiry")
				}
				if _, err := s.Heartbeat(testContext(t), lease, 30); !errors.Is(err, ErrLeaseLost) {
					t.Fatal("historical acceptance renewed execution authority")
				}
				t.Log("RF-05/F01: acceptance timestamp < lease expiry < commit release; one result receipt and one successor; heartbeat fenced")
			}
		})
	}
}

func TestResultReplayAndLostApplicationACK(t *testing.T) {
	s := storeForTest(t)
	admission, run := admitForTest(t, s, "result-lost-app-ack")
	lease, err := s.ClaimForRun(testContext(t), run.RunID, "synthetic-result-worker", 1)
	if err != nil {
		t.Fatal("result claim failed")
	}
	c := validAdvance(t, admission, lease)
	// This discards an application response AFTER a confirmed successful COMMIT.
	// It is intentionally distinct from the wire-level COMMIT tests below.
	if _, err := s.advanceInvestigation(testContext(t), c); err != nil {
		t.Fatal("original result was not accepted")
	}
	before := snapshotFor(t, s, run)
	waitForLeaseExpiry(t, s, run.TaskID)
	reconnect := storeForTest(t)
	original, err := reconnect.reconcileInvestigation(testContext(t), c)
	if err != nil {
		t.Fatal("lost application ACK receipt could not be recovered")
	}
	replayCommand := c
	replayCommand.Lease.ExpiresAt = time.Now().Add(time.Hour)
	replayCommand.Lease.DeliveryCount++
	replay, err := reconnect.advanceInvestigation(testContext(t), replayCommand)
	if err != nil || replay != original {
		t.Fatal("expired exact replay did not preserve every original receipt field")
	}
	checkAdvanced(t, reconnect, run, replay)
	for _, changeBinding := range []bool{false, true} {
		conflict := c
		if changeBinding {
			conflict.Lease.ClaimantID += "-other"
		} else {
			conflict.EvidenceRef += "-changed"
		}
		if _, err := reconnect.advanceInvestigation(testContext(t), conflict); !errors.Is(err, ErrResultConflict) {
			t.Fatal("changed replay did not return typed conflict")
		}
	}
	wrongRun := c
	wrongRun.Lease.RunID, err = freshUUID()
	if err != nil {
		t.Fatal("binding fixture unavailable")
	}
	if _, err := reconnect.advanceInvestigation(testContext(t), wrongRun); !errors.Is(err, ErrResultConflict) {
		t.Fatal("accepted operation rebound to absent run did not return typed conflict")
	}
	otherOperation := validAdvance(t, admission, lease)
	if _, err := reconnect.advanceInvestigation(testContext(t), otherOperation); !errors.Is(err, ErrTransitionRejected) {
		t.Fatal("another operation acquired a completed task's receipt")
	}
	if _, err := reconnect.reconcileInvestigation(testContext(t), otherOperation); !errors.Is(err, ErrResultUnknown) {
		t.Fatal("absence was incorrectly treated as durable acceptance")
	}
	if snapshotFor(t, s, run) != before {
		t.Fatal("replay/conflict changed persisted bundle or transport accounting")
	}
	t.Log("RF-06: discarded application ACK recovered after reconnect and expiry; exact receipt IDs retained; tasks=2 attempts=2 transitions=2 outbox=2 receipts=1 budget=0")
}

func TestResultConcurrentSubmissions(t *testing.T) {
	for _, conflicting := range []bool{false, true} {
		t.Run(map[bool]string{false: "matching", true: "conflicting"}[conflicting], func(t *testing.T) {
			s := storeForTest(t)
			admission, run := admitForTest(t, s, "result-concurrent")
			lease, err := s.ClaimForRun(testContext(t), run.RunID, "synthetic-result-worker", 30)
			if err != nil {
				t.Fatal("concurrent fixture claim failed")
			}
			c := validAdvance(t, admission, lease)
			const submissions = 8
			begin := make(chan struct{})
			replies := make(chan resultReply, submissions)
			var wg sync.WaitGroup
			for i := 0; i < submissions; i++ {
				request := c
				if conflicting && i%2 == 1 {
					request.EvidenceRef += "-other"
				}
				wg.Add(1)
				go func() {
					defer wg.Done()
					<-begin
					r, err := s.advanceInvestigation(testContext(t), request)
					replies <- resultReply{r, err}
				}()
			}
			close(begin)
			wg.Wait()
			close(replies)
			accepted, conflicts := 0, 0
			var original advanceReceipt
			for response := range replies {
				if response.err == nil {
					if accepted > 0 && original != response.receipt {
						t.Fatal("matching submissions returned different durable receipts")
					}
					original = response.receipt
					accepted++
				} else if errors.Is(response.err, ErrResultConflict) {
					conflicts++
				} else {
					t.Fatal("unexpected concurrent result failure")
				}
			}
			if (!conflicting && (accepted != 8 || conflicts != 0)) || (conflicting && (accepted != 4 || conflicts != 4)) {
				t.Fatalf("accepted=%d conflicts=%d", accepted, conflicts)
			}
			checkAdvanced(t, s, run, original)
			persisted := snapshotFor(t, s, run)
			if persisted.firstTaskClaimant != lease.ClaimantID || persisted.firstTaskEpoch != lease.LeaseEpoch ||
				persisted.firstTaskDeliveries != lease.DeliveryCount || !persisted.firstTaskExpiry.Time.Equal(lease.ExpiresAt) {
				t.Fatal("concurrent result acceptance/replay changed lease transport metadata")
			}
			t.Logf("RF-06: accepted/replayed=%d conflicts=%d; exactly one receipt/ADVANCE/successor/outbox, no extra delivery or budget", accepted, conflicts)
		})
	}
}

func TestResultOperationIdentityAcrossRuns(t *testing.T) {
	s := storeForTest(t)
	var commands [2]investigationAdvance
	var runs [2]AdmissionReceipt
	var before [2]transitionSnapshot
	for i := range commands {
		admission, run := admitForTest(t, s, "result-global-operation")
		lease, err := s.ClaimForRun(testContext(t), run.RunID, "synthetic-global-operation", 30)
		if err != nil {
			t.Fatal("cross-run claim failed")
		}
		commands[i], runs[i] = validAdvance(t, admission, lease), run
		before[i] = snapshotFor(t, s, run)
	}
	commands[1].OperationID = commands[0].OperationID
	begin := make(chan struct{})
	var replies [2]resultReply
	var wg sync.WaitGroup
	for i := range commands {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-begin
			replies[i].receipt, replies[i].err = s.advanceInvestigation(testContext(t), commands[i])
		}()
	}
	close(begin)
	wg.Wait()
	accepted, conflicts := 0, 0
	for i, response := range replies {
		if response.err == nil {
			accepted++
			checkAdvanced(t, s, runs[i], response.receipt)
		} else if errors.Is(response.err, ErrResultConflict) {
			conflicts++
			if snapshotFor(t, s, runs[i]) != before[i] {
				t.Fatal("operation uniqueness race committed partial losing bundle")
			}
		} else {
			t.Fatal("unexpected cross-run uniqueness failure classification")
		}
	}
	if accepted != 1 || conflicts != 1 {
		t.Fatal("operation ID was not globally unique")
	}
	t.Log("RF-06: same global operation across distinct run locks: one accepted, one typed conflict; losing transaction fully rolled back")
}

func TestResultReceiptConstraintsAndPermissions(t *testing.T) {
	s := storeForTest(t)
	admission, run := admitForTest(t, s, "receipt-permissions")
	lease, err := s.ClaimForRun(testContext(t), run.RunID, "synthetic-receipt-worker", 30)
	if err != nil {
		t.Fatal("receipt fixture claim failed")
	}
	c := validAdvance(t, admission, lease)
	r, err := s.advanceInvestigation(testContext(t), c)
	if err != nil {
		t.Fatal("receipt fixture advance failed")
	}
	before := snapshotFor(t, s, run)
	var read, appendOnly, noRewrite bool
	err = s.db.QueryRowContext(testContext(t), `SELECT
        has_table_privilege(current_user,'orchestration.workflow_result_receipts','SELECT'),
        has_table_privilege(current_user,'orchestration.workflow_result_receipts','INSERT'),
        NOT has_table_privilege(current_user,'orchestration.workflow_result_receipts','UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')`).Scan(&read, &appendOnly, &noRewrite)
	if err != nil || !read || !appendOnly || !noRewrite {
		t.Fatal("receipt runtime privileges are incorrect")
	}
	for _, statement := range []string{
		`UPDATE orchestration.workflow_result_receipts SET payload_sha256=repeat('0',64)`,
		`DELETE FROM orchestration.workflow_result_receipts`,
		`TRUNCATE orchestration.workflow_result_receipts`,
	} {
		_, err := s.db.ExecContext(testContext(t), statement)
		var pgerr *pgconn.PgError
		if !errors.As(err, &pgerr) || pgerr.Code != "42501" {
			t.Fatal("receipt rewrite was not rejected by PostgreSQL permission boundary")
		}
	}
	// Exercise operation/task uniqueness through actual INSERT attempts.
	for _, sameOperation := range []bool{true, false} {
		op := r.OperationID
		if !sameOperation {
			op, err = freshUUID()
			if err != nil {
				t.Fatal("operation fixture unavailable")
			}
		}
		_, err = s.db.ExecContext(testContext(t), `INSERT INTO orchestration.workflow_result_receipts
            SELECT $1::uuid,digest_version,payload_sha256,run_id,task_id,attempt_id,claimant_id,lease_epoch,
            expected_run_version,policy_epoch,source_fingerprint_sha256,transition_id,next_task_id,
            next_attempt_id,outbox_message_id,accepted_run_version,accepted_at
            FROM orchestration.workflow_result_receipts WHERE operation_id=$2::uuid`, op, r.OperationID)
		var pgerr *pgconn.PgError
		expectedConstraint := "workflow_result_receipts_task_id_key"
		if sameOperation {
			expectedConstraint = "workflow_result_receipts_pkey"
		}
		if !errors.As(err, &pgerr) || pgerr.Code != "23505" || pgerr.ConstraintName != expectedConstraint {
			t.Fatal("receipt duplicate did not hit a durable uniqueness constraint")
		}
	}
	// All unique IDs are fresh, so an inconsistent old-task/run/attempt binding
	// must reach the FK check rather than being masked by a uniqueness failure.
	ids := make([]string, 6)
	for i := range ids {
		ids[i], err = freshUUID()
		if err != nil {
			t.Fatal("FK probe identity unavailable")
		}
	}
	_, err = s.db.ExecContext(testContext(t), `INSERT INTO orchestration.workflow_result_receipts
        SELECT $1::uuid,digest_version,payload_sha256,run_id,$2::uuid,attempt_id,claimant_id,lease_epoch,
        expected_run_version,policy_epoch,source_fingerprint_sha256,$3::uuid,$4::uuid,$5::uuid,$6::uuid,
        accepted_run_version,accepted_at FROM orchestration.workflow_result_receipts WHERE operation_id=$7::uuid`,
		ids[0], ids[1], ids[2], ids[3], ids[4], ids[5], r.OperationID)
	var bindingError *pgconn.PgError
	if !errors.As(err, &bindingError) || bindingError.Code != "23503" || bindingError.ConstraintName != "workflow_result_receipts_task_id_run_id_attempt_id_fkey" {
		t.Fatal("inconsistent receipt task binding did not hit the expected foreign key")
	}
	var foreignKeys int
	if err := s.db.QueryRowContext(testContext(t), `SELECT count(*) FROM pg_constraint
        WHERE conrelid='orchestration.workflow_result_receipts'::regclass AND contype='f'`).Scan(&foreignKeys); err != nil || foreignKeys != 5 {
		t.Fatal("receipt bundle binding foreign keys missing")
	}
	durable, err := queryResult(testContext(t), s.db, r.OperationID, r.PayloadSHA256)
	if err != nil || durable != r || snapshotFor(t, s, run) != before {
		t.Fatal("receipt permission/constraint probes mutated accepted state")
	}
	t.Log("receipt ACL: SELECT/INSERT only; UPDATE/DELETE/TRUNCATE denied 42501; duplicate INSERT denied 23505; five binding FKs installed; original receipt unchanged")
}

func TestResultReconciliationDeadline(t *testing.T) {
	s := storeForTest(t)
	admission, run := admitForTest(t, s, "result-reconcile-deadline")
	lease, err := s.ClaimForRun(testContext(t), run.RunID, "synthetic-result-worker", 30)
	if err != nil {
		t.Fatal("deadline fixture claim failed")
	}
	c := validAdvance(t, admission, lease)
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	start := time.Now()
	if _, err := s.reconcileInvestigation(ctx, c); !errors.Is(err, ErrResultUnknown) || !errors.Is(err, context.Canceled) {
		t.Fatal("reconciliation did not preserve cancellation and UNKNOWN")
	}
	if time.Since(start) > time.Second {
		t.Fatal("reconciliation ignored caller deadline")
	}
	if _, err := s.reconcileInvestigation(testContext(t), c); !errors.Is(err, ErrResultUnknown) || !errors.Is(err, sql.ErrNoRows) {
		t.Fatal("missing receipt incorrectly proved rollback or acceptance")
	}
	if z := snapshotFor(t, s, run); z.receipts != 0 || z.version != 1 || z.tasks != 1 || z.used != 0 {
		t.Fatal("reconciliation executed work")
	}
	// A real connected TCP peer that never answers PostgreSQL startup must not
	// outlive the caller's shorter deadline. It is local and logs no wire bytes.
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal("deadline transport fixture unavailable")
	}
	t.Cleanup(func() { _ = listener.Close() })
	accepted := make(chan net.Conn, 1)
	go func() { connection, _ := listener.Accept(); accepted <- connection }()
	u, err := url.Parse(s.databaseURL)
	if err != nil {
		t.Fatal("guarded deadline fixture URL unavailable")
	}
	u.Host = listener.Addr().String()
	stalled := &Store{databaseURL: u.String()}
	bounded, stop := context.WithTimeout(t.Context(), 100*time.Millisecond)
	defer stop()
	start = time.Now()
	_, err = stalled.reconcileInvestigation(bounded, c)
	if !errors.Is(err, ErrResultUnknown) || !errors.Is(err, context.DeadlineExceeded) || time.Since(start) > time.Second {
		t.Fatal("stalled reconciliation ignored caller deadline or lost UNKNOWN classification")
	}
	select {
	case connection := <-accepted:
		if connection != nil {
			_ = connection.Close()
		}
	case <-testContext(t).Done():
		t.Fatal("deadline transport did not accept the reconciliation connection")
	}
	// Missing evidence while a genuine transaction is still in flight cannot
	// prove rollback. Reconciliation reads only; the original operation proceeds.
	probe, pid := heartbeatProbe(t)
	blocker, blockerPID := holdHeartbeatRow(t, s, `SELECT attempt_id::text
        FROM orchestration.stage_attempts WHERE attempt_id=$1::uuid FOR UPDATE`, run.AttemptID)
	reply := startResult(t, probe, c)
	waitHeartbeatBlockedBy(t, s, pid, blockerPID)
	if _, err := s.reconcileInvestigation(testContext(t), c); !errors.Is(err, ErrResultUnknown) {
		t.Fatal("in-flight uncommitted result was falsely classified as accepted or rejected")
	}
	if z := snapshotFor(t, s, run); z.receipts != 0 || z.version != 1 || z.tasks != 1 {
		t.Fatal("reconciliation repeated in-flight work")
	}
	if err := blocker.Commit(); err != nil {
		t.Fatal("in-flight acceptance gate release failed")
	}
	actual := awaitResult(t, reply)
	if actual.err != nil {
		t.Fatal("original in-flight transition failed")
	}
	recovered, err := s.reconcileInvestigation(testContext(t), c)
	if err != nil || recovered != actual.receipt {
		t.Fatal("later reconciliation lost original in-flight receipt")
	}
	checkAdvanced(t, s, run, recovered)
	t.Log("RF-10: canceled lookup bounded by parent context; missing receipt remains UNKNOWN; no transition retried")
	t.Log("RF-10: stalled connected peer respected 100ms deadline; genuine in-flight result stayed UNKNOWN until original commit, then exact receipt recovered")
}
