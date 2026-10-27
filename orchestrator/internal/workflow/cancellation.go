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

var (
	errCancellationRejected = errors.New("cancellation request does not match current durable state")
	errCancellationConflict = errors.New("cancellation operation conflicts with accepted payload")
	errCancellationUnknown  = errors.New("cancellation outcome unknown; reconcile original operation")
)

type cancellationUnknownError struct{ cause error }

func (e *cancellationUnknownError) Error() string        { return errCancellationUnknown.Error() }
func (e *cancellationUnknownError) Unwrap() error        { return e.cause }
func (e *cancellationUnknownError) Is(target error) bool { return target == errCancellationUnknown }

// Private trusted Go-local synthetic control, never a worker or public API.
// Task is an expected binding, NOT the caller's authority to cancel. A future
// production adapter must establish authenticated business/control authority.
type cancellationRequest struct {
	OperationID                     string
	Task                            TaskLease
	ExpectedRunVersion              int64
	ExpectedPolicyEpoch             int64
	ExpectedSourceFingerprintSHA256 string
	SyntheticPrefix                 string
}

type cancellationReceipt struct {
	OperationID, PayloadSHA256, RunID, TaskID, AttemptID string
	Stage, ClaimantID, SourceFingerprintSHA256           string
	DigestVersion                                        int
	LeaseEpoch, ExpectedRunVersion, PolicyEpoch          int64
	TransitionID                                         string
	RunVersion                                           int64
	RequestedAt                                          time.Time
}

func (c cancellationRequest) validate() error {
	if !canonicalUUID(c.OperationID) || !canonicalUUID(c.Task.RunID) ||
		!canonicalUUID(c.Task.TaskID) || !canonicalUUID(c.Task.AttemptID) ||
		(c.Task.Stage != "INVESTIGATING" && c.Task.Stage != "PLANNING") ||
		c.Task.LeaseEpoch < 0 || len(c.Task.ClaimantID) > 128 || !utf8.ValidString(c.Task.ClaimantID) ||
		(c.Task.LeaseEpoch == 0 && c.Task.ClaimantID != "") ||
		(c.Task.LeaseEpoch > 0 && strings.TrimSpace(c.Task.ClaimantID) == "") ||
		c.ExpectedRunVersion < 1 || c.ExpectedPolicyEpoch < 1 ||
		!isLowerHex(c.ExpectedSourceFingerprintSHA256, 64) ||
		!syntheticPrefixPattern.MatchString(c.SyntheticPrefix) {
		return errCancellationRejected
	}
	return nil
}

func (c cancellationRequest) canonicalPayloadSHA256() (string, error) {
	// Fixed-order version-1 Go JSON, a private contract like the existing B2
	// encoding. Operation ID is the key; expiry/delivery count are not semantics.
	body, err := json.Marshal(struct {
		Version                        int
		Kind, RunID, TaskID, AttemptID string
		Stage, ClaimantID              string
		LeaseEpoch, ExpectedRunVersion int64
		PolicyEpoch                    int64
		SourceFingerprint, ScopePrefix string
	}{1, "CANCEL_REQUEST", c.Task.RunID, c.Task.TaskID, c.Task.AttemptID,
		c.Task.Stage, c.Task.ClaimantID, c.Task.LeaseEpoch, c.ExpectedRunVersion,
		c.ExpectedPolicyEpoch, c.ExpectedSourceFingerprintSHA256, c.SyntheticPrefix})
	if err != nil {
		return "", err
	}
	digest := sha256.Sum256(body)
	return hex.EncodeToString(digest[:]), nil
}

func queryCancellation(ctx context.Context, db interface {
	QueryRowContext(context.Context, string, ...any) *sql.Row
}, operationID, digest string) (cancellationReceipt, error) {
	var r cancellationReceipt
	err := db.QueryRowContext(ctx, `SELECT control_operation_id::text,control_digest_version,
        control_payload_sha256,run_id::text,control_task_id::text,attempt_id::text,
        from_state,control_claimant_id,control_lease_epoch,prior_run_version,
        control_policy_epoch,control_source_fingerprint_sha256,transition_id::text,
        prior_run_version+1,created_at
        FROM orchestration.workflow_transitions WHERE control_operation_id=$1::uuid`, operationID).Scan(
		&r.OperationID, &r.DigestVersion, &r.PayloadSHA256, &r.RunID, &r.TaskID, &r.AttemptID,
		&r.Stage, &r.ClaimantID, &r.LeaseEpoch, &r.ExpectedRunVersion, &r.PolicyEpoch,
		&r.SourceFingerprintSHA256, &r.TransitionID, &r.RunVersion, &r.RequestedAt)
	if err != nil {
		return cancellationReceipt{}, err
	}
	if r.DigestVersion != 1 || r.PayloadSHA256 != digest {
		return cancellationReceipt{}, errCancellationConflict
	}
	return r, nil
}

// The audit itself is the durable stop intent. This operation fences future
// work via run state; it does not retire a task/epoch, stop a child or finalize
// CANCELLED. No worker lease-expiry/COMMIT interpretation is introduced (F01).
func (s *Store) requestCancellation(ctx context.Context, c cancellationRequest) (cancellationReceipt, error) {
	if err := c.validate(); err != nil {
		return cancellationReceipt{}, err
	}
	digest, err := c.canonicalPayloadSHA256()
	if err != nil {
		return cancellationReceipt{}, err
	}
	id, err := freshUUID()
	if err != nil {
		return cancellationReceipt{}, err
	}
	tx, err := s.db.BeginTx(ctx, &sql.TxOptions{Isolation: sql.LevelReadCommitted})
	if err != nil {
		return cancellationReceipt{}, err
	}
	defer tx.Rollback()
	var state, source, subject string
	var version, policy int64
	err = tx.QueryRowContext(ctx, `SELECT state,run_version,policy_epoch,source_fingerprint_sha256,subject_key
        FROM orchestration.workflow_runs WHERE run_id=$1::uuid FOR UPDATE`, c.Task.RunID).Scan(
		&state, &version, &policy, &source, &subject)
	missing := errors.Is(err, sql.ErrNoRows)
	if err != nil && !missing {
		return cancellationReceipt{}, fmt.Errorf("lock cancellation run: %w", err)
	}
	// Historical control replay precedes validation of the current state. The
	// global typed key also detects conflicting concurrent requests across runs.
	prior, err := queryCancellation(ctx, tx, c.OperationID, digest)
	if err == nil {
		return prior, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return cancellationReceipt{}, err
	}
	if missing || state != c.Task.Stage || version != c.ExpectedRunVersion ||
		policy != c.ExpectedPolicyEpoch || source != c.ExpectedSourceFingerprintSHA256 ||
		!strings.HasPrefix(subject, c.SyntheticPrefix+":") {
		return cancellationReceipt{}, errCancellationRejected
	}
	var taskStatus, stage, claimant, attempt string
	var epoch int64
	err = tx.QueryRowContext(ctx, `SELECT status,stage,coalesce(claimant_id,''),lease_epoch,attempt_id::text
        FROM orchestration.task_deliveries WHERE task_id=$1::uuid AND run_id=$2::uuid FOR UPDATE`,
		c.Task.TaskID, c.Task.RunID).Scan(&taskStatus, &stage, &claimant, &epoch, &attempt)
	if errors.Is(err, sql.ErrNoRows) {
		return cancellationReceipt{}, errCancellationRejected
	}
	if err != nil {
		return cancellationReceipt{}, fmt.Errorf("lock cancellation task: %w", err)
	}
	if (taskStatus != "QUEUED" && taskStatus != "LEASED") || stage != state ||
		claimant != c.Task.ClaimantID || epoch != c.Task.LeaseEpoch || attempt != c.Task.AttemptID {
		return cancellationReceipt{}, errCancellationRejected
	}
	var attemptState string
	err = tx.QueryRowContext(ctx, `SELECT state FROM orchestration.stage_attempts
        WHERE attempt_id=$1::uuid AND run_id=$2::uuid AND stage=$3 FOR UPDATE`,
		c.Task.AttemptID, c.Task.RunID, state).Scan(&attemptState)
	if errors.Is(err, sql.ErrNoRows) {
		return cancellationReceipt{}, errCancellationRejected
	}
	if err != nil {
		return cancellationReceipt{}, fmt.Errorf("lock cancellation attempt: %w", err)
	}
	if attemptState != "PENDING" && attemptState != "RUNNING" {
		return cancellationReceipt{}, errCancellationRejected
	}
	changed, err := tx.ExecContext(ctx, `UPDATE orchestration.workflow_runs
        SET state='CANCEL_REQUESTED',run_version=run_version+1,updated_at=clock_timestamp()
        WHERE run_id=$1::uuid AND state=$2 AND run_version=$3 AND policy_epoch=$4
          AND source_fingerprint_sha256=$5`, c.Task.RunID, state, version, policy, source)
	if err != nil {
		return cancellationReceipt{}, fmt.Errorf("request cancellation: %w", err)
	}
	n, err := changed.RowsAffected()
	if err != nil {
		return cancellationReceipt{}, err
	}
	if n != 1 {
		return cancellationReceipt{}, errCancellationRejected
	}
	var at time.Time
	err = tx.QueryRowContext(ctx, `INSERT INTO orchestration.workflow_transitions(
        transition_id,run_id,attempt_id,from_state,to_state,prior_run_version,evidence_ref,budget_debit,
        control_operation_id,control_kind,control_digest_version,control_payload_sha256,
        control_task_id,control_claimant_id,control_lease_epoch,control_policy_epoch,
        control_source_fingerprint_sha256,created_at)
        VALUES ($1::uuid,$2::uuid,$3::uuid,$4,'CANCEL_REQUESTED',$5,$6,0,
          $7::uuid,'CANCEL_REQUEST',1,$8,$9::uuid,$10,$11,$12,$13,clock_timestamp()) RETURNING created_at`,
		id, c.Task.RunID, c.Task.AttemptID, state, version, c.SyntheticPrefix+":cancellation-request",
		c.OperationID, digest, c.Task.TaskID, claimant, epoch, policy, source).Scan(&at)
	if err != nil {
		var pgerr *pgconn.PgError
		if errors.As(err, &pgerr) && pgerr.Code == "23505" && pgerr.ConstraintName == "uq_cancel_request_operation" {
			if rollbackErr := tx.Rollback(); rollbackErr != nil {
				return cancellationReceipt{}, rollbackErr
			}
			return s.reconcileCancellation(ctx, c)
		}
		return cancellationReceipt{}, fmt.Errorf("insert cancellation stop intent: %w", err)
	}
	if err := freezeExecutionInventory(ctx, tx, c.Task.RunID, id); err != nil {
		return cancellationReceipt{}, fmt.Errorf("freeze cancellation inventory: %w", err)
	}
	if err = tx.Commit(); err != nil {
		r, lookupErr := s.reconcileCancellation(ctx, c)
		if lookupErr == nil || errors.Is(lookupErr, errCancellationConflict) {
			return r, lookupErr
		}
		return cancellationReceipt{}, &cancellationUnknownError{err}
	}
	return cancellationReceipt{OperationID: c.OperationID, DigestVersion: 1, PayloadSHA256: digest,
		RunID: c.Task.RunID, TaskID: c.Task.TaskID, AttemptID: attempt, Stage: state,
		ClaimantID: claimant, LeaseEpoch: epoch, ExpectedRunVersion: version, PolicyEpoch: policy,
		SourceFingerprintSHA256: source, TransitionID: id, RunVersion: version + 1, RequestedAt: at}, nil
}

// Read-only independent lookup. Missing/unavailable evidence remains UNKNOWN;
// no automatic mutation retry, terminal cleanup or fabricated V5 receipt.
func (s *Store) reconcileCancellation(ctx context.Context, c cancellationRequest) (cancellationReceipt, error) {
	if err := c.validate(); err != nil {
		return cancellationReceipt{}, err
	}
	digest, err := c.canonicalPayloadSHA256()
	if err != nil {
		return cancellationReceipt{}, err
	}
	ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	if s.databaseURL == "" {
		return cancellationReceipt{}, &cancellationUnknownError{}
	}
	db, err := sql.Open("pgx", s.databaseURL)
	if err != nil {
		return cancellationReceipt{}, &cancellationUnknownError{err}
	}
	defer db.Close()
	db.SetMaxOpenConns(1)
	db.SetMaxIdleConns(0)
	r, err := queryCancellation(ctx, db, c.OperationID, digest)
	if err == nil || errors.Is(err, errCancellationConflict) {
		return r, err
	}
	return cancellationReceipt{}, &cancellationUnknownError{err}
}
