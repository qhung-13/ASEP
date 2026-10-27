//go:build integration

package workflow

import (
	"context"
	"database/sql"
	"errors"
	"net"
	"net/url"
	"testing"
	"time"

	"github.com/qhung-13/ASEP/orchestrator/internal/testsupport"
)

func unavailableEndpoint(t *testing.T) string {
	t.Helper()
	u, err := url.Parse(testsupport.DatabaseURL(t))
	if err != nil {
		t.Fatal("verified outage target unavailable")
	}
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal("outage listener unavailable")
	}
	u.Host = listener.Addr().String()
	if listener.Close() != nil {
		t.Fatal("outage listener did not close")
	}
	return u.String()
}

func TestOutageBeforeOperationAndReconciliation(t *testing.T) {
	s, _, run, c := planningRetry(t, syntheticProfile{2, 1, 1, true}, 2)
	attestRetry(t, s, c)
	before := snapshotFor(t, s, run)
	endpoint := unavailableEndpoint(t)
	db, err := sql.Open("pgx", endpoint)
	if err != nil {
		t.Fatal("outage pool configuration failed")
	}
	t.Cleanup(func() { _ = db.Close() })
	faulty := &Store{db: db, databaseURL: endpoint}
	ctx, cancel := context.WithTimeout(t.Context(), time.Second)
	defer cancel()
	if r, err := faulty.decideRetry(ctx, c); err == nil || r != (retryReceipt{}) {
		t.Fatal("unavailable PostgreSQL endpoint acknowledged progress")
	}
	for range 3 {
		ctx, cancel := context.WithTimeout(t.Context(), time.Second)
		r, err := faulty.reconcileRetry(ctx, c)
		cancel()
		if !errors.Is(err, ErrResultUnknown) || r != (retryReceipt{}) {
			t.Fatal("unavailable receipt query resolved UNKNOWN")
		}
	}
	if snapshotFor(t, s, run) != before {
		t.Fatal("failed operations mutated healthy durable state")
	}
	if _, err := s.reconcileRetry(testContext(t), c); !errors.Is(err, ErrResultUnknown) {
		t.Fatal("receipt absence incorrectly proved rollback")
	}
	// A separately controlled restored invocation keeps the original identity;
	// neither the Store nor reconciliation automatically retries the operation.
	r, err := s.decideRetry(testContext(t), c)
	if err != nil {
		t.Fatal("restored controlled invocation failed", err)
	}
	after := snapshotFor(t, s, run)
	if got, err := s.reconcileRetry(testContext(t), c); err != nil || got != r || snapshotFor(t, s, run) != after {
		t.Fatal("restored lookup duplicated budget/progress")
	}
}

func TestOutagePolicyLookupFailsClosed(t *testing.T) {
	for _, operation := range []string{"advance", "retry", "heartbeat", "dispatch", "receive"} {
		t.Run(operation, func(t *testing.T) {
			var s *Store
			var run AdmissionReceipt
			var invoke func(*Store) error
			switch operation {
			case "retry":
				var c retryDecision
				s, _, run, c = planningRetry(t, syntheticProfile{2, 1, 1, true}, 2)
				attestRetry(t, s, c)
				invoke = func(f *Store) error {
					r, err := f.decideRetry(testContext(t), c)
					if r != (retryReceipt{}) {
						t.Fatal("policy outage produced correction receipt")
					}
					return err
				}
			case "advance", "heartbeat":
				var a Admission
				s, a, run = boundedRun(t, syntheticProfile{2, 1, 1, true}, 2)
				l, err := s.ClaimForRun(testContext(t), run.RunID, "synthetic-outage", 60)
				if err != nil {
					t.Fatal(err)
				}
				c := validAdvance(t, a, l)
				invoke = func(f *Store) error {
					if operation == "heartbeat" {
						expiry, err := f.Heartbeat(testContext(t), l, 60)
						if !expiry.IsZero() {
							t.Fatal("policy outage renewed expiry")
						}
						return err
					}
					r, err := f.advanceInvestigation(testContext(t), c)
					if r != (advanceReceipt{}) {
						t.Fatal("policy outage accepted result")
					}
					return err
				}
			case "dispatch", "receive":
				var e stageEnvelope
				s, _, run, e = messagingRun(t, 2)
				invoke = func(f *Store) error {
					var r inboxReceipt
					var err error
					if operation == "dispatch" {
						r, err = f.dispatchSynthetic(testContext(t), e.MessageID, func(context.Context, stageEnvelope) (inboxReceipt, error) {
							t.Fatal("policy outage reached transport")
							return inboxReceipt{}, nil
						})
					} else {
						r, err = f.receiveSynthetic(testContext(t), e)
					}
					if r != (inboxReceipt{}) {
						t.Fatal("policy outage acknowledged inbox")
					}
					return err
				}
			}
			before := snapshotFor(t, s, run)
			proxy, injected := commitFaultProxy(t, "before-policy")
			faulty, err := Open(testContext(t), proxy)
			if err != nil {
				t.Fatal("policy-fault connection failed")
			}
			t.Cleanup(func() { _ = faulty.Close() })
			err = invoke(faulty)
			select {
			case <-injected:
			case <-testContext(t).Done():
				t.Fatal("real policy query disconnect not reached")
			}
			if err == nil || errors.Is(err, ErrTransitionRejected) || errors.Is(err, ErrLeaseLost) {
				t.Fatal("policy database error was accepted or misclassified as ordinary rejection", err)
			}
			if snapshotFor(t, s, run) != before {
				t.Fatal("unavailable policy changed workflow/lease/accounting")
			}
			assertSyntheticEffects(t, s, run, 0, 0)
		})
	}
}

func TestOutageMidTransactionRollbackEvidence(t *testing.T) {
	for _, mode := range []string{"before-task-update", "before-effect"} {
		t.Run(mode, func(t *testing.T) {
			var s *Store
			var run AdmissionReceipt
			var invoke func(*Store) error
			if mode == "before-task-update" {
				var c retryDecision
				s, _, run, c = planningRetry(t, syntheticProfile{2, 1, 1, true}, 2)
				attestRetry(t, s, c)
				invoke = func(f *Store) error { _, err := f.decideRetry(testContext(t), c); return err }
			} else {
				var e stageEnvelope
				s, _, run, e = messagingRun(t, 2)
				invoke = func(f *Store) error { _, err := f.receiveSynthetic(testContext(t), e); return err }
			}
			before := snapshotFor(t, s, run)
			proxy, injected := commitFaultProxy(t, mode)
			faulty, err := Open(testContext(t), proxy)
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { _ = faulty.Close() })
			if err := invoke(faulty); err == nil {
				t.Fatal("mid-transaction disconnect acknowledged progress")
			}
			select {
			case <-injected:
			case <-testContext(t).Done():
				t.Fatal("mid-transaction cut not observed")
			}
			if snapshotFor(t, s, run) != before {
				t.Fatal("disconnected transaction left partial progress")
			}
			// For before-effect, the INSERT into inbox was already sent. This
			// independent observation, not the disconnect itself, proves no partial
			// durable inbox/effect escaped the uncommitted transaction.
			assertSyntheticEffects(t, s, run, 0, 0)
		})
	}
}

func TestOutageRepeatedRecoveryPreservesReceipt(t *testing.T) {
	s, a, run, c := planningRetry(t, syntheticProfile{2, 1, 1, true}, 2)
	attestRetry(t, s, c)
	r, err := s.decideRetry(testContext(t), c)
	if err != nil {
		t.Fatal(err)
	}
	accepted := snapshotFor(t, s, run)
	unavailable := &Store{db: s.db, databaseURL: unavailableEndpoint(t)}
	for range 3 {
		if got, err := unavailable.reconcileRetry(testContext(t), c); !errors.Is(err, ErrResultUnknown) || got != (retryReceipt{}) {
			t.Fatal("unavailable independent lookup fabricated acceptance")
		}
	}
	restarted := storeForTest(t)
	for range 3 {
		if got, err := restarted.reconcileRetry(testContext(t), c); err != nil || got != r || snapshotFor(t, s, run) != accepted {
			t.Fatal("restored repeated lookup lost exact receipt/accounting", err)
		}
	}
	l, err := s.ClaimForRun(testContext(t), run.RunID, "synthetic-recovery-cancel", 60)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.requestCancellation(testContext(t), cancellationForTest(t, a, l, 3)); err != nil {
		t.Fatal(err)
	}
	cancelled := snapshotFor(t, s, run)
	if got, err := restarted.decideRetry(testContext(t), c); err != nil || got != r || snapshotFor(t, s, run) != cancelled {
		t.Fatal("historical recovery reactivated cancelled work")
	}
}

func TestOutagePolicyRevocationSerialization(t *testing.T) {
	for _, winner := range []string{"rework", "revocation"} {
		t.Run(winner, func(t *testing.T) {
			s, _, run, c := planningRetry(t, syntheticProfile{2, 1, 1, true}, 2)
			attestRetry(t, s, c)
			rework, reworkPID := heartbeatProbe(t)
			rework.databaseURL = s.databaseURL
			revoke, revokePID := heartbeatProbe(t)
			taskQuery := `SELECT task_id::text FROM orchestration.task_deliveries WHERE task_id=$1::uuid FOR UPDATE`
			policyQuery := `SELECT run_id::text FROM orchestration.workflow_synthetic_policies WHERE run_id=$1::uuid FOR UPDATE`
			query, id := taskQuery, c.Lease.TaskID
			if winner == "revocation" {
				query, id = policyQuery, run.RunID
			}
			gate, pid := holdHeartbeatRow(t, s, query, id)
			reworkReply, revokeReply := make(chan error, 1), make(chan error, 1)
			startRework := func() { go func() { _, err := rework.decideRetry(testContext(t), c); reworkReply <- err }() }
			startRevoke := func() { go func() { revokeReply <- revoke.revokeSyntheticPolicy(testContext(t), run.RunID, 1) }() }
			if winner == "rework" {
				startRework()
				waitHeartbeatBlockedBy(t, s, reworkPID, pid)
				startRevoke()
				waitHeartbeatBlockedBy(t, s, revokePID, reworkPID)
			} else {
				startRevoke()
				waitHeartbeatBlockedBy(t, s, revokePID, pid)
				startRework()
				waitHeartbeatBlockedBy(t, s, reworkPID, revokePID)
			}
			if gate.Commit() != nil {
				t.Fatal("policy serialization gate failed")
			}
			var reworkErr, revokeErr error
			select {
			case reworkErr = <-reworkReply:
			case <-testContext(t).Done():
				t.Fatal("rework/revoke deadline")
			}
			select {
			case revokeErr = <-revokeReply:
			case <-testContext(t).Done():
				t.Fatal("revoke/rework deadline")
			}
			if revokeErr != nil {
				t.Fatal("revocation failed", revokeErr)
			}
			if winner == "rework" && reworkErr != nil {
				t.Fatal("serialized valid rework lost", reworkErr)
			}
			if winner == "revocation" && !errors.Is(reworkErr, ErrTransitionRejected) {
				t.Fatal("revocation-first allowed new progress", reworkErr)
			}
			if _, err := s.ClaimForRun(testContext(t), run.RunID, "synthetic-after-revoke", 60); !errors.Is(err, ErrTransitionRejected) {
				t.Fatal("revoked run was newly claimable", err)
			}
		})
	}
}
