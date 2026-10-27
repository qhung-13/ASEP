package workflow

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"time"
)

var (
	errDeliveryDeferred  = errors.New("synthetic delivery deferred; no progression authority")
	errDeliveryExhausted = errors.New("synthetic delivery limit exhausted; reconcile original identity")
)

type stageEnvelope struct {
	MessageID, RunID, AttemptID, TaskID, Stage, PayloadSHA256, EvidenceRef string
	RunVersion                                                             int64
	Fields                                                                 int
}

func (e stageEnvelope) body() ([]byte, error) {
	if !canonicalUUID(e.MessageID) || !canonicalUUID(e.RunID) || !canonicalUUID(e.TaskID) || !canonicalUUID(e.AttemptID) {
		return nil, ErrResultConflict
	}
	if e.Fields == 4 && e.Stage == "INVESTIGATING" && e.RunVersion == 1 && e.EvidenceRef == "" {
		return json.Marshal(struct {
			RunID     string `json:"run_id"`
			AttemptID string `json:"attempt_id"`
			TaskID    string `json:"task_id"`
			Stage     string `json:"stage"`
		}{e.RunID, e.AttemptID, e.TaskID, e.Stage})
	}
	if e.Fields == 6 && e.Stage == "PLANNING" && e.RunVersion > 1 {
		return json.Marshal(struct {
			RunID       string `json:"run_id"`
			AttemptID   string `json:"attempt_id"`
			TaskID      string `json:"task_id"`
			Stage       string `json:"stage"`
			RunVersion  int64  `json:"run_version"`
			EvidenceRef string `json:"evidence_ref"`
		}{e.RunID, e.AttemptID, e.TaskID, e.Stage, e.RunVersion, e.EvidenceRef})
	}
	return nil, ErrResultConflict
}

// Recognize only the original four- and six-field codecs; reconstruct their
// exact Go JSON hash, not PostgreSQL jsonb's rendering. Never replace identities.
func readStageEnvelope(ctx context.Context, db executionReader, id string) (stageEnvelope, error) {
	e := stageEnvelope{MessageID: id}
	var raw []byte
	var event string
	err := db.QueryRowContext(ctx, `SELECT run_id::text,event_type,payload_sha256,payload FROM orchestration.workflow_outbox WHERE message_id=$1::uuid`, id).Scan(&e.RunID, &event, &e.PayloadSHA256, &raw)
	if err != nil {
		return stageEnvelope{}, err
	}
	if event != "STAGE_TASK_QUEUED" {
		return stageEnvelope{}, ErrResultConflict
	}
	var fields map[string]json.RawMessage
	if json.Unmarshal(raw, &fields) != nil || (len(fields) != 4 && len(fields) != 6) {
		return stageEnvelope{}, ErrResultConflict
	}
	for key := range fields {
		if key != "run_id" && key != "attempt_id" && key != "task_id" && key != "stage" && key != "run_version" && key != "evidence_ref" {
			return stageEnvelope{}, ErrResultConflict
		}
	}
	var body struct {
		RunID       string `json:"run_id"`
		AttemptID   string `json:"attempt_id"`
		TaskID      string `json:"task_id"`
		Stage       string `json:"stage"`
		RunVersion  int64  `json:"run_version"`
		EvidenceRef string `json:"evidence_ref"`
	}
	if json.Unmarshal(raw, &body) != nil || body.RunID != e.RunID {
		return stageEnvelope{}, ErrResultConflict
	}
	e.AttemptID, e.TaskID, e.Stage, e.RunVersion, e.EvidenceRef, e.Fields = body.AttemptID, body.TaskID, body.Stage, body.RunVersion, body.EvidenceRef, len(fields)
	if e.Fields == 4 {
		e.RunVersion = 1
	} else if fields["run_version"] == nil || fields["evidence_ref"] == nil {
		return stageEnvelope{}, ErrResultConflict
	}
	canonical, err := e.body()
	if err != nil {
		return stageEnvelope{}, err
	}
	hash := sha256.Sum256(canonical)
	if hex.EncodeToString(hash[:]) != e.PayloadSHA256 {
		return stageEnvelope{}, ErrResultConflict
	}
	var bound bool
	if e.Fields == 4 {
		err = db.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM orchestration.stage_attempts a
            JOIN orchestration.task_deliveries t USING(run_id,attempt_id)
            JOIN orchestration.workflow_transitions w USING(run_id)
            WHERE a.run_id=$1::uuid AND a.attempt_id=$2::uuid AND t.task_id=$3::uuid
              AND a.stage='INVESTIGATING' AND t.stage=a.stage AND a.semantic_attempt_no=1
              AND w.prior_run_version=0 AND w.from_state='ADMITTED' AND w.to_state='INVESTIGATING')`, e.RunID, e.AttemptID, e.TaskID).Scan(&bound)
	} else {
		err = db.QueryRowContext(ctx, `SELECT EXISTS(
            SELECT 1 FROM orchestration.workflow_result_receipts r JOIN orchestration.workflow_transitions t USING(transition_id)
              WHERE r.outbox_message_id=$1::uuid AND r.run_id=$2::uuid AND r.next_task_id=$3::uuid AND r.next_attempt_id=$4::uuid AND r.accepted_run_version=$5 AND t.evidence_ref=$6
            UNION ALL SELECT 1 FROM orchestration.workflow_retry_receipts r JOIN orchestration.workflow_transitions t USING(transition_id)
              WHERE r.outbox_message_id=$1::uuid AND r.run_id=$2::uuid AND r.next_task_id=$3::uuid AND r.next_attempt_id=$4::uuid AND r.expected_run_version+1=$5 AND t.evidence_ref=$6)`, e.MessageID, e.RunID, e.TaskID, e.AttemptID, e.RunVersion, e.EvidenceRef).Scan(&bound)
	}
	if err != nil {
		return stageEnvelope{}, err
	}
	if !bound {
		return stageEnvelope{}, ErrResultConflict
	}
	return e, nil
}

type syntheticDeliveryPolicy struct {
	MaximumAttempts, LeaseSeconds int
	PolicyEpoch                   int64
	SourceSHA256                  string
}

func readDeliveryPolicy(ctx context.Context, db executionReader, id string) (syntheticDeliveryPolicy, error) {
	var p syntheticDeliveryPolicy
	err := db.QueryRowContext(ctx, `SELECT maximum_attempts,lease_seconds,policy_epoch,source_sha256 FROM orchestration.workflow_synthetic_delivery_policies WHERE message_id=$1::uuid`, id).Scan(&p.MaximumAttempts, &p.LeaseSeconds, &p.PolicyEpoch, &p.SourceSHA256)
	return p, err
}

func (s *Store) enrollSyntheticDelivery(ctx context.Context, id string, maximumAttempts, leaseSeconds int) error {
	if !canonicalUUID(id) || maximumAttempts < 1 || leaseSeconds < 1 || leaseSeconds > 300 {
		return ErrTransitionRejected
	}
	e, err := readStageEnvelope(ctx, s.db, id)
	if err != nil {
		return err
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var source string
	var epoch int64
	if err := tx.QueryRowContext(ctx, `SELECT policy_epoch,source_fingerprint_sha256 FROM orchestration.workflow_runs WHERE run_id=$1::uuid FOR UPDATE`, e.RunID).Scan(&epoch, &source); err != nil {
		return err
	}
	if old, err := readDeliveryPolicy(ctx, tx, id); err == nil {
		if old.MaximumAttempts != maximumAttempts || old.LeaseSeconds != leaseSeconds {
			return ErrResultConflict
		}
		return nil
	} else if !errors.Is(err, sql.ErrNoRows) {
		return err
	}
	if _, err := readSyntheticProfile(ctx, tx, e.RunID); err != nil {
		return err
	}
	if err := checkBoundedPolicy(ctx, tx, e.RunID, epoch); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO orchestration.workflow_synthetic_delivery_policies(message_id,run_id,payload_sha256,policy_epoch,source_sha256,maximum_attempts,lease_seconds) VALUES($1::uuid,$2::uuid,$3,$4,$5,$6,$7)`, id, e.RunID, e.PayloadSHA256, epoch, source, maximumAttempts, leaseSeconds); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO orchestration.workflow_synthetic_deliveries(message_id,phase,attempts,lease_epoch) VALUES($1::uuid,'READY',0,0)`, id); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return &resultUnknownError{err}
	}
	return nil
}

type inboxReceipt struct {
	MessageID, RunID, PayloadSHA256, TaskID, AttemptID, ReceiptID, Disposition string
	RunVersion                                                                 int64
	ReceivedAt                                                                 time.Time
}

func queryInbox(ctx context.Context, db executionReader, id, hash string) (inboxReceipt, error) {
	var r inboxReceipt
	err := db.QueryRowContext(ctx, `SELECT message_id::text,run_id::text,payload_sha256,task_id::text,attempt_id::text,receipt_id::text,disposition,run_version,received_at FROM orchestration.workflow_synthetic_inbox WHERE message_id=$1::uuid`, id).Scan(&r.MessageID, &r.RunID, &r.PayloadSHA256, &r.TaskID, &r.AttemptID, &r.ReceiptID, &r.Disposition, &r.RunVersion, &r.ReceivedAt)
	if err == nil && r.PayloadSHA256 != hash {
		return inboxReceipt{}, ErrResultConflict
	}
	return r, err
}

func (s *Store) reconcileInbox(ctx context.Context, e stageEnvelope) (inboxReceipt, error) {
	ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	db, err := sql.Open("pgx", s.databaseURL)
	if err != nil {
		return inboxReceipt{}, &resultUnknownError{err}
	}
	defer db.Close()
	db.SetMaxOpenConns(1)
	db.SetMaxIdleConns(0)
	r, err := queryInbox(ctx, db, e.MessageID, e.PayloadSHA256)
	if err == nil || errors.Is(err, ErrResultConflict) {
		return r, err
	}
	return inboxReceipt{}, &resultUnknownError{err}
}

// The receiver's *synthetic* logical effect and immutable inbox COMMIT together.
// This models an idempotent receiver contract; it proves no remote exactly-once
// property. A receipt acknowledges transport only, never a completed workflow.
func (s *Store) receiveSynthetic(ctx context.Context, e stageEnvelope) (inboxReceipt, error) {
	if !canonicalUUID(e.MessageID) || !canonicalUUID(e.RunID) || !isLowerHex(e.PayloadSHA256, 64) {
		return inboxReceipt{}, ErrResultConflict
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return inboxReceipt{}, err
	}
	defer tx.Rollback()
	var state, source string
	var version, epoch int64
	err = tx.QueryRowContext(ctx, `SELECT state,run_version,policy_epoch,source_fingerprint_sha256 FROM orchestration.workflow_runs WHERE run_id=$1::uuid FOR UPDATE`, e.RunID).Scan(&state, &version, &epoch, &source)
	if err != nil {
		return inboxReceipt{}, err
	}
	actual, err := readStageEnvelope(ctx, tx, e.MessageID)
	if err != nil {
		return inboxReceipt{}, err
	}
	if actual != e {
		return inboxReceipt{}, ErrResultConflict
	}
	if old, err := queryInbox(ctx, tx, e.MessageID, e.PayloadSHA256); !errors.Is(err, sql.ErrNoRows) {
		return old, err
	}
	p, err := readDeliveryPolicy(ctx, tx, e.MessageID)
	if errors.Is(err, sql.ErrNoRows) {
		return inboxReceipt{}, errDeliveryDeferred
	}
	if err != nil {
		return inboxReceipt{}, err
	}
	disposition := "ACCEPTED"
	if version > e.RunVersion || state == "CANCEL_REQUESTED" || state == "CANCELLED" || state == "BLOCKED" || state == "FAILED" || state == "ESCALATED" || state == "SUPERSEDED" || state == "COMPLETED_DRAFT_MR" {
		disposition = "OBSOLETE"
	} else {
		if version != e.RunVersion || state != e.Stage || epoch != p.PolicyEpoch || source != p.SourceSHA256 {
			return inboxReceipt{}, errDeliveryDeferred
		}
		if err := checkBoundedPolicy(ctx, tx, e.RunID, epoch); err != nil {
			return inboxReceipt{}, err
		}
		var ready bool
		var task string
		err = tx.QueryRowContext(ctx, `SELECT task_id::text FROM orchestration.task_deliveries WHERE task_id=$1::uuid AND run_id=$2::uuid FOR UPDATE`, e.TaskID, e.RunID).Scan(&task)
		if err != nil {
			return inboxReceipt{}, err
		}
		err = tx.QueryRowContext(ctx, `SELECT stage=$4 AND ((status='QUEUED' AND available_at<=clock_timestamp()) OR (status='LEASED' AND lease_expires_at>clock_timestamp())) FROM orchestration.task_deliveries WHERE task_id=$1::uuid AND run_id=$2::uuid AND attempt_id=$3::uuid`, e.TaskID, e.RunID, e.AttemptID, e.Stage).Scan(&ready)
		if err != nil {
			return inboxReceipt{}, err
		}
		if !ready {
			return inboxReceipt{}, errDeliveryDeferred
		}
	}
	id, err := freshUUID()
	if err != nil {
		return inboxReceipt{}, err
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO orchestration.workflow_synthetic_inbox(message_id,run_id,payload_sha256,task_id,attempt_id,receipt_id,disposition,run_version) VALUES($1::uuid,$2::uuid,$3,$4::uuid,$5::uuid,$6::uuid,$7,$8)`, e.MessageID, e.RunID, e.PayloadSHA256, e.TaskID, e.AttemptID, id, disposition, e.RunVersion)
	if err != nil {
		return inboxReceipt{}, err
	}
	if disposition == "ACCEPTED" {
		if _, err := tx.ExecContext(ctx, `INSERT INTO orchestration.workflow_synthetic_effects(message_id,run_id,receipt_id,disposition) VALUES($1::uuid,$2::uuid,$3::uuid,'ACCEPTED')`, e.MessageID, e.RunID, id); err != nil {
			return inboxReceipt{}, err
		}
	}
	r, err := queryInbox(ctx, tx, e.MessageID, e.PayloadSHA256)
	if err != nil {
		return inboxReceipt{}, err
	}
	if err := tx.Commit(); err != nil {
		return s.reconcileInbox(ctx, e)
	}
	return r, nil
}

// Callback acknowledgement is evidence-only: independently validate the actual
// immutable receiver receipt, even after cancellation or exhausted delivery.
func (s *Store) acknowledgeSyntheticDelivery(ctx context.Context, r inboxReceipt) error {
	if !canonicalUUID(r.MessageID) || !canonicalUUID(r.RunID) || !isLowerHex(r.PayloadSHA256, 64) {
		return ErrResultConflict
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var run string
	if err := tx.QueryRowContext(ctx, `SELECT run_id::text FROM orchestration.workflow_runs WHERE run_id=$1::uuid FOR UPDATE`, r.RunID).Scan(&run); err != nil {
		return err
	}
	actual, err := queryInbox(ctx, tx, r.MessageID, r.PayloadSHA256)
	if err != nil {
		return err
	}
	if actual != r {
		return ErrResultConflict
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO orchestration.workflow_synthetic_delivery_receipts(message_id,run_id,receipt_id,disposition) VALUES($1::uuid,$2::uuid,$3::uuid,$4) ON CONFLICT(message_id) DO NOTHING`, r.MessageID, r.RunID, r.ReceiptID, r.Disposition)
	if err != nil {
		return err
	}
	phase := "DELIVERED"
	if r.Disposition == "OBSOLETE" {
		phase = "OBSOLETE"
	}
	if _, err := tx.ExecContext(ctx, `UPDATE orchestration.workflow_synthetic_deliveries SET phase=$2 WHERE message_id=$1::uuid`, r.MessageID, phase); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `UPDATE orchestration.workflow_outbox SET status='DELIVERED',delivered_at=coalesce(delivered_at,clock_timestamp()) WHERE message_id=$1::uuid`, r.MessageID); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return &resultUnknownError{err}
	}
	return nil
}

// One explicit bounded dispatch, with no automatic send loop. On recovery, look
// up original receiver identity first. UNKNOWN can be redelivered only to this
// contract's atomic idempotent synthetic receiver, under the original ID/hash.
func (s *Store) dispatchSynthetic(ctx context.Context, id string, send func(context.Context, stageEnvelope) (inboxReceipt, error)) (inboxReceipt, error) {
	if !canonicalUUID(id) || send == nil {
		return inboxReceipt{}, ErrResultConflict
	}
	e, err := readStageEnvelope(ctx, s.db, id)
	if err != nil {
		return inboxReceipt{}, err
	}
	p, err := readDeliveryPolicy(ctx, s.db, id)
	if err != nil {
		return inboxReceipt{}, err
	}
	ctx, cancel := context.WithTimeout(ctx, time.Duration(p.LeaseSeconds)*time.Second)
	defer cancel()
	if r, err := queryInbox(ctx, s.db, id, e.PayloadSHA256); err == nil {
		return r, s.acknowledgeSyntheticDelivery(ctx, r)
	} else if !errors.Is(err, sql.ErrNoRows) {
		return inboxReceipt{}, &resultUnknownError{err}
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return inboxReceipt{}, err
	}
	defer tx.Rollback()
	var state, source string
	var version, epoch int64
	if err := tx.QueryRowContext(ctx, `SELECT state,run_version,policy_epoch,source_fingerprint_sha256 FROM orchestration.workflow_runs WHERE run_id=$1::uuid FOR UPDATE`, e.RunID).Scan(&state, &version, &epoch, &source); err != nil {
		return inboxReceipt{}, err
	}
	if state != e.Stage || version != e.RunVersion || epoch != p.PolicyEpoch || source != p.SourceSHA256 {
		return inboxReceipt{}, errDeliveryDeferred
	}
	if err := checkBoundedPolicy(ctx, tx, e.RunID, epoch); err != nil {
		return inboxReceipt{}, err
	}
	var attempts int
	var active bool
	if err := tx.QueryRowContext(ctx, `SELECT attempts,coalesce(lease_until>clock_timestamp(),false) FROM orchestration.workflow_synthetic_deliveries WHERE message_id=$1::uuid FOR UPDATE`, id).Scan(&attempts, &active); err != nil {
		return inboxReceipt{}, err
	}
	if active {
		return inboxReceipt{}, errDeliveryDeferred
	}
	if attempts >= p.MaximumAttempts {
		if _, err := tx.ExecContext(ctx, `UPDATE orchestration.workflow_synthetic_deliveries SET phase='EXHAUSTED' WHERE message_id=$1::uuid`, id); err != nil {
			return inboxReceipt{}, err
		}
		if err := tx.Commit(); err != nil {
			return inboxReceipt{}, &resultUnknownError{err}
		}
		return inboxReceipt{}, errDeliveryExhausted
	}
	if _, err := tx.ExecContext(ctx, `UPDATE orchestration.workflow_synthetic_deliveries SET phase='UNKNOWN',attempts=attempts+1,lease_epoch=lease_epoch+1,lease_until=clock_timestamp()+($2::int*interval '1 second') WHERE message_id=$1::uuid`, id, p.LeaseSeconds); err != nil {
		return inboxReceipt{}, err
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO orchestration.workflow_synthetic_delivery_attempts(message_id,lease_epoch) VALUES($1::uuid,$2)`, id, attempts+1); err != nil {
		return inboxReceipt{}, err
	}
	if err := tx.Commit(); err != nil {
		return inboxReceipt{}, &resultUnknownError{err}
	}
	// No SQL locks/transaction survive the transport invocation.
	r, err := send(ctx, e)
	if err != nil {
		if errors.Is(err, errDeliveryDeferred) {
			if _, updateErr := s.db.ExecContext(ctx, `UPDATE orchestration.workflow_synthetic_deliveries SET phase='DEFERRED' WHERE message_id=$1::uuid AND lease_epoch=$2 AND phase='UNKNOWN'`, id, attempts+1); updateErr != nil {
				return inboxReceipt{}, &resultUnknownError{updateErr}
			}
		}
		return inboxReceipt{}, &resultUnknownError{err}
	}
	if r.MessageID != e.MessageID || r.RunID != e.RunID || r.PayloadSHA256 != e.PayloadSHA256 || r.TaskID != e.TaskID || r.AttemptID != e.AttemptID || r.RunVersion != e.RunVersion {
		return inboxReceipt{}, ErrResultConflict
	}
	if err := s.acknowledgeSyntheticDelivery(ctx, r); err != nil {
		return inboxReceipt{}, &resultUnknownError{err}
	}
	return r, nil
}
