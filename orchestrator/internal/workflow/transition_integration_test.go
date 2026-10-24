//go:build integration

package workflow

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"strings"
	"sync"
	"testing"
	"time"
)

func validAdvance(t *testing.T, c Admission, l TaskLease) investigationAdvance {
	t.Helper()
	prefix := os.Getenv("ASEP_VN_T10_TEST_PREFIX")
	if !strings.HasPrefix(prefix, "synthetic:vn-t10-it-") {
		t.Fatal("unsafe fixture prefix")
	}
	return investigationAdvance{
		Lease: l, ExpectedRunVersion: 1, ExpectedPolicyEpoch: c.PolicyEpoch,
		ExpectedSourceFingerprintSHA256: c.SourceFingerprintSHA256,
		EvidenceRef:                     prefix + ":trusted-investigation-fixture", BudgetDebit: 0,
	}
}

type transitionSnapshot struct {
	state                                string
	version                              int64
	used                                 int
	tasks, attempts, transitions, outbox int
	firstTaskStatus                      string
	firstAttemptState                    string
}

func snapshotFor(t *testing.T, s *Store, r AdmissionReceipt) transitionSnapshot {
	t.Helper()
	var z transitionSnapshot
	err := s.db.QueryRow(`SELECT state,run_version,budget_used FROM orchestration.workflow_runs WHERE run_id=$1::uuid`, r.RunID).Scan(&z.state, &z.version, &z.used)
	if err != nil {
		t.Fatal(err)
	}
	queries := []struct {
		table  string
		target *int
	}{
		{"task_deliveries", &z.tasks}, {"stage_attempts", &z.attempts},
		{"workflow_transitions", &z.transitions}, {"workflow_outbox", &z.outbox},
	}
	for _, q := range queries {
		// Identifiers are hardcoded, never provided by candidate/untrusted input.
		err = s.db.QueryRow(`SELECT count(*) FROM orchestration.`+q.table+` WHERE run_id=$1::uuid`, r.RunID).Scan(q.target)
		if err != nil {
			t.Fatal(err)
		}
	}
	err = s.db.QueryRow(`SELECT status FROM orchestration.task_deliveries WHERE task_id=$1::uuid`, r.TaskID).Scan(&z.firstTaskStatus)
	if err != nil {
		t.Fatal(err)
	}
	err = s.db.QueryRow(`SELECT state FROM orchestration.stage_attempts WHERE attempt_id=$1::uuid`, r.AttemptID).Scan(&z.firstAttemptState)
	if err != nil {
		t.Fatal(err)
	}
	return z
}

func checkAdvanced(t *testing.T, s *Store, r AdmissionReceipt, receipt advanceReceipt) {
	t.Helper()
	z := snapshotFor(t, s, r)
	if z.state != "PLANNING" || z.version != 2 || z.used != 0 ||
		z.tasks != 2 || z.attempts != 2 || z.transitions != 2 || z.outbox != 2 ||
		z.firstTaskStatus != "COMPLETED" || z.firstAttemptState != "SUCCEEDED" {
		t.Fatalf("invalid atomic successor snapshot: %+v", z)
	}
	var nextStatus, nextAttemptState string
	err := s.db.QueryRow(`SELECT status FROM orchestration.task_deliveries WHERE task_id=$1::uuid`, receipt.NextTaskID).Scan(&nextStatus)
	if err != nil || nextStatus != "QUEUED" {
		t.Fatalf("next task: status=%s err=%v", nextStatus, err)
	}
	err = s.db.QueryRow(`SELECT state FROM orchestration.stage_attempts WHERE attempt_id=$1::uuid`, receipt.NextAttemptID).Scan(&nextAttemptState)
	if err != nil || nextAttemptState != "PENDING" {
		t.Fatalf("next attempt: state=%s err=%v", nextAttemptState, err)
	}
	var eventRaw []byte
	var sha string
	err = s.db.QueryRow(`SELECT payload,payload_sha256 FROM orchestration.workflow_outbox WHERE message_id=$1::uuid`, receipt.OutboxMessageID).Scan(&eventRaw, &sha)
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
	err = s.db.QueryRow(`SELECT count(*) FROM orchestration.task_deliveries WHERE run_id=$1::uuid AND status IN ('QUEUED','LEASED')`, r.RunID).Scan(&pending)
	if err != nil || pending != 1 {
		t.Fatalf("outstanding tasks=%d err=%v", pending, err)
	}
}

func TestAdvanceInvestigationAtomicSuccessor(t *testing.T) {
	s := storeForTest(t)
	c, r := admitForTest(t, s, "advance-success")
	lease, err := s.ClaimForRun(context.Background(), r.RunID, "synthetic-stage-worker", 30)
	if err != nil {
		t.Fatal(err)
	}
	receipt, err := s.advanceInvestigation(context.Background(), validAdvance(t, c, lease))
	if err != nil {
		t.Fatalf("advance investigation: %v", err)
	}
	if receipt.RunID != r.RunID || receipt.RunVersion != 2 || receipt.NextTaskID == "" {
		t.Fatalf("invalid receipt: %+v", receipt)
	}
	checkAdvanced(t, s, r, receipt)
	if _, err = s.Heartbeat(context.Background(), lease, 10); !errors.Is(err, ErrLeaseLost) {
		t.Fatalf("completed lease still valid: %v", err)
	}
	next, err := s.ClaimForRun(context.Background(), r.RunID, "synthetic-planner", 30)
	if err != nil || next.Stage != "PLANNING" || next.TaskID != receipt.NextTaskID {
		t.Fatalf("next task cannot be claimed: %+v %v", next, err)
	}
}

func TestAdvanceInvestigationRejectsStaleAndTamperedAuthority(t *testing.T) {
	s := storeForTest(t)
	c, r := admitForTest(t, s, "advance-rejections")
	lease, err := s.ClaimForRun(context.Background(), r.RunID, "synthetic-stage-worker", 30)
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
			_, err := s.advanceInvestigation(context.Background(), altered)
			if !errors.Is(err, ErrLeaseLost) && !errors.Is(err, ErrTransitionRejected) {
				t.Fatalf("unexpected rejection: %v", err)
			}
			after := snapshotFor(t, s, r)
			if after != before {
				t.Fatalf("rejected result changed DB: before=%+v after=%+v", before, after)
			}
		})
	}
	receipt, err := s.advanceInvestigation(context.Background(), valid)
	if err != nil {
		t.Fatalf("valid attempt failed after rejected forgeries: %v", err)
	}
	checkAdvanced(t, s, r, receipt)
	if _, err = s.advanceInvestigation(context.Background(), valid); !errors.Is(err, ErrTransitionRejected) {
		t.Fatalf("old replay should be fenced: %v", err)
	}
	checkAdvanced(t, s, r, receipt)
}

func TestAdvanceInvestigationExpiredLeaseFailsClosed(t *testing.T) {
	s := storeForTest(t)
	c, r := admitForTest(t, s, "advance-expired")
	old, err := s.ClaimForRun(context.Background(), r.RunID, "synthetic-old-worker", 1)
	if err != nil {
		t.Fatal(err)
	}
	time.Sleep(1300 * time.Millisecond)
	_, err = s.advanceInvestigation(context.Background(), validAdvance(t, c, old))
	if !errors.Is(err, ErrLeaseLost) {
		t.Fatalf("expired result accepted or unknown error: %v", err)
	}
	before := snapshotFor(t, s, r)
	if before.state != "INVESTIGATING" || before.version != 1 || before.used != 0 || before.tasks != 1 || before.outbox != 1 {
		t.Fatalf("expired result changed state: %+v", before)
	}
	replacement, err := s.ClaimForRun(context.Background(), r.RunID, "synthetic-new-worker", 30)
	if err != nil {
		t.Fatal(err)
	}
	if replacement.LeaseEpoch != old.LeaseEpoch+1 || replacement.AttemptID != old.AttemptID {
		t.Fatalf("reclaim created new semantic attempt: %+v %+v", old, replacement)
	}
	receipt, err := s.advanceInvestigation(context.Background(), validAdvance(t, c, replacement))
	if err != nil {
		t.Fatal(err)
	}
	checkAdvanced(t, s, r, receipt)
}

func TestAdvanceInvestigationCompetingWorkersSingleTransition(t *testing.T) {
	s := storeForTest(t)
	c, r := admitForTest(t, s, "advance-concurrent")
	lease, err := s.ClaimForRun(context.Background(), r.RunID, "synthetic-worker", 30)
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
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-begin
			ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
			defer cancel()
			result, err := s.advanceInvestigation(ctx, cmd)
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
	lease, err := s.ClaimForRun(context.Background(), r.RunID, "synthetic-worker", 30)
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
	_, err = s.db.Exec(`INSERT INTO orchestration.stage_attempts(attempt_id,run_id,stage,semantic_attempt_no)
        VALUES($1::uuid,$2::uuid,'PLANNING',1)`, poisonID, r.RunID)
	if err != nil {
		t.Fatal(err)
	}
	before := snapshotFor(t, s, r)
	_, err = s.advanceInvestigation(context.Background(), validAdvance(t, c, lease))
	if err == nil {
		t.Fatal("constraint failure did not abort the transition")
	}
	after := snapshotFor(t, s, r)
	if before != after {
		t.Fatalf("partial commit after SQL error: before=%+v after=%+v error=%v", before, after, err)
	}
}

func TestAdvanceInvestigationPersistsAcrossNewConnection(t *testing.T) {
	s := storeForTest(t)
	c, r := admitForTest(t, s, "advance-reconnect")
	lease, err := s.ClaimForRun(context.Background(), r.RunID, "synthetic-worker", 30)
	if err != nil {
		t.Fatal(err)
	}
	receipt, err := s.advanceInvestigation(context.Background(), validAdvance(t, c, lease))
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
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
