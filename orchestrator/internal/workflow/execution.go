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

// These private records are trusted synthetic keeper attestations, not public
// authorization or PID-based capabilities. Only the keeper retains OS handles.
type keeperIdentity struct {
	ID, BootID string
	PID        int
	Start      int64
}

func (k keeperIdentity) valid() bool {
	return canonicalUUID(k.ID) && canonicalUUID(k.BootID) && k.PID > 0 && k.Start > 0
}

type executionInventory struct {
	RunID, SyntheticPrefix string
	Keeper                 keeperIdentity
}

type executionScope struct {
	ExecutionID, RunID string
	Keeper             keeperIdentity
}

type executionBinding struct {
	Scope      executionScope
	Task       TaskLease
	RunVersion int64
}

type cleanupObservation struct {
	ObservationID, RequestTransitionID  string
	Scope                               executionScope
	Outcome                             string
	WaitStatus                          int
	ScopeClosed, Reaped, WorkspaceClean bool
}

type executionReader interface {
	QueryRowContext(context.Context, string, ...any) *sql.Row
	QueryContext(context.Context, string, ...any) (*sql.Rows, error)
}

type executionRun struct {
	state, subject string
	version        int64
}

func privateDigest(body any) (string, error) {
	raw, err := json.Marshal(body)
	if err != nil {
		return "", err
	}
	hash := sha256.Sum256(raw)
	return hex.EncodeToString(hash[:]), nil
}

// One short run-locked bookkeeping transaction. On uncertain COMMIT, confirm
// only the original immutable identity on an independent connection; never retry.
func (s *Store) executionTransaction(ctx context.Context, runID string,
	apply func(*sql.Tx, executionRun) error, confirm func(context.Context, executionReader) error) error {
	tx, err := s.db.BeginTx(ctx, &sql.TxOptions{Isolation: sql.LevelReadCommitted})
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var run executionRun
	err = tx.QueryRowContext(ctx, `SELECT state,subject_key,run_version FROM orchestration.workflow_runs
        WHERE run_id=$1::uuid FOR UPDATE`, runID).Scan(&run.state, &run.subject, &run.version)
	if errors.Is(err, sql.ErrNoRows) {
		return errCancellationRejected
	}
	if err != nil {
		return err
	}
	if err := apply(tx, run); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		lookup := s.confirmExecution(ctx, confirm)
		if lookup == nil || errors.Is(lookup, errCancellationConflict) {
			return lookup
		}
		return &cancellationUnknownError{err}
	}
	return nil
}

func (s *Store) confirmExecution(ctx context.Context, confirm func(context.Context, executionReader) error) error {
	ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	if s.databaseURL == "" {
		return &cancellationUnknownError{}
	}
	db, err := sql.Open("pgx", s.databaseURL)
	if err != nil {
		return &cancellationUnknownError{err}
	}
	defer db.Close()
	db.SetMaxOpenConns(1)
	db.SetMaxIdleConns(0)
	err = confirm(ctx, db)
	if err == nil || errors.Is(err, errCancellationConflict) {
		return err
	}
	return &cancellationUnknownError{err}
}

func readExecutionInventory(ctx context.Context, db executionReader, runID string) (executionInventory, error) {
	r := executionInventory{RunID: runID}
	err := db.QueryRowContext(ctx, `SELECT synthetic_prefix,keeper_id::text,boot_id::text,keeper_pid,keeper_start
        FROM orchestration.workflow_execution_inventories WHERE run_id=$1::uuid`, runID).Scan(
		&r.SyntheticPrefix, &r.Keeper.ID, &r.Keeper.BootID, &r.Keeper.PID, &r.Keeper.Start)
	return r, err
}

func matchInventory(ctx context.Context, db executionReader, want executionInventory) error {
	got, err := readExecutionInventory(ctx, db, want.RunID)
	if err != nil {
		return err
	}
	if got != want {
		return errCancellationConflict
	}
	return nil
}

// Enrollment is a completeness attestation by the trusted fixture keeper,
// before the FIRST claim/spawn. Legacy empty ledgers cannot be enrolled later.
func (s *Store) admitExecution(ctx context.Context, c Admission, keeper keeperIdentity) (AdmissionReceipt, error) {
	parts := strings.SplitN(c.SubjectKey, ":", 3)
	if !keeper.valid() || len(parts) != 3 || !syntheticPrefixPattern.MatchString(parts[0]+":"+parts[1]) {
		return AdmissionReceipt{}, errCancellationRejected
	}
	return s.admit(ctx, c, &executionInventory{SyntheticPrefix: parts[0] + ":" + parts[1], Keeper: keeper})
}

func lookupExecutionAdmission(ctx context.Context, db executionReader, c Admission, inventory executionInventory) (AdmissionReceipt, error) {
	var r AdmissionReceipt
	var hash string
	err := db.QueryRowContext(ctx, `SELECT r.run_id::text,t.task_id::text,t.attempt_id::text,r.command_payload_sha256
        FROM orchestration.workflow_runs r JOIN orchestration.stage_attempts a ON a.run_id=r.run_id
        AND a.stage='INVESTIGATING' AND a.semantic_attempt_no=1
        JOIN orchestration.task_deliveries t ON t.attempt_id=a.attempt_id WHERE r.command_id=$1::uuid`, c.CommandID).Scan(
		&r.RunID, &r.TaskID, &r.AttemptID, &hash)
	if err != nil {
		return r, err
	}
	want, err := c.canonicalPayloadSHA256()
	if err != nil {
		return r, err
	}
	if hash != want {
		return AdmissionReceipt{}, errCancellationConflict
	}
	inventory.RunID = r.RunID
	return r, matchInventory(ctx, db, inventory)
}

func (s *Store) reconcileExecutionAdmission(ctx context.Context, c Admission, inventory executionInventory) (AdmissionReceipt, error) {
	var r AdmissionReceipt
	err := s.confirmExecution(ctx, func(ctx context.Context, db executionReader) error {
		var err error
		r, err = lookupExecutionAdmission(ctx, db, c, inventory)
		return err
	})
	return r, err
}

func readExecutionScope(ctx context.Context, db executionReader, id string) (executionScope, error) {
	var r executionScope
	err := db.QueryRowContext(ctx, `SELECT s.execution_id::text,s.run_id::text,i.keeper_id::text,
        i.boot_id::text,i.keeper_pid,i.keeper_start FROM orchestration.workflow_execution_scopes s
        JOIN orchestration.workflow_execution_inventories i USING(run_id,keeper_id) WHERE execution_id=$1::uuid`, id).Scan(
		&r.ExecutionID, &r.RunID, &r.Keeper.ID, &r.Keeper.BootID, &r.Keeper.PID, &r.Keeper.Start)
	return r, err
}

func (s *Store) registerExecution(ctx context.Context, c executionScope) error {
	if !canonicalUUID(c.ExecutionID) || !canonicalUUID(c.RunID) || !c.Keeper.valid() {
		return errCancellationRejected
	}
	confirm := func(ctx context.Context, db executionReader) error {
		got, err := readExecutionScope(ctx, db, c.ExecutionID)
		if err != nil {
			return err
		}
		if got != c {
			return errCancellationConflict
		}
		return nil
	}
	return s.executionTransaction(ctx, c.RunID, func(tx *sql.Tx, run executionRun) error {
		if err := confirm(ctx, tx); !errors.Is(err, sql.ErrNoRows) {
			return err
		}
		inventory, err := readExecutionInventory(ctx, tx, c.RunID)
		if errors.Is(err, sql.ErrNoRows) {
			return errCancellationRejected
		}
		if err != nil {
			return err
		}
		if inventory.Keeper != c.Keeper || (run.state != "INVESTIGATING" && run.state != "PLANNING") {
			return errCancellationRejected
		}
		_, err = tx.ExecContext(ctx, `INSERT INTO orchestration.workflow_execution_scopes(execution_id,run_id,keeper_id)
            VALUES($1::uuid,$2::uuid,$3::uuid) ON CONFLICT(execution_id) DO NOTHING`, c.ExecutionID, c.RunID, c.Keeper.ID)
		if err != nil {
			return err
		}
		return confirm(ctx, tx)
	}, confirm)
}

func readExecutionBinding(ctx context.Context, db executionReader, id string) (executionBinding, error) {
	scope, err := readExecutionScope(ctx, db, id)
	if err != nil {
		return executionBinding{}, err
	}
	r := executionBinding{Scope: scope}
	r.Task.RunID = scope.RunID
	err = db.QueryRowContext(ctx, `SELECT task_id::text,attempt_id::text,stage,claimant_id,lease_epoch,run_version
        FROM orchestration.workflow_execution_bindings WHERE execution_id=$1::uuid`, id).Scan(
		&r.Task.TaskID, &r.Task.AttemptID, &r.Task.Stage, &r.Task.ClaimantID, &r.Task.LeaseEpoch, &r.RunVersion)
	return r, err
}

func (s *Store) bindExecution(ctx context.Context, c executionBinding) error {
	// Binding records existing authority; it never grants result authority or
	// changes the lease. Mutable expiry/delivery metadata is not binding identity.
	c.Task.ExpiresAt, c.Task.DeliveryCount = time.Time{}, 0
	if !canonicalUUID(c.Scope.ExecutionID) || !canonicalUUID(c.Scope.RunID) || !c.Scope.Keeper.valid() ||
		c.Task.RunID != c.Scope.RunID || !canonicalUUID(c.Task.TaskID) || !canonicalUUID(c.Task.AttemptID) ||
		c.Task.LeaseEpoch < 1 || c.RunVersion < 1 {
		return errCancellationRejected
	}
	confirm := func(ctx context.Context, db executionReader) error {
		got, err := readExecutionBinding(ctx, db, c.Scope.ExecutionID)
		if err != nil {
			return err
		}
		if got != c {
			return errCancellationConflict
		}
		return nil
	}
	return s.executionTransaction(ctx, c.Scope.RunID, func(tx *sql.Tx, run executionRun) error {
		if err := confirm(ctx, tx); !errors.Is(err, sql.ErrNoRows) {
			return err
		}
		scope, err := readExecutionScope(ctx, tx, c.Scope.ExecutionID)
		if errors.Is(err, sql.ErrNoRows) {
			return errCancellationRejected
		}
		if err != nil {
			return err
		}
		if scope != c.Scope || run.state != c.Task.Stage || run.version != c.RunVersion {
			return errCancellationRejected
		}
		var id string
		err = tx.QueryRowContext(ctx, `SELECT task_id::text FROM orchestration.task_deliveries
            WHERE task_id=$1::uuid AND run_id=$2::uuid FOR UPDATE`, c.Task.TaskID, c.Task.RunID).Scan(&id)
		if errors.Is(err, sql.ErrNoRows) {
			return errCancellationRejected
		}
		if err != nil {
			return err
		}
		var valid bool
		err = tx.QueryRowContext(ctx, `SELECT status='LEASED' AND attempt_id=$3::uuid AND stage=$4
            AND claimant_id=$5 AND lease_epoch=$6 AND lease_expires_at>clock_timestamp()
            FROM orchestration.task_deliveries WHERE task_id=$1::uuid AND run_id=$2::uuid`,
			c.Task.TaskID, c.Task.RunID, c.Task.AttemptID, c.Task.Stage, c.Task.ClaimantID, c.Task.LeaseEpoch).Scan(&valid)
		if err != nil {
			return err
		}
		if !valid {
			return errCancellationRejected
		}
		_, err = tx.ExecContext(ctx, `INSERT INTO orchestration.workflow_execution_bindings
            (execution_id,run_id,keeper_id,task_id,attempt_id,stage,claimant_id,lease_epoch,run_version)
            VALUES($1::uuid,$2::uuid,$3::uuid,$4::uuid,$5::uuid,$6,$7,$8,$9)
            ON CONFLICT(execution_id) DO NOTHING`, c.Scope.ExecutionID, c.Scope.RunID, c.Scope.Keeper.ID,
			c.Task.TaskID, c.Task.AttemptID, c.Task.Stage, c.Task.ClaimantID, c.Task.LeaseEpoch, c.RunVersion)
		if err != nil {
			return err
		}
		return confirm(ctx, tx)
	}, confirm)
}

type inventoryMember struct {
	Scope                                executionScope
	TaskID, AttemptID, Stage, ClaimantID string
	Epoch, RunVersion                    int64
}

func executionManifest(ctx context.Context, db executionReader, inventory executionInventory) (string, int, error) {
	rows, err := db.QueryContext(ctx, `SELECT s.execution_id::text,coalesce(b.task_id::text,''),
        coalesce(b.attempt_id::text,''),coalesce(b.stage,''),coalesce(b.claimant_id,''),
        coalesce(b.lease_epoch,0),coalesce(b.run_version,0)
        FROM orchestration.workflow_execution_scopes s LEFT JOIN orchestration.workflow_execution_bindings b USING(execution_id)
        WHERE s.run_id=$1::uuid ORDER BY s.execution_id`, inventory.RunID)
	if err != nil {
		return "", 0, err
	}
	defer rows.Close()
	members := []inventoryMember{}
	for rows.Next() {
		m := inventoryMember{Scope: executionScope{RunID: inventory.RunID, Keeper: inventory.Keeper}}
		if err := rows.Scan(&m.Scope.ExecutionID, &m.TaskID, &m.AttemptID, &m.Stage, &m.ClaimantID, &m.Epoch, &m.RunVersion); err != nil {
			return "", 0, err
		}
		members = append(members, m)
	}
	if err := rows.Err(); err != nil {
		return "", 0, err
	}
	digest, err := privateDigest(struct {
		Version   int
		Inventory executionInventory
		Members   []inventoryMember
	}{1, inventory, members})
	return digest, len(members), err
}

func freezeExecutionInventory(ctx context.Context, tx *sql.Tx, runID, requestID string) error {
	// Preserve the V6 request path during a populated upgrade. Absence of V7
	// grants no finalization authority; RF-07B always requires its frozen proof.
	var available bool
	if err := tx.QueryRowContext(ctx, `SELECT to_regclass('orchestration.workflow_execution_inventories') IS NOT NULL`).Scan(&available); err != nil {
		return err
	}
	if !available {
		return nil
	}
	inventory, err := readExecutionInventory(ctx, tx, runID)
	if errors.Is(err, sql.ErrNoRows) {
		return nil
	} // Legacy is NOT an empty, complete inventory.
	if err != nil {
		return err
	}
	digest, count, err := executionManifest(ctx, tx, inventory)
	if err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO orchestration.workflow_cancellation_inventories
        (request_transition_id,run_id,keeper_id,manifest_sha256,scope_count) VALUES($1::uuid,$2::uuid,$3::uuid,$4,$5)`,
		requestID, runID, inventory.Keeper.ID, digest, count)
	return err
}

func (c cleanupObservation) digest() (string, error) {
	return privateDigest(struct {
		Version  int
		Evidence cleanupObservation
	}{1, c})
}

func readCleanup(ctx context.Context, db executionReader, id string) (cleanupObservation, error) {
	var c cleanupObservation
	var stored string
	err := db.QueryRowContext(ctx, `SELECT o.observation_id::text,o.request_transition_id::text,
        o.execution_id::text,o.run_id::text,i.keeper_id::text,i.boot_id::text,i.keeper_pid,i.keeper_start,
        o.outcome,o.wait_status,o.scope_closed,o.reaped,o.workspace_clean,o.evidence_sha256
        FROM orchestration.workflow_execution_cleanups o JOIN orchestration.workflow_execution_inventories i USING(run_id,keeper_id)
        WHERE observation_id=$1::uuid`, id).Scan(&c.ObservationID, &c.RequestTransitionID, &c.Scope.ExecutionID, &c.Scope.RunID,
		&c.Scope.Keeper.ID, &c.Scope.Keeper.BootID, &c.Scope.Keeper.PID, &c.Scope.Keeper.Start,
		&c.Outcome, &c.WaitStatus, &c.ScopeClosed, &c.Reaped, &c.WorkspaceClean, &stored)
	if err != nil {
		return c, err
	}
	digest, err := c.digest()
	if err != nil {
		return c, err
	}
	if digest != stored {
		return c, errCancellationConflict
	}
	return c, nil
}

func (s *Store) recordExecutionCleanup(ctx context.Context, c cleanupObservation) error {
	validOutcome := c.Outcome == "TERM" || c.Outcome == "KILL" || c.Outcome == "EXITED" || c.Outcome == "NOT_STARTED"
	if !canonicalUUID(c.ObservationID) || !canonicalUUID(c.RequestTransitionID) || !canonicalUUID(c.Scope.ExecutionID) ||
		!canonicalUUID(c.Scope.RunID) || !c.Scope.Keeper.valid() || !validOutcome || !c.ScopeClosed || !c.WorkspaceClean ||
		c.WaitStatus < 0 || (c.Outcome == "NOT_STARTED" && (c.Reaped || c.WaitStatus != 0)) ||
		(c.Outcome != "NOT_STARTED" && !c.Reaped) {
		return errCancellationRejected
	}
	confirm := func(ctx context.Context, db executionReader) error {
		got, err := readCleanup(ctx, db, c.ObservationID)
		if err != nil {
			return err
		}
		if got != c {
			return errCancellationConflict
		}
		return nil
	}
	return s.executionTransaction(ctx, c.Scope.RunID, func(tx *sql.Tx, run executionRun) error {
		if err := confirm(ctx, tx); !errors.Is(err, sql.ErrNoRows) {
			return err
		}
		if run.state != "CANCEL_REQUESTED" {
			return errCancellationRejected
		}
		scope, err := readExecutionScope(ctx, tx, c.Scope.ExecutionID)
		if errors.Is(err, sql.ErrNoRows) {
			return errCancellationRejected
		}
		if err != nil {
			return err
		}
		if scope != c.Scope {
			return errCancellationRejected
		}
		var exists bool
		err = tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM orchestration.workflow_cancellation_inventories
            WHERE request_transition_id=$1::uuid AND run_id=$2::uuid AND keeper_id=$3::uuid)`,
			c.RequestTransitionID, c.Scope.RunID, c.Scope.Keeper.ID).Scan(&exists)
		if err != nil {
			return err
		}
		if !exists {
			return errCancellationRejected
		}
		digest, err := c.digest()
		if err != nil {
			return err
		}
		_, err = tx.ExecContext(ctx, `INSERT INTO orchestration.workflow_execution_cleanups
            (observation_id,request_transition_id,execution_id,run_id,keeper_id,evidence_sha256,outcome,
              wait_status,scope_closed,reaped,workspace_clean)
            VALUES($1::uuid,$2::uuid,$3::uuid,$4::uuid,$5::uuid,$6,$7,$8,$9,$10,$11)
            ON CONFLICT DO NOTHING`, c.ObservationID, c.RequestTransitionID, c.Scope.ExecutionID, c.Scope.RunID,
			c.Scope.Keeper.ID, digest, c.Outcome, c.WaitStatus, c.ScopeClosed, c.Reaped, c.WorkspaceClean)
		if err != nil {
			return err
		}
		if err := confirm(ctx, tx); errors.Is(err, sql.ErrNoRows) {
			return errCancellationConflict
		} else {
			return err
		}
	}, confirm)
}
