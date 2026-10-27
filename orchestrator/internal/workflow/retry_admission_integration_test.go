//go:build integration

package workflow

import (
	"errors"
	"sync"
	"testing"
)

func TestRetryAdmissionReconciliation(t *testing.T) {
	p := syntheticProfile{2, 1, 1, true}
	s, a, run := boundedRun(t, p, 2)
	before := snapshotFor(t, s, run)
	want := run
	want.Replayed = true
	// Concurrent ordinary submissions and original-identity reconciliation must
	// return the same historical bundle or a definitive conflict, without debit.
	var wg sync.WaitGroup
	for i := range 12 {
		wg.Go(func() {
			c := a
			if i%2 != 0 {
				c.ActorID = "synthetic-conflicting-actor"
			}
			for range 3 {
				var r AdmissionReceipt
				var err error
				if i%3 == 0 {
					r, err = s.admitBounded(testContext(t), c, p)
				} else {
					r, err = s.reconcileBoundedAdmission(testContext(t), c, p)
				}
				if i%2 == 0 {
					if err != nil || r != want {
						t.Error("same-payload admission lost exact historical replay", err)
					}
				} else if !errors.Is(err, ErrConflict) || errors.Is(err, ErrResultUnknown) || r != (AdmissionReceipt{}) {
					t.Error("proven admission conflict classified UNKNOWN", err)
				}
			}
		})
	}
	wg.Wait()
	changed := p
	changed.DeliveryLimit++
	if _, err := s.reconcileBoundedAdmission(testContext(t), a, changed); !errors.Is(err, ErrResultConflict) || errors.Is(err, ErrResultUnknown) {
		t.Fatal("proven profile conflict classified UNKNOWN", err)
	}
	missing := admissionForTest(t, "bounded-missing")
	if r, err := s.reconcileBoundedAdmission(testContext(t), missing, p); !errors.Is(err, ErrResultUnknown) || errors.Is(err, ErrConflict) || r != (AdmissionReceipt{}) {
		t.Fatal("missing admission proof is not a definitive conflict", err)
	}
	if snapshotFor(t, s, run) != before {
		t.Fatal("reconciliation or duplicate/conflicting admission changed accounting")
	}
}

func TestRetryAdmissionCommitResponseLoss(t *testing.T) {
	for _, mode := range []string{"before-forward", "after-confirm", "after-confirm-outage"} {
		t.Run(mode, func(t *testing.T) {
			s := storeForTest(t)
			a := admissionForTest(t, "bounded-commit")
			p := syntheticProfile{2, 1, 1, true}
			url, injected := commitFaultProxy(t, mode)
			faulty, err := Open(testContext(t), url)
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { _ = faulty.Close() })
			r, err := faulty.admitBounded(testContext(t), a, p)
			select {
			case <-injected:
			case <-testContext(t).Done():
				t.Fatal("admission COMMIT fault not injected")
			}
			if mode == "after-confirm" {
				if err != nil || !r.Replayed {
					t.Fatal("committed admission was not reconciled", err)
				}
			} else if !errors.Is(err, ErrResultUnknown) || errors.Is(err, ErrConflict) || r != (AdmissionReceipt{}) {
				t.Fatal("missing/unavailable admission evidence must stay UNKNOWN", err)
			}
			if mode == "before-forward" {
				var count int
				if err := s.db.QueryRowContext(testContext(t), `SELECT count(*) FROM orchestration.workflow_runs WHERE command_id=$1::uuid`, a.CommandID).Scan(&count); err != nil || count != 0 {
					t.Fatal("unforwarded admission COMMIT persisted work", err)
				}
				return
			}
			recovered, err := s.reconcileBoundedAdmission(testContext(t), a, p)
			if err != nil || !recovered.Replayed {
				t.Fatal("restored lookup lost original admission", err)
			}
			before := snapshotFor(t, s, recovered)
			for range 3 {
				if replay, err := s.admitBounded(testContext(t), a, p); err != nil || replay != recovered {
					t.Fatal("admission replay replaced historical identity", err)
				}
			}
			if snapshotFor(t, s, recovered) != before || before.used != 0 || before.tasks != 1 || before.outbox != 1 || before.transitions != 1 {
				t.Fatal("COMMIT reconciliation duplicated admission/debit")
			}
		})
	}
}
