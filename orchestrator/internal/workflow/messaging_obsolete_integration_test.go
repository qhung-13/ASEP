//go:build integration

package workflow

import (
	"context"
	"errors"
	"io"
	"sync"
	"sync/atomic"
	"testing"

	"github.com/jackc/pgx/v5/pgconn"
)

func obsoleteMessage(t *testing.T, s *Store, a Admission, l TaskLease, mode string) error {
	t.Helper()
	if mode == "advanced" {
		_, err := s.advanceInvestigation(testContext(t), validAdvance(t, a, l))
		return err
	}
	_, err := s.requestCancellation(testContext(t), cancellationForTest(t, a, l, 1))
	return err
}

func assertUnsentDisposition(t *testing.T, s *Store, e stageEnvelope) string {
	t.Helper()
	var phase, status, hash, proof string
	var attempts, history, inbox, effects, acknowledgements int
	var timestampAbsent bool
	err := s.db.QueryRowContext(testContext(t), `SELECT d.phase,o.status,o.payload_sha256,d.attempts,o.delivered_at IS NULL,
        (SELECT count(*) FROM orchestration.workflow_synthetic_delivery_attempts WHERE message_id=o.message_id),
        (SELECT count(*) FROM orchestration.workflow_synthetic_inbox WHERE message_id=o.message_id),
        (SELECT count(*) FROM orchestration.workflow_synthetic_effects WHERE message_id=o.message_id),
        (SELECT count(*) FROM orchestration.workflow_synthetic_delivery_receipts WHERE message_id=o.message_id),to_jsonb(p)::text
        FROM orchestration.workflow_outbox o JOIN orchestration.workflow_synthetic_deliveries d USING(message_id)
        JOIN orchestration.workflow_synthetic_sender_dispositions p USING(message_id,run_id,payload_sha256)
        WHERE o.message_id=$1::uuid AND o.run_id=$2::uuid AND p.intent_run_version=$3
          AND p.disposition='NEVER_SENT_OBSOLETE'`, e.MessageID, e.RunID, e.RunVersion).Scan(
		&phase, &status, &hash, &attempts, &timestampAbsent, &history, &inbox, &effects, &acknowledgements, &proof)
	if err != nil || phase != "OBSOLETE" || status != "OBSOLETE" || hash != e.PayloadSHA256 || !timestampAbsent || attempts != 0 || history != 0 || inbox != 0 || effects != 0 || acknowledgements != 0 {
		t.Fatal("unsent terminal identity/evidence invariant failed", err)
	}
	actual, err := readStageEnvelope(testContext(t), s.db, e.MessageID)
	if err != nil || actual != e {
		t.Fatal("retirement changed original envelope/codec/hash", err)
	}
	return proof
}

func TestMessagingObsoleteUnsentIntent(t *testing.T) {
	for _, mode := range []string{"advanced", "cancelled"} {
		t.Run(mode, func(t *testing.T) {
			s, a, run, e := messagingRun(t, 2)
			l, err := s.ClaimForRun(testContext(t), run.RunID, "synthetic-unsent", 60)
			if err != nil {
				t.Fatal(err)
			}
			if err := obsoleteMessage(t, s, a, l, mode); err != nil {
				t.Fatal(err)
			}
			before := snapshotFor(t, s, run)
			var proof string
			for range 3 {
				r, err := storeForTest(t).dispatchSynthetic(testContext(t), e.MessageID, func(context.Context, stageEnvelope) (inboxReceipt, error) {
					t.Error("obsolete unsent message reached transport")
					return inboxReceipt{}, io.ErrUnexpectedEOF
				})
				if !errors.Is(err, errDeliveryObsolete) || r != (inboxReceipt{}) {
					t.Fatal("unsent disposition is not a receiver ACK", err)
				}
				got := assertUnsentDisposition(t, s, e)
				if proof != "" && got != proof {
					t.Fatal("terminal replay replaced immutable sender evidence")
				}
				proof = got
			}
			if r, err := s.receiveSynthetic(testContext(t), e); !errors.Is(err, errDeliveryObsolete) || r != (inboxReceipt{}) {
				t.Fatal("local retirement fabricated a late receiver receipt", err)
			}
			assertUnsentDisposition(t, s, e)
			if snapshotFor(t, s, run) != before {
				t.Fatal("sender retirement changed workflow/budget/history")
			}
		})
	}
}

func TestMessagingObsoleteDispatchContention(t *testing.T) {
	for _, mode := range []string{"advanced", "cancelled"} {
		t.Run(mode, func(t *testing.T) {
			s, a, run, e := messagingRunWithLease(t, 2, 10)
			l, err := s.ClaimForRun(testContext(t), run.RunID, "synthetic-state-first", 60)
			if err != nil {
				t.Fatal(err)
			}
			writer, writerPID := heartbeatProbe(t)
			dispatcher, dispatchPID := heartbeatProbe(t)
			gate, gatePID := holdHeartbeatRow(t, s, `SELECT task_id::text FROM orchestration.task_deliveries WHERE task_id=$1::uuid FOR UPDATE`, l.TaskID)
			stateDone := make(chan error, 1)
			go func() { stateDone <- obsoleteMessage(t, writer, a, l, mode) }()
			waitHeartbeatBlockedBy(t, s, writerPID, gatePID)
			var sends atomic.Int32
			send := func(context.Context, stageEnvelope) (inboxReceipt, error) {
				sends.Add(1)
				return inboxReceipt{}, io.ErrUnexpectedEOF
			}
			dispatched := make(chan error, 1)
			go func() { _, err := dispatcher.dispatchSynthetic(testContext(t), e.MessageID, send); dispatched <- err }()
			waitHeartbeatBlockedBy(t, s, dispatchPID, writerPID)
			if err := gate.Commit(); err != nil {
				t.Fatal(err)
			}
			select {
			case err := <-stateDone:
				if err != nil {
					t.Fatal("state writer failed", err)
				}
			case <-testContext(t).Done():
				t.Fatal("state writer deadline exceeded")
			}
			select {
			case err := <-dispatched:
				if !errors.Is(err, errDeliveryObsolete) {
					t.Fatal("waiting dispatcher missed committed obsolescence", err)
				}
			case <-testContext(t).Done():
				t.Fatal("waiting dispatcher deadline exceeded")
			}
			var wg sync.WaitGroup
			for range 8 {
				wg.Go(func() {
					if r, err := s.dispatchSynthetic(testContext(t), e.MessageID, send); !errors.Is(err, errDeliveryObsolete) || r != (inboxReceipt{}) {
						t.Error("concurrent terminal replay was not stable", err)
					}
				})
			}
			wg.Wait()
			if sends.Load() != 0 {
				t.Fatal("dispatcher sent after terminal disposition committed")
			}
			assertUnsentDisposition(t, s, e)
		})
	}
}

func TestMessagingObsoletePreservesUncertainty(t *testing.T) {
	for _, mode := range []string{"advanced", "cancelled"} {
		for _, lateReceipt := range []bool{false, true} {
			t.Run(mode+map[bool]string{false: "-unknown", true: "-late-receipt"}[lateReceipt], func(t *testing.T) {
				s, a, run, e := messagingRunWithLease(t, 2, 10)
				l, err := s.ClaimForRun(testContext(t), run.RunID, "synthetic-dispatch-first", 60)
				if err != nil {
					t.Fatal(err)
				}
				entered, release := make(chan struct{}), make(chan struct{})
				var once sync.Once
				defer once.Do(func() { close(release) })
				done := make(chan error, 1)
				go func() {
					_, err := s.dispatchSynthetic(testContext(t), e.MessageID, func(ctx context.Context, msg stageEnvelope) (inboxReceipt, error) {
						close(entered)
						select {
						case <-release:
						case <-ctx.Done():
							return inboxReceipt{}, ctx.Err()
						}
						if lateReceipt {
							return s.receiveSynthetic(ctx, msg)
						}
						return inboxReceipt{}, io.ErrUnexpectedEOF
					})
					done <- err
				}()
				select {
				case <-entered:
				case <-testContext(t).Done():
					t.Fatal("transport reservation checkpoint not reached")
				}
				// This real state operation completing proves no transaction spans IPC.
				if err := obsoleteMessage(t, s, a, l, mode); err != nil {
					t.Fatal(err)
				}
				if _, err := s.dispatchSynthetic(testContext(t), e.MessageID, func(context.Context, stageEnvelope) (inboxReceipt, error) {
					t.Error("uncertain stale intent resent")
					return inboxReceipt{}, io.ErrUnexpectedEOF
				}); !errors.Is(err, errDeliveryDeferred) {
					t.Fatal("prior transport reservation incorrectly certified unsent", err)
				}
				once.Do(func() { close(release) })
				select {
				case err := <-done:
					if lateReceipt && err != nil || !lateReceipt && !errors.Is(err, ErrResultUnknown) {
						t.Fatal("late transport evidence not preserved", err)
					}
				case <-testContext(t).Done():
					t.Fatal("transport completion deadline exceeded")
				}
				var phase, status string
				var attempts, proofs int
				if err := s.db.QueryRowContext(testContext(t), `SELECT d.phase,o.status,d.attempts,(SELECT count(*) FROM orchestration.workflow_synthetic_sender_dispositions WHERE message_id=o.message_id) FROM orchestration.workflow_outbox o JOIN orchestration.workflow_synthetic_deliveries d USING(message_id) WHERE o.message_id=$1::uuid`, e.MessageID).Scan(&phase, &status, &attempts, &proofs); err != nil || attempts != 1 || proofs != 0 {
					t.Fatal("prior send history was erased", err)
				}
				if lateReceipt {
					if phase != "OBSOLETE" || status != "DELIVERED" {
						t.Fatal("genuine late receiver ACK lost")
					}
					assertSyntheticEffects(t, s, run, 0, 1)
				} else if phase != "UNKNOWN" || status != "PENDING" {
					t.Fatal("uncertain send incorrectly resolved")
				}
			})
		}
	}
}

func TestMessagingObsoleteRequiresImmutableHistory(t *testing.T) {
	s, a, run, e := messagingRun(t, 2)
	if _, err := s.dispatchSynthetic(testContext(t), e.MessageID, func(context.Context, stageEnvelope) (inboxReceipt, error) { return inboxReceipt{}, io.ErrUnexpectedEOF }); !errors.Is(err, ErrResultUnknown) {
		t.Fatal(err)
	}
	// Mutable counters are deliberately corrupted by the trusted fixture. The
	// append-only reservation remains proof that zero counters do NOT mean unsent.
	if _, err := s.db.ExecContext(testContext(t), `UPDATE orchestration.workflow_synthetic_deliveries SET phase='READY',attempts=0,lease_epoch=0,lease_until=NULL WHERE message_id=$1::uuid`, e.MessageID); err != nil {
		t.Fatal(err)
	}
	l, err := s.ClaimForRun(testContext(t), run.RunID, "synthetic-history", 60)
	if err != nil {
		t.Fatal(err)
	}
	if err := obsoleteMessage(t, s, a, l, "advanced"); err != nil {
		t.Fatal(err)
	}
	if _, err := s.dispatchSynthetic(testContext(t), e.MessageID, func(context.Context, stageEnvelope) (inboxReceipt, error) {
		t.Error("stale history resent")
		return inboxReceipt{}, io.ErrUnexpectedEOF
	}); !errors.Is(err, errDeliveryDeferred) {
		t.Fatal("mutable zero counters overrode immutable send history", err)
	}
	var count int
	if err := s.db.QueryRowContext(testContext(t), `SELECT count(*) FROM orchestration.workflow_synthetic_sender_dispositions WHERE message_id=$1::uuid`, e.MessageID).Scan(&count); err != nil || count != 0 {
		t.Fatal("uncertain history certified unsent", err)
	}
}

func TestMessagingObsoleteCommitResponseLoss(t *testing.T) {
	for _, mode := range []string{"before-forward", "after-confirm", "after-confirm-outage"} {
		t.Run(mode, func(t *testing.T) {
			s, a, run, e := messagingRunWithLease(t, 2, 3)
			l, err := s.ClaimForRun(testContext(t), run.RunID, "synthetic-obsolete-commit", 60)
			if err != nil {
				t.Fatal(err)
			}
			if err := obsoleteMessage(t, s, a, l, "cancelled"); err != nil {
				t.Fatal(err)
			}
			before := snapshotFor(t, s, run)
			url, injected := commitFaultProxy(t, mode)
			faulty, err := Open(testContext(t), url)
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { _ = faulty.Close() })
			r, err := faulty.dispatchSynthetic(testContext(t), e.MessageID, func(context.Context, stageEnvelope) (inboxReceipt, error) {
				t.Error("obsolete COMMIT sent")
				return inboxReceipt{}, io.ErrUnexpectedEOF
			})
			select {
			case <-injected:
			case <-testContext(t).Done():
				t.Fatal("sender disposition COMMIT fault not injected")
			}
			if r != (inboxReceipt{}) || (mode == "after-confirm" && !errors.Is(err, errDeliveryObsolete)) || (mode != "after-confirm" && !errors.Is(err, ErrResultUnknown)) {
				t.Fatal("sender disposition COMMIT misclassified", err)
			}
			if mode == "before-forward" {
				if err := s.reconcileUnsentDisposition(testContext(t), e); !errors.Is(err, ErrResultUnknown) {
					t.Fatal("uncommitted sender evidence fabricated", err)
				}
				var phase, status string
				if err := s.db.QueryRowContext(testContext(t), `SELECT phase,status FROM orchestration.workflow_synthetic_deliveries JOIN orchestration.workflow_outbox USING(message_id) WHERE message_id=$1::uuid`, e.MessageID).Scan(&phase, &status); err != nil || phase != "READY" || status != "PENDING" {
					t.Fatal("late transaction rollback was not atomic", err)
				}
			} else {
				if err := s.reconcileUnsentDisposition(testContext(t), e); !errors.Is(err, errDeliveryObsolete) {
					t.Fatal("restored original-identity sender lookup failed", err)
				}
				assertUnsentDisposition(t, s, e)
			}
			if snapshotFor(t, s, run) != before {
				t.Fatal("sender COMMIT uncertainty changed workflow")
			}
		})
	}
}

func TestMessagingObsoleteConstraintsAndPermissions(t *testing.T) {
	s, a, run, e := messagingRun(t, 2)
	l, err := s.ClaimForRun(testContext(t), run.RunID, "synthetic-sender-acl", 60)
	if err != nil {
		t.Fatal(err)
	}
	if err := obsoleteMessage(t, s, a, l, "advanced"); err != nil {
		t.Fatal(err)
	}
	for _, query := range []string{"UPDATE orchestration.workflow_synthetic_sender_dispositions SET message_id=message_id WHERE false", "DELETE FROM orchestration.workflow_synthetic_sender_dispositions WHERE false"} {
		_, err := s.db.ExecContext(testContext(t), query)
		var pg *pgconn.PgError
		if !errors.As(err, &pg) || pg.Code != "42501" {
			t.Fatal("sender evidence mutable by runtime", err)
		}
	}
	_, _, other, _ := messagingRun(t, 2)
	_, err = s.db.ExecContext(testContext(t), `INSERT INTO orchestration.workflow_synthetic_sender_dispositions(message_id,run_id,payload_sha256,intent_run_version,observed_prior_run_version,disposition) VALUES($1::uuid,$2::uuid,$3,1,1,'NEVER_SENT_OBSOLETE')`, e.MessageID, other.RunID, e.PayloadSHA256)
	var pg *pgconn.PgError
	if !errors.As(err, &pg) || pg.Code != "23503" {
		t.Fatal("sender evidence accepted conflicting run/hash binding", err)
	}
}
