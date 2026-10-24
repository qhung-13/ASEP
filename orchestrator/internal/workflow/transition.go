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
)

var ErrTransitionRejected = errors.New("workflow transition not authorized by current durable state")

// investigationAdvance is intentionally unexported. It is a synthetic-only
// demonstration of the *Go-local transaction*, not a production authorization
// endpoint or trusted evidence gate. A future trusted adapter must verify
// caller identity, policy, evidence provenance and permitted state transitions
// before invoking any corresponding production method.
type investigationAdvance struct {
	Lease                           TaskLease
	ExpectedRunVersion              int64
	ExpectedPolicyEpoch             int64
	ExpectedSourceFingerprintSHA256 string
	EvidenceRef                     string
	BudgetDebit                     int
}

type advanceReceipt struct {
	RunID           string
	PreviousTaskID  string
	NextAttemptID   string
	NextTaskID      string
	TransitionID    string
	OutboxMessageID string
	RunVersion      int64
}

func (c investigationAdvance) validate() error {
	if strings.TrimSpace(c.Lease.RunID) == "" || strings.TrimSpace(c.Lease.TaskID) == "" ||
		strings.TrimSpace(c.Lease.AttemptID) == "" || strings.TrimSpace(c.Lease.ClaimantID) == "" ||
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
// Lease expiry is evaluated with DB clock_timestamp() at task UPDATE, not only
// in the earlier application-side read; stale owners never commit a transition.
func (s *Store) advanceInvestigation(ctx context.Context, c investigationAdvance) (advanceReceipt, error) {
	if err := c.validate(); err != nil {
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
	if errors.Is(err, sql.ErrNoRows) {
		return advanceReceipt{}, ErrTransitionRejected
	}
	if err != nil {
		return advanceReceipt{}, fmt.Errorf("lock run: %w", err)
	}
	if state != "INVESTIGATING" || runVersion != c.ExpectedRunVersion ||
		policyEpoch != c.ExpectedPolicyEpoch ||
		sourceFingerprint != c.ExpectedSourceFingerprintSHA256 {
		return advanceReceipt{}, ErrTransitionRejected
	}

	// Must still own the *current, unexpired* task lease. There is no
	// acceptance grace period after expiration, even without a reclaim.
	changed, err := tx.ExecContext(ctx, `
        UPDATE orchestration.task_deliveries
        SET status='COMPLETED',updated_at=clock_timestamp()
        WHERE task_id=$1::uuid AND run_id=$2::uuid AND attempt_id=$3::uuid
          AND stage='INVESTIGATING' AND status='LEASED'
          AND claimant_id=$4 AND lease_epoch=$5
          AND lease_expires_at>clock_timestamp()`,
		c.Lease.TaskID, c.Lease.RunID, c.Lease.AttemptID,
		c.Lease.ClaimantID, c.Lease.LeaseEpoch)
	if err != nil {
		return advanceReceipt{}, fmt.Errorf("complete task: %w", err)
	}
	n, err := changed.RowsAffected()
	if err != nil {
		return advanceReceipt{}, err
	}
	if n != 1 {
		return advanceReceipt{}, ErrLeaseLost
	}

	changed, err = tx.ExecContext(ctx, `
        UPDATE orchestration.stage_attempts
        SET state='SUCCEEDED',completed_at=clock_timestamp()
        WHERE attempt_id=$1::uuid AND run_id=$2::uuid
          AND stage='INVESTIGATING' AND state='RUNNING'`,
		c.Lease.AttemptID, c.Lease.RunID)
	if err != nil {
		return advanceReceipt{}, fmt.Errorf("complete attempt: %w", err)
	}
	n, err = changed.RowsAffected()
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

	// Recheck DB lease time directly before transaction commit. Retain the
	// previous owner/expiry as audit metadata on the COMPLETED task; heartbeat
	// still rejects non-LEASED status. This narrows, but does not eliminate,
	// the tiny PostgreSQL check-to-commit timing window (VN-T11 fault gate).
	var validAtBoundary bool
	err = tx.QueryRowContext(ctx, `
        SELECT lease_expires_at > clock_timestamp()
        FROM orchestration.task_deliveries
        WHERE task_id=$1::uuid AND run_id=$2::uuid AND attempt_id=$3::uuid
          AND status='COMPLETED' AND claimant_id=$4 AND lease_epoch=$5`,
		c.Lease.TaskID, c.Lease.RunID, c.Lease.AttemptID,
		c.Lease.ClaimantID, c.Lease.LeaseEpoch).Scan(&validAtBoundary)
	if errors.Is(err, sql.ErrNoRows) {
		return advanceReceipt{}, ErrLeaseLost
	}
	if err != nil {
		return advanceReceipt{}, fmt.Errorf("revalidate lease before commit: %w", err)
	}
	if !validAtBoundary {
		return advanceReceipt{}, ErrLeaseLost
	}

	if err = tx.Commit(); err != nil {
		return advanceReceipt{}, fmt.Errorf("commit transition: %w", err)
	}
	return advanceReceipt{RunID: c.Lease.RunID, PreviousTaskID: c.Lease.TaskID,
		NextAttemptID: attemptID, NextTaskID: taskID, TransitionID: transitionID,
		OutboxMessageID: messageID, RunVersion: nextVersion}, nil
}
