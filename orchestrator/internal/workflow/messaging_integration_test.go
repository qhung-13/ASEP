//go:build integration

package workflow

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"os/exec"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgconn"
	"github.com/qhung-13/ASEP/orchestrator/internal/testsupport"
)

func messagingRun(t *testing.T, limit int) (*Store, Admission, AdmissionReceipt, stageEnvelope) {
	t.Helper()
	return messagingRunWithLease(t, limit, 1)
}

func messagingRunWithLease(t *testing.T, limit, seconds int) (*Store, Admission, AdmissionReceipt, stageEnvelope) {
	t.Helper()
	s, a, run := boundedRun(t, syntheticProfile{2, 1, 1, true}, 2)
	var id string
	if err := s.db.QueryRowContext(testContext(t), `SELECT message_id::text FROM orchestration.workflow_outbox WHERE run_id=$1::uuid`, run.RunID).Scan(&id); err != nil {
		t.Fatal(err)
	}
	if err := s.enrollSyntheticDelivery(testContext(t), id, limit, seconds); err != nil {
		t.Fatal("delivery enrollment failed", err)
	}
	e, err := readStageEnvelope(testContext(t), s.db, id)
	if err != nil {
		t.Fatal(err)
	}
	return s, a, run, e
}

func assertSyntheticEffects(t *testing.T, s *Store, run AdmissionReceipt, effects, inbox int) {
	t.Helper()
	var actualEffects, actualInbox int
	if err := s.db.QueryRowContext(testContext(t), `SELECT (SELECT count(*) FROM orchestration.workflow_synthetic_effects WHERE run_id=$1::uuid),(SELECT count(*) FROM orchestration.workflow_synthetic_inbox WHERE run_id=$1::uuid)`, run.RunID).Scan(&actualEffects, &actualInbox); err != nil || actualEffects != effects || actualInbox != inbox {
		t.Fatal("synthetic effect/inbox counts differ", actualEffects, actualInbox, err)
	}
}

func waitDeliveryExpiry(t *testing.T, s *Store, id string) {
	t.Helper()
	ticker := time.NewTicker(10 * time.Millisecond)
	defer ticker.Stop()
	for {
		var expired bool
		if err := s.db.QueryRowContext(testContext(t), `SELECT lease_until<=clock_timestamp() FROM orchestration.workflow_synthetic_deliveries WHERE message_id=$1::uuid`, id).Scan(&expired); err != nil {
			t.Fatal(err)
		}
		if expired {
			return
		}
		select {
		case <-ticker.C:
		case <-testContext(t).Done():
			t.Fatal("delivery expiry deadline")
		}
	}
}

func TestMessagingDuplicateAndReorderedDelivery(t *testing.T) {
	s, a, run, old := messagingRun(t, 2)
	lease, err := s.ClaimForRun(testContext(t), run.RunID, "synthetic-message", 60)
	if err != nil {
		t.Fatal(err)
	}
	advanced, err := s.advanceInvestigation(testContext(t), validAdvance(t, a, lease))
	if err != nil {
		t.Fatal(err)
	}
	if err := s.enrollSyntheticDelivery(testContext(t), advanced.OutboxMessageID, 2, 1); err != nil {
		t.Fatal(err)
	}
	next, err := readStageEnvelope(testContext(t), s.db, advanced.OutboxMessageID)
	if err != nil || old.Fields != 4 || next.Fields != 6 {
		t.Fatal("original codecs not preserved", err)
	}
	before := snapshotFor(t, s, run)
	transport := func(ctx context.Context, e stageEnvelope) (inboxReceipt, error) {
		var wg sync.WaitGroup
		replies := make(chan inboxReceipt, 8)
		failures := make(chan error, 8)
		for range 8 {
			wg.Go(func() { r, err := s.receiveSynthetic(ctx, e); replies <- r; failures <- err })
		}
		wg.Wait()
		close(replies)
		close(failures)
		for err := range failures {
			if err != nil {
				return inboxReceipt{}, err
			}
		}
		var original inboxReceipt
		for r := range replies {
			if original.MessageID == "" {
				original = r
			}
			if r != original {
				return inboxReceipt{}, ErrResultConflict
			}
		}
		return original, nil
	}
	r, err := s.dispatchSynthetic(testContext(t), next.MessageID, transport)
	if err != nil || r.Disposition != "ACCEPTED" {
		t.Fatal("concurrent receiver did not deduplicate", err)
	}
	obsolete, err := s.receiveSynthetic(testContext(t), old)
	if err != nil || obsolete.Disposition != "OBSOLETE" {
		t.Fatal("out-of-order original message not obsolete", err)
	}
	if err := s.acknowledgeSyntheticDelivery(testContext(t), obsolete); err != nil {
		t.Fatal(err)
	}
	for range 3 {
		if err := s.acknowledgeSyntheticDelivery(testContext(t), r); err != nil {
			t.Fatal(err)
		}
	}
	if snapshotFor(t, s, run) != before {
		t.Fatal("transport receipt incorrectly advanced workflow")
	}
	assertSyntheticEffects(t, s, run, 1, 2)
	got, err := storeForTest(t).dispatchSynthetic(testContext(t), next.MessageID, func(context.Context, stageEnvelope) (inboxReceipt, error) {
		t.Error("receipt recovery resent an accepted message")
		return inboxReceipt{}, io.ErrUnexpectedEOF
	})
	if err != nil || got != r {
		t.Fatal("reconnect lost original message receipt", err)
	}
	t.Log("RF-09 four/six-field codecs retain original IDs/hashes; eight concurrent receives yield one effect; reordered initial intent OBSOLETE; no workflow progress from callbacks")
}

func TestMessagingRejectsConflictsAndAuthority(t *testing.T) {
	for _, mode := range []string{"hash", "task", "run", "version", "policy-epoch", "revoked", "cancelled", "unknown-fields"} {
		t.Run(mode, func(t *testing.T) {
			s, a, run, e := messagingRun(t, 2)
			switch mode {
			case "hash":
				e.PayloadSHA256 = strings.Repeat("e", 64)
			case "task":
				e.TaskID, _ = freshUUID()
			case "run":
				e.RunID, _ = freshUUID()
			case "version":
				e.RunVersion++
			case "policy-epoch":
				if _, err := s.db.ExecContext(testContext(t), `UPDATE orchestration.workflow_runs SET policy_epoch=policy_epoch+1 WHERE run_id=$1::uuid`, run.RunID); err != nil {
					t.Fatal(err)
				}
			case "revoked":
				if err := s.revokeSyntheticPolicy(testContext(t), run.RunID, 1); err != nil {
					t.Fatal(err)
				}
			case "cancelled":
				l, err := s.ClaimForRun(testContext(t), run.RunID, "synthetic-message-cancel", 60)
				if err != nil {
					t.Fatal(err)
				}
				if _, err := s.requestCancellation(testContext(t), cancellationForTest(t, a, l, 1)); err != nil {
					t.Fatal(err)
				}
			case "unknown-fields":
				if _, err := s.db.ExecContext(testContext(t), `UPDATE orchestration.workflow_outbox SET payload=payload||'{"unknown":true}'::jsonb WHERE message_id=$1::uuid`, e.MessageID); err != nil {
					t.Fatal(err)
				}
			}
			before := snapshotFor(t, s, run)
			r, err := s.receiveSynthetic(testContext(t), e)
			if mode == "cancelled" {
				if err != nil || r.Disposition != "OBSOLETE" {
					t.Fatal("cancelled intent not evidence-only", err)
				}
				assertSyntheticEffects(t, s, run, 0, 1)
			} else {
				if err == nil {
					t.Fatal("forged/stale/revoked message accepted")
				}
				assertSyntheticEffects(t, s, run, 0, 0)
			}
			if snapshotFor(t, s, run) != before {
				t.Fatal("rejected delivery changed workflow")
			}
		})
	}
}

func TestMessagingDispatchBoundsAndLateEvidence(t *testing.T) {
	s, _, run, e := messagingRun(t, 1)
	sends := 0
	drop := func(context.Context, stageEnvelope) (inboxReceipt, error) {
		sends++
		return inboxReceipt{}, io.ErrUnexpectedEOF
	}
	if _, err := s.dispatchSynthetic(testContext(t), e.MessageID, drop); !errors.Is(err, ErrResultUnknown) {
		t.Fatal("dropped send did not preserve UNKNOWN", err)
	}
	if _, err := s.dispatchSynthetic(testContext(t), e.MessageID, drop); !errors.Is(err, errDeliveryDeferred) {
		t.Fatal("active dispatch lease allowed retry", err)
	}
	waitDeliveryExpiry(t, s, e.MessageID)
	if _, err := s.dispatchSynthetic(testContext(t), e.MessageID, drop); !errors.Is(err, errDeliveryExhausted) || sends != 1 {
		t.Fatal("exhausted delivery sent again", err, sends)
	}
	// Model an already sent request arriving late. The receiver contract, not
	// sender exhaustion, controls the one local logical effect.
	r, err := s.receiveSynthetic(testContext(t), e)
	if err != nil {
		t.Fatal(err)
	}
	before := snapshotFor(t, s, run)
	got, err := s.dispatchSynthetic(testContext(t), e.MessageID, drop)
	if err != nil || got != r || sends != 1 || snapshotFor(t, s, run) != before {
		t.Fatal("late evidence caused redelivery/progression", err)
	}
	var attempts int
	var phase string
	if err := s.db.QueryRowContext(testContext(t), `SELECT attempts,phase FROM orchestration.workflow_synthetic_deliveries WHERE message_id=$1::uuid`, e.MessageID).Scan(&attempts, &phase); err != nil || attempts != 1 || phase != "DELIVERED" {
		t.Fatal("late evidence reset accounting", err)
	}
	assertSyntheticEffects(t, s, run, 1, 1)
}

func TestMessagingDeferredAndForgedCallback(t *testing.T) {
	s, _, run, e := messagingRun(t, 2)
	l, err := s.ClaimForRun(testContext(t), run.RunID, "synthetic-expiring-receiver", 1)
	if err != nil {
		t.Fatal(err)
	}
	waitForLeaseExpiry(t, s, l.TaskID)
	if _, err := s.dispatchSynthetic(testContext(t), e.MessageID, s.receiveSynthetic); !errors.Is(err, ErrResultUnknown) || !errors.Is(err, errDeliveryDeferred) {
		t.Fatal("expired receiver authority was not deferred", err)
	}
	assertSyntheticEffects(t, s, run, 0, 0)
	var phase string
	if err := s.db.QueryRowContext(testContext(t), `SELECT phase FROM orchestration.workflow_synthetic_deliveries WHERE message_id=$1::uuid`, e.MessageID).Scan(&phase); err != nil || phase != "DEFERRED" {
		t.Fatal("deferred identity not durable", err)
	}
	if _, err := s.ClaimForRun(testContext(t), run.RunID, "synthetic-restored", 60); err != nil {
		t.Fatal(err)
	}
	waitDeliveryExpiry(t, s, e.MessageID)
	r, err := s.dispatchSynthetic(testContext(t), e.MessageID, s.receiveSynthetic)
	if err != nil {
		t.Fatal("deferred delivery could not recover", err)
	}
	forged := r
	forged.ReceiptID, _ = freshUUID()
	if err := s.acknowledgeSyntheticDelivery(testContext(t), forged); !errors.Is(err, ErrResultConflict) {
		t.Fatal("forged callback trusted supplied receipt", err)
	}
	assertSyntheticEffects(t, s, run, 1, 1)
}

func TestMessagingWrongTransportReceipt(t *testing.T) {
	s, _, run, e := messagingRun(t, 2)
	other, _, _, otherEnvelope := messagingRun(t, 2)
	receipt, err := other.receiveSynthetic(testContext(t), otherEnvelope)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.dispatchSynthetic(testContext(t), e.MessageID, func(context.Context, stageEnvelope) (inboxReceipt, error) { return receipt, nil }); !errors.Is(err, ErrResultConflict) {
		t.Fatal("other message receipt acknowledged original send", err)
	}
	assertSyntheticEffects(t, s, run, 0, 0)
}

func TestMessagingCancellationDuringDelivery(t *testing.T) {
	s, a, run, e := messagingRun(t, 2)
	r, err := s.dispatchSynthetic(testContext(t), e.MessageID, func(ctx context.Context, envelope stageEnvelope) (inboxReceipt, error) {
		queued := TaskLease{RunID: run.RunID, TaskID: run.TaskID, AttemptID: run.AttemptID, Stage: "INVESTIGATING"}
		if _, err := s.requestCancellation(ctx, cancellationForTest(t, a, queued, 1)); err != nil {
			return inboxReceipt{}, err
		}
		return s.receiveSynthetic(ctx, envelope)
	})
	if err != nil || r.Disposition != "OBSOLETE" || snapshotFor(t, s, run).state != "CANCEL_REQUESTED" {
		t.Fatal("transport held run lock or bypassed concurrent cancellation", err)
	}
	assertSyntheticEffects(t, s, run, 0, 1)
}

func TestMessagingCommitResponseLoss(t *testing.T) {
	for _, mode := range []string{"before-forward", "after-confirm", "after-confirm-outage"} {
		t.Run(mode, func(t *testing.T) {
			s, _, run, e := messagingRun(t, 2)
			url, injected := commitFaultProxy(t, mode)
			faulty, err := Open(testContext(t), url)
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { _ = faulty.Close() })
			r, err := faulty.receiveSynthetic(testContext(t), e)
			select {
			case <-injected:
			case <-testContext(t).Done():
				t.Fatal("receiver COMMIT fault not injected")
			}
			if mode == "after-confirm" {
				if err != nil || r.MessageID != e.MessageID {
					t.Fatal("receiver durable receipt not reconciled", err)
				}
			} else if !errors.Is(err, ErrResultUnknown) || r != (inboxReceipt{}) {
				t.Fatal("unproven inbox COMMIT acknowledged", err)
			}
			if mode == "before-forward" {
				assertSyntheticEffects(t, s, run, 0, 0)
				return
			}
			got, err := s.reconcileInbox(testContext(t), e)
			if err != nil {
				t.Fatal(err)
			}
			if replay, err := s.receiveSynthetic(testContext(t), e); err != nil || replay != got {
				t.Fatal("receiver COMMIT uncertainty duplicated effect", err)
			}
			assertSyntheticEffects(t, s, run, 1, 1)
		})
	}
}

func TestMessagingInboxConstraintsAndPermissions(t *testing.T) {
	s, _, run, e := messagingRun(t, 2)
	for _, table := range []string{"workflow_synthetic_delivery_policies", "workflow_synthetic_delivery_attempts", "workflow_synthetic_inbox", "workflow_synthetic_effects", "workflow_synthetic_delivery_receipts"} {
		for _, query := range []string{"UPDATE orchestration." + table + " SET message_id=message_id WHERE false", "DELETE FROM orchestration." + table + " WHERE false"} {
			_, err := s.db.ExecContext(testContext(t), query)
			var pg *pgconn.PgError
			if !errors.As(err, &pg) || pg.Code != "42501" {
				t.Fatal("messaging receipt/identity mutable", err)
			}
		}
	}
	_, _, other, _ := messagingRun(t, 1)
	id, _ := freshUUID()
	_, err := s.db.ExecContext(testContext(t), `INSERT INTO orchestration.workflow_synthetic_inbox(message_id,run_id,payload_sha256,task_id,attempt_id,receipt_id,disposition,run_version) VALUES($1::uuid,$2::uuid,$3,$4::uuid,$5::uuid,$6::uuid,'ACCEPTED',1)`, e.MessageID, run.RunID, e.PayloadSHA256, other.TaskID, other.AttemptID, id)
	var pg *pgconn.PgError
	if !errors.As(err, &pg) || pg.Code != "23503" {
		t.Fatal("cross-run inbox binding accepted", err)
	}
	assertSyntheticEffects(t, s, run, 0, 0)
}

func TestMessagingReceiptTimestampIdentity(t *testing.T) {
	s, _, run, e := messagingRun(t, 2)
	original, err := s.receiveSynthetic(testContext(t), e)
	if err != nil {
		t.Fatal("receipt fixture unavailable", err)
	}
	before := snapshotFor(t, s, run)
	for _, field := range []string{"timestamp", "receipt", "hash", "task", "attempt", "version", "disposition"} {
		t.Run("changed-"+field, func(t *testing.T) {
			changed := original
			switch field {
			case "timestamp":
				changed.ReceivedAt = changed.ReceivedAt.Add(time.Nanosecond)
			case "receipt":
				changed.ReceiptID, _ = freshUUID()
			case "hash":
				changed.PayloadSHA256 = strings.Repeat("f", 64)
			case "task":
				changed.TaskID, _ = freshUUID()
			case "attempt":
				changed.AttemptID, _ = freshUUID()
			case "version":
				changed.RunVersion++
			case "disposition":
				changed.Disposition = "OBSOLETE"
			}
			if err := s.acknowledgeSyntheticDelivery(testContext(t), changed); !errors.Is(err, ErrResultConflict) {
				t.Fatal("altered receipt was accepted", err)
			}
		})
	}
	var acks int
	var status string
	if err := s.db.QueryRowContext(testContext(t), `SELECT status,(SELECT count(*) FROM orchestration.workflow_synthetic_delivery_receipts WHERE message_id=$1::uuid) FROM orchestration.workflow_outbox WHERE message_id=$1::uuid`, e.MessageID).Scan(&status, &acks); err != nil || status != "PENDING" || acks != 0 {
		t.Fatal("rejected receipt changed durable acknowledgement", err)
	}
	for _, location := range []*time.Location{time.UTC, time.FixedZone("audit-zero-offset", 0), time.FixedZone("audit-offset", 7*60*60)} {
		t.Run("same-instant-"+location.String(), func(t *testing.T) {
			ack := original
			ack.ReceivedAt = ack.ReceivedAt.In(location)
			if !ack.ReceivedAt.Equal(original.ReceivedAt) {
				t.Fatal("timezone fixture changed the timestamp instant")
			}
			if err := s.acknowledgeSyntheticDelivery(testContext(t), ack); err != nil {
				t.Fatal("same receipt instant rejected due to location metadata", err)
			}
		})
	}
	t.Run("json-roundtrip", func(t *testing.T) {
		wire := original
		wire.ReceivedAt = wire.ReceivedAt.UTC()
		body, err := json.Marshal(wire)
		if err != nil {
			t.Fatal("receipt encoding failed")
		}
		var ack inboxReceipt
		if json.Unmarshal(body, &ack) != nil || !ack.ReceivedAt.Equal(original.ReceivedAt) {
			t.Fatal("wire receipt changed timestamp instant")
		}
		if err := s.acknowledgeSyntheticDelivery(testContext(t), ack); err != nil {
			t.Fatal("JSON receipt timestamp representation rejected", err)
		}
	})
	actual, err := queryInbox(testContext(t), s.db, e.MessageID, e.PayloadSHA256)
	if err != nil || actual != original || snapshotFor(t, s, run) != before {
		t.Fatal("acknowledgement changed original evidence or workflow", err)
	}
	assertSyntheticEffects(t, s, run, 1, 1)
}

type inboxControllerConfig struct {
	DatabaseURL, Nonce, Prefix string
	Envelope                   stageEnvelope
}

func runInboxController() int {
	info, err := os.Stdin.Stat()
	if err != nil || info.Mode()&os.ModeNamedPipe == 0 {
		return 2
	}
	marker := os.NewFile(3, "inbox-commit-checkpoint")
	if marker == nil {
		return 2
	}
	defer marker.Close()
	if info, err := marker.Stat(); err != nil || info.Mode()&os.ModeNamedPipe == 0 {
		return 2
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	stop := context.AfterFunc(ctx, func() { _ = os.Stdin.Close(); _ = marker.Close(); _ = os.Stdout.Close() })
	defer stop()
	d := json.NewDecoder(io.LimitReader(os.Stdin, 16*1024))
	d.DisallowUnknownFields()
	var c inboxControllerConfig
	if d.Decode(&c) != nil {
		return 2
	}
	if os.Setenv("ASEP_VN_T10_DISPOSABLE_DB", "true") != nil || os.Setenv("ASEP_VN_T10_TEST_PREFIX", c.Prefix) != nil {
		return 2
	}
	db, err := testsupport.Connect(ctx, c.DatabaseURL, c.Nonce)
	if err != nil {
		return 3
	}
	defer db.Close()
	s := &Store{db: db, databaseURL: c.DatabaseURL}
	if _, err := s.SyntheticRunStatus(ctx, c.Envelope.RunID, c.Prefix); err != nil {
		return 2
	}
	r, err := s.receiveSynthetic(ctx, c.Envelope)
	if err != nil {
		return 2
	}
	if _, err := marker.Write([]byte{1}); err != nil {
		return 2
	}
	_ = marker.Close()
	var release string
	if d.Decode(&release) != nil || release != "emit-ack" {
		return 2
	}
	if json.NewEncoder(os.Stdout).Encode(r) != nil {
		return 2
	}
	return 0
}

func startInboxController(t *testing.T, c inboxControllerConfig) *resultController {
	t.Helper()
	ctx, cancel := context.WithTimeout(t.Context(), 15*time.Second)
	t.Cleanup(cancel)
	input, control := resultProcessPipe(t)
	checkpoint, marker := resultProcessPipe(t)
	output, ack := resultProcessPipe(t)
	cmd := exec.CommandContext(ctx, os.Args[0], "-test.run=^$")
	// Exercise wire timestamps independently of the parent host's timezone.
	cmd.Env = append(testsupport.Environment(), "ASEP_RF09_RECEIVER=true", "TZ=UTC")
	cmd.Stdin, cmd.Stdout, cmd.Stderr = input, ack, io.Discard
	cmd.ExtraFiles = []*os.File{marker}
	cmd.WaitDelay = time.Second
	if cmd.Start() != nil {
		t.Fatal("trusted inbox controller failed")
	}
	_ = input.Close()
	_ = marker.Close()
	_ = ack.Close()
	check := make(chan error, 1)
	reply := make(chan resultACK, 1)
	child := &resultController{command: cmd, input: control, checkpoint: check, ack: reply, done: make(chan struct{})}
	go func() { child.waitErr = cmd.Wait(); close(child.done) }()
	go func() {
		var b [1]byte
		_, err := io.ReadFull(checkpoint, b[:])
		if err == nil && b[0] != 1 {
			err = errors.New("invalid inbox checkpoint")
		}
		check <- err
	}()
	go func() { b, err := io.ReadAll(io.LimitReader(output, 16*1024)); reply <- resultACK{b, err} }()
	t.Cleanup(func() {
		_ = cmd.Process.Kill()
		cancel()
		_ = control.Close()
		select {
		case <-child.done:
		case <-time.After(3 * time.Second):
			t.Error("inbox controller not reaped")
		}
	})
	if json.NewEncoder(control).Encode(c) != nil {
		t.Fatal("private inbox configuration failed")
	}
	return child
}

func TestMessagingProcessCrashBeforeACK(t *testing.T) {
	for _, mode := range []string{"crash", "ack", "wrong-proof"} {
		t.Run(mode, func(t *testing.T) {
			// Go's race runtime delays normal child exit; this explicit fixture
			// profile bounds the complete process/ACK exchange without a 1s race.
			s, _, run, e := messagingRunWithLease(t, 2, 3)
			var original inboxReceipt
			transport := func(ctx context.Context, envelope stageEnvelope) (inboxReceipt, error) {
				config := inboxControllerConfig{testsupport.DatabaseURL(t), os.Getenv("ASEP_VN_T11_DISPOSABLE_NONCE"), os.Getenv("ASEP_VN_T10_TEST_PREFIX"), envelope}
				if mode == "wrong-proof" {
					if config.Nonce[0] == '0' {
						config.Nonce = "1" + config.Nonce[1:]
					} else {
						config.Nonce = "0" + config.Nonce[1:]
					}
				}
				child := startInboxController(t, config)
				select {
				case err := <-child.checkpoint:
					if mode == "wrong-proof" {
						if err == nil {
							t.Fatal("invalid B0 proof reached inbox COMMIT")
						}
						awaitResultControllerExit(t, child)
						if len(awaitResultACK(t, child)) != 0 {
							t.Fatal("rejected controller emitted ACK")
						}
						return inboxReceipt{}, io.ErrUnexpectedEOF
					}
					if err != nil {
						t.Fatal("post-inbox-COMMIT checkpoint missing")
					}
				case <-ctx.Done():
					t.Fatal("inbox checkpoint deadline")
				}
				var err error
				original, err = queryInbox(testContext(t), s.db, envelope.MessageID, envelope.PayloadSHA256)
				if err != nil {
					t.Fatal("independent receipt proof unavailable", err)
				}
				if mode == "crash" {
					killFinalizationController(t, child)
					return inboxReceipt{}, io.ErrUnexpectedEOF
				}
				if json.NewEncoder(child.input).Encode("emit-ack") != nil {
					t.Fatal("ACK gate failed")
				}
				var ack inboxReceipt
				if json.Unmarshal(awaitResultACK(t, child), &ack) != nil || !ack.ReceivedAt.Equal(original.ReceivedAt) {
					t.Fatal("ACK differed from independent durable receipt")
				}
				comparison := ack
				comparison.ReceivedAt = original.ReceivedAt
				if comparison != original {
					t.Fatal("ACK identity differed from independent durable receipt")
				}
				awaitResultControllerExit(t, child)
				if child.waitErr != nil {
					t.Fatal("positive ACK child failed")
				}
				return ack, nil
			}
			r, err := s.dispatchSynthetic(testContext(t), e.MessageID, transport)
			if mode == "wrong-proof" {
				if !errors.Is(err, ErrResultUnknown) {
					t.Fatal("invalid proof was acknowledged", err)
				}
				assertSyntheticEffects(t, s, run, 0, 0)
				return
			}
			if mode == "crash" {
				if !errors.Is(err, ErrResultUnknown) {
					t.Fatal("crashed receiver falsely acknowledged", err)
				}
				r, err = storeForTest(t).dispatchSynthetic(testContext(t), e.MessageID, func(context.Context, stageEnvelope) (inboxReceipt, error) {
					t.Fatal("recovery resent committed receiver effect")
					return inboxReceipt{}, io.ErrUnexpectedEOF
				})
			}
			if err != nil || !r.ReceivedAt.Equal(original.ReceivedAt) {
				t.Fatal("process recovery lost exact receipt", err)
			}
			comparison := r
			comparison.ReceivedAt = original.ReceivedAt
			if comparison != original {
				t.Fatal("process recovery changed receipt identity")
			}
			assertSyntheticEffects(t, s, run, 1, 1)
		})
	}
}
