package workflow

import (
	"context"
	"crypto/md5"
	"crypto/sha256"
	"database/sql"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"sort"
	"strconv"
)

type DeliveryIntentEnvelope struct {
	MessageID   string          `json:"messageId"`
	OperationID string          `json:"operationId"`
	RequestHash string          `json:"requestHash"`
	MessageHash string          `json:"messageHash"`
	ClaimToken  string          `json:"claimToken"`
	Request     DeliveryRequest `json:"request"`
}
type DeliveryIntentAck struct {
	MessageID   string `json:"messageId"`
	MessageHash string `json:"messageHash"`
	ClaimToken  string `json:"claimToken"`
}

func digestFields(fields []string) string {
	hash := sha256.New()
	for _, field := range fields {
		_ = binary.Write(hash, binary.BigEndian, uint32(len([]byte(field))))
		_, _ = hash.Write([]byte(field))
	}
	return hex.EncodeToString(hash.Sum(nil))
}
func intentMessageHash(message, operation, hash string) string {
	return digestFields([]string{"asep-delivery-intent-v1", message, operation, hash})
}

// ClaimDeliveryIntent leases transport delivery only. Java still requires its own
// Owner, candidate, CI and current Go gates before reserving/sending an operation.
func (s *Store) ClaimDeliveryIntent(ctx context.Context) (DeliveryIntentEnvelope, error) {
	return s.ClaimDeliveryIntentFor(ctx, "")
}
func (s *Store) ClaimDeliveryIntentFor(ctx context.Context, operation string) (DeliveryIntentEnvelope, error) {
	var selected any
	if operation != "" {
		if !canonicalUUID(operation) {
			return DeliveryIntentEnvelope{}, ErrDeliveryDenied
		}
		selected = operation
	}

	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return DeliveryIntentEnvelope{}, ErrDeliveryDenied
	}
	defer tx.Rollback()
	var envelope DeliveryIntentEnvelope
	var body []byte
	err = tx.QueryRowContext(ctx, `SELECT o.message_id::text,a.operation_id::text,a.request_hash,e.request_json
        FROM orchestration.workflow_outbox o JOIN orchestration.delivery_authorizations a ON a.outbox_message_id=o.message_id
        JOIN orchestration.delivery_review_evidence e ON e.evidence_id=a.evidence_id
        JOIN orchestration.workflow_runs r ON r.run_id=a.run_id
        JOIN orchestration.delivery_policies p ON p.policy_id=r.policy_id
        LEFT JOIN orchestration.delivery_dispatches d ON d.message_id=o.message_id
        WHERE o.event_type='DELIVERY_AUTHORIZED' AND o.status<>'DELIVERED' AND ($1::uuid IS NULL OR a.operation_id=$1::uuid)
          AND e.run_id=a.run_id AND e.request_hash=a.request_hash
          AND r.gitlab_project_id='87786140' AND r.target_branch='main' AND r.policy_id=e.policy_id
          AND r.repository_base_sha=e.request_json->>'baseSha' AND r.observed_target_head_sha=e.request_json->>'expectedTargetSha'
          AND r.state='DELIVERY_PENDING' AND r.run_version=e.expected_run_version+1
          AND r.policy_epoch=e.policy_epoch AND p.policy_epoch=r.policy_epoch AND p.enabled AND p.expires_at>clock_timestamp()
          AND e.expires_at>clock_timestamp() AND e.revoked_at IS NULL
          AND (d.message_id IS NULL OR d.acknowledged_at IS NULL AND d.attempts<5 AND d.lease_expires_at<=clock_timestamp())
        ORDER BY o.created_at,o.message_id LIMIT 1 FOR UPDATE OF o SKIP LOCKED`, selected).Scan(&envelope.MessageID, &envelope.OperationID, &envelope.RequestHash, &body)
	if err == sql.ErrNoRows {
		return DeliveryIntentEnvelope{}, ErrNoTask
	}
	if err != nil {
		return DeliveryIntentEnvelope{}, ErrDeliveryDenied
	}
	r, err := decodeDeliveryRequest(body)
	if err != nil {
		return DeliveryIntentEnvelope{}, err
	}
	hash, _ := r.Hash()
	if hash != envelope.RequestHash || r.OperationID != envelope.OperationID {
		return DeliveryIntentEnvelope{}, ErrDeliveryDenied
	}
	token, err := freshUUID()
	if err != nil {
		return DeliveryIntentEnvelope{}, ErrDeliveryDenied
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO orchestration.delivery_dispatches(message_id,claim_token,attempts,lease_expires_at) VALUES($1::uuid,$2::uuid,1,clock_timestamp()+interval '10 seconds') ON CONFLICT(message_id) DO UPDATE SET claim_token=EXCLUDED.claim_token,attempts=orchestration.delivery_dispatches.attempts+1,lease_expires_at=EXCLUDED.lease_expires_at WHERE orchestration.delivery_dispatches.acknowledged_at IS NULL AND orchestration.delivery_dispatches.attempts<5 AND orchestration.delivery_dispatches.lease_expires_at<=clock_timestamp()`, envelope.MessageID, token)
	if err != nil {
		return DeliveryIntentEnvelope{}, ErrDeliveryDenied
	}
	if _, err = tx.ExecContext(ctx, `UPDATE orchestration.workflow_outbox SET status='DELIVERING' WHERE message_id=$1::uuid`, envelope.MessageID); err != nil {
		return DeliveryIntentEnvelope{}, ErrDeliveryDenied
	}
	if tx.Commit() != nil {
		return DeliveryIntentEnvelope{}, ErrDeliveryDenied
	}
	envelope.Request = r
	envelope.ClaimToken = token
	envelope.MessageHash = intentMessageHash(envelope.MessageID, envelope.OperationID, envelope.RequestHash)
	return envelope, nil
}
func (s *Store) AcknowledgeDeliveryIntent(ctx context.Context, c DeliveryIntentAck) error {
	if !canonicalUUID(c.MessageID) || !canonicalUUID(c.ClaimToken) || !isLowerHex(c.MessageHash, 64) {
		return ErrDeliveryDenied
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return ErrDeliveryDenied
	}
	defer tx.Rollback()
	var operation, hash string
	if tx.QueryRowContext(ctx, `SELECT a.operation_id::text,a.request_hash FROM orchestration.delivery_authorizations a JOIN orchestration.workflow_outbox o ON o.message_id=a.outbox_message_id WHERE o.message_id=$1::uuid AND o.event_type='DELIVERY_AUTHORIZED' FOR UPDATE OF o`, c.MessageID).Scan(&operation, &hash) != nil || intentMessageHash(c.MessageID, operation, hash) != c.MessageHash {
		return ErrDeliveryDenied
	}
	result, err := tx.ExecContext(ctx, `UPDATE orchestration.delivery_dispatches SET acknowledged_at=COALESCE(acknowledged_at,clock_timestamp()) WHERE message_id=$1::uuid AND claim_token=$2::uuid`, c.MessageID, c.ClaimToken)
	if err != nil {
		return ErrDeliveryDenied
	}
	if n, _ := result.RowsAffected(); n != 1 {
		return ErrDeliveryDenied
	}
	if _, err = tx.ExecContext(ctx, `UPDATE orchestration.workflow_outbox SET status='DELIVERED',delivered_at=COALESCE(delivered_at,clock_timestamp()) WHERE message_id=$1::uuid`, c.MessageID); err != nil {
		return ErrDeliveryDenied
	}
	if tx.Commit() != nil {
		return ErrDeliveryDenied
	}
	return nil
}

type DeliveryCallback struct {
	CallbackID            string  `json:"callbackId"`
	OperationID           string  `json:"operationId"`
	RunID                 string  `json:"runId"`
	ExpectedRunVersion    int64   `json:"expectedRunVersion"`
	RequestHash           string  `json:"requestHash"`
	Revision              int64   `json:"revision"`
	Push                  string  `json:"push"`
	MergeRequest          string  `json:"mergeRequest"`
	PushClaimed           bool    `json:"pushClaimed"`
	MRClaimed             bool    `json:"mrClaimed"`
	LastConfirmedPushHead *string `json:"lastConfirmedPushHead"`
	KnownMRIIDs           []int64 `json:"knownMrIids"`
	ResidualRace          bool    `json:"residualRace"`
}
type DeliveryOutcomeEnvelope struct {
	Callback    DeliveryCallback `json:"callback"`
	PayloadHash string           `json:"payloadHash"`
}

func (c DeliveryCallback) hash() string {
	head := ""
	if c.LastConfirmedPushHead != nil {
		head = *c.LastConfirmedPushHead
	}
	fields := []string{"asep-vn-t14-callback-v1", c.CallbackID, c.OperationID, c.RunID, strconv.FormatInt(c.ExpectedRunVersion, 10), c.RequestHash, strconv.FormatInt(c.Revision, 10), c.Push, c.MergeRequest, strconv.FormatBool(c.PushClaimed), strconv.FormatBool(c.MRClaimed), head, strconv.FormatBool(c.ResidualRace)}
	for _, id := range c.KnownMRIIDs {
		fields = append(fields, strconv.FormatInt(id, 10))
	}
	return digestFields(fields)
}
func callbackID(operation string, revision int64) string {
	value := md5.Sum([]byte("asep-vn-t14-callback-v1:" + operation + ":" + strconv.FormatInt(revision, 10)))
	value[6] = (value[6] & 15) | 48
	value[8] = (value[8] & 63) | 128
	return fmt.Sprintf("%x-%x-%x-%x-%x", value[:4], value[4:6], value[6:8], value[8:10], value[10:])
}
func (c DeliveryCallback) valid(candidate string) bool {
	states := map[string]bool{"PLANNED": true, "IN_PROGRESS": true, "CONFIRMED": true, "UNKNOWN": true, "BLOCKED": true, "MANUAL_RECONCILIATION_REQUIRED": true}
	if !canonicalUUID(c.CallbackID) || !canonicalUUID(c.OperationID) || !canonicalUUID(c.RunID) || c.CallbackID != callbackID(c.OperationID, c.Revision) || c.ExpectedRunVersion < 1 || c.Revision < 1 || !isLowerHex(c.RequestHash, 64) || !states[c.Push] || !states[c.MergeRequest] || len(c.KnownMRIIDs) > 1000 || c.MRClaimed && !c.PushClaimed || !c.MRClaimed && len(c.KnownMRIIDs) != 0 {
		return false
	}
	for n, id := range c.KnownMRIIDs {
		if id < 1 || n > 0 && id <= c.KnownMRIIDs[n-1] {
			return false
		}
	}
	if c.LastConfirmedPushHead != nil && (!c.PushClaimed || *c.LastConfirmedPushHead != candidate) {
		return false
	}
	if c.Push == "CONFIRMED" && (!c.PushClaimed || c.LastConfirmedPushHead == nil) {
		return false
	}
	if c.MergeRequest == "CONFIRMED" && (!c.MRClaimed || c.Push != "CONFIRMED" || len(c.KnownMRIIDs) != 1) {
		return false
	}
	if c.PushClaimed && c.Push == "PLANNED" || c.MRClaimed && c.MergeRequest == "PLANNED" {
		return false
	}
	return true
}

// AcceptDeliveryOutcome stores authenticated Java observations even after cancellation.
// Completion additionally needs current Go policy and exact remote facts. Old/reordered
// callbacks never erase newer observations or actual MR identities. ACK is not authority.
func (s *Store) AcceptDeliveryOutcome(ctx context.Context, envelope DeliveryOutcomeEnvelope) error {
	c := envelope.Callback
	if !canonicalUUID(c.OperationID) || !canonicalUUID(c.RunID) {
		return ErrDeliveryDenied
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return ErrDeliveryDenied
	}
	defer tx.Rollback()
	var state, policy string
	var version, epoch int64
	if tx.QueryRowContext(ctx, `SELECT state,run_version,policy_id,policy_epoch FROM orchestration.workflow_runs WHERE run_id=$1::uuid FOR UPDATE`, c.RunID).Scan(&state, &version, &policy, &epoch) != nil {
		return ErrDeliveryDenied
	}
	var body []byte
	var expectedHash, deliveryAttempt, evidenceID string
	if tx.QueryRowContext(ctx, `SELECT a.request_hash,e.request_json,a.delivery_attempt_id::text,a.evidence_id::text FROM orchestration.delivery_authorizations a JOIN orchestration.delivery_review_evidence e ON e.evidence_id=a.evidence_id WHERE a.operation_id=$1::uuid AND a.run_id=$2::uuid`, c.OperationID, c.RunID).Scan(&expectedHash, &body, &deliveryAttempt, &evidenceID) != nil {
		return ErrDeliveryDenied
	}
	request, err := decodeDeliveryRequest(body)
	if err != nil {
		return err
	}
	requestHash, _ := request.Hash()
	if expectedHash != requestHash || expectedHash != c.RequestHash || request.OperationID != c.OperationID || request.RunID != c.RunID || request.RunVersion != c.ExpectedRunVersion || !c.valid(request.CandidateCommitSHA) || c.hash() != envelope.PayloadHash {
		return ErrDeliveryDenied
	}
	var prior string
	err = tx.QueryRowContext(ctx, `SELECT payload_hash FROM orchestration.delivery_outcome_inbox WHERE callback_id=$1::uuid`, c.CallbackID).Scan(&prior)
	if err == nil {
		if prior != envelope.PayloadHash {
			return ErrDeliveryDenied
		}
		return nil
	}
	if err != sql.ErrNoRows {
		return ErrDeliveryDenied
	}
	raw, _ := json.Marshal(c)
	if _, err = tx.ExecContext(ctx, `INSERT INTO orchestration.delivery_outcome_inbox(callback_id,operation_id,revision,payload_hash,payload) VALUES($1::uuid,$2::uuid,$3,$4,$5::jsonb)`, c.CallbackID, c.OperationID, c.Revision, envelope.PayloadHash, string(raw)); err != nil {
		return ErrDeliveryDenied
	}
	var storedRevision int64
	var knownJSON []byte
	var sticky bool
	var head sql.NullString
	err = tx.QueryRowContext(ctx, `SELECT revision,known_mr_iids,residual_race,last_confirmed_push_head FROM orchestration.delivery_observations WHERE operation_id=$1::uuid FOR UPDATE`, c.OperationID).Scan(&storedRevision, &knownJSON, &sticky, &head)
	if err != nil && err != sql.ErrNoRows {
		return ErrDeliveryDenied
	}

	var oldIDs []int64
	if knownJSON != nil && json.Unmarshal(knownJSON, &oldIDs) != nil {
		return ErrDeliveryDenied
	}
	priorRace := sticky
	ids := map[int64]bool{}
	for _, id := range c.KnownMRIIDs {
		ids[id] = true
	}
	for _, id := range oldIDs {
		if !ids[id] {
			sticky = true
		}
		ids[id] = true
	}
	merged := make([]int64, 0, len(ids))
	for id := range ids {
		merged = append(merged, id)
	}
	sort.Slice(merged, func(i, j int) bool { return merged[i] < merged[j] })
	if storedRevision >= c.Revision {
		// Reordering cannot erase a real IID omitted by a newer message. Keep
		// the newer states/revision, retain every identity, and flag divergence.
		extraIdentity := len(merged) > len(oldIDs)
		recoverHead := !head.Valid && c.LastConfirmedPushHead != nil
		sticky = priorRace || c.ResidualRace || extraIdentity // An older omission is expected, not a new divergence.
		if extraIdentity || sticky != priorRace || recoverHead {
			if recoverHead {
				head = sql.NullString{String: *c.LastConfirmedPushHead, Valid: true}
			}
			rawIDs, _ := json.Marshal(merged)
			if _, err := tx.ExecContext(ctx, `UPDATE orchestration.delivery_observations SET known_mr_iids=$2::jsonb,residual_race=$3,last_confirmed_push_head=$4,updated_at=clock_timestamp() WHERE operation_id=$1::uuid`, c.OperationID, string(rawIDs), sticky, head); err != nil {
				return ErrDeliveryDenied
			}
			if sticky && (state == "DELIVERY_PENDING" || state == "COMPLETED_DRAFT_MR") {
				if err := recordDeliveryOutcomeTransition(ctx, tx, c, deliveryAttempt, state, "RECONCILIATION_REQUIRED", version); err != nil {
					return err
				}
			}
		}
		if tx.Commit() != nil {
			return ErrDeliveryDenied
		}
		return nil
	}
	clean := (state == "DELIVERY_PENDING" && version == request.RunVersion || state == "COMPLETED_DRAFT_MR" && version == request.RunVersion+1) && policy == request.PolicyID && epoch == request.PolicyEpoch
	// Serialize revocation with completion; an unavailable policy only removes
	// completion eligibility, never discards already observed external effects.
	expiry, policyErr := lockDeliveryPolicy(ctx, tx, policy, epoch)
	var proofCurrent bool
	if tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM orchestration.delivery_review_evidence WHERE evidence_id=$1::uuid AND revoked_at IS NULL AND expires_at>clock_timestamp()) AND NOT EXISTS(SELECT 1 FROM orchestration.controlled_candidate_evidence WHERE evidence_id=$1::uuid AND (revoked_at IS NOT NULL OR expires_at<=clock_timestamp())) AND clock_timestamp()<$2::timestamptz`, evidenceID, expiry).Scan(&proofCurrent) != nil {
		return ErrDeliveryDenied
	}
	clean = clean && policyErr == nil && proofCurrent
	sticky = sticky || c.ResidualRace || (!clean && (c.PushClaimed || c.MRClaimed))
	if c.LastConfirmedPushHead != nil {
		head = sql.NullString{String: *c.LastConfirmedPushHead, Valid: true}
	}
	serializedIDs, _ := json.Marshal(merged)
	_, err = tx.ExecContext(ctx, `INSERT INTO orchestration.delivery_observations(operation_id,revision,push_state,mr_state,known_mr_iids,last_confirmed_push_head,residual_race) VALUES($1::uuid,$2,$3,$4,$5::jsonb,$6,$7) ON CONFLICT(operation_id) DO UPDATE SET revision=EXCLUDED.revision,push_state=EXCLUDED.push_state,mr_state=EXCLUDED.mr_state,known_mr_iids=EXCLUDED.known_mr_iids,last_confirmed_push_head=EXCLUDED.last_confirmed_push_head,residual_race=EXCLUDED.residual_race,updated_at=clock_timestamp()`, c.OperationID, c.Revision, c.Push, c.MergeRequest, string(serializedIDs), head, sticky)
	if err != nil {
		return ErrDeliveryDenied
	}
	completed := state == "DELIVERY_PENDING" && clean && !sticky && c.Push == "CONFIRMED" && c.MergeRequest == "CONFIRMED" && len(merged) == 1
	next := ""
	if completed {
		next = "COMPLETED_DRAFT_MR"
	} else if (state == "DELIVERY_PENDING" || state == "COMPLETED_DRAFT_MR") && sticky {
		next = "RECONCILIATION_REQUIRED"
	}
	if next != "" {
		if err := recordDeliveryOutcomeTransition(ctx, tx, c, deliveryAttempt, state, next, version); err != nil {
			return err
		}
	}
	if tx.Commit() != nil {
		return ErrDeliveryDenied
	}
	return nil
}

func recordDeliveryOutcomeTransition(ctx context.Context, tx *sql.Tx, c DeliveryCallback, deliveryAttempt, state, next string, version int64) error {
	completed := next == "COMPLETED_DRAFT_MR"

	transition, err := freshUUID()
	if err != nil {
		return ErrDeliveryDenied
	}
	message, err := freshUUID()
	if err != nil {
		return ErrDeliveryDenied
	}
	if _, err = tx.ExecContext(ctx, `UPDATE orchestration.workflow_runs SET state=$2,run_version=run_version+1,updated_at=clock_timestamp() WHERE run_id=$1::uuid`, c.RunID, next); err != nil {
		return ErrDeliveryDenied
	}
	if completed {
		if _, err = tx.ExecContext(ctx, `UPDATE orchestration.stage_attempts SET state='SUCCEEDED',completed_at=clock_timestamp() WHERE attempt_id=$1::uuid AND run_id=$2::uuid AND stage='DELIVERY_PENDING' AND state='PENDING'`, deliveryAttempt, c.RunID); err != nil {
			return ErrDeliveryDenied
		}
	}
	if _, err = tx.ExecContext(ctx, `INSERT INTO orchestration.workflow_transitions(transition_id,run_id,attempt_id,from_state,to_state,prior_run_version,evidence_ref) VALUES($1::uuid,$2::uuid,$3::uuid,$4,$5,$6,$7)`, transition, c.RunID, deliveryAttempt, state, next, version, "delivery-callback:"+c.CallbackID); err != nil {
		return ErrDeliveryDenied
	}
	payload, _ := json.Marshal(struct {
		OperationID string `json:"operationId"`
		CallbackID  string `json:"callbackId"`
		State       string `json:"state"`
	}{c.OperationID, c.CallbackID, next})
	digest := sha256.Sum256(payload)
	if _, err = tx.ExecContext(ctx, `INSERT INTO orchestration.workflow_outbox(message_id,run_id,event_type,payload_sha256,payload) VALUES($1::uuid,$2::uuid,'DELIVERY_OUTCOME_RECORDED',$3,$4::jsonb)`, message, c.RunID, hex.EncodeToString(digest[:]), string(payload)); err != nil {
		return ErrDeliveryDenied
	}

	return nil
}
