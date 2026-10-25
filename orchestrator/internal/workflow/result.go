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
)

var (
	ErrResultConflict = errors.New("workflow result operation conflicts with accepted payload")
	ErrResultUnknown  = errors.New("workflow result outcome unknown; reconcile original operation")
)

// Keep diagnostics available to trusted callers without including connection or
// payload details in the ordinary UNKNOWN error message.
type resultUnknownError struct{ cause error }

func (e *resultUnknownError) Error() string        { return ErrResultUnknown.Error() }
func (e *resultUnknownError) Unwrap() error        { return e.cause }
func (e *resultUnknownError) Is(target error) bool { return target == ErrResultUnknown }

func canonicalUUID(value string) bool {
	return len(value) == 36 && value[8] == '-' && value[13] == '-' &&
		value[18] == '-' && value[23] == '-' && isLowerHex(strings.ReplaceAll(value, "-", ""), 32)
}

// Version 1 uses fixed-order Go JSON. This private synthetic contract is not a
// cross-language canonical JSON standard. OperationID is the envelope key;
// expiry/delivery count are mutable transport metadata and deliberately excluded.
func (c investigationAdvance) canonicalPayloadSHA256() (string, error) {
	body, err := json.Marshal(struct {
		Version            int    `json:"version"`
		Result             string `json:"result"`
		RunID              string `json:"run_id"`
		TaskID             string `json:"task_id"`
		AttemptID          string `json:"attempt_id"`
		Stage              string `json:"stage"`
		ClaimantID         string `json:"claimant_id"`
		LeaseEpoch         int64  `json:"lease_epoch"`
		ExpectedRunVersion int64  `json:"expected_run_version"`
		PolicyEpoch        int64  `json:"policy_epoch"`
		SourceFingerprint  string `json:"source_fingerprint_sha256"`
		EvidenceRef        string `json:"evidence_ref"`
		BudgetDebit        int    `json:"budget_debit"`
	}{1, "ADVANCE_INVESTIGATION_TO_PLANNING", c.Lease.RunID, c.Lease.TaskID,
		c.Lease.AttemptID, c.Lease.Stage, c.Lease.ClaimantID, c.Lease.LeaseEpoch,
		c.ExpectedRunVersion, c.ExpectedPolicyEpoch, c.ExpectedSourceFingerprintSHA256,
		c.EvidenceRef, c.BudgetDebit})
	if err != nil {
		return "", err
	}
	digest := sha256.Sum256(body)
	return hex.EncodeToString(digest[:]), nil
}

// queryResult returns only durable receipt data, never a newly generated ID.
// sql.ErrNoRows is intentionally left distinct for the transaction's first try.
func queryResult(ctx context.Context, db interface {
	QueryRowContext(context.Context, string, ...any) *sql.Row
}, operationID, digest string) (advanceReceipt, error) {
	var r advanceReceipt
	err := db.QueryRowContext(ctx, `SELECT operation_id::text,digest_version,payload_sha256,
        run_id::text,task_id::text,attempt_id::text,claimant_id,lease_epoch,
        expected_run_version,policy_epoch,source_fingerprint_sha256,
        transition_id::text,next_task_id::text,next_attempt_id::text,outbox_message_id::text,
        accepted_run_version,accepted_at
        FROM orchestration.workflow_result_receipts WHERE operation_id=$1::uuid`, operationID).Scan(
		&r.OperationID, &r.DigestVersion, &r.PayloadSHA256, &r.RunID, &r.PreviousTaskID,
		&r.PreviousAttemptID, &r.ClaimantID, &r.LeaseEpoch, &r.ExpectedRunVersion,
		&r.PolicyEpoch, &r.SourceFingerprintSHA256, &r.TransitionID, &r.NextTaskID,
		&r.NextAttemptID, &r.OutboxMessageID, &r.RunVersion, &r.AcceptedAt)
	if err != nil {
		return advanceReceipt{}, err
	}
	if r.DigestVersion != 1 || r.PayloadSHA256 != digest {
		return advanceReceipt{}, ErrResultConflict
	}
	return r, nil
}

// Reconciliation never executes a transition. Absence is UNKNOWN: the original
// transaction may still be in flight. A fresh pool avoids reusing the failed
// transaction's connection. The parent's deadline/cancellation always wins.
func (s *Store) reconcileInvestigation(ctx context.Context, c investigationAdvance) (advanceReceipt, error) {
	if err := c.validate(); err != nil {
		return advanceReceipt{}, err
	}
	digest, err := c.canonicalPayloadSHA256()
	if err != nil {
		return advanceReceipt{}, err
	}
	ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	if s.databaseURL == "" {
		return advanceReceipt{}, &resultUnknownError{}
	}
	db, err := sql.Open("pgx", s.databaseURL)
	if err != nil {
		return advanceReceipt{}, &resultUnknownError{err}
	}
	defer db.Close()
	db.SetMaxOpenConns(1)
	db.SetMaxIdleConns(0)
	r, err := queryResult(ctx, db, c.OperationID, digest)
	if err == nil || errors.Is(err, ErrResultConflict) {
		return r, err
	}
	return advanceReceipt{}, &resultUnknownError{err}
}
