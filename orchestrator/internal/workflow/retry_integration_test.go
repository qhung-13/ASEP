//go:build integration

package workflow

import (
	"errors"
	"strings"
	"sync"
	"testing"

	"github.com/jackc/pgx/v5/pgconn"
)

func boundedRun(t *testing.T, p syntheticProfile, budget int) (*Store, Admission, AdmissionReceipt) {
	t.Helper()
	s := storeForTest(t)
	a := admissionForTest(t, "bounded-retry")
	a.BudgetLimit = budget
	r, err := s.admitBounded(testContext(t), a, p)
	if err != nil {
		t.Fatal("bounded admission failed", err)
	}
	return s, a, r
}

func retryForTest(t *testing.T, a Admission, l TaskLease, version int64, kind string) retryDecision {
	t.Helper()
	id, err := freshUUID()
	if err != nil {
		t.Fatal(err)
	}
	return retryDecision{OperationID: id, Kind: kind, Lease: l, ExpectedRunVersion: version, PolicyEpoch: a.PolicyEpoch, SourceSHA256: a.SourceFingerprintSHA256, EvidenceRef: strings.Split(a.SubjectKey, ":bounded-retry:")[0] + ":gate-" + id}
}

func attestRetry(t *testing.T, s *Store, c retryDecision) {
	t.Helper()
	if err := s.authorizeSyntheticRetry(testContext(t), c); err != nil {
		t.Fatal("trusted fixture attestation failed", err)
	}
}

func planningRetry(t *testing.T, p syntheticProfile, budget int) (*Store, Admission, AdmissionReceipt, retryDecision) {
	t.Helper()
	s, a, run := boundedRun(t, p, budget)
	l, err := s.ClaimForRun(testContext(t), run.RunID, "synthetic-retry-worker", 60)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.advanceInvestigation(testContext(t), validAdvance(t, a, l)); err != nil {
		t.Fatal(err)
	}
	l, err = s.ClaimForRun(testContext(t), run.RunID, "synthetic-planner", 60)
	if err != nil {
		t.Fatal(err)
	}
	return s, a, run, retryForTest(t, a, l, 2, "REWORK")
}

func TestRetryBoundedInfrastructure(t *testing.T) {
	s, a, run := boundedRun(t, syntheticProfile{2, 1, 1, true}, 2)
	first, err := s.ClaimForRun(testContext(t), run.RunID, "synthetic-first", 1)
	if err != nil {
		t.Fatal(err)
	}
	waitForLeaseExpiry(t, s, run.TaskID)
	restarted := storeForTest(t)
	second, err := restarted.ClaimForRun(testContext(t), run.RunID, "synthetic-restart", 1)
	if err != nil {
		t.Fatal(err)
	}
	if second.TaskID != first.TaskID || second.AttemptID != first.AttemptID || second.LeaseEpoch != first.LeaseEpoch+1 || second.DeliveryCount != 2 {
		t.Fatal("infra reclaim changed semantic identity")
	}
	var used, number int
	if err := s.db.QueryRowContext(testContext(t), `SELECT r.budget_used,a.semantic_attempt_no FROM orchestration.workflow_runs r JOIN orchestration.stage_attempts a USING(run_id) WHERE a.attempt_id=$1::uuid`, first.AttemptID).Scan(&used, &number); err != nil || used != 0 || number != 1 {
		t.Fatal("infra delivery consumed semantic budget")
	}
	waitForLeaseExpiry(t, s, run.TaskID)
	if _, err := s.ClaimForRun(testContext(t), run.RunID, "synthetic-over-limit", 60); !errors.Is(err, ErrNoTask) {
		t.Fatal("finite delivery bound bypassed", err)
	}
	stale := retryForTest(t, a, first, 1, "EXHAUST")
	attestRetry(t, s, stale)
	before := snapshotFor(t, s, run)
	if _, err := s.decideRetry(testContext(t), stale); !errors.Is(err, ErrLeaseLost) || snapshotFor(t, s, run) != before {
		t.Fatal("old epoch exhausted current owner", err)
	}
	c := retryForTest(t, a, second, 1, "EXHAUST")
	attestRetry(t, s, c)
	r, err := s.decideRetry(testContext(t), c)
	if err != nil {
		t.Fatal("authorized exhaustion failed", err)
	}
	after := snapshotFor(t, s, run)
	if after.state != "BLOCKED" || after.firstTaskStatus != "EXHAUSTED" || after.version != 2 || after.tasks != before.tasks || after.outbox != before.outbox {
		t.Fatal("exhaustion created work or failed atomic blocking")
	}
	got, err := restarted.reconcileRetry(testContext(t), c)
	if err != nil || got != r {
		t.Fatal("exhaustion receipt not recovered")
	}
	if _, err := s.Heartbeat(testContext(t), second, 60); !errors.Is(err, ErrLeaseLost) {
		t.Fatal("exhausted authority renewed")
	}
	t.Log("RF-08 delivery_count=2 epoch=2 semantic_attempt=1 budget=0; explicit expired exhaustion BLOCKED; reconnect retained original receipt")
}

func TestRetryAdmissionProfileIdentity(t *testing.T) {
	p := syntheticProfile{2, 1, 1, true}
	s, a, run := boundedRun(t, p, 2)
	replay, err := s.admitBounded(testContext(t), a, p)
	if err != nil || replay.RunID != run.RunID || !replay.Replayed {
		t.Fatal("bounded admission replay changed identity", err)
	}
	p.DeliveryLimit++
	if _, err := s.admitBounded(testContext(t), a, p); !errors.Is(err, ErrResultConflict) {
		t.Fatal("same command changed immutable profile", err)
	}
	if err := s.revokeSyntheticPolicy(testContext(t), run.RunID, 1); err != nil {
		t.Fatal(err)
	}
	if _, err := s.ClaimForRun(testContext(t), run.RunID, "synthetic-denied", 60); !errors.Is(err, ErrTransitionRejected) {
		t.Fatal("revoked bounded claim was authorized", err)
	}
}

func TestRetryConcurrentDecisions(t *testing.T) {
	s, a, run, first := planningRetry(t, syntheticProfile{2, 2, 2, true}, 1)
	commands := []retryDecision{first, retryForTest(t, a, first.Lease, 2, "REPLAN")}
	for _, c := range commands {
		attestRetry(t, s, c)
	}
	results := make(chan error, 2)
	var wg sync.WaitGroup
	for _, c := range commands {
		wg.Go(func() { _, err := s.decideRetry(testContext(t), c); results <- err })
	}
	wg.Wait()
	close(results)
	accepted := 0
	for err := range results {
		if err == nil {
			accepted++
		} else if !errors.Is(err, ErrTransitionRejected) {
			t.Fatal("unexpected competing decision error", err)
		}
	}
	after := snapshotFor(t, s, run)
	if accepted != 1 || after.used != 1 || after.version != 3 || after.tasks != 3 || after.outbox != 3 {
		t.Fatal("competing correction duplicated progress/budget")
	}
	lease, err := s.ClaimForRun(testContext(t), run.RunID, "synthetic-current", 60)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.revokeSyntheticPolicy(testContext(t), run.RunID, 1); err != nil {
		t.Fatal(err)
	}
	before := snapshotFor(t, s, run)
	if _, err := s.Heartbeat(testContext(t), lease, 60); !errors.Is(err, ErrTransitionRejected) || snapshotFor(t, s, run) != before {
		t.Fatal("revoked profile retained heartbeat authority", err)
	}
}

func TestRetrySemanticBudgetRecovery(t *testing.T) {
	s, a, run, c := planningRetry(t, syntheticProfile{2, 1, 1, true}, 2)
	before := snapshotFor(t, s, run)
	if _, err := s.decideRetry(testContext(t), c); !errors.Is(err, ErrTransitionRejected) || snapshotFor(t, s, run) != before {
		t.Fatal("worker-supplied evidence ref authorized rework")
	}
	attestRetry(t, s, c)
	var wg sync.WaitGroup
	replies := make(chan retryReceipt, 8)
	failures := make(chan error, 8)
	for range 8 {
		wg.Go(func() { r, err := s.decideRetry(testContext(t), c); replies <- r; failures <- err })
	}
	wg.Wait()
	close(replies)
	close(failures)
	for err := range failures {
		if err != nil {
			t.Fatal("same-key concurrent replay failed", err)
		}
	}
	var first retryReceipt
	for r := range replies {
		if first.OperationID == "" {
			first = r
		}
		if r != first {
			t.Fatal("concurrent replay changed receipt")
		}
	}
	accepted := snapshotFor(t, s, run)
	conflict := c
	conflict.Kind = "REPLAN"
	if _, err := s.decideRetry(testContext(t), conflict); !errors.Is(err, ErrResultConflict) {
		t.Fatal("conflicting payload reused operation")
	}
	old, err := storeForTest(t).reconcileRetry(testContext(t), c)
	if err != nil || old != first || snapshotFor(t, s, run) != accepted {
		t.Fatal("restart lost correction identity")
	}
	l, err := s.ClaimForRun(testContext(t), run.RunID, "synthetic-replanner", 60)
	if err != nil {
		t.Fatal(err)
	}
	next := retryForTest(t, a, l, 3, "REPLAN")
	attestRetry(t, s, next)
	if _, err := s.decideRetry(testContext(t), next); err != nil {
		t.Fatal("authorized synthetic replanning failed", err)
	}
	l, err = s.ClaimForRun(testContext(t), run.RunID, "synthetic-no-budget", 60)
	if err != nil {
		t.Fatal(err)
	}
	for _, kind := range []string{"REWORK", "REPLAN"} {
		c := retryForTest(t, a, l, 4, kind)
		attestRetry(t, s, c)
		before := snapshotFor(t, s, run)
		if _, err := s.decideRetry(testContext(t), c); !errors.Is(err, ErrTransitionRejected) || snapshotFor(t, s, run) != before {
			t.Fatal("exhausted budget created semantic progress", err)
		}
	}
	var used, number, count int
	if err := s.db.QueryRowContext(testContext(t), `SELECT r.budget_used,max(a.semantic_attempt_no),count(DISTINCT q.operation_id) FROM orchestration.workflow_runs r JOIN orchestration.stage_attempts a USING(run_id) JOIN orchestration.workflow_retry_receipts q USING(run_id) WHERE r.run_id=$1::uuid GROUP BY r.budget_used`, run.RunID).Scan(&used, &number, &count); err != nil || used != 2 || number != 3 || count != 2 {
		t.Fatal("semantic accounting not durable", err)
	}
	if err := s.revokeSyntheticPolicy(testContext(t), run.RunID, a.PolicyEpoch); err != nil {
		t.Fatal(err)
	}
	if replay, err := s.decideRetry(testContext(t), c); err != nil || replay != first {
		t.Fatal("revocation changed historical receipt", err)
	}
	t.Log("RF-08 concurrent replay and restart: two distinct corrections; budget_used=2; planning attempt=3; historical receipt survives revocation")
}

func TestRetryRejectsStaleAuthority(t *testing.T) {
	for _, mode := range []string{"version", "policy", "source", "epoch", "cancel", "revoke", "legacy"} {
		t.Run(mode, func(t *testing.T) {
			s, a, run, c := planningRetry(t, syntheticProfile{1, 1, 0, true}, 1)
			switch mode {
			case "version":
				c.ExpectedRunVersion++
			case "policy":
				c.PolicyEpoch++
			case "source":
				c.SourceSHA256 = strings.Repeat("e", 64)
			case "epoch":
				c.Lease.LeaseEpoch++
			case "cancel":
				if _, err := s.requestCancellation(testContext(t), cancellationForTest(t, a, c.Lease, 2)); err != nil {
					t.Fatal(err)
				}
			case "revoke":
				if err := s.revokeSyntheticPolicy(testContext(t), run.RunID, a.PolicyEpoch); err != nil {
					t.Fatal(err)
				}
			case "legacy":
				a, run = admitForTest(t, s, "legacy-retry")
				l, err := s.ClaimForRun(testContext(t), run.RunID, "synthetic-legacy", 60)
				if err != nil {
					t.Fatal(err)
				}
				c = retryForTest(t, a, l, 1, "EXHAUST")
			}
			if mode != "legacy" {
				attestRetry(t, s, c)
			}
			before := snapshotFor(t, s, run)
			if _, err := s.decideRetry(testContext(t), c); err == nil || snapshotFor(t, s, run) != before {
				t.Fatal("stale/cancelled/unapproved correction changed state")
			}
		})
	}
}

func TestRetryCompletionAndCancellationRaces(t *testing.T) {
	for _, winner := range []string{"correction", "cancellation"} {
		t.Run(winner, func(t *testing.T) {
			s, a, run, c := planningRetry(t, syntheticProfile{1, 1, 0, true}, 1)
			attestRetry(t, s, c)
			retryStore, retryPID := heartbeatProbe(t)
			retryStore.databaseURL = s.databaseURL
			cancelStore, cancelPID := heartbeatProbe(t)
			cancelStore.databaseURL = s.databaseURL
			cancelCommand := cancellationForTest(t, a, c.Lease, 2)
			gate, pid := holdHeartbeatRow(t, s, `SELECT task_id::text FROM orchestration.task_deliveries WHERE task_id=$1::uuid FOR UPDATE`, c.Lease.TaskID)
			var cancelReply <-chan cancellationReply
			retryReply := make(chan error, 1)
			startRetry := func() { go func() { _, err := retryStore.decideRetry(testContext(t), c); retryReply <- err }() }
			if winner == "correction" {
				startRetry()
				waitHeartbeatBlockedBy(t, s, retryPID, pid)
				cancelReply = startCancellation(t, cancelStore, cancelCommand)
				waitHeartbeatBlockedBy(t, s, cancelPID, retryPID)
			} else {
				cancelReply = startCancellation(t, cancelStore, cancelCommand)
				waitHeartbeatBlockedBy(t, s, cancelPID, pid)
				startRetry()
				waitHeartbeatBlockedBy(t, s, retryPID, cancelPID)
			}
			if gate.Commit() != nil {
				t.Fatal("race gate failed")
			}
			cancelResult := awaitCancellation(t, cancelReply)
			var retryErr error
			select {
			case retryErr = <-retryReply:
			case <-testContext(t).Done():
				t.Fatal("retry race deadline")
			}
			if winner == "correction" {
				if retryErr != nil || !errors.Is(cancelResult.err, errCancellationRejected) {
					t.Fatal("correction-first race not serialized", retryErr, cancelResult.err)
				}
			} else if cancelResult.err != nil || !errors.Is(retryErr, ErrTransitionRejected) {
				t.Fatal("cancel-first allowed correction", retryErr, cancelResult.err)
			}
			_ = run
		})
	}
	// Both calls start while authority is unexpired. Exhaustion cannot use an
	// uncertain or merely attempted completion as proof of failure (F01 unchanged).
	s, a, run := boundedRun(t, syntheticProfile{1, 0, 0, true}, 1)
	l, err := s.ClaimForRun(testContext(t), run.RunID, "synthetic-race", 60)
	if err != nil {
		t.Fatal(err)
	}
	c := retryForTest(t, a, l, 1, "EXHAUST")
	attestRetry(t, s, c)
	result := startResult(t, s, validAdvance(t, a, l))
	if _, err := s.decideRetry(testContext(t), c); err == nil {
		t.Fatal("unexpired/completed task was exhausted")
	}
	if r := awaitResult(t, result); r.err != nil {
		t.Fatal("exhaustion overwrote completion", r.err)
	}
	if snapshotFor(t, s, run).state != "PLANNING" {
		t.Fatal("accepted completion lost to exhaustion")
	}
}

func TestRetryRollbackAndPermissions(t *testing.T) {
	s, _, run, c := planningRetry(t, syntheticProfile{2, 1, 1, true}, 2)
	attestRetry(t, s, c)
	for _, table := range []string{"workflow_synthetic_profiles", "workflow_synthetic_gate_evidence", "workflow_retry_receipts"} {
		for _, statement := range []string{"UPDATE orchestration." + table + " SET run_id=run_id WHERE false", "DELETE FROM orchestration." + table + " WHERE false"} {
			_, err := s.db.ExecContext(testContext(t), statement)
			var pg *pgconn.PgError
			if !errors.As(err, &pg) || pg.Code != "42501" {
				t.Fatal("append-only grant weakened", err)
			}
		}
	}
	id, _ := freshUUID()
	if _, err := s.db.ExecContext(testContext(t), `INSERT INTO orchestration.stage_attempts(attempt_id,run_id,stage,semantic_attempt_no) VALUES($1::uuid,$2::uuid,'PLANNING',2)`, id, run.RunID); err != nil {
		t.Fatal(err)
	}
	before := snapshotFor(t, s, run)
	_, err := s.decideRetry(testContext(t), c)
	var pg *pgconn.PgError
	if !errors.As(err, &pg) || pg.Code != "23505" || snapshotFor(t, s, run) != before {
		t.Fatal("late successor failure did not roll back entire debit/bundle", err)
	}
	if _, err := s.reconcileRetry(testContext(t), c); !errors.Is(err, ErrResultUnknown) {
		t.Fatal("rolled-back receipt became accepted")
	}
}

func TestRetryCommitResponseLoss(t *testing.T) {
	for _, mode := range []string{"before-forward", "after-confirm", "after-confirm-outage"} {
		t.Run(mode, func(t *testing.T) {
			s, _, run, c := planningRetry(t, syntheticProfile{2, 1, 1, true}, 2)
			attestRetry(t, s, c)
			before := snapshotFor(t, s, run)
			url, injected := commitFaultProxy(t, mode)
			faulty, err := Open(testContext(t), url)
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { _ = faulty.Close() })
			r, err := faulty.decideRetry(testContext(t), c)
			select {
			case <-injected:
			case <-testContext(t).Done():
				t.Fatal("real COMMIT fault not injected")
			}
			if mode == "after-confirm" {
				if err != nil || r.OperationID != c.OperationID {
					t.Fatal("durable correction receipt not recovered", err)
				}
			} else if !errors.Is(err, ErrResultUnknown) || r != (retryReceipt{}) {
				t.Fatal("uncertain correction was acknowledged", err)
			}
			if mode == "before-forward" {
				if snapshotFor(t, s, run) != before {
					t.Fatal("unforwarded COMMIT mutated durable accounting")
				}
				return
			}
			recovered, err := s.reconcileRetry(testContext(t), c)
			if err != nil {
				t.Fatal(err)
			}
			accepted := snapshotFor(t, s, run)
			if replay, err := s.decideRetry(testContext(t), c); err != nil || replay != recovered || snapshotFor(t, s, run) != accepted {
				t.Fatal("uncertain correction double-debited")
			}
		})
	}
}
