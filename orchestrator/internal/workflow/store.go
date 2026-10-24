package workflow

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgconn"
	_ "github.com/jackc/pgx/v5/stdlib"
)

// Store is trusted Go orchestration state, NOT an untrusted agent interface.
// Admission inputs are assumed to have passed Java authorization/policy checks.
// The cross-service authentication/gate adapter remains a later ticket.
type Store struct{ db *sql.DB }

var (
	ErrConflict  = errors.New("workflow admission conflict")
	ErrNoTask    = errors.New("no eligible task")
	ErrLeaseLost = errors.New("lease invalid, expired, or revoked")
)

func Open(ctx context.Context, databaseURL string) (*Store, error) {
	if databaseURL == "" {
		return nil, errors.New("missing database configuration")
	}
	db, err := sql.Open("pgx", databaseURL)
	if err != nil {
		return nil, fmt.Errorf("open orchestration database: %w", err)
	}
	db.SetMaxOpenConns(20)
	db.SetMaxIdleConns(8)
	db.SetConnMaxLifetime(10 * time.Minute)
	if err := db.PingContext(ctx); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("connect orchestration database: %w", err)
	}
	return &Store{db: db}, nil
}

func (s *Store) Close() error { return s.db.Close() }

type Admission struct {
	CommandID               string
	PayloadSHA256           string
	SubjectKey              string
	ActorID                 string
	JiraSiteID              string
	JiraProjectID           string
	JiraIssueID             string
	GitLabProjectID         string
	RepositoryBaseSHA       string
	TargetBranch            string
	ObservedTargetHeadSHA   string
	SourceFingerprintSHA256 string
	PolicyID                string
	PolicyEpoch             int64
	BudgetLimit             int
}

type AdmissionReceipt struct {
	RunID     string
	AttemptID string
	TaskID    string
	Replayed  bool
}

func isLowerHex(v string, lengths ...int) bool {
	ok := false
	for _, n := range lengths {
		if len(v) == n {
			ok = true
		}
	}
	if !ok {
		return false
	}
	for _, ch := range v {
		if !(ch >= '0' && ch <= '9' || ch >= 'a' && ch <= 'f') {
			return false
		}
	}
	return true
}

// canonicalPayloadSHA256 hashes only the semantic, versioned admission body.
// CommandID is an idempotency key (outside the body), and the optional caller
// digest is never trusted. The struct gives JSON a fixed, language-independent
// field order for this v1 Go-owned prototype contract.
func (c Admission) canonicalPayloadSHA256() (string, error) {
	payload := struct {
		Version                 int    `json:"version"`
		SubjectKey              string `json:"subject_key"`
		ActorID                 string `json:"actor_id"`
		JiraSiteID              string `json:"jira_site_id"`
		JiraProjectID           string `json:"jira_project_id"`
		JiraIssueID             string `json:"jira_issue_id"`
		GitLabProjectID         string `json:"gitlab_project_id"`
		RepositoryBaseSHA       string `json:"repository_base_sha"`
		TargetBranch            string `json:"target_branch"`
		ObservedTargetHeadSHA   string `json:"observed_target_head_sha"`
		SourceFingerprintSHA256 string `json:"source_fingerprint_sha256"`
		PolicyID                string `json:"policy_id"`
		PolicyEpoch             int64  `json:"policy_epoch"`
		BudgetLimit             int    `json:"budget_limit"`
	}{
		Version:    1,
		SubjectKey: c.SubjectKey, ActorID: c.ActorID,
		JiraSiteID: c.JiraSiteID, JiraProjectID: c.JiraProjectID,
		JiraIssueID: c.JiraIssueID, GitLabProjectID: c.GitLabProjectID,
		RepositoryBaseSHA: c.RepositoryBaseSHA, TargetBranch: c.TargetBranch,
		ObservedTargetHeadSHA:   c.ObservedTargetHeadSHA,
		SourceFingerprintSHA256: c.SourceFingerprintSHA256,
		PolicyID:                c.PolicyID, PolicyEpoch: c.PolicyEpoch,
		BudgetLimit: c.BudgetLimit,
	}
	body, err := json.Marshal(payload)
	if err != nil {
		return "", fmt.Errorf("encode canonical admission v1: %w", err)
	}
	digest := sha256.Sum256(body)
	return hex.EncodeToString(digest[:]), nil
}

func (c Admission) validate() error {
	if c.CommandID == "" || (c.PayloadSHA256 != "" && !isLowerHex(c.PayloadSHA256, 64)) ||
		!isLowerHex(c.SourceFingerprintSHA256, 64) ||
		!isLowerHex(c.RepositoryBaseSHA, 40, 64) ||
		!isLowerHex(c.ObservedTargetHeadSHA, 40, 64) {
		return errors.New("invalid command or source digest")
	}
	for _, v := range []string{c.SubjectKey, c.ActorID, c.JiraSiteID, c.JiraProjectID,
		c.JiraIssueID, c.GitLabProjectID, c.TargetBranch, c.PolicyID} {
		if strings.TrimSpace(v) == "" {
			return errors.New("missing required admission identity")
		}
	}
	if len(c.SubjectKey) > 256 || len(c.ActorID) > 128 || len(c.PolicyID) > 128 ||
		len(c.TargetBranch) > 128 || c.PolicyEpoch <= 0 || c.BudgetLimit < 1 || c.BudgetLimit > 100 {
		return errors.New("admission field outside approved bounds")
	}
	return nil
}

func freshUUID() (string, error) {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16]), nil
}

func isUnique(err error) bool {
	var pgerr *pgconn.PgError
	return errors.As(err, &pgerr) && pgerr.Code == "23505"
}

// Admit atomically creates a run, first semantic attempt/task, transition, and outbox.
// Same command+canonical payload replays the original receipt; changed content
// conflicts. Active-run uniqueness is enforced by durable Jira/GitLab identity,
// not the caller-provided SubjectKey, which is only a scoped display label.
func (s *Store) Admit(ctx context.Context, c Admission) (AdmissionReceipt, error) {
	if err := c.validate(); err != nil {
		return AdmissionReceipt{}, err
	}
	canonicalDigest, err := c.canonicalPayloadSHA256()
	if err != nil {
		return AdmissionReceipt{}, err
	}
	// Callers may optionally assert a digest, but it must match the actual
	// admitted body. A claimed digest is not proof of payload equality.
	if c.PayloadSHA256 != "" && c.PayloadSHA256 != canonicalDigest {
		return AdmissionReceipt{}, ErrConflict
	}
	runID, err := freshUUID()
	if err != nil {
		return AdmissionReceipt{}, err
	}
	attemptID, err := freshUUID()
	if err != nil {
		return AdmissionReceipt{}, err
	}
	taskID, err := freshUUID()
	if err != nil {
		return AdmissionReceipt{}, err
	}
	transitionID, err := freshUUID()
	if err != nil {
		return AdmissionReceipt{}, err
	}
	messageID, err := freshUUID()
	if err != nil {
		return AdmissionReceipt{}, err
	}
	tx, err := s.db.BeginTx(ctx, &sql.TxOptions{Isolation: sql.LevelReadCommitted})
	if err != nil {
		return AdmissionReceipt{}, err
	}
	defer tx.Rollback()
	_, err = tx.ExecContext(ctx, `
        INSERT INTO orchestration.workflow_runs(
          run_id,command_id,command_payload_sha256,subject_key,actor_id,
          jira_site_id,jira_project_id,jira_issue_id,gitlab_project_id,
          repository_base_sha,target_branch,observed_target_head_sha,
          source_fingerprint_sha256,policy_id,policy_epoch,budget_limit
        ) VALUES ($1::uuid,$2::uuid,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16)`,
		runID, c.CommandID, canonicalDigest, c.SubjectKey, c.ActorID, c.JiraSiteID,
		c.JiraProjectID, c.JiraIssueID, c.GitLabProjectID, c.RepositoryBaseSHA,
		c.TargetBranch, c.ObservedTargetHeadSHA, c.SourceFingerprintSHA256,
		c.PolicyID, c.PolicyEpoch, c.BudgetLimit)
	if err != nil {
		_ = tx.Rollback()
		if isUnique(err) {
			return s.lookupConflict(ctx, c.CommandID, canonicalDigest)
		}
		return AdmissionReceipt{}, fmt.Errorf("insert workflow run: %w", err)
	}
	_, err = tx.ExecContext(ctx, `
        INSERT INTO orchestration.stage_attempts(
          attempt_id,run_id,stage,semantic_attempt_no
        ) VALUES ($1::uuid,$2::uuid,'INVESTIGATING',1)`, attemptID, runID)
	if err != nil {
		return AdmissionReceipt{}, fmt.Errorf("insert stage attempt: %w", err)
	}
	_, err = tx.ExecContext(ctx, `
        INSERT INTO orchestration.task_deliveries(
          task_id,run_id,attempt_id,stage
        ) VALUES ($1::uuid,$2::uuid,$3::uuid,'INVESTIGATING')`, taskID, runID, attemptID)
	if err != nil {
		return AdmissionReceipt{}, fmt.Errorf("enqueue first task: %w", err)
	}
	updated, err := tx.ExecContext(ctx, `
        UPDATE orchestration.workflow_runs
        SET state='INVESTIGATING',run_version=1,updated_at=clock_timestamp()
        WHERE run_id=$1::uuid AND state='ADMITTED' AND run_version=0`, runID)
	if err != nil {
		return AdmissionReceipt{}, fmt.Errorf("activate admitted run: %w", err)
	}
	rows, err := updated.RowsAffected()
	if err != nil || rows != 1 {
		return AdmissionReceipt{}, errors.New("initial workflow activation failed")
	}
	_, err = tx.ExecContext(ctx, `
        INSERT INTO orchestration.workflow_transitions(
          transition_id,run_id,attempt_id,from_state,to_state,prior_run_version
        ) VALUES($1::uuid,$2::uuid,$3::uuid,'ADMITTED','INVESTIGATING',0)`, transitionID, runID, attemptID)
	if err != nil {
		return AdmissionReceipt{}, fmt.Errorf("initial transition: %w", err)
	}
	event := struct {
		RunID     string `json:"run_id"`
		AttemptID string `json:"attempt_id"`
		TaskID    string `json:"task_id"`
		Stage     string `json:"stage"`
	}{runID, attemptID, taskID, "INVESTIGATING"}
	body, err := json.Marshal(event)
	if err != nil {
		return AdmissionReceipt{}, err
	}
	digest := sha256.Sum256(body)
	_, err = tx.ExecContext(ctx, `
        INSERT INTO orchestration.workflow_outbox(message_id,run_id,event_type,payload_sha256,payload)
        VALUES($1::uuid,$2::uuid,'STAGE_TASK_QUEUED',$3,$4::jsonb)`,
		messageID, runID, hex.EncodeToString(digest[:]), string(body))
	if err != nil {
		return AdmissionReceipt{}, fmt.Errorf("enqueue outbox: %w", err)
	}
	if err = tx.Commit(); err != nil {
		return AdmissionReceipt{}, fmt.Errorf("commit admission: %w", err)
	}
	return AdmissionReceipt{RunID: runID, AttemptID: attemptID, TaskID: taskID}, nil
}

func (s *Store) lookupConflict(ctx context.Context, commandID, digest string) (AdmissionReceipt, error) {
	var receipt AdmissionReceipt
	var priorDigest string
	err := s.db.QueryRowContext(ctx, `
        SELECT r.run_id::text,a.attempt_id::text,t.task_id::text,r.command_payload_sha256
        FROM orchestration.workflow_runs r
        JOIN orchestration.stage_attempts a ON a.run_id=r.run_id
          AND a.stage='INVESTIGATING' AND a.semantic_attempt_no=1
        JOIN orchestration.task_deliveries t ON t.attempt_id=a.attempt_id
        WHERE r.command_id=$1::uuid`, commandID).Scan(
		&receipt.RunID, &receipt.AttemptID, &receipt.TaskID, &priorDigest)
	if err == nil {
		if priorDigest == digest {
			receipt.Replayed = true
			return receipt, nil
		}
		return AdmissionReceipt{}, ErrConflict
	}
	if errors.Is(err, sql.ErrNoRows) {
		return AdmissionReceipt{}, ErrConflict
	}
	return AdmissionReceipt{}, fmt.Errorf("resolve duplicate admission: %w", err)
}

type TaskLease struct {
	RunID         string
	TaskID        string
	AttemptID     string
	Stage         string
	ClaimantID    string
	LeaseEpoch    int64
	DeliveryCount int
	ExpiresAt     time.Time
}

// ClaimForRun uses PostgreSQL row locks only for the short claim transaction.
// An expired lease is reclaimable, increasing the lease epoch but NOT the semantic attempt.
func (s *Store) ClaimForRun(ctx context.Context, runID, claimant string, leaseSeconds int) (TaskLease, error) {
	if strings.TrimSpace(runID) == "" || strings.TrimSpace(claimant) == "" ||
		len(claimant) > 128 || leaseSeconds < 1 || leaseSeconds > 300 {
		return TaskLease{}, errors.New("invalid claim parameters")
	}
	tx, err := s.db.BeginTx(ctx, &sql.TxOptions{Isolation: sql.LevelReadCommitted})
	if err != nil {
		return TaskLease{}, err
	}
	defer tx.Rollback()
	var state string
	err = tx.QueryRowContext(ctx, `SELECT state FROM orchestration.workflow_runs
        WHERE run_id=$1::uuid FOR SHARE`, runID).Scan(&state)
	if errors.Is(err, sql.ErrNoRows) {
		return TaskLease{}, ErrNoTask
	}
	if err != nil {
		return TaskLease{}, fmt.Errorf("lock workflow for claim: %w", err)
	}
	var lease TaskLease
	lease.RunID = runID
	lease.ClaimantID = claimant
	err = tx.QueryRowContext(ctx, `
        SELECT t.task_id::text,t.attempt_id::text,t.stage
        FROM orchestration.task_deliveries t
        WHERE t.run_id=$1::uuid AND t.stage=$2
          AND ((t.status='QUEUED' AND t.available_at<=clock_timestamp())
            OR (t.status='LEASED' AND t.lease_expires_at<=clock_timestamp()))
        ORDER BY t.available_at,t.created_at,t.task_id
        LIMIT 1 FOR UPDATE OF t SKIP LOCKED`, runID, state).Scan(
		&lease.TaskID, &lease.AttemptID, &lease.Stage)
	if errors.Is(err, sql.ErrNoRows) {
		return TaskLease{}, ErrNoTask
	}
	if err != nil {
		return TaskLease{}, fmt.Errorf("select eligible task: %w", err)
	}
	err = tx.QueryRowContext(ctx, `
        UPDATE orchestration.task_deliveries
        SET status='LEASED',claimant_id=$2,lease_epoch=lease_epoch+1,
            lease_expires_at=clock_timestamp()+($3::int*interval '1 second'),
            delivery_count=delivery_count+1,updated_at=clock_timestamp()
        WHERE task_id=$1::uuid
        RETURNING lease_epoch,delivery_count,lease_expires_at`,
		lease.TaskID, claimant, leaseSeconds).Scan(
		&lease.LeaseEpoch, &lease.DeliveryCount, &lease.ExpiresAt)
	if err != nil {
		return TaskLease{}, fmt.Errorf("claim task: %w", err)
	}
	changed, err := tx.ExecContext(ctx, `
        UPDATE orchestration.stage_attempts SET state='RUNNING'
        WHERE attempt_id=$1::uuid AND run_id=$2::uuid
          AND state IN ('PENDING','RUNNING')`, lease.AttemptID, runID)
	if err != nil {
		return TaskLease{}, fmt.Errorf("start semantic attempt: %w", err)
	}
	n, err := changed.RowsAffected()
	if err != nil || n != 1 {
		return TaskLease{}, errors.New("semantic attempt is not claimable")
	}
	if err = tx.Commit(); err != nil {
		return TaskLease{}, fmt.Errorf("commit claim: %w", err)
	}
	return lease, nil
}

// Heartbeat only renews a currently valid, owned lease for an active stage.
// It is NOT authorization to commit a workflow transition.
func (s *Store) Heartbeat(ctx context.Context, lease TaskLease, leaseSeconds int) (time.Time, error) {
	if leaseSeconds < 1 || leaseSeconds > 300 {
		return time.Time{}, errors.New("invalid lease duration")
	}
	var expires time.Time
	err := s.db.QueryRowContext(ctx, `
        UPDATE orchestration.task_deliveries t
        SET lease_expires_at=clock_timestamp()+($5::int*interval '1 second'),
            updated_at=clock_timestamp()
        WHERE t.task_id=$1::uuid AND t.run_id=$2::uuid
          AND t.claimant_id=$3 AND t.lease_epoch=$4
          AND t.attempt_id=$6::uuid AND t.stage=$7
          AND t.status='LEASED' AND t.lease_expires_at>clock_timestamp()
          AND EXISTS (SELECT 1 FROM orchestration.workflow_runs r
                      WHERE r.run_id=t.run_id AND r.state=t.stage)
        RETURNING t.lease_expires_at`,
		lease.TaskID, lease.RunID, lease.ClaimantID, lease.LeaseEpoch, leaseSeconds, lease.AttemptID, lease.Stage).Scan(&expires)
	if errors.Is(err, sql.ErrNoRows) {
		return time.Time{}, ErrLeaseLost
	}
	if err != nil {
		return time.Time{}, fmt.Errorf("heartbeat task: %w", err)
	}
	return expires, nil
}
