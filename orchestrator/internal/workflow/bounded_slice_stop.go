package workflow

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"strings"
	"time"
)

// StopSyntheticSlice is a trusted supervisor disposition, not an AgentResult.
// Stopping remains possible after policy/deadline expiry; it cannot create a
// successor or delivery intent. The exact run/version/task/epoch still fence it.
func (s *Store) StopSyntheticSlice(ctx context.Context, c SliceAdvance, disposition string) (advanceReceipt, error) {
	if (disposition != "BLOCKED" && disposition != "ESCALATED") || !canonicalUUID(c.OperationID) || !canonicalUUID(c.Lease.RunID) || !isLowerHex(c.SourceFingerprint, 64) || !strings.HasPrefix(c.EvidenceRef, "synthetic:") || len(c.EvidenceRef) > 256 {
		return advanceReceipt{}, ErrTransitionRejected
	}
	digest, err := sliceStopDigest(c, disposition)
	if err != nil {
		return advanceReceipt{}, err
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return advanceReceipt{}, err
	}
	defer tx.Rollback()
	var state, source string
	var version, epoch int64
	err = tx.QueryRowContext(ctx, `SELECT r.state,r.run_version,r.policy_epoch,r.source_fingerprint_sha256 FROM orchestration.workflow_runs r JOIN orchestration.workflow_slice_profiles p ON p.run_id=r.run_id WHERE r.run_id=$1::uuid AND r.subject_key LIKE p.scope_prefix || ':%' FOR UPDATE OF r`, c.Lease.RunID).Scan(&state, &version, &epoch, &source)
	if err != nil {
		return advanceReceipt{}, ErrTransitionRejected
	}
	if prior, e := querySliceResult(ctx, tx, c.OperationID, digest); e == nil {
		return prior, nil
	} else if !errors.Is(e, sql.ErrNoRows) {
		return advanceReceipt{}, e
	}
	if version != c.ExpectedRunVersion || epoch != c.PolicyEpoch || state != c.Lease.Stage || source != c.SourceFingerprint {
		return advanceReceipt{}, ErrTransitionRejected
	}
	// A stale claimant cannot stop a newly reclaimed delivery. Expiry itself does
	// not forbid a trusted supervisor from retaining a terminal failure outcome.
	result, err := tx.ExecContext(ctx, `UPDATE orchestration.task_deliveries SET status='CANCELLED',updated_at=clock_timestamp() WHERE task_id=$1::uuid AND run_id=$2::uuid AND attempt_id=$3::uuid AND stage=$4 AND claimant_id=$5 AND lease_epoch=$6 AND status='LEASED'`, c.Lease.TaskID, c.Lease.RunID, c.Lease.AttemptID, state, c.Lease.ClaimantID, c.Lease.LeaseEpoch)
	if e := oneCancellationRow(result, err); e != nil {
		return advanceReceipt{}, e
	}
	result, err = tx.ExecContext(ctx, `UPDATE orchestration.stage_attempts SET state='FAILED',output_artifact_ref=$3,completed_at=clock_timestamp() WHERE attempt_id=$1::uuid AND run_id=$2::uuid AND state='RUNNING'`, c.Lease.AttemptID, c.Lease.RunID, c.EvidenceRef)
	if e := oneCancellationRow(result, err); e != nil {
		return advanceReceipt{}, e
	}
	transition, err := freshUUID()
	if err != nil {
		return advanceReceipt{}, err
	}
	message, err := freshUUID()
	if err != nil {
		return advanceReceipt{}, err
	}
	_, err = tx.ExecContext(ctx, `UPDATE orchestration.workflow_runs SET state=$2,run_version=run_version+1,updated_at=clock_timestamp() WHERE run_id=$1::uuid`, c.Lease.RunID, disposition)
	if err != nil {
		return advanceReceipt{}, err
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO orchestration.workflow_transitions(transition_id,run_id,attempt_id,from_state,to_state,prior_run_version,evidence_ref) VALUES($1::uuid,$2::uuid,$3::uuid,$4,$5,$6,$7)`, transition, c.Lease.RunID, c.Lease.AttemptID, state, disposition, version, c.EvidenceRef)
	if err != nil {
		return advanceReceipt{}, err
	}
	payload := struct {
		Schema, RunID, Outcome, Evidence string
		Version                          int64
	}{"asep.stage.v1", c.Lease.RunID, disposition, c.EvidenceRef, version + 1}
	body, err := json.Marshal(payload)
	if err != nil {
		return advanceReceipt{}, err
	}
	hash, _ := privateDigest(payload)
	_, err = tx.ExecContext(ctx, `INSERT INTO orchestration.workflow_outbox(message_id,run_id,event_type,payload_sha256,payload) VALUES($1::uuid,$2::uuid,'BOUNDED_RUN_STOPPED',$3,$4::jsonb)`, message, c.Lease.RunID, hash, string(body))
	if err != nil {
		return advanceReceipt{}, err
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO orchestration.workflow_result_receipts(operation_id,digest_version,payload_sha256,run_id,task_id,attempt_id,claimant_id,lease_epoch,expected_run_version,policy_epoch,source_fingerprint_sha256,transition_id,next_task_id,next_attempt_id,outbox_message_id,accepted_run_version,accepted_at)
 VALUES($1::uuid,1,$2,$3::uuid,$4::uuid,$5::uuid,$6,$7,$8,$9,$10,$11::uuid,NULL,NULL,$12::uuid,$13,clock_timestamp())`, c.OperationID, digest, c.Lease.RunID, c.Lease.TaskID, c.Lease.AttemptID, c.Lease.ClaimantID, c.Lease.LeaseEpoch, version, epoch, source, transition, message, version+1)
	if err != nil {
		return advanceReceipt{}, err
	}
	if err = tx.Commit(); err != nil {
		return s.ReconcileSliceStop(ctx, c, disposition)
	}
	return querySliceResult(ctx, s.db, c.OperationID, digest)
}
func sliceStopDigest(c SliceAdvance, disposition string) (string, error) {
	c.Lease.ExpiresAt = time.Time{}
	c.Lease.DeliveryCount = 0
	return privateDigest(struct {
		Domain, Disposition string
		Command             SliceAdvance
	}{"asep.slice.stop.v1", disposition, c})
}
func querySliceResult(ctx context.Context, db executionReader, id, digest string) (advanceReceipt, error) {
	var r advanceReceipt
	err := db.QueryRowContext(ctx, `SELECT operation_id::text,digest_version,payload_sha256,run_id::text,task_id::text,attempt_id::text,claimant_id,lease_epoch,expected_run_version,policy_epoch,source_fingerprint_sha256,transition_id::text,coalesce(next_task_id::text,''),coalesce(next_attempt_id::text,''),outbox_message_id::text,accepted_run_version,accepted_at FROM orchestration.workflow_result_receipts WHERE operation_id=$1::uuid`, id).Scan(&r.OperationID, &r.DigestVersion, &r.PayloadSHA256, &r.RunID, &r.PreviousTaskID, &r.PreviousAttemptID, &r.ClaimantID, &r.LeaseEpoch, &r.ExpectedRunVersion, &r.PolicyEpoch, &r.SourceFingerprintSHA256, &r.TransitionID, &r.NextTaskID, &r.NextAttemptID, &r.OutboxMessageID, &r.RunVersion, &r.AcceptedAt)
	if err != nil {
		return advanceReceipt{}, err
	}
	if r.PayloadSHA256 != digest || r.DigestVersion != 1 {
		return advanceReceipt{}, ErrResultConflict
	}
	return r, nil
}
func (s *Store) ReconcileSliceStop(ctx context.Context, c SliceAdvance, disposition string) (advanceReceipt, error) {
	digest, err := sliceStopDigest(c, disposition)
	if err != nil {
		return advanceReceipt{}, err
	}
	ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	db, err := sql.Open("pgx", s.databaseURL)
	if err != nil {
		return advanceReceipt{}, &resultUnknownError{err}
	}
	defer db.Close()
	db.SetMaxOpenConns(1)
	r, err := querySliceResult(ctx, db, c.OperationID, digest)
	if err == nil || errors.Is(err, ErrResultConflict) {
		return r, err
	}
	return advanceReceipt{}, &resultUnknownError{err}
}
