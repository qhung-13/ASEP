package workflow

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"sort"
	"time"

	"github.com/jackc/pgx/v5/pgconn"
)

type cancellationFinalization struct {
	OperationID                    string
	Request                        cancellationRequest
	ExpectedRunVersion             int64
	InventorySHA256, CleanupSHA256 string
}

type finalizationReceipt struct {
	Control                                             cancellationReceipt
	RequestTransitionID, InventorySHA256, CleanupSHA256 string
}

func (c cancellationFinalization) validate() error {
	if !canonicalUUID(c.OperationID) || c.Request.validate() != nil || c.ExpectedRunVersion < 1 ||
		!isLowerHex(c.InventorySHA256, 64) || !isLowerHex(c.CleanupSHA256, 64) {
		return errCancellationRejected
	}
	return nil
}

func (c cancellationFinalization) digest() (string, error) {
	requestHash, err := c.Request.canonicalPayloadSHA256()
	if err != nil {
		return "", err
	}
	return privateDigest(struct {
		Version                                 int
		Kind, RequestOperationID, RequestSHA256 string
		ExpectedRunVersion                      int64
		InventorySHA256, CleanupSHA256          string
	}{1, "CANCEL_FINALIZE", c.Request.OperationID, requestHash, c.ExpectedRunVersion, c.InventorySHA256, c.CleanupSHA256})
}

func cleanupSetDigest(requestID, inventoryHash string, observations []cleanupObservation) (string, error) {
	ordered := append([]cleanupObservation{}, observations...)
	sort.Slice(ordered, func(i, j int) bool { return ordered[i].Scope.ExecutionID < ordered[j].Scope.ExecutionID })
	return privateDigest(struct {
		Version                  int
		RequestID, InventoryHash string
		Observations             []cleanupObservation
	}{
		1, requestID, inventoryHash, ordered})
}

// The frozen digest covers every registration, including unbound and older
// epoch scopes. Cleanup evidence is immutable, keeper/request/run bound, and
// complete. A legacy empty ledger is never a complete-inventory certificate.
func cancellationCleanup(ctx context.Context, db executionReader, request cancellationReceipt) (string, string, error) {
	inventory, err := readExecutionInventory(ctx, db, request.RunID)
	if errors.Is(err, sql.ErrNoRows) {
		return "", "", errCancellationRejected
	}
	if err != nil {
		return "", "", err
	}
	var frozen string
	var count int
	err = db.QueryRowContext(ctx, `SELECT manifest_sha256,scope_count FROM orchestration.workflow_cancellation_inventories
        WHERE request_transition_id=$1::uuid AND run_id=$2::uuid AND keeper_id=$3::uuid`,
		request.TransitionID, request.RunID, inventory.Keeper.ID).Scan(&frozen, &count)
	if errors.Is(err, sql.ErrNoRows) {
		return "", "", errCancellationRejected
	}
	if err != nil {
		return "", "", err
	}
	current, n, err := executionManifest(ctx, db, inventory)
	if err != nil {
		return "", "", err
	}
	if current != frozen || n != count {
		return "", "", errCancellationRejected
	}
	rows, err := db.QueryContext(ctx, `SELECT observation_id::text FROM orchestration.workflow_execution_cleanups
        WHERE request_transition_id=$1::uuid AND run_id=$2::uuid ORDER BY execution_id`, request.TransitionID, request.RunID)
	if err != nil {
		return "", "", err
	}
	ids := []string{}
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return "", "", err
		}
		ids = append(ids, id)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return "", "", err
	}
	if len(ids) != count {
		return "", "", errCancellationRejected
	}
	observations := []cleanupObservation{}
	for _, id := range ids {
		c, err := readCleanup(ctx, db, id)
		if err != nil {
			return "", "", err
		}
		if c.Scope.Keeper != inventory.Keeper || c.RequestTransitionID != request.TransitionID ||
			c.Scope.RunID != request.RunID || !c.ScopeClosed || !c.WorkspaceClean || (c.Outcome != "NOT_STARTED" && !c.Reaped) {
			return "", "", errCancellationRejected
		}
		observations = append(observations, c)
	}
	digest, err := cleanupSetDigest(request.TransitionID, frozen, observations)
	return frozen, digest, err
}

func queryFinalization(ctx context.Context, db executionReader, operationID, digest string) (finalizationReceipt, error) {
	var r finalizationReceipt
	var kind string
	c := &r.Control
	err := db.QueryRowContext(ctx, `SELECT control_kind,control_operation_id::text,control_digest_version,
        control_payload_sha256,run_id::text,control_task_id::text,attempt_id::text,from_state,
        control_claimant_id,control_lease_epoch,prior_run_version,control_policy_epoch,
        control_source_fingerprint_sha256,transition_id::text,prior_run_version+1,created_at,
        coalesce(control_request_transition_id::text,''),coalesce(control_inventory_sha256,''),coalesce(control_cleanup_sha256,'')
        FROM orchestration.workflow_transitions WHERE control_operation_id=$1::uuid`, operationID).Scan(
		&kind, &c.OperationID, &c.DigestVersion, &c.PayloadSHA256, &c.RunID, &c.TaskID, &c.AttemptID, &c.Stage,
		&c.ClaimantID, &c.LeaseEpoch, &c.ExpectedRunVersion, &c.PolicyEpoch, &c.SourceFingerprintSHA256, &c.TransitionID,
		&c.RunVersion, &c.RequestedAt, &r.RequestTransitionID, &r.InventorySHA256, &r.CleanupSHA256)
	if err != nil {
		return finalizationReceipt{}, err
	}
	if kind != "CANCEL_FINALIZE" || c.DigestVersion != 1 || c.PayloadSHA256 != digest {
		return finalizationReceipt{}, errCancellationConflict
	}
	return r, nil
}

func (s *Store) finalizeCancellation(ctx context.Context, c cancellationFinalization) (finalizationReceipt, error) {
	if err := c.validate(); err != nil {
		return finalizationReceipt{}, err
	}
	digest, err := c.digest()
	if err != nil {
		return finalizationReceipt{}, err
	}
	id, err := freshUUID()
	if err != nil {
		return finalizationReceipt{}, err
	}
	tx, err := s.db.BeginTx(ctx, &sql.TxOptions{Isolation: sql.LevelReadCommitted})
	if err != nil {
		return finalizationReceipt{}, err
	}
	defer tx.Rollback()
	var state, source string
	var version, policy int64
	err = tx.QueryRowContext(ctx, `SELECT state,run_version,policy_epoch,source_fingerprint_sha256
        FROM orchestration.workflow_runs WHERE run_id=$1::uuid FOR UPDATE`, c.Request.Task.RunID).Scan(&state, &version, &policy, &source)
	if errors.Is(err, sql.ErrNoRows) {
		return finalizationReceipt{}, errCancellationRejected
	}
	if err != nil {
		return finalizationReceipt{}, err
	}
	prior, err := queryFinalization(ctx, tx, c.OperationID, digest)
	if err == nil {
		return prior, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return finalizationReceipt{}, err
	}
	requestHash, err := c.Request.canonicalPayloadSHA256()
	if err != nil {
		return finalizationReceipt{}, err
	}
	request, err := queryCancellation(ctx, tx, c.Request.OperationID, requestHash)
	if errors.Is(err, sql.ErrNoRows) {
		return finalizationReceipt{}, errCancellationRejected
	}
	if err != nil {
		return finalizationReceipt{}, err
	}
	if state != "CANCEL_REQUESTED" || version != c.ExpectedRunVersion || version != request.RunVersion ||
		policy != request.PolicyEpoch || source != request.SourceFingerprintSHA256 {
		return finalizationReceipt{}, errCancellationRejected
	}
	var taskID string
	err = tx.QueryRowContext(ctx, `SELECT task_id::text FROM orchestration.task_deliveries
        WHERE task_id=$1::uuid AND run_id=$2::uuid FOR UPDATE`, request.TaskID, request.RunID).Scan(&taskID)
	if errors.Is(err, sql.ErrNoRows) {
		return finalizationReceipt{}, errCancellationRejected
	}
	if err != nil {
		return finalizationReceipt{}, err
	}
	var attemptID string
	err = tx.QueryRowContext(ctx, `SELECT attempt_id::text FROM orchestration.stage_attempts
        WHERE attempt_id=$1::uuid AND run_id=$2::uuid FOR UPDATE`, request.AttemptID, request.RunID).Scan(&attemptID)
	if errors.Is(err, sql.ErrNoRows) {
		return finalizationReceipt{}, errCancellationRejected
	}
	if err != nil {
		return finalizationReceipt{}, err
	}
	inventory, cleanup, err := cancellationCleanup(ctx, tx, request)
	if err != nil {
		return finalizationReceipt{}, err
	}
	if inventory != c.InventorySHA256 || cleanup != c.CleanupSHA256 {
		return finalizationReceipt{}, errCancellationRejected
	}
	changed, err := tx.ExecContext(ctx, `UPDATE orchestration.task_deliveries SET status='CANCELLED',updated_at=clock_timestamp()
        WHERE task_id=$1::uuid AND run_id=$2::uuid AND attempt_id=$3::uuid AND stage=$4
          AND coalesce(claimant_id,'')=$5 AND lease_epoch=$6 AND status IN ('QUEUED','LEASED')`,
		request.TaskID, request.RunID, request.AttemptID, c.Request.Task.Stage, request.ClaimantID, request.LeaseEpoch)
	if err := oneCancellationRow(changed, err); err != nil {
		return finalizationReceipt{}, err
	}
	changed, err = tx.ExecContext(ctx, `UPDATE orchestration.stage_attempts SET state='CANCELLED',completed_at=clock_timestamp()
        WHERE attempt_id=$1::uuid AND run_id=$2::uuid AND stage=$3 AND state IN ('PENDING','RUNNING')`,
		request.AttemptID, request.RunID, c.Request.Task.Stage)
	if err := oneCancellationRow(changed, err); err != nil {
		return finalizationReceipt{}, err
	}
	changed, err = tx.ExecContext(ctx, `UPDATE orchestration.workflow_runs SET state='CANCELLED',run_version=run_version+1,updated_at=clock_timestamp()
        WHERE run_id=$1::uuid AND state='CANCEL_REQUESTED' AND run_version=$2 AND policy_epoch=$3 AND source_fingerprint_sha256=$4`,
		request.RunID, version, policy, source)
	if err := oneCancellationRow(changed, err); err != nil {
		return finalizationReceipt{}, err
	}
	var at time.Time
	err = tx.QueryRowContext(ctx, `INSERT INTO orchestration.workflow_transitions
        (transition_id,run_id,attempt_id,from_state,to_state,prior_run_version,evidence_ref,budget_debit,
         control_operation_id,control_kind,control_digest_version,control_payload_sha256,control_task_id,
         control_claimant_id,control_lease_epoch,control_policy_epoch,control_source_fingerprint_sha256,
         control_request_transition_id,control_inventory_sha256,control_cleanup_sha256,created_at)
        VALUES($1::uuid,$2::uuid,$3::uuid,'CANCEL_REQUESTED','CANCELLED',$4,$5,0,
         $6::uuid,'CANCEL_FINALIZE',1,$7,$8::uuid,$9,$10,$11,$12,$13::uuid,$14,$15,clock_timestamp()) RETURNING created_at`,
		id, request.RunID, request.AttemptID, version, c.Request.SyntheticPrefix+":cancellation-finalize",
		c.OperationID, digest, request.TaskID, request.ClaimantID, request.LeaseEpoch, policy, source,
		request.TransitionID, inventory, cleanup).Scan(&at)
	if err != nil {
		var pgerr *pgconn.PgError
		if errors.As(err, &pgerr) && pgerr.Code == "23505" && pgerr.ConstraintName == "uq_cancel_request_operation" {
			if rollback := tx.Rollback(); rollback != nil {
				return finalizationReceipt{}, rollback
			}
			return s.reconcileFinalization(ctx, c)
		}
		return finalizationReceipt{}, fmt.Errorf("insert cancellation finalization: %w", err)
	}
	if err := tx.Commit(); err != nil {
		r, lookup := s.reconcileFinalization(ctx, c)
		if lookup == nil || errors.Is(lookup, errCancellationConflict) {
			return r, lookup
		}
		return finalizationReceipt{}, &cancellationUnknownError{err}
	}
	return finalizationReceipt{Control: cancellationReceipt{OperationID: c.OperationID, PayloadSHA256: digest,
		DigestVersion: 1, RunID: request.RunID, TaskID: request.TaskID, AttemptID: request.AttemptID, Stage: "CANCEL_REQUESTED",
		ClaimantID: request.ClaimantID, LeaseEpoch: request.LeaseEpoch, ExpectedRunVersion: version, PolicyEpoch: policy,
		SourceFingerprintSHA256: source, TransitionID: id, RunVersion: version + 1, RequestedAt: at},
		RequestTransitionID: request.TransitionID, InventorySHA256: inventory, CleanupSHA256: cleanup}, nil
}

func oneCancellationRow(result sql.Result, err error) error {
	if err != nil {
		return err
	}
	n, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if n != 1 {
		return errCancellationRejected
	}
	return nil
}

func (s *Store) reconcileFinalization(ctx context.Context, c cancellationFinalization) (finalizationReceipt, error) {
	if err := c.validate(); err != nil {
		return finalizationReceipt{}, err
	}
	digest, err := c.digest()
	if err != nil {
		return finalizationReceipt{}, err
	}
	var r finalizationReceipt
	err = s.confirmExecution(ctx, func(ctx context.Context, db executionReader) error {
		var err error
		r, err = queryFinalization(ctx, db, c.OperationID, digest)
		return err
	})
	return r, err
}
