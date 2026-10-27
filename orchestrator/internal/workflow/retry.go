package workflow

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/jackc/pgx/v5/pgconn"
)

// Private, trusted synthetic profile. These explicit caller-selected numbers
// and planning-only decisions are NOT production policy or agent capabilities.
type syntheticProfile struct {
	DeliveryLimit, ReworkLimit, ReplanLimit int
	PolicyGranted                           bool
}

func (s *Store) admitBounded(ctx context.Context, c Admission, p syntheticProfile) (AdmissionReceipt, error) {
	parts := strings.SplitN(c.SubjectKey, ":", 3)
	if len(parts) != 3 || !syntheticPrefixPattern.MatchString(parts[0]+":"+parts[1]) ||
		p.DeliveryLimit < 1 || p.ReworkLimit < 0 || p.ReplanLimit < 0 || !p.PolicyGranted {
		return AdmissionReceipt{}, ErrTransitionRejected
	}
	return s.admit(ctx, c, nil, &p)
}

func insertSyntheticProfile(ctx context.Context, tx *sql.Tx, runID string, epoch int64, p syntheticProfile) error {
	if _, err := tx.ExecContext(ctx, `INSERT INTO orchestration.workflow_synthetic_profiles
        (run_id,delivery_limit,rework_limit,replan_limit) VALUES($1::uuid,$2,$3,$4)`,
		runID, p.DeliveryLimit, p.ReworkLimit, p.ReplanLimit); err != nil {
		return err
	}
	_, err := tx.ExecContext(ctx, `INSERT INTO orchestration.workflow_synthetic_policies(run_id,policy_epoch,enabled)
        VALUES($1::uuid,$2,$3)`, runID, epoch, p.PolicyGranted)
	return err
}

func readSyntheticProfile(ctx context.Context, db executionReader, runID string) (syntheticProfile, error) {
	var p syntheticProfile
	err := db.QueryRowContext(ctx, `SELECT delivery_limit,rework_limit,replan_limit
        FROM orchestration.workflow_synthetic_profiles WHERE run_id=$1::uuid`, runID).Scan(
		&p.DeliveryLimit, &p.ReworkLimit, &p.ReplanLimit)
	p.PolicyGranted = true // Original admission grant, not current mutable policy authority.
	return p, err
}

func matchSyntheticProfile(ctx context.Context, db executionReader, runID string, want syntheticProfile) error {
	got, err := readSyntheticProfile(ctx, db, runID)
	if err != nil {
		return err
	}
	if got != want {
		return ErrResultConflict
	}
	return nil
}

// Legacy V1-V7 has no profile: retain its established behavior, without
// fabricating finite limits. New bounded operations always require a profile.
func hasSyntheticProfile(ctx context.Context, db executionReader, runID string) (bool, error) {
	var available, found bool
	if err := db.QueryRowContext(ctx, `SELECT to_regclass('orchestration.workflow_synthetic_profiles') IS NOT NULL`).Scan(&available); err != nil {
		return false, err
	}
	if !available {
		return false, nil
	}
	err := db.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM orchestration.workflow_synthetic_profiles WHERE run_id=$1::uuid)`, runID).Scan(&found)
	return found, err
}

func checkBoundedPolicy(ctx context.Context, db executionReader, runID string, epoch int64) error {
	found, err := hasSyntheticProfile(ctx, db, runID)
	if err != nil || !found {
		return err
	}
	var enabled bool
	err = db.QueryRowContext(ctx, `SELECT enabled AND policy_epoch=$2 FROM orchestration.workflow_synthetic_policies
        WHERE run_id=$1::uuid FOR SHARE`, runID, epoch).Scan(&enabled)
	if errors.Is(err, sql.ErrNoRows) || (err == nil && !enabled) {
		return ErrTransitionRejected
	}
	return err
}

// Revocation follows run -> policy ordering, serializing with all bounded gates.
// It does not retroactively change historical receipts or re-enable a run.
func (s *Store) revokeSyntheticPolicy(ctx context.Context, runID string, epoch int64) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var id string
	if err := tx.QueryRowContext(ctx, `SELECT run_id::text FROM orchestration.workflow_runs WHERE run_id=$1::uuid FOR UPDATE`, runID).Scan(&id); err != nil {
		return err
	}
	r, err := tx.ExecContext(ctx, `UPDATE orchestration.workflow_synthetic_policies SET enabled=false WHERE run_id=$1::uuid AND policy_epoch=$2`, runID, epoch)
	if err := oneCancellationRow(r, err); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *Store) reconcileBoundedAdmission(ctx context.Context, c Admission, p syntheticProfile) (AdmissionReceipt, error) {
	ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	db, err := sql.Open("pgx", s.databaseURL)
	if err != nil {
		return AdmissionReceipt{}, &resultUnknownError{err}
	}
	defer db.Close()
	hash, err := c.canonicalPayloadSHA256()
	if err != nil {
		return AdmissionReceipt{}, err
	}
	// Admission's duplicate-insert helper also maps absence to ErrConflict.
	// Reconciliation must distinguish absence of proof from a proven mismatch.
	var r AdmissionReceipt
	var persistedHash string
	err = db.QueryRowContext(ctx, `SELECT r.run_id::text,a.attempt_id::text,t.task_id::text,r.command_payload_sha256
        FROM orchestration.workflow_runs r JOIN orchestration.stage_attempts a ON a.run_id=r.run_id
          AND a.stage='INVESTIGATING' AND a.semantic_attempt_no=1
        JOIN orchestration.task_deliveries t ON t.attempt_id=a.attempt_id
        WHERE r.command_id=$1::uuid`, c.CommandID).Scan(&r.RunID, &r.AttemptID, &r.TaskID, &persistedHash)
	if err == nil && persistedHash != hash {
		return AdmissionReceipt{}, ErrConflict
	}
	if err == nil {
		err = matchSyntheticProfile(ctx, db, r.RunID, p)
	}
	if err != nil {
		if errors.Is(err, ErrConflict) || errors.Is(err, ErrResultConflict) {
			return AdmissionReceipt{}, err
		}
		return AdmissionReceipt{}, &resultUnknownError{err}
	}
	r.Replayed = true
	return r, nil
}

func (s *Store) claimBounded(ctx context.Context, runID, claimant string, seconds int) (TaskLease, bool, error) {
	found, err := hasSyntheticProfile(ctx, s.db, runID)
	if err != nil || !found {
		return TaskLease{}, false, err
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return TaskLease{}, true, err
	}
	defer tx.Rollback()
	var state string
	var epoch int64
	if err := tx.QueryRowContext(ctx, `SELECT state,policy_epoch FROM orchestration.workflow_runs WHERE run_id=$1::uuid FOR UPDATE`, runID).Scan(&state, &epoch); err != nil {
		return TaskLease{}, true, err
	}
	if err := checkBoundedPolicy(ctx, tx, runID, epoch); err != nil {
		return TaskLease{}, true, err
	}
	p, err := readSyntheticProfile(ctx, tx, runID)
	if err != nil {
		return TaskLease{}, true, err
	}
	l := TaskLease{RunID: runID, ClaimantID: claimant}
	err = tx.QueryRowContext(ctx, `SELECT task_id::text,attempt_id::text,stage FROM orchestration.task_deliveries
        WHERE run_id=$1::uuid AND stage=$2 AND delivery_count<$3
          AND ((status='QUEUED' AND available_at<=clock_timestamp()) OR (status='LEASED' AND lease_expires_at<=clock_timestamp()))
        ORDER BY created_at,task_id LIMIT 1 FOR UPDATE`, runID, state, p.DeliveryLimit).Scan(&l.TaskID, &l.AttemptID, &l.Stage)
	if errors.Is(err, sql.ErrNoRows) {
		return TaskLease{}, true, ErrNoTask
	}
	if err != nil {
		return TaskLease{}, true, err
	}
	err = tx.QueryRowContext(ctx, `UPDATE orchestration.task_deliveries SET status='LEASED',claimant_id=$2,lease_epoch=lease_epoch+1,
        lease_expires_at=clock_timestamp()+($3::int*interval '1 second'),delivery_count=delivery_count+1,updated_at=clock_timestamp()
        WHERE task_id=$1::uuid RETURNING lease_epoch,delivery_count,lease_expires_at`, l.TaskID, claimant, seconds).Scan(&l.LeaseEpoch, &l.DeliveryCount, &l.ExpiresAt)
	if err != nil {
		return TaskLease{}, true, err
	}
	r, err := tx.ExecContext(ctx, `UPDATE orchestration.stage_attempts SET state='RUNNING' WHERE attempt_id=$1::uuid AND run_id=$2::uuid AND state IN ('PENDING','RUNNING')`, l.AttemptID, runID)
	if err := oneCancellationRow(r, err); err != nil {
		return TaskLease{}, true, err
	}
	if err := tx.Commit(); err != nil {
		return TaskLease{}, true, &resultUnknownError{err}
	}
	return l, true, nil
}

type retryDecision struct {
	OperationID, Kind               string
	Lease                           TaskLease
	ExpectedRunVersion, PolicyEpoch int64
	SourceSHA256, EvidenceRef       string
}

type retryReceipt struct {
	OperationID, PayloadSHA256, Kind, RunID, TaskID, AttemptID string
	ExpectedRunVersion                                         int64
	TransitionID, NextTaskID, NextAttemptID, OutboxMessageID   string
	AcceptedAt                                                 time.Time
}

func (c retryDecision) digest() (string, error) {
	// Expiry and delivery count remain mutable transport facts, excluded.
	return privateDigest(struct {
		Version                                                  int
		OperationKind, RunID, TaskID, AttemptID, Stage, Claimant string
		Epoch, VersionExpected, Policy                           int64
		Source, Evidence                                         string
	}{
		1, c.Kind, c.Lease.RunID, c.Lease.TaskID, c.Lease.AttemptID, c.Lease.Stage, c.Lease.ClaimantID, c.Lease.LeaseEpoch, c.ExpectedRunVersion, c.PolicyEpoch, c.SourceSHA256, c.EvidenceRef})
}

func (c retryDecision) validate() error {
	if !canonicalUUID(c.OperationID) || !canonicalUUID(c.Lease.RunID) || !canonicalUUID(c.Lease.TaskID) || !canonicalUUID(c.Lease.AttemptID) ||
		c.Lease.LeaseEpoch < 1 || strings.TrimSpace(c.Lease.ClaimantID) == "" || len(c.Lease.ClaimantID) > 128 || c.ExpectedRunVersion < 1 || c.PolicyEpoch < 1 ||
		!utf8.ValidString(c.Lease.ClaimantID) || !utf8.ValidString(c.EvidenceRef) ||
		!isLowerHex(c.SourceSHA256, 64) || !strings.HasPrefix(c.EvidenceRef, "synthetic:vn-t10-it-") || len(c.EvidenceRef) > 256 ||
		(c.Kind != "REWORK" && c.Kind != "REPLAN" && c.Kind != "EXHAUST") || (c.Kind != "EXHAUST" && c.Lease.Stage != "PLANNING") {
		return ErrTransitionRejected
	}
	return nil
}

// Only the trusted synthetic controller may attest the protected fixture's
// decision. Neither a worker's result string nor an evidence_ref grants it.
func (s *Store) authorizeSyntheticRetry(ctx context.Context, c retryDecision) error {
	if err := c.validate(); err != nil {
		return err
	}
	hash, err := c.digest()
	if err != nil {
		return err
	}
	_, err = s.db.ExecContext(ctx, `INSERT INTO orchestration.workflow_synthetic_gate_evidence
        (evidence_ref,run_id,task_id,attempt_id,payload_sha256) VALUES($1,$2::uuid,$3::uuid,$4::uuid,$5)
        ON CONFLICT(evidence_ref) DO NOTHING`, c.EvidenceRef, c.Lease.RunID, c.Lease.TaskID, c.Lease.AttemptID, hash)
	if err != nil {
		return err
	}
	var stored string
	if err := s.db.QueryRowContext(ctx, `SELECT payload_sha256 FROM orchestration.workflow_synthetic_gate_evidence WHERE evidence_ref=$1`, c.EvidenceRef).Scan(&stored); err != nil {
		return err
	}
	if stored != hash {
		return ErrResultConflict
	}
	return nil
}

func queryRetry(ctx context.Context, db executionReader, id, hash string) (retryReceipt, error) {
	var r retryReceipt
	err := db.QueryRowContext(ctx, `SELECT operation_id::text,payload_sha256,kind,run_id::text,task_id::text,attempt_id::text,
        expected_run_version,transition_id::text,coalesce(next_task_id::text,''),coalesce(next_attempt_id::text,''),
        coalesce(outbox_message_id::text,''),accepted_at FROM orchestration.workflow_retry_receipts WHERE operation_id=$1::uuid`, id).Scan(
		&r.OperationID, &r.PayloadSHA256, &r.Kind, &r.RunID, &r.TaskID, &r.AttemptID, &r.ExpectedRunVersion, &r.TransitionID, &r.NextTaskID, &r.NextAttemptID, &r.OutboxMessageID, &r.AcceptedAt)
	if err == nil && r.PayloadSHA256 != hash {
		return retryReceipt{}, ErrResultConflict
	}
	return r, err
}

func (s *Store) reconcileRetry(ctx context.Context, c retryDecision) (retryReceipt, error) {
	if err := c.validate(); err != nil {
		return retryReceipt{}, err
	}
	hash, err := c.digest()
	if err != nil {
		return retryReceipt{}, err
	}
	ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	db, err := sql.Open("pgx", s.databaseURL)
	if err != nil {
		return retryReceipt{}, &resultUnknownError{err}
	}
	defer db.Close()
	db.SetMaxOpenConns(1)
	db.SetMaxIdleConns(0)
	r, err := queryRetry(ctx, db, c.OperationID, hash)
	if err == nil || errors.Is(err, ErrResultConflict) {
		return r, err
	}
	return retryReceipt{}, &resultUnknownError{err}
}

// Run -> task -> attempt. Fresh DB-time validation follows stable row locks.
// EXHAUST requires expired current ownership at its explicit finite delivery
// bound; it cannot overwrite completion/cancellation or treat UNKNOWN as failure.
// No model, policy-service, transport or process calls occur in this transaction.
func (s *Store) decideRetry(ctx context.Context, c retryDecision) (retryReceipt, error) {
	if err := c.validate(); err != nil {
		return retryReceipt{}, err
	}
	hash, err := c.digest()
	if err != nil {
		return retryReceipt{}, err
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return retryReceipt{}, err
	}
	defer tx.Rollback()
	var state, source string
	var version, policy int64
	var used, limit int
	err = tx.QueryRowContext(ctx, `SELECT state,run_version,policy_epoch,source_fingerprint_sha256,budget_used,budget_limit
        FROM orchestration.workflow_runs WHERE run_id=$1::uuid FOR UPDATE`, c.Lease.RunID).Scan(&state, &version, &policy, &source, &used, &limit)
	if err != nil {
		return retryReceipt{}, err
	}
	if old, err := queryRetry(ctx, tx, c.OperationID, hash); !errors.Is(err, sql.ErrNoRows) {
		return old, err
	}
	p, err := readSyntheticProfile(ctx, tx, c.Lease.RunID)
	if errors.Is(err, sql.ErrNoRows) {
		return retryReceipt{}, ErrTransitionRejected
	}
	if err != nil {
		return retryReceipt{}, err
	}
	if err := checkBoundedPolicy(ctx, tx, c.Lease.RunID, policy); err != nil {
		return retryReceipt{}, err
	}
	if state != c.Lease.Stage || version != c.ExpectedRunVersion || policy != c.PolicyEpoch || source != c.SourceSHA256 || (state != "INVESTIGATING" && state != "PLANNING") {
		return retryReceipt{}, ErrTransitionRejected
	}
	var attested string
	err = tx.QueryRowContext(ctx, `SELECT payload_sha256 FROM orchestration.workflow_synthetic_gate_evidence
        WHERE evidence_ref=$1 AND run_id=$2::uuid AND task_id=$3::uuid AND attempt_id=$4::uuid`, c.EvidenceRef, c.Lease.RunID, c.Lease.TaskID, c.Lease.AttemptID).Scan(&attested)
	if errors.Is(err, sql.ErrNoRows) || (err == nil && attested != hash) {
		return retryReceipt{}, ErrTransitionRejected
	}
	if err != nil {
		return retryReceipt{}, err
	}
	var task string
	if err := tx.QueryRowContext(ctx, `SELECT task_id::text FROM orchestration.task_deliveries WHERE task_id=$1::uuid AND run_id=$2::uuid FOR UPDATE`, c.Lease.TaskID, c.Lease.RunID).Scan(&task); err != nil {
		return retryReceipt{}, err
	}
	var valid bool
	err = tx.QueryRowContext(ctx, `SELECT status='LEASED' AND attempt_id=$3::uuid AND stage=$4 AND claimant_id=$5 AND lease_epoch=$6
        AND CASE WHEN $7 THEN lease_expires_at<=clock_timestamp() AND delivery_count>=$8 ELSE lease_expires_at>clock_timestamp() END
        FROM orchestration.task_deliveries WHERE task_id=$1::uuid AND run_id=$2::uuid`, c.Lease.TaskID, c.Lease.RunID, c.Lease.AttemptID, c.Lease.Stage, c.Lease.ClaimantID, c.Lease.LeaseEpoch, c.Kind == "EXHAUST", p.DeliveryLimit).Scan(&valid)
	if err != nil {
		return retryReceipt{}, err
	}
	if !valid {
		return retryReceipt{}, ErrLeaseLost
	}
	var number int
	err = tx.QueryRowContext(ctx, `SELECT semantic_attempt_no FROM orchestration.stage_attempts
        WHERE attempt_id=$1::uuid AND run_id=$2::uuid AND stage=$3 AND state='RUNNING' FOR UPDATE`, c.Lease.AttemptID, c.Lease.RunID, state).Scan(&number)
	if errors.Is(err, sql.ErrNoRows) {
		return retryReceipt{}, ErrTransitionRejected
	}
	if err != nil {
		return retryReceipt{}, err
	}
	debit := 0
	nextState := "BLOCKED"
	taskStatus := "EXHAUSTED"
	r := retryReceipt{OperationID: c.OperationID, PayloadSHA256: hash, Kind: c.Kind, RunID: c.Lease.RunID, TaskID: c.Lease.TaskID, AttemptID: c.Lease.AttemptID, ExpectedRunVersion: version}
	r.TransitionID, err = freshUUID()
	if err != nil {
		return retryReceipt{}, err
	}
	if c.Kind != "EXHAUST" {
		var count int
		if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM orchestration.workflow_retry_receipts WHERE run_id=$1::uuid AND kind=$2`, c.Lease.RunID, c.Kind).Scan(&count); err != nil {
			return retryReceipt{}, err
		}
		allowed := p.ReworkLimit
		if c.Kind == "REPLAN" {
			allowed = p.ReplanLimit
		}
		if used >= limit || count >= allowed {
			return retryReceipt{}, ErrTransitionRejected
		}
		debit = 1
		nextState = "PLANNING"
		taskStatus = "COMPLETED"
		for _, id := range []*string{&r.NextTaskID, &r.NextAttemptID, &r.OutboxMessageID} {
			*id, err = freshUUID()
			if err != nil {
				return retryReceipt{}, err
			}
		}
	}
	changed, err := tx.ExecContext(ctx, `UPDATE orchestration.task_deliveries SET status=$2,updated_at=clock_timestamp() WHERE task_id=$1::uuid`, c.Lease.TaskID, taskStatus)
	if err := oneCancellationRow(changed, err); err != nil {
		return retryReceipt{}, err
	}
	changed, err = tx.ExecContext(ctx, `UPDATE orchestration.stage_attempts SET state='FAILED',completed_at=clock_timestamp() WHERE attempt_id=$1::uuid`, c.Lease.AttemptID)
	if err := oneCancellationRow(changed, err); err != nil {
		return retryReceipt{}, err
	}
	changed, err = tx.ExecContext(ctx, `UPDATE orchestration.workflow_runs SET state=$2,run_version=run_version+1,budget_used=budget_used+$3,updated_at=clock_timestamp() WHERE run_id=$1::uuid`, c.Lease.RunID, nextState, debit)
	if err := oneCancellationRow(changed, err); err != nil {
		return retryReceipt{}, err
	}
	if debit != 0 {
		if _, err := tx.ExecContext(ctx, `INSERT INTO orchestration.stage_attempts(attempt_id,run_id,stage,semantic_attempt_no) VALUES($1::uuid,$2::uuid,'PLANNING',$3)`, r.NextAttemptID, r.RunID, number+1); err != nil {
			return retryReceipt{}, err
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO orchestration.task_deliveries(task_id,run_id,attempt_id,stage) VALUES($1::uuid,$2::uuid,$3::uuid,'PLANNING')`, r.NextTaskID, r.RunID, r.NextAttemptID); err != nil {
			return retryReceipt{}, err
		}
		body, err := json.Marshal(struct {
			RunID       string `json:"run_id"`
			AttemptID   string `json:"attempt_id"`
			TaskID      string `json:"task_id"`
			Stage       string `json:"stage"`
			RunVersion  int64  `json:"run_version"`
			EvidenceRef string `json:"evidence_ref"`
		}{r.RunID, r.NextAttemptID, r.NextTaskID, nextState, version + 1, c.EvidenceRef})
		if err != nil {
			return retryReceipt{}, err
		}
		digest := sha256.Sum256(body)
		if _, err := tx.ExecContext(ctx, `INSERT INTO orchestration.workflow_outbox(message_id,run_id,event_type,payload_sha256,payload) VALUES($1::uuid,$2::uuid,'STAGE_TASK_QUEUED',$3,$4::jsonb)`, r.OutboxMessageID, r.RunID, hex.EncodeToString(digest[:]), string(body)); err != nil {
			return retryReceipt{}, err
		}
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO orchestration.workflow_transitions(transition_id,run_id,attempt_id,from_state,to_state,prior_run_version,evidence_ref,budget_debit) VALUES($1::uuid,$2::uuid,$3::uuid,$4,$5,$6,$7,$8)`, r.TransitionID, r.RunID, r.AttemptID, state, nextState, version, c.EvidenceRef, debit); err != nil {
		return retryReceipt{}, err
	}
	err = tx.QueryRowContext(ctx, `INSERT INTO orchestration.workflow_retry_receipts(operation_id,payload_sha256,kind,run_id,task_id,attempt_id,expected_run_version,transition_id,next_task_id,next_attempt_id,outbox_message_id)
        VALUES($1::uuid,$2,$3,$4::uuid,$5::uuid,$6::uuid,$7,$8::uuid,NULLIF($9,'')::uuid,NULLIF($10,'')::uuid,NULLIF($11,'')::uuid) RETURNING accepted_at`, r.OperationID, hash, r.Kind, r.RunID, r.TaskID, r.AttemptID, version, r.TransitionID, r.NextTaskID, r.NextAttemptID, r.OutboxMessageID).Scan(&r.AcceptedAt)
	if err != nil {
		var pgerr *pgconn.PgError
		if errors.As(err, &pgerr) && pgerr.Code == "23505" && pgerr.ConstraintName == "workflow_retry_receipts_pkey" {
			if rollback := tx.Rollback(); rollback != nil {
				return retryReceipt{}, rollback
			}
			return s.reconcileRetry(ctx, c)
		}
		return retryReceipt{}, err
	}
	if err := tx.Commit(); err != nil {
		return s.reconcileRetry(ctx, c)
	}
	return r, nil
}
