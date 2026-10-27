package workflow

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/jackc/pgx/v5/pgconn"
)

var ErrTransitionRejected = errors.New("workflow transition not authorized by current durable state")

// investigationAdvance is intentionally unexported. It is a synthetic-only
// demonstration of the *Go-local transaction*, not a production authorization
// endpoint or trusted evidence gate. A future trusted adapter must verify
// caller identity, policy, evidence provenance and permitted state transitions
// before invoking any corresponding production method.
type investigationAdvance struct {
	OperationID                     string // Generated once by the trusted caller, reused on retry.
	Lease                           TaskLease
	ExpectedRunVersion              int64
	ExpectedPolicyEpoch             int64
	ExpectedSourceFingerprintSHA256 string
	EvidenceRef                     string
	BudgetDebit                     int
}

type advanceReceipt struct {
	OperationID             string
	DigestVersion           int
	PayloadSHA256           string
	RunID                   string
	PreviousTaskID          string
	PreviousAttemptID       string
	ClaimantID              string
	LeaseEpoch              int64
	ExpectedRunVersion      int64
	PolicyEpoch             int64
	SourceFingerprintSHA256 string
	NextAttemptID           string
	NextTaskID              string
	TransitionID            string
	OutboxMessageID         string
	RunVersion              int64
	AcceptedAt              time.Time
}

func (c investigationAdvance) validate() error {
	if !canonicalUUID(c.OperationID) || !canonicalUUID(c.Lease.RunID) ||
		!canonicalUUID(c.Lease.TaskID) || !canonicalUUID(c.Lease.AttemptID) ||
		strings.TrimSpace(c.Lease.ClaimantID) == "" || len(c.Lease.ClaimantID) > 128 ||
		!utf8.ValidString(c.Lease.ClaimantID) || !utf8.ValidString(c.EvidenceRef) ||
		c.Lease.Stage != "INVESTIGATING" || c.Lease.LeaseEpoch < 1 ||
		c.ExpectedRunVersion < 1 || c.ExpectedPolicyEpoch < 1 ||
		!isLowerHex(c.ExpectedSourceFingerprintSHA256, 64) ||
		len(c.EvidenceRef) > 256 ||
		!strings.HasPrefix(c.EvidenceRef, "synthetic:vn-t10-it-") ||
		c.BudgetDebit != 0 {
		// ADVANCE is not semantic REWORK; it may not debit correction budget.
		// A later trusted gate must own any nonzero REWORK/REPLAN debit.
		return ErrTransitionRejected
	}
	return nil
}

// advanceInvestigation completes one valid LEASED INVESTIGATING task and makes
// PLANNING available with an outbox event in ONE PostgreSQL transaction.
// No long-running work or external calls happen while locks are held.
// After run -> task locks, a fresh statement captures one DB timestamp and
// conditionally completes the task. This is the locked acceptance point, not a
// promise that COMMIT occurs before expiry. Locks serialize reclaim/heartbeat
// until commit. Exact receipt replay does not confer new execution authority.
func (s *Store) advanceInvestigation(ctx context.Context, c investigationAdvance) (advanceReceipt, error) {
	if err := c.validate(); err != nil {
		return advanceReceipt{}, err
	}
	canonicalDigest, err := c.canonicalPayloadSHA256()
	if err != nil {
		return advanceReceipt{}, err
	}

	attemptID, err := freshUUID()
	if err != nil {
		return advanceReceipt{}, err
	}
	taskID, err := freshUUID()
	if err != nil {
		return advanceReceipt{}, err
	}
	transitionID, err := freshUUID()
	if err != nil {
		return advanceReceipt{}, err
	}
	messageID, err := freshUUID()
	if err != nil {
		return advanceReceipt{}, err
	}
	nextVersion := c.ExpectedRunVersion + 1

	event := struct {
		RunID       string `json:"run_id"`
		AttemptID   string `json:"attempt_id"`
		TaskID      string `json:"task_id"`
		Stage       string `json:"stage"`
		RunVersion  int64  `json:"run_version"`
		EvidenceRef string `json:"evidence_ref"`
	}{c.Lease.RunID, attemptID, taskID, "PLANNING", nextVersion, c.EvidenceRef}
	body, err := json.Marshal(event)
	if err != nil {
		return advanceReceipt{}, err
	}
	digest := sha256.Sum256(body)

	tx, err := s.db.BeginTx(ctx, &sql.TxOptions{Isolation: sql.LevelReadCommitted})
	if err != nil {
		return advanceReceipt{}, err
	}
	defer tx.Rollback()

	// Serialize transition decisions (and cancel/other state writers later)
	// by taking the authoritative run row FOR UPDATE first.
	var state, sourceFingerprint string
	var runVersion, policyEpoch int64
	err = tx.QueryRowContext(ctx, `
        SELECT state,run_version,policy_epoch,source_fingerprint_sha256
        FROM orchestration.workflow_runs
        WHERE run_id=$1::uuid FOR UPDATE`, c.Lease.RunID).Scan(
		&state, &runVersion, &policyEpoch, &sourceFingerprint)
	runMissing := errors.Is(err, sql.ErrNoRows)
	if err != nil && !runMissing {
		return advanceReceipt{}, fmt.Errorf("lock run: %w", err)
	}
	// Resolve historical identity before active-stage/lease validation. This is
	// a read-only replay, including after lease expiry or later run progress.
	prior, err := queryResult(ctx, tx, c.OperationID, canonicalDigest)
	if err == nil {
		return prior, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return advanceReceipt{}, err
	}
	if err := checkBoundedPolicy(ctx, tx, c.Lease.RunID, c.ExpectedPolicyEpoch); err != nil {
		return advanceReceipt{}, err
	}
	if runMissing || state != "INVESTIGATING" || runVersion != c.ExpectedRunVersion ||
		policyEpoch != c.ExpectedPolicyEpoch ||
		sourceFingerprint != c.ExpectedSourceFingerprintSHA256 {
		return advanceReceipt{}, ErrTransitionRejected
	}

	// Lock without an expiry predicate: waiting must not preserve a previously
	// true time check on an unchanged tuple (the F01/F08 failure condition).
	var lockedTask string
	err = tx.QueryRowContext(ctx, `SELECT task_id::text FROM orchestration.task_deliveries
        WHERE task_id=$1::uuid AND run_id=$2::uuid FOR UPDATE`, c.Lease.TaskID, c.Lease.RunID).Scan(&lockedTask)
	if errors.Is(err, sql.ErrNoRows) {
		return advanceReceipt{}, ErrLeaseLost
	}
	if err != nil {
		return advanceReceipt{}, fmt.Errorf("lock result task: %w", err)
	}
	var acceptedAt time.Time
	err = tx.QueryRowContext(ctx, `
        WITH acceptance AS MATERIALIZED (SELECT clock_timestamp() AS at)
        UPDATE orchestration.task_deliveries t
        SET status='COMPLETED',updated_at=clock_timestamp()
        FROM acceptance
        WHERE t.task_id=$1::uuid AND t.run_id=$2::uuid AND t.attempt_id=$3::uuid
          AND t.stage='INVESTIGATING' AND t.status='LEASED'
          AND t.claimant_id=$4 AND t.lease_epoch=$5
          AND t.lease_expires_at>acceptance.at
        RETURNING acceptance.at`,
		c.Lease.TaskID, c.Lease.RunID, c.Lease.AttemptID,
		c.Lease.ClaimantID, c.Lease.LeaseEpoch).Scan(&acceptedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return advanceReceipt{}, ErrLeaseLost
	}
	if err != nil {
		return advanceReceipt{}, fmt.Errorf("complete task: %w", err)
	}

	changed, err := tx.ExecContext(ctx, `
        UPDATE orchestration.stage_attempts
        SET state='SUCCEEDED',completed_at=clock_timestamp()
        WHERE attempt_id=$1::uuid AND run_id=$2::uuid
          AND stage='INVESTIGATING' AND state='RUNNING'`,
		c.Lease.AttemptID, c.Lease.RunID)
	if err != nil {
		return advanceReceipt{}, fmt.Errorf("complete attempt: %w", err)
	}
	n, err := changed.RowsAffected()
	if err != nil {
		return advanceReceipt{}, err
	}
	if n != 1 {
		return advanceReceipt{}, ErrTransitionRejected
	}

	changed, err = tx.ExecContext(ctx, `
        UPDATE orchestration.workflow_runs
        SET state='PLANNING',run_version=run_version+1,updated_at=clock_timestamp()
        WHERE run_id=$1::uuid AND state='INVESTIGATING' AND run_version=$2
          AND policy_epoch=$3 AND source_fingerprint_sha256=$4`,
		c.Lease.RunID, c.ExpectedRunVersion, c.ExpectedPolicyEpoch,
		c.ExpectedSourceFingerprintSHA256)
	if err != nil {
		return advanceReceipt{}, fmt.Errorf("advance run: %w", err)
	}
	n, err = changed.RowsAffected()
	if err != nil {
		return advanceReceipt{}, err
	}
	if n != 1 {
		return advanceReceipt{}, ErrTransitionRejected
	}

	// A late SQL error in any of these inserts MUST roll back the task/attempt
	// and run changes above. Only commit if the entire bundle succeeds.
	_, err = tx.ExecContext(ctx, `
        INSERT INTO orchestration.stage_attempts(
          attempt_id,run_id,stage,semantic_attempt_no
        ) VALUES ($1::uuid,$2::uuid,'PLANNING',1)`, attemptID, c.Lease.RunID)
	if err != nil {
		return advanceReceipt{}, fmt.Errorf("insert next attempt: %w", err)
	}
	_, err = tx.ExecContext(ctx, `
        INSERT INTO orchestration.task_deliveries(
          task_id,run_id,attempt_id,stage
        ) VALUES ($1::uuid,$2::uuid,$3::uuid,'PLANNING')`,
		taskID, c.Lease.RunID, attemptID)
	if err != nil {
		return advanceReceipt{}, fmt.Errorf("insert next task: %w", err)
	}
	_, err = tx.ExecContext(ctx, `
        INSERT INTO orchestration.workflow_transitions(
          transition_id,run_id,attempt_id,from_state,to_state,
          prior_run_version,evidence_ref,budget_debit
        ) VALUES($1::uuid,$2::uuid,$3::uuid,'INVESTIGATING','PLANNING',
                 $4,$5,0)`, transitionID, c.Lease.RunID, c.Lease.AttemptID,
		c.ExpectedRunVersion, c.EvidenceRef)
	if err != nil {
		return advanceReceipt{}, fmt.Errorf("insert transition: %w", err)
	}
	_, err = tx.ExecContext(ctx, `
        INSERT INTO orchestration.workflow_outbox(
          message_id,run_id,event_type,payload_sha256,payload
        ) VALUES($1::uuid,$2::uuid,'STAGE_TASK_QUEUED',$3,$4::jsonb)`,
		messageID, c.Lease.RunID, hex.EncodeToString(digest[:]), string(body))
	if err != nil {
		return advanceReceipt{}, fmt.Errorf("insert outbox: %w", err)
	}

	_, err = tx.ExecContext(ctx, `INSERT INTO orchestration.workflow_result_receipts(
        operation_id,digest_version,payload_sha256,run_id,task_id,attempt_id,claimant_id,
        lease_epoch,expected_run_version,policy_epoch,source_fingerprint_sha256,
        transition_id,next_task_id,next_attempt_id,outbox_message_id,accepted_run_version,accepted_at)
        VALUES($1::uuid,1,$2,$3::uuid,$4::uuid,$5::uuid,$6,$7,$8,$9,$10,
               $11::uuid,$12::uuid,$13::uuid,$14::uuid,$15,$16)`,
		c.OperationID, canonicalDigest, c.Lease.RunID, c.Lease.TaskID, c.Lease.AttemptID,
		c.Lease.ClaimantID, c.Lease.LeaseEpoch, c.ExpectedRunVersion, c.ExpectedPolicyEpoch,
		c.ExpectedSourceFingerprintSHA256, transitionID, taskID, attemptID, messageID, nextVersion, acceptedAt)
	if err != nil {
		// Only the operation PK can represent a cross-run operation race. Other
		// SQL failures retain their real classification and roll back the bundle.
		var pgerr *pgconn.PgError
		if errors.As(err, &pgerr) && pgerr.Code == "23505" && pgerr.ConstraintName == "workflow_result_receipts_pkey" {
			if rollbackErr := tx.Rollback(); rollbackErr != nil {
				return advanceReceipt{}, rollbackErr
			}
			return s.reconcileInvestigation(ctx, c)
		}
		return advanceReceipt{}, fmt.Errorf("insert result receipt: %w", err)
	}

	if err = tx.Commit(); err != nil {
		recovered, lookupErr := s.reconcileInvestigation(ctx, c)
		if lookupErr == nil || errors.Is(lookupErr, ErrResultConflict) {
			return recovered, lookupErr
		}
		return advanceReceipt{}, &resultUnknownError{err}
	}
	return advanceReceipt{OperationID: c.OperationID, DigestVersion: 1, PayloadSHA256: canonicalDigest,
		RunID: c.Lease.RunID, PreviousTaskID: c.Lease.TaskID, PreviousAttemptID: c.Lease.AttemptID,
		ClaimantID: c.Lease.ClaimantID, LeaseEpoch: c.Lease.LeaseEpoch,
		ExpectedRunVersion: c.ExpectedRunVersion, PolicyEpoch: c.ExpectedPolicyEpoch,
		SourceFingerprintSHA256: c.ExpectedSourceFingerprintSHA256, AcceptedAt: acceptedAt,
		NextAttemptID: attemptID, NextTaskID: taskID, TransitionID: transitionID,
		OutboxMessageID: messageID, RunVersion: nextVersion}, nil
}
