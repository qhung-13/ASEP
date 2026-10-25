//go:build integration

package workflow

import (
	"context"
	"errors"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgconn"
)

func cancellationForTest(t *testing.T, admission Admission, task TaskLease, version int64) cancellationRequest {
	t.Helper()
	op, err := freshUUID()
	if err != nil {
		t.Fatal("cancellation identity unavailable")
	}
	return cancellationRequest{OperationID: op, Task: task, ExpectedRunVersion: version,
		ExpectedPolicyEpoch: admission.PolicyEpoch, ExpectedSourceFingerprintSHA256: admission.SourceFingerprintSHA256,
		SyntheticPrefix: os.Getenv("ASEP_VN_T10_TEST_PREFIX")}
}

func cancellationFixture(t *testing.T) (*Store, AdmissionReceipt, investigationAdvance, cancellationRequest) {
	t.Helper()
	s := storeForTest(t)
	a, run := admitForTest(t, s, "cancellation")
	lease, err := s.ClaimForRun(testContext(t), run.RunID, "synthetic-cancel-race", 60)
	if err != nil {
		t.Fatal("cancellation fixture claim failed")
	}
	return s, run, validAdvance(t, a, lease), cancellationForTest(t, a, lease, 1)
}

func checkCancellation(t *testing.T, s *Store, run AdmissionReceipt, c cancellationRequest,
	before transitionSnapshot, receipt cancellationReceipt) {
	t.Helper()
	want := before
	want.state, want.version = "CANCEL_REQUESTED", before.version+1
	want.transitions++
	if snapshotFor(t, s, run) != want {
		t.Fatal("cancellation changed task/epoch/attempt/budget, fabricated completion/handoff or lost atomic stop intent")
	}
	digest, err := c.canonicalPayloadSHA256()
	if err != nil {
		t.Fatal("control digest unavailable")
	}
	durable, err := queryCancellation(testContext(t), s.db, c.OperationID, digest)
	if err != nil || durable != receipt || receipt.OperationID != c.OperationID || receipt.DigestVersion != 1 ||
		receipt.PayloadSHA256 != digest || receipt.RunID != c.Task.RunID || receipt.TaskID != c.Task.TaskID ||
		receipt.AttemptID != c.Task.AttemptID || receipt.Stage != c.Task.Stage ||
		receipt.ClaimantID != c.Task.ClaimantID || receipt.LeaseEpoch != c.Task.LeaseEpoch ||
		receipt.ExpectedRunVersion != c.ExpectedRunVersion || receipt.RunVersion != c.ExpectedRunVersion+1 ||
		receipt.PolicyEpoch != c.ExpectedPolicyEpoch || receipt.SourceFingerprintSHA256 != c.ExpectedSourceFingerprintSHA256 ||
		!canonicalUUID(receipt.TransitionID) || receipt.RequestedAt.IsZero() {
		t.Fatal("cancellation control receipt does not match original durable binding")
	}
}

func requireCancellationRaceBeforeExpiry(t *testing.T, s *Store, lease TaskLease) {
	t.Helper()
	var live bool
	if err := s.db.QueryRowContext(testContext(t), `SELECT clock_timestamp() < $1::timestamptz`, lease.ExpiresAt).Scan(&live); err != nil || !live {
		t.Fatal("cancellation fixture crossed expiry; F01 schedules are outside RF-07A")
	}
}

type cancellationReply struct {
	receipt cancellationReceipt
	err     error
}

func startCancellation(t *testing.T, s *Store, c cancellationRequest) <-chan cancellationReply {
	t.Helper()
	ctx := testContext(t)
	reply := make(chan cancellationReply, 1)
	go func() { r, err := s.requestCancellation(ctx, c); reply <- cancellationReply{r, err} }()
	return reply
}

func awaitCancellation(t *testing.T, reply <-chan cancellationReply) cancellationReply {
	t.Helper()
	select {
	case result := <-reply:
		return result
	case <-testContext(t).Done():
		t.Fatal("cancellation request deadline exceeded")
		return cancellationReply{}
	}
}

func TestCancellationCompletionRace(t *testing.T) {
	for _, winner := range []string{"cancellation", "completion"} {
		t.Run(winner+"-first", func(t *testing.T) {
			s, run, advance, c := cancellationFixture(t)
			before := snapshotFor(t, s, run)
			cancelStore, cancelPID := heartbeatProbe(t)
			resultStore, resultPID := heartbeatProbe(t)
			blocker, blockerPID := holdHeartbeatRow(t, s, `SELECT task_id::text
                FROM orchestration.task_deliveries WHERE task_id=$1::uuid FOR UPDATE`, run.TaskID)
			var cancelReply <-chan cancellationReply
			var result <-chan resultReply
			if winner == "cancellation" {
				cancelReply = startCancellation(t, cancelStore, c)
				waitHeartbeatBlockedBy(t, s, cancelPID, blockerPID)
				result = startResult(t, resultStore, advance)
				waitHeartbeatBlockedBy(t, s, resultPID, cancelPID)
			} else {
				result = startResult(t, resultStore, advance)
				waitHeartbeatBlockedBy(t, s, resultPID, blockerPID)
				cancelReply = startCancellation(t, cancelStore, c)
				waitHeartbeatBlockedBy(t, s, cancelPID, resultPID)
			}
			requireCancellationRaceBeforeExpiry(t, s, c.Task)
			if blocker.Commit() != nil {
				t.Fatal("cancellation race gate release failed")
			}
			cancelled, completed := awaitCancellation(t, cancelReply), awaitResult(t, result)
			requireCancellationRaceBeforeExpiry(t, s, c.Task)
			if winner == "cancellation" {
				if cancelled.err != nil || !errors.Is(completed.err, ErrTransitionRejected) || completed.receipt != (advanceReceipt{}) {
					t.Fatal("cancel-first failed to fence a never-accepted completion")
				}
				checkCancellation(t, s, run, c, before, cancelled.receipt)
				t.Log("RF-07A cancel-first: serialized lock wait; CANCEL_REQUESTED version=2; task/epoch/attempt unchanged; no successor/result receipt/debit")
			} else {
				if completed.err != nil || !errors.Is(cancelled.err, errCancellationRejected) || cancelled.receipt != (cancellationReceipt{}) {
					t.Fatal("completion-first did not reject stale cancellation")
				}
				checkAdvanced(t, s, run, completed.receipt)
				t.Log("RF-07A completion-first: committed before expiry; original result/successor retained; stale cancellation rejected without following next stage")
			}
		})
	}
}

func TestCancellationReplayAndFencing(t *testing.T) {
	s, run, _, c := cancellationFixture(t)
	before := snapshotFor(t, s, run)
	r, err := s.requestCancellation(testContext(t), c)
	if err != nil {
		t.Fatal("valid cancellation request failed")
	}
	checkCancellation(t, s, run, c, before, r)
	accepted := snapshotFor(t, s, run)
	observer := storeForTest(t)
	// The application response is deliberately discarded; recover independently.
	recovered, err := observer.reconcileCancellation(testContext(t), c)
	if err != nil || recovered != r {
		t.Fatal("lost cancellation application response did not recover original audit receipt")
	}
	transport := c
	transport.Task.DeliveryCount += 10
	transport.Task.ExpiresAt = transport.Task.ExpiresAt.Add(time.Hour)
	replay, err := observer.requestCancellation(testContext(t), transport)
	if err != nil || replay != r {
		t.Fatal("historical cancellation replay changed identity")
	}
	conflict := c
	conflict.ExpectedPolicyEpoch++
	if _, err := observer.requestCancellation(testContext(t), conflict); !errors.Is(err, errCancellationConflict) {
		t.Fatal("changed payload was not a typed control conflict")
	}
	conflict.OperationID, _ = freshUUID()
	if _, err := observer.requestCancellation(testContext(t), conflict); !errors.Is(err, errCancellationRejected) {
		t.Fatal("a new stale control operation was not rejected")
	}
	if _, err := observer.ClaimForRun(testContext(t), run.RunID, "synthetic-next-worker", 60); !errors.Is(err, ErrNoTask) {
		t.Fatal("cancellation did not fence new claims")
	}
	if _, err := observer.Heartbeat(testContext(t), c.Task, 60); !errors.Is(err, ErrLeaseLost) {
		t.Fatal("cancellation did not fence heartbeat renewal")
	}
	if snapshotFor(t, s, run) != accepted {
		t.Fatal("replay/conflict/fenced worker changed durable cancellation outcome")
	}
	// CANCEL_REQUESTED must continue to occupy the active canonical-subject slot.
	a := admissionForTest(t, "duplicate-cancelled-scope")
	var site, project, issue, gitlab string
	if err := s.db.QueryRowContext(testContext(t), `SELECT jira_site_id,jira_project_id,jira_issue_id,gitlab_project_id
        FROM orchestration.workflow_runs WHERE run_id=$1::uuid`, run.RunID).Scan(&site, &project, &issue, &gitlab); err != nil {
		t.Fatal("original subject identity unavailable")
	}
	a.JiraSiteID, a.JiraProjectID, a.JiraIssueID, a.GitLabProjectID = site, project, issue, gitlab
	if _, err := s.Admit(testContext(t), a); !errors.Is(err, ErrConflict) {
		t.Fatal("pending cancellation permitted a second active canonical-subject run")
	}
	requireCancellationRaceBeforeExpiry(t, s, c.Task)
	t.Log("RF-07A replay/reconnect: one control audit; claims/heartbeat fenced; no task retirement or cleanup/finalization; active subject retained")
}

func TestCancellationHistoricalCompletionReplay(t *testing.T) {
	s, run, advance, _ := cancellationFixture(t)
	r, err := s.advanceInvestigation(testContext(t), advance)
	if err != nil {
		t.Fatal("ordinary completion fixture failed")
	}
	checkAdvanced(t, s, run, r)
	a := Admission{PolicyEpoch: advance.ExpectedPolicyEpoch, SourceFingerprintSHA256: advance.ExpectedSourceFingerprintSHA256}
	c := cancellationForTest(t, a, TaskLease{RunID: run.RunID, TaskID: r.NextTaskID,
		AttemptID: r.NextAttemptID, Stage: "PLANNING"}, r.RunVersion)
	before := snapshotFor(t, s, run)
	queued := AdmissionReceipt{RunID: run.RunID, TaskID: r.NextTaskID, AttemptID: r.NextAttemptID}
	queuedBefore := snapshotFor(t, s, queued)
	control, err := s.requestCancellation(testContext(t), c)
	if err != nil {
		t.Fatal("fresh cancellation of queued PLANNING task failed")
	}
	checkCancellation(t, s, run, c, before, control)
	checkCancellation(t, s, queued, c, queuedBefore, control)
	accepted := snapshotFor(t, s, run)
	queuedAccepted := snapshotFor(t, s, queued)
	observer := storeForTest(t)
	replay, err := observer.advanceInvestigation(testContext(t), advance)
	if err != nil || replay != r || snapshotFor(t, s, run) != accepted || snapshotFor(t, s, queued) != queuedAccepted {
		t.Fatal("cancellation erased accepted completion or replay granted new authority")
	}
	if _, err := observer.ClaimForRun(testContext(t), run.RunID, "synthetic-planning-worker", 60); !errors.Is(err, ErrNoTask) {
		t.Fatal("queued successor remained claimable after cancellation")
	}
	if snapshotFor(t, s, queued) != queuedAccepted {
		t.Fatal("fenced claim mutated queued successor or its pending attempt")
	}
	requireCancellationRaceBeforeExpiry(t, s, advance.Lease)
	t.Log("RF-07A historical completion replay: exact V5 receipt retained after fresh PLANNING cancellation; queued successor preserved and fenced; no extra outbox/debit")
}

func TestCancellationRejectsStaleBindings(t *testing.T) {
	s, run, _, c := cancellationFixture(t)
	before := snapshotFor(t, s, run)
	otherID, _ := freshUUID()
	for name, change := range map[string]func(*cancellationRequest){
		"version": func(c *cancellationRequest) { c.ExpectedRunVersion++ },
		"state":   func(c *cancellationRequest) { c.Task.Stage = "PLANNING" },
		"policy":  func(c *cancellationRequest) { c.ExpectedPolicyEpoch++ },
		"source":  func(c *cancellationRequest) { c.ExpectedSourceFingerprintSHA256 = strings.Repeat("c", 64) },
		"task":    func(c *cancellationRequest) { c.Task.TaskID = otherID },
		"attempt": func(c *cancellationRequest) { c.Task.AttemptID = otherID },
		"owner":   func(c *cancellationRequest) { c.Task.ClaimantID += "-other" },
		"epoch":   func(c *cancellationRequest) { c.Task.LeaseEpoch++ },
		"run":     func(c *cancellationRequest) { c.Task.RunID = otherID },
		"scope":   func(c *cancellationRequest) { c.SyntheticPrefix = "synthetic:vn-t10-it-" + strings.Repeat("f", 32) },
	} {
		t.Run(name, func(t *testing.T) {
			x := c
			change(&x)
			if _, err := s.requestCancellation(testContext(t), x); !errors.Is(err, errCancellationRejected) || snapshotFor(t, s, run) != before {
				t.Fatal("mismatched cancellation binding changed durable state")
			}
		})
	}
	requireCancellationRaceBeforeExpiry(t, s, c.Task)
}

func TestCancellationConcurrentOperationIdentity(t *testing.T) {
	for _, scenario := range []string{"same-request", "different-runs"} {
		t.Run(scenario, func(t *testing.T) {
			s, run, _, c := cancellationFixture(t)
			otherStore, otherRun, _, other := cancellationFixture(t)
			before, otherBefore := snapshotFor(t, s, run), snapshotFor(t, otherStore, otherRun)
			other.OperationID = c.OperationID
			if scenario == "same-request" {
				other = c
			}
			// Both decisions acquire their run locks then wait on controlled task
			// gates. Different runs must reach the unique-control-ID arbitration.
			first, firstPID := heartbeatProbe(t)
			second, secondPID := heartbeatProbe(t)
			first.databaseURL, second.databaseURL = s.databaseURL, otherStore.databaseURL
			gate, gatePID := holdHeartbeatRow(t, s, `SELECT task_id::text FROM orchestration.task_deliveries
                WHERE task_id=$1::uuid FOR UPDATE`, c.Task.TaskID)
			if scenario == "different-runs" {
				otherGate, otherGatePID := holdHeartbeatRow(t, otherStore, `SELECT task_id::text FROM orchestration.task_deliveries
                    WHERE task_id=$1::uuid FOR UPDATE`, other.Task.TaskID)
				secondReply := startCancellation(t, second, other)
				waitHeartbeatBlockedBy(t, otherStore, secondPID, otherGatePID)
				firstReply := startCancellation(t, first, c)
				waitHeartbeatBlockedBy(t, s, firstPID, gatePID)
				if gate.Commit() != nil {
					t.Fatal("first cancellation gate release failed")
				}
				if otherGate.Commit() != nil {
					t.Fatal("second cancellation gate release failed")
				}
				a, b := awaitCancellation(t, firstReply), awaitCancellation(t, secondReply)
				// Exactly one accepted control; the other run must roll back fully.
				if a.err == nil && errors.Is(b.err, errCancellationConflict) {
					checkCancellation(t, s, run, c, before, a.receipt)
					if snapshotFor(t, otherStore, otherRun) != otherBefore {
						t.Fatal("losing control transaction partially committed")
					}
				} else if b.err == nil && errors.Is(a.err, errCancellationConflict) {
					checkCancellation(t, otherStore, otherRun, other, otherBefore, b.receipt)
					if snapshotFor(t, s, run) != before {
						t.Fatal("losing control transaction partially committed")
					}
				} else {
					t.Fatal("cross-run control identity did not produce one acceptance and one conflict")
				}
			} else {
				a := startCancellation(t, first, c)
				waitHeartbeatBlockedBy(t, s, firstPID, gatePID)
				b := startCancellation(t, second, other)
				waitHeartbeatBlockedBy(t, s, secondPID, firstPID)
				if gate.Commit() != nil {
					t.Fatal("matching replay gate release failed")
				}
				x, y := awaitCancellation(t, a), awaitCancellation(t, b)
				if x.err != nil || y.err != nil || x.receipt != y.receipt {
					t.Fatal("concurrent matching controls did not recover one receipt")
				}
				checkCancellation(t, s, run, c, before, x.receipt)
			}
			requireCancellationRaceBeforeExpiry(t, s, c.Task)
			requireCancellationRaceBeforeExpiry(t, otherStore, other.Task)
		})
	}
}

func TestCancellationRollbackOnAuditFailure(t *testing.T) {
	s, run, _, c := cancellationFixture(t)
	id, _ := freshUUID()
	// Fault fixture only: reserve the next audit version without changing run.
	// The request's INSERT fails AFTER its run UPDATE, which must roll back.
	_, err := s.db.ExecContext(testContext(t), `INSERT INTO orchestration.workflow_transitions(
        transition_id,run_id,attempt_id,from_state,to_state,prior_run_version)
        VALUES ($1::uuid,$2::uuid,$3::uuid,'INVESTIGATING','BLOCKED',1)`, id, run.RunID, run.AttemptID)
	if err != nil {
		t.Fatal("late cancellation fault fixture unavailable")
	}
	before := snapshotFor(t, s, run)
	_, err = s.requestCancellation(testContext(t), c)
	var pgerr *pgconn.PgError
	if !errors.As(err, &pgerr) || pgerr.Code != "23505" || errors.Is(err, errCancellationConflict) || errors.Is(err, errCancellationUnknown) || snapshotFor(t, s, run) != before {
		t.Fatal("late cancellation SQL failure lost its classification or partially committed")
	}
	if _, err := s.reconcileCancellation(testContext(t), c); !errors.Is(err, errCancellationUnknown) {
		t.Fatal("rolled-back cancellation fabricated a durable control receipt")
	}
	requireCancellationRaceBeforeExpiry(t, s, c.Task)
}

func TestCancellationCommitResponseLoss(t *testing.T) {
	for _, mode := range []string{"before-forward", "after-confirm", "after-confirm-outage"} {
		t.Run(mode, func(t *testing.T) {
			s, run, _, c := cancellationFixture(t)
			before := snapshotFor(t, s, run)
			url, injected := commitFaultProxy(t, mode)
			faulty, err := Open(testContext(t), url)
			if err != nil {
				t.Fatal("guarded cancellation proxy unavailable")
			}
			t.Cleanup(func() { _ = faulty.Close() })
			start := time.Now()
			r, err := faulty.requestCancellation(testContext(t), c)
			select {
			case <-injected:
			case <-testContext(t).Done():
				t.Fatal("cancellation COMMIT wire fault was not observed")
			}
			if time.Since(start) > 4*time.Second {
				t.Fatal("cancellation COMMIT reconciliation exceeded its bound")
			}
			if mode == "after-confirm" {
				if err != nil {
					t.Fatal("confirmed cancellation COMMIT did not reconcile")
				}
				checkCancellation(t, s, run, c, before, r)
			} else {
				if !errors.Is(err, errCancellationUnknown) || r != (cancellationReceipt{}) {
					t.Fatal("uncertain cancellation produced a false acknowledgement")
				}
				if mode == "before-forward" {
					if snapshotFor(t, s, run) != before {
						t.Fatal("unforwarded cancellation COMMIT changed durable state")
					}
					if _, err := s.reconcileCancellation(testContext(t), c); !errors.Is(err, errCancellationUnknown) {
						t.Fatal("receipt absence was interpreted as a confirmed outcome")
					}
				} else {
					recovered, err := s.reconcileCancellation(testContext(t), c)
					if err != nil {
						t.Fatal("healthy reconnect did not recover committed cancellation")
					}
					checkCancellation(t, s, run, c, before, recovered)
					accepted := snapshotFor(t, s, run)
					replay, err := s.requestCancellation(testContext(t), c)
					if err != nil || replay != recovered || snapshotFor(t, s, run) != accepted {
						t.Fatal("uncertain cancellation recovery duplicated control progress")
					}
				}
			}
			requireCancellationRaceBeforeExpiry(t, s, c.Task)
			t.Log("RF-07A actual PostgreSQL COMMIT fault observed; original control identity preserved; no automatic transaction retry")
		})
	}
}

func TestCancellationReconciliationDeadline(t *testing.T) {
	s, run, _, c := cancellationFixture(t)
	before := snapshotFor(t, s, run)
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	start := time.Now()
	if _, err := s.reconcileCancellation(ctx, c); !errors.Is(err, errCancellationUnknown) || !errors.Is(err, context.Canceled) {
		t.Fatal("cancellation reconciliation lost parent cancellation or UNKNOWN")
	}
	if time.Since(start) > time.Second || snapshotFor(t, s, run) != before {
		t.Fatal("canceled lookup was unbounded or mutated workflow")
	}
	if _, err := s.requestCancellation(ctx, c); !errors.Is(err, context.Canceled) || errors.Is(err, errCancellationRejected) {
		t.Fatal("real canceled transaction misclassified as ordinary rejection")
	}
}

func TestCancellationAuditConstraintsAndPermissions(t *testing.T) {
	s, run, _, c := cancellationFixture(t)
	_, foreignRun, _, _ := cancellationFixture(t)
	before := snapshotFor(t, s, run)
	r, err := s.requestCancellation(testContext(t), c)
	if err != nil {
		t.Fatal("cancellation audit fixture failed")
	}
	checkCancellation(t, s, run, c, before, r)
	accepted := snapshotFor(t, s, run)
	for _, statement := range []string{
		`UPDATE orchestration.workflow_transitions SET control_kind=control_kind WHERE false`,
		`DELETE FROM orchestration.workflow_transitions WHERE false`,
		`TRUNCATE orchestration.workflow_transitions`,
	} {
		_, err := s.db.ExecContext(testContext(t), statement)
		var pgerr *pgconn.PgError
		if !errors.As(err, &pgerr) || pgerr.Code != "42501" {
			t.Fatal("runtime could rewrite cancellation stop intent")
		}
	}
	for _, scenario := range []struct{ name, code, constraint string }{
		{"missing-kind", "23514", "ck_cancel_request_metadata"},
		{"missing-digest", "23514", "ck_cancel_request_metadata"},
		{"partial-control", "23514", "ck_cancel_request_metadata"},
		{"foreign-task", "23503", "fk_cancel_request_task"},
		{"duplicate-operation", "23505", "uq_cancel_request_operation"},
	} {
		t.Run(scenario.name, func(t *testing.T) {
			id, _ := freshUUID()
			op, _ := freshUUID()
			var operation, kind, digest any = op, "CANCEL_REQUEST", r.PayloadSHA256
			task := run.TaskID
			switch scenario.name {
			case "missing-kind":
				kind = nil
			case "missing-digest":
				digest = nil
			case "partial-control":
				operation = nil
			case "foreign-task":
				task = foreignRun.TaskID
			case "duplicate-operation":
				operation = c.OperationID
			}
			_, err := s.db.ExecContext(testContext(t), `INSERT INTO orchestration.workflow_transitions(
                transition_id,run_id,attempt_id,from_state,to_state,prior_run_version,
                control_operation_id,control_kind,control_digest_version,control_payload_sha256,
                control_task_id,control_claimant_id,control_lease_epoch,control_policy_epoch,control_source_fingerprint_sha256)
                SELECT $1::uuid,run_id,attempt_id,from_state,to_state,prior_run_version+1,
                  $2::uuid,$3,1,$4,$5::uuid,control_claimant_id,control_lease_epoch,control_policy_epoch,control_source_fingerprint_sha256
                FROM orchestration.workflow_transitions WHERE transition_id=$6::uuid`, id, operation, kind, digest, task, r.TransitionID)
			var pgerr *pgconn.PgError
			if !errors.As(err, &pgerr) || pgerr.Code != scenario.code || pgerr.ConstraintName != scenario.constraint {
				t.Fatal("malformed cancellation audit escaped its database invariant")
			}
			if snapshotFor(t, s, run) != accepted {
				t.Fatal("failed audit probe changed durable cancellation")
			}
		})
	}
	t.Run("missing-control-metadata", func(t *testing.T) {
		id, err := freshUUID()
		if err != nil {
			t.Fatal("missing-control audit probe identity unavailable")
		}
		// Omit every control column, so all nine default to NULL. Reuse valid
		// run/attempt bindings and a fresh transition/version to isolate CHECK.
		_, err = s.db.ExecContext(testContext(t), `INSERT INTO orchestration.workflow_transitions(
            transition_id,run_id,attempt_id,from_state,to_state,prior_run_version,evidence_ref,budget_debit)
            SELECT $1::uuid,run_id,attempt_id,from_state,'CANCEL_REQUESTED',prior_run_version+1,evidence_ref,0
            FROM orchestration.workflow_transitions WHERE transition_id=$2::uuid`, id, r.TransitionID)
		var pgerr *pgconn.PgError
		if !errors.As(err, &pgerr) || pgerr.Code != "23514" || pgerr.ConstraintName != "ck_cancel_request_metadata" {
			t.Fatal("cancellation audit without control identity was not rejected by ck_cancel_request_metadata")
		}
		if snapshotFor(t, s, run) != accepted {
			t.Fatal("missing-control audit probe changed durable cancellation")
		}
	})
	requireCancellationRaceBeforeExpiry(t, s, c.Task)
}
