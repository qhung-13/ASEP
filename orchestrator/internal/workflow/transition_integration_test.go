//go:build integration

package workflow

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgconn"
)

func validAdvance(t *testing.T, c Admission, l TaskLease) investigationAdvance {
	t.Helper()
	prefix := os.Getenv("ASEP_VN_T10_TEST_PREFIX")
	if !strings.HasPrefix(prefix, "synthetic:vn-t10-it-") {
		t.Fatal("unsafe fixture prefix")
	}
	operationID, err := freshUUID()
	if err != nil {
		t.Fatal("operation identity unavailable")
	}
	return investigationAdvance{
		OperationID: operationID, Lease: l, ExpectedRunVersion: 1, ExpectedPolicyEpoch: c.PolicyEpoch,
		ExpectedSourceFingerprintSHA256: c.SourceFingerprintSHA256,
		EvidenceRef:                     prefix + ":trusted-investigation-fixture", BudgetDebit: 0,
	}
}

type transitionSnapshot struct {
	state                                                       string
	version                                                     int64
	used, limit                                                 int
	tasks, attempts, transitions, outbox, outstanding, receipts int
	firstTaskStatus, firstTaskClaimant, firstTaskStage          string
	firstTaskEpoch                                              int64
	firstTaskDeliveries                                         int
	firstTaskExpiry                                             sql.NullTime
	firstAttemptState                                           string
	firstAttemptNo                                              int
}

func snapshotFor(t *testing.T, s *Store, r AdmissionReceipt) transitionSnapshot {
	t.Helper()
	var z transitionSnapshot
	// A single PostgreSQL statement observes one MVCC snapshot, even if another
	// controlled writer commits while the test is collecting evidence.
	err := s.db.QueryRowContext(testContext(t), `
        SELECT r.state,r.run_version,r.budget_used,r.budget_limit,
          (SELECT count(*) FROM orchestration.task_deliveries WHERE run_id=r.run_id),
          (SELECT count(*) FROM orchestration.stage_attempts WHERE run_id=r.run_id),
          (SELECT count(*) FROM orchestration.workflow_transitions WHERE run_id=r.run_id),
          (SELECT count(*) FROM orchestration.workflow_outbox WHERE run_id=r.run_id),
          (SELECT count(*) FROM orchestration.task_deliveries WHERE run_id=r.run_id AND status IN ('QUEUED','LEASED')),
          (SELECT count(*) FROM orchestration.workflow_result_receipts WHERE run_id=r.run_id),
          t.status,coalesce(t.claimant_id,''),t.stage,t.lease_epoch,t.delivery_count,t.lease_expires_at,
          a.state,a.semantic_attempt_no
        FROM orchestration.workflow_runs r
        JOIN orchestration.task_deliveries t ON t.run_id=r.run_id AND t.task_id=$2::uuid
        JOIN orchestration.stage_attempts a ON a.run_id=r.run_id AND a.attempt_id=$3::uuid
        WHERE r.run_id=$1::uuid`, r.RunID, r.TaskID, r.AttemptID).Scan(
		&z.state, &z.version, &z.used, &z.limit, &z.tasks, &z.attempts, &z.transitions, &z.outbox, &z.outstanding, &z.receipts,
		&z.firstTaskStatus, &z.firstTaskClaimant, &z.firstTaskStage, &z.firstTaskEpoch, &z.firstTaskDeliveries,
		&z.firstTaskExpiry, &z.firstAttemptState, &z.firstAttemptNo)
	if err != nil {
		t.Fatal("consistent workflow evidence snapshot unavailable")
	}
	return z
}

func checkAdvanced(t *testing.T, s *Store, r AdmissionReceipt, receipt advanceReceipt) {
	t.Helper()
	z := snapshotFor(t, s, r)
	if z.state != "PLANNING" || z.version != 2 || z.used != 0 ||
		z.tasks != 2 || z.attempts != 2 || z.transitions != 2 || z.outbox != 2 ||
		z.firstTaskStatus != "COMPLETED" || z.firstAttemptState != "SUCCEEDED" ||
		z.firstAttemptNo != 1 || z.outstanding != 1 || z.receipts != 1 || z.firstTaskEpoch < 1 || z.firstTaskClaimant == "" {
		t.Fatalf("invalid atomic successor snapshot: %+v", z)
	}
	durable, err := queryResult(testContext(t), s.db, receipt.OperationID, receipt.PayloadSHA256)
	if err != nil || durable != receipt || receipt.PreviousTaskID != r.TaskID || receipt.PreviousAttemptID != r.AttemptID ||
		receipt.AcceptedAt.IsZero() || !receipt.AcceptedAt.Before(z.firstTaskExpiry.Time) {
		t.Fatal("durable result receipt differs from accepted bundle or acceptance time")
	}
	var nextStatus, nextAttemptState string
	err = s.db.QueryRowContext(testContext(t), `SELECT status FROM orchestration.task_deliveries WHERE task_id=$1::uuid`, receipt.NextTaskID).Scan(&nextStatus)
	if err != nil || nextStatus != "QUEUED" {
		t.Fatalf("next task: status=%s err=%v", nextStatus, err)
	}
	err = s.db.QueryRowContext(testContext(t), `SELECT state FROM orchestration.stage_attempts WHERE attempt_id=$1::uuid`, receipt.NextAttemptID).Scan(&nextAttemptState)
	if err != nil || nextAttemptState != "PENDING" {
		t.Fatalf("next attempt: state=%s err=%v", nextAttemptState, err)
	}
	var eventRaw []byte
	var sha string
	err = s.db.QueryRowContext(testContext(t), `SELECT payload,payload_sha256 FROM orchestration.workflow_outbox WHERE message_id=$1::uuid`, receipt.OutboxMessageID).Scan(&eventRaw, &sha)
	if err != nil {
		t.Fatal(err)
	}
	var event struct {
		RunID       string `json:"run_id"`
		AttemptID   string `json:"attempt_id"`
		TaskID      string `json:"task_id"`
		Stage       string `json:"stage"`
		RunVersion  int64  `json:"run_version"`
		EvidenceRef string `json:"evidence_ref"`
	}
	if err = json.Unmarshal(eventRaw, &event); err != nil {
		t.Fatal(err)
	}
	if event.RunID != r.RunID || event.AttemptID != receipt.NextAttemptID ||
		event.TaskID != receipt.NextTaskID || event.Stage != "PLANNING" ||
		event.RunVersion != 2 || !strings.HasPrefix(event.EvidenceRef, "synthetic:vn-t10-it-") {
		t.Fatalf("unexpected outbox event: %+v", event)
	}
	reserialized, err := json.Marshal(event)
	if err != nil {
		t.Fatal(err)
	}
	digest := sha256.Sum256(reserialized)
	if sha != hex.EncodeToString(digest[:]) {
		t.Fatalf("outbox logical JSON digest mismatch")
	}
	var pending int
	err = s.db.QueryRowContext(testContext(t), `SELECT count(*) FROM orchestration.task_deliveries WHERE run_id=$1::uuid AND status IN ('QUEUED','LEASED')`, r.RunID).Scan(&pending)
	if err != nil || pending != 1 {
		t.Fatalf("outstanding tasks=%d err=%v", pending, err)
	}
}

func TestAdvanceInvestigationAtomicSuccessor(t *testing.T) {
	s := storeForTest(t)
	c, r := admitForTest(t, s, "advance-success")
	lease, err := s.ClaimForRun(testContext(t), r.RunID, "synthetic-stage-worker", 30)
	if err != nil {
		t.Fatal(err)
	}
	receipt, err := s.advanceInvestigation(testContext(t), validAdvance(t, c, lease))
	if err != nil {
		t.Fatalf("advance investigation: %v", err)
	}
	if receipt.RunID != r.RunID || receipt.RunVersion != 2 || receipt.NextTaskID == "" {
		t.Fatalf("invalid receipt: %+v", receipt)
	}
	checkAdvanced(t, s, r, receipt)
	if _, err = s.Heartbeat(testContext(t), lease, 10); !errors.Is(err, ErrLeaseLost) {
		t.Fatalf("completed lease still valid: %v", err)
	}
	next, err := s.ClaimForRun(testContext(t), r.RunID, "synthetic-planner", 30)
	if err != nil || next.Stage != "PLANNING" || next.TaskID != receipt.NextTaskID {
		t.Fatalf("next task cannot be claimed: %+v %v", next, err)
	}
}

func TestAdvanceInvestigationRejectsStaleAndTamperedAuthority(t *testing.T) {
	s := storeForTest(t)
	c, r := admitForTest(t, s, "advance-rejections")
	lease, err := s.ClaimForRun(testContext(t), r.RunID, "synthetic-stage-worker", 30)
	if err != nil {
		t.Fatal(err)
	}
	valid := validAdvance(t, c, lease)
	before := snapshotFor(t, s, r)
	randomID, err := freshUUID()
	if err != nil {
		t.Fatal(err)
	}
	cases := []struct {
		name   string
		change func(*investigationAdvance)
	}{
		{"wrong-worker", func(a *investigationAdvance) { a.Lease.ClaimantID = "synthetic-other-worker" }},
		{"wrong-lease-epoch", func(a *investigationAdvance) { a.Lease.LeaseEpoch++ }},
		{"wrong-task-id", func(a *investigationAdvance) { a.Lease.TaskID = randomID }},
		{"wrong-attempt-id", func(a *investigationAdvance) { a.Lease.AttemptID = randomID }},
		{"stale-run-version", func(a *investigationAdvance) { a.ExpectedRunVersion++ }},
		{"stale-policy-epoch", func(a *investigationAdvance) { a.ExpectedPolicyEpoch++ }},
		{"wrong-source-fingerprint", func(a *investigationAdvance) { a.ExpectedSourceFingerprintSHA256 = strings.Repeat("c", 64) }},
		{"untrusted-evidence", func(a *investigationAdvance) { a.EvidenceRef = "agent-claims-PASS" }},
		{"forged-rework-budget-debit", func(a *investigationAdvance) { a.BudgetDebit = 1 }},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			altered := valid
			tc.change(&altered)
			_, err := s.advanceInvestigation(testContext(t), altered)
			if !errors.Is(err, ErrLeaseLost) && !errors.Is(err, ErrTransitionRejected) {
				t.Fatalf("unexpected rejection: %v", err)
			}
			after := snapshotFor(t, s, r)
			if after != before {
				t.Fatalf("rejected result changed DB: before=%+v after=%+v", before, after)
			}
		})
	}
	receipt, err := s.advanceInvestigation(testContext(t), valid)
	if err != nil {
		t.Fatalf("valid attempt failed after rejected forgeries: %v", err)
	}
	checkAdvanced(t, s, r, receipt)
	if replay, err := s.advanceInvestigation(testContext(t), valid); err != nil || replay != receipt {
		t.Fatal("exact replay did not return the original durable receipt")
	}
	checkAdvanced(t, s, r, receipt)
}

func TestAdvanceInvestigationExpiredLeaseFailsClosed(t *testing.T) {
	s := storeForTest(t)
	c, r := admitForTest(t, s, "advance-expired")
	old, err := s.ClaimForRun(testContext(t), r.RunID, "synthetic-old-worker", 1)
	if err != nil {
		t.Fatal(err)
	}
	waitForLeaseExpiry(t, s, r.TaskID)
	_, err = s.advanceInvestigation(testContext(t), validAdvance(t, c, old))
	if !errors.Is(err, ErrLeaseLost) {
		t.Fatalf("expired result accepted or unknown error: %v", err)
	}
	before := snapshotFor(t, s, r)
	if before.state != "INVESTIGATING" || before.version != 1 || before.used != 0 || before.tasks != 1 || before.outbox != 1 {
		t.Fatalf("expired result changed state: %+v", before)
	}
	replacement, err := s.ClaimForRun(testContext(t), r.RunID, "synthetic-new-worker", 30)
	if err != nil {
		t.Fatal(err)
	}
	if replacement.LeaseEpoch != old.LeaseEpoch+1 || replacement.AttemptID != old.AttemptID {
		t.Fatalf("reclaim created new semantic attempt: %+v %+v", old, replacement)
	}
	afterReclaim := snapshotFor(t, s, r)
	if _, err = s.Heartbeat(testContext(t), old, 30); !errors.Is(err, ErrLeaseLost) {
		t.Fatalf("RF-05: stale heartbeat accepted after actual reclaim: %v", err)
	}
	if _, err = s.advanceInvestigation(testContext(t), validAdvance(t, c, old)); !errors.Is(err, ErrLeaseLost) {
		t.Fatalf("RF-05: stale result accepted after actual reclaim: %v", err)
	}
	if after := snapshotFor(t, s, r); after != afterReclaim {
		t.Fatalf("RF-05: rejected old owner changed persisted state: before=%+v after=%+v", afterReclaim, after)
	}
	receipt, err := s.advanceInvestigation(testContext(t), validAdvance(t, c, replacement))
	if err != nil {
		t.Fatal(err)
	}
	checkAdvanced(t, s, r, receipt)
	accepted := snapshotFor(t, s, r)
	if _, err = s.advanceInvestigation(testContext(t), validAdvance(t, c, replacement)); !errors.Is(err, ErrTransitionRejected) {
		t.Fatal("RF-05: duplicate valid result was not fenced")
	}
	if after := snapshotFor(t, s, r); after != accepted {
		t.Fatal("RF-05: duplicate result created another durable outcome")
	}
	t.Logf("RF-05 observed after expiry/reclaim: epoch=%d deliveries=%d semantic_attempt=%d budget=%d version=%d tasks=%d transitions=%d outbox=%d; stale submissions unchanged", accepted.firstTaskEpoch, accepted.firstTaskDeliveries, accepted.firstAttemptNo, accepted.used, accepted.version, accepted.tasks, accepted.transitions, accepted.outbox)
}

func TestAdvanceInvestigationCompetingWorkersSingleTransition(t *testing.T) {
	s := storeForTest(t)
	c, r := admitForTest(t, s, "advance-concurrent")
	lease, err := s.ClaimForRun(testContext(t), r.RunID, "synthetic-worker", 30)
	if err != nil {
		t.Fatal(err)
	}
	cmd := validAdvance(t, c, lease)
	const workers = 12
	type reply struct {
		receipt advanceReceipt
		err     error
	}
	ch := make(chan reply, workers)
	begin := make(chan struct{})
	var wg sync.WaitGroup
	for i := 0; i < workers; i++ {
		// Distinct logical operations compete for one completion. Matching
		// operation replay is covered separately by the B2 result tests.
		request := cmd
		request.OperationID, err = freshUUID()
		if err != nil {
			t.Fatal("competing operation identity unavailable")
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-begin
			ctx, cancel := context.WithTimeout(t.Context(), 15*time.Second)
			defer cancel()
			result, err := s.advanceInvestigation(ctx, request)
			ch <- reply{result, err}
		}()
	}
	close(begin)
	wg.Wait()
	close(ch)
	winners := 0
	var winner advanceReceipt
	for response := range ch {
		if response.err == nil {
			winners++
			winner = response.receipt
		} else if !errors.Is(response.err, ErrTransitionRejected) {
			t.Errorf("unrecognized contention outcome: %v", response.err)
		}
	}
	if winners != 1 {
		t.Fatalf("expected exactly one valid transition, got %d", winners)
	}
	checkAdvanced(t, s, r, winner)
}

func TestAdvanceInvestigationRollsBackOnLateConstraintFailure(t *testing.T) {
	s := storeForTest(t)
	c, r := admitForTest(t, s, "advance-rollback")
	lease, err := s.ClaimForRun(testContext(t), r.RunID, "synthetic-worker", 30)
	if err != nil {
		t.Fatal(err)
	}
	// Poison an otherwise synthetic run with a conflicting PLANNING attempt.
	// The next-attempt INSERT must fail after earlier task/run UPDATEs, and
	// the transaction must roll those changes back. This simulates a late
	// database constraint failure; it is NOT a normal state of production runs.
	poisonID, err := freshUUID()
	if err != nil {
		t.Fatal(err)
	}
	_, err = s.db.ExecContext(testContext(t), `INSERT INTO orchestration.stage_attempts(attempt_id,run_id,stage,semantic_attempt_no)
        VALUES($1::uuid,$2::uuid,'PLANNING',1)`, poisonID, r.RunID)
	if err != nil {
		t.Fatal(err)
	}
	before := snapshotFor(t, s, r)
	_, err = s.advanceInvestigation(testContext(t), validAdvance(t, c, lease))
	var pgerr *pgconn.PgError
	if !errors.As(err, &pgerr) || pgerr.Code != "23505" || errors.Is(err, ErrResultConflict) || errors.Is(err, ErrResultUnknown) {
		t.Fatal("unrelated late constraint failure lost its database error classification")
	}
	after := snapshotFor(t, s, r)
	if before != after {
		t.Fatalf("partial commit after SQL error: before=%+v after=%+v error=%v", before, after, err)
	}
}

func TestAdvanceInvestigationPersistsAcrossNewConnection(t *testing.T) {
	s := storeForTest(t)
	c, r := admitForTest(t, s, "advance-reconnect")
	lease, err := s.ClaimForRun(testContext(t), r.RunID, "synthetic-worker", 30)
	if err != nil {
		t.Fatal(err)
	}
	receipt, err := s.advanceInvestigation(testContext(t), validAdvance(t, c, lease))
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
	defer cancel()
	reconnect, err := Open(ctx, os.Getenv("ASEP_ORCHESTRATOR_DATABASE_URL"))
	if err != nil {
		t.Fatal(err)
	}
	defer reconnect.Close()
	checkAdvanced(t, reconnect, r, receipt)
	newLease, err := reconnect.ClaimForRun(ctx, r.RunID, "synthetic-worker-after-reconnect", 30)
	if err != nil || newLease.TaskID != receipt.NextTaskID || newLease.Stage != "PLANNING" {
		t.Fatalf("reconnect lost next task: %+v err=%v", newLease, err)
	}
}
