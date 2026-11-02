//go:build integration && deliveryauthority

package workflow

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"github.com/jackc/pgx/v5/pgconn"
	"net/url"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/qhung-13/ASEP/orchestrator/internal/testsupport"
)

// Higher privilege fixture handles exist only after the ordinary runtime's owned
// instance proof passes. They are never passed to an agent or subprocess.
func deliveryAuthorityDatabase(t *testing.T, role, variable string) *sql.DB {
	t.Helper()
	u, err := url.Parse(testsupport.DatabaseURL(t))
	if err != nil {
		t.Fatal("owned proof unavailable")
	}
	password := os.Getenv(variable)
	if len(password) != 64 {
		t.Fatal("scoped authority fixture secret missing")
	}
	u.User = url.UserPassword(role, password)
	db, err := sql.Open("pgx", u.String())
	if err != nil {
		t.Fatal("authority fixture unavailable")
	}
	t.Cleanup(func() { _ = db.Close() })
	var actual string
	var powerful bool
	err = db.QueryRowContext(testContext(t), `SELECT current_user,rolsuper OR rolcreaterole OR rolcreatedb OR rolbypassrls FROM pg_roles WHERE rolname=current_user`).Scan(&actual, &powerful)
	if err != nil || actual != role || powerful {
		t.Fatal("invalid authority fixture role")
	}
	return db
}

type deliveryFixture struct {
	store                  *Store
	issuer                 *sql.DB
	request                DeliveryRequest
	evidence, review, task string
}

func deliveryReviewFixture(t *testing.T) deliveryFixture {
	t.Helper()
	s := storeForTest(t)
	ctx := testContext(t)
	admission := admissionForTest(t, "delivery")
	admission.GitLabProjectID = "87786140"
	admission.ObservedTargetHeadSHA = admission.RepositoryBaseSHA
	admission.PolicyID = "delivery-" + admission.CommandID
	admitted, err := s.Admit(ctx, admission)
	if err != nil {
		t.Fatal(err)
	}
	id := func() string {
		v, err := freshUUID()
		if err != nil {
			t.Fatal(err)
		}
		return v
	}
	review, task, evidence := id(), id(), id()
	// Fixture-only upstream state. No production API jumps INVESTIGATING to REVIEWING.
	for _, statement := range []string{
		`UPDATE orchestration.task_deliveries SET status='COMPLETED' WHERE run_id=$1::uuid`,
		`UPDATE orchestration.stage_attempts SET stage='IMPLEMENTING',state='SUCCEEDED' WHERE run_id=$1::uuid`,
		`UPDATE orchestration.workflow_runs SET state='REVIEWING',run_version=8 WHERE run_id=$1::uuid`,
	} {
		if _, err = s.db.ExecContext(ctx, statement, admitted.RunID); err != nil {
			t.Fatal(err)
		}
	}
	_, err = s.db.ExecContext(ctx, `INSERT INTO orchestration.stage_attempts(attempt_id,run_id,stage,semantic_attempt_no,state) VALUES($1::uuid,$2::uuid,'REVIEWING',1,'RUNNING')`, review, admitted.RunID)
	if err != nil {
		t.Fatal(err)
	}
	_, err = s.db.ExecContext(ctx, `INSERT INTO orchestration.task_deliveries(task_id,run_id,attempt_id,stage,status,claimant_id,lease_epoch,lease_expires_at) VALUES($1::uuid,$2::uuid,$3::uuid,'REVIEWING','LEASED','protected-review-controller',4,clock_timestamp()+interval '3 minutes')`, task, admitted.RunID, review)
	if err != nil {
		t.Fatal(err)
	}
	r := DeliveryRequest{id(), admitted.RunID, admitted.AttemptID, 9, id(), admission.PolicyID, 1, admission.RepositoryBaseSHA, admission.ObservedTargetHeadSHA, strings.Repeat("b", 40), strings.Repeat("c", 40), strings.Repeat("d", 64), id(), id(), strings.Repeat("e", 64), strings.Repeat("f", 64)}
	issuer := deliveryAuthorityDatabase(t, "asep_orch_authority", "ASEP_DELIVERY_AUTHORITY_TEST_PASSWORD")
	_, err = issuer.ExecContext(ctx, `INSERT INTO orchestration.delivery_policies(policy_id,policy_epoch,project_id,repository_path,target_branch,enabled,expires_at) VALUES($1,1,87786140,'qhung-13/asep-m01-sandbox','main',true,clock_timestamp()+interval '30 minutes')`, r.PolicyID)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := json.Marshal(r)
	hash, _ := r.Hash()
	_, err = issuer.ExecContext(ctx, `INSERT INTO orchestration.delivery_review_evidence(evidence_id,run_id,producer_attempt_id,review_attempt_id,task_id,claimant_id,lease_epoch,expected_run_version,policy_id,policy_epoch,request_hash,request_json,expires_at) VALUES($1::uuid,$2::uuid,$3::uuid,$4::uuid,$5::uuid,'protected-review-controller',4,8,$6,1,$7,$8::jsonb,clock_timestamp()+interval '10 minutes')`, evidence, r.RunID, r.AttemptID, review, task, r.PolicyID, hash, string(body))
	if err != nil {
		t.Fatal(err)
	}
	return deliveryFixture{s, issuer, r, evidence, review, task}
}
func (f deliveryFixture) current(t *testing.T) (DeliveryAuthority, error) {
	t.Helper()
	return f.store.CurrentDeliveryAuthority(testContext(t), f.request.OperationID, f.request.RunID, f.request.AttemptID)
}
func TestDeliveryAuthorityAtomicTransitionAndReplay(t *testing.T) {
	f := deliveryReviewFixture(t)
	ctx := testContext(t)
	if _, err := f.current(t); err == nil {
		t.Fatal("reviewing run authorized before transition")
	}
	got, err := f.store.AuthorizeDelivery(ctx, f.evidence)
	if err != nil {
		t.Fatal(err)
	}
	hash, _ := f.request.Hash()
	if got.AuthorizedRequestHash != hash || got.OperationID != f.request.OperationID || got.RunVersion != 9 || !got.DeliveryPolicyApproved || got.State != "DELIVERY_PENDING" || !got.ObservedAt.Before(got.ValidUntil) {
		t.Fatal("wrong exact authorization")
	}
	for range 3 {
		if replay, err := f.store.AuthorizeDelivery(ctx, f.evidence); err != nil || replay.AuthorizedRequestHash != hash {
			t.Fatal("exact replay failed")
		}
	}
	var authorizations, transitions, outbox, attempts int
	err = f.store.db.QueryRowContext(ctx, `SELECT (SELECT count(*) FROM orchestration.delivery_authorizations WHERE run_id=$1::uuid),(SELECT count(*) FROM orchestration.workflow_transitions WHERE run_id=$1::uuid AND to_state='DELIVERY_PENDING'),(SELECT count(*) FROM orchestration.workflow_outbox WHERE run_id=$1::uuid AND event_type='DELIVERY_AUTHORIZED'),(SELECT count(*) FROM orchestration.stage_attempts WHERE run_id=$1::uuid AND stage='DELIVERY_PENDING')`, f.request.RunID).Scan(&authorizations, &transitions, &outbox, &attempts)
	if err != nil || authorizations != 1 || transitions != 1 || outbox != 1 || attempts != 1 {
		t.Fatal("replay duplicated atomic bundle")
	}
	if _, err = f.store.CurrentDeliveryAuthority(ctx, f.request.OperationID, f.request.RunID, f.review); err == nil {
		t.Fatal("review attempt substituted for producer")
	}
}
func TestDeliveryAuthorityRejectsInvalidCurrentState(t *testing.T) {
	for _, fault := range []string{"cancel-before", "stale-version", "stale-policy", "disabled", "wrong-project", "wrong-target", "changed-head", "lease", "wrong-attempt", "revoked-review", "expired-policy", "bad-request-hash"} {
		t.Run(fault, func(t *testing.T) {
			f := deliveryReviewFixture(t)
			ctx := testContext(t)
			var err error
			switch fault {
			case "cancel-before":
				err = f.store.CancelDelivery(ctx, f.request.RunID, 8)
				if err == nil {
					err = f.store.CancelDelivery(ctx, f.request.RunID, 8)
				}
			case "stale-version":
				_, err = f.store.db.ExecContext(ctx, `UPDATE orchestration.workflow_runs SET run_version=9 WHERE run_id=$1::uuid`, f.request.RunID)
			case "stale-policy":
				_, err = f.issuer.ExecContext(ctx, `UPDATE orchestration.delivery_policies SET policy_epoch=2 WHERE policy_id=$1`, f.request.PolicyID)
			case "disabled":
				_, err = f.issuer.ExecContext(ctx, `UPDATE orchestration.delivery_policies SET enabled=false WHERE policy_id=$1`, f.request.PolicyID)
			case "wrong-project":
				_, err = f.store.db.ExecContext(ctx, `UPDATE orchestration.workflow_runs SET gitlab_project_id='42' WHERE run_id=$1::uuid`, f.request.RunID)
			case "wrong-target":
				_, err = f.store.db.ExecContext(ctx, `UPDATE orchestration.workflow_runs SET target_branch='other' WHERE run_id=$1::uuid`, f.request.RunID)
			case "changed-head":
				_, err = f.store.db.ExecContext(ctx, `UPDATE orchestration.workflow_runs SET observed_target_head_sha=$2 WHERE run_id=$1::uuid`, f.request.RunID, strings.Repeat("9", 40))
			case "lease":
				_, err = f.store.db.ExecContext(ctx, `UPDATE orchestration.task_deliveries SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE task_id=$1::uuid`, f.task)
			case "wrong-attempt":
				_, err = f.store.db.ExecContext(ctx, `UPDATE orchestration.stage_attempts SET state='FAILED' WHERE attempt_id=$1::uuid`, f.request.AttemptID)
			case "revoked-review":
				_, err = f.issuer.ExecContext(ctx, `UPDATE orchestration.delivery_review_evidence SET revoked_at=clock_timestamp() WHERE evidence_id=$1::uuid`, f.evidence)
			case "expired-policy":
				_, err = f.issuer.ExecContext(ctx, `UPDATE orchestration.delivery_policies SET policy_epoch=2,expires_at=clock_timestamp()-interval '1 second' WHERE policy_id=$1`, f.request.PolicyID)
				if err == nil {
					_, err = f.store.db.ExecContext(ctx, `UPDATE orchestration.workflow_runs SET policy_epoch=2 WHERE run_id=$1::uuid`, f.request.RunID)
				}
				if err == nil {
					r := f.request
					r.PolicyEpoch = 2
					f.evidence = cloneDeliveryReview(t, f, r, 120)
					f.request = r
				}
			case "bad-request-hash":
				original := f.evidence
				f.evidence, _ = freshUUID()
				_, err = f.issuer.ExecContext(ctx, `INSERT INTO orchestration.delivery_review_evidence(evidence_id,run_id,producer_attempt_id,review_attempt_id,task_id,claimant_id,lease_epoch,expected_run_version,policy_id,policy_epoch,request_hash,request_json,expires_at) SELECT $1::uuid,run_id,producer_attempt_id,review_attempt_id,task_id,claimant_id,lease_epoch,expected_run_version,policy_id,policy_epoch,$3,request_json,expires_at FROM orchestration.delivery_review_evidence WHERE evidence_id=$2::uuid`, f.evidence, original, strings.Repeat("0", 64))
			}
			if err != nil {
				t.Fatal(err)
			}
			if got, err := f.store.AuthorizeDelivery(ctx, f.evidence); err == nil || got.DeliveryPolicyApproved {
				t.Fatal("unsafe state authorized")
			}
			if _, err = f.current(t); err == nil {
				t.Fatal("denied transition exposed authority")
			}
			if fault == "cancel-before" {
				var n, events int
				if f.store.db.QueryRowContext(ctx, `SELECT (SELECT count(*) FROM orchestration.workflow_transitions WHERE run_id=$1::uuid AND control_kind='DELIVERY_CANCEL'),(SELECT count(*) FROM orchestration.workflow_outbox WHERE run_id=$1::uuid AND event_type='DELIVERY_CANCEL_REQUESTED')`, f.request.RunID).Scan(&n, &events) != nil || n != 1 || events != 1 {
					t.Fatal("cancel replay duplicated durable stop intent")
				}
			}
			var n int
			if f.store.db.QueryRowContext(ctx, `SELECT count(*) FROM orchestration.delivery_authorizations WHERE run_id=$1::uuid`, f.request.RunID).Scan(&n) != nil || n != 0 {
				t.Fatal("denied transition left durable authority")
			}
		})
	}
}
func TestDeliveryAuthorityRevocationAndCancellationRechecked(t *testing.T) {
	for _, fault := range []string{"cancel", "policy-disable", "policy-epoch", "review-revoke", "supersede", "head-change"} {
		t.Run(fault, func(t *testing.T) {
			f := deliveryReviewFixture(t)
			ctx := testContext(t)
			if _, err := f.store.AuthorizeDelivery(ctx, f.evidence); err != nil {
				t.Fatal(err)
			}
			var err error
			switch fault {
			case "cancel":
				err = f.store.CancelDelivery(ctx, f.request.RunID, 9)
			case "policy-disable":
				_, err = f.issuer.ExecContext(ctx, `UPDATE orchestration.delivery_policies SET enabled=false WHERE policy_id=$1`, f.request.PolicyID)
			case "policy-epoch":
				_, err = f.issuer.ExecContext(ctx, `UPDATE orchestration.delivery_policies SET policy_epoch=2 WHERE policy_id=$1`, f.request.PolicyID)
			case "review-revoke":
				_, err = f.issuer.ExecContext(ctx, `UPDATE orchestration.delivery_review_evidence SET revoked_at=clock_timestamp() WHERE evidence_id=$1::uuid`, f.evidence)
			case "supersede":
				_, err = f.store.db.ExecContext(ctx, `UPDATE orchestration.workflow_runs SET state='SUPERSEDED',run_version=run_version+1 WHERE run_id=$1::uuid`, f.request.RunID)
			case "head-change":
				_, err = f.store.db.ExecContext(ctx, `UPDATE orchestration.workflow_runs SET observed_target_head_sha=$2 WHERE run_id=$1::uuid`, f.request.RunID, strings.Repeat("9", 40))
			}
			if err != nil {
				t.Fatal(err)
			}
			if _, err = f.current(t); err == nil {
				t.Fatal("stale authority survived mutation boundary read")
			}
			var n int
			if f.store.db.QueryRowContext(ctx, `SELECT count(*) FROM orchestration.delivery_authorizations WHERE run_id=$1::uuid`, f.request.RunID).Scan(&n) != nil || n != 1 {
				t.Fatal("historical identity lost after revocation")
			}
		})
	}
}
func TestDeliveryAuthorityConcurrentReplayAndCancellation(t *testing.T) {
	for _, cancelRace := range []bool{false, true} {
		t.Run(map[bool]string{false: "replay", true: "cancel"}[cancelRace], func(t *testing.T) {
			f := deliveryReviewFixture(t)
			ctx := testContext(t)
			start := make(chan struct{})
			var wg sync.WaitGroup
			for i := range 8 {
				wg.Add(1)
				go func(i int) {
					defer wg.Done()
					<-start
					if cancelRace && i == 0 {
						_ = f.store.CancelDelivery(ctx, f.request.RunID, 8)
						_ = f.store.CancelDelivery(ctx, f.request.RunID, 9)
					} else {
						_, _ = f.store.AuthorizeDelivery(ctx, f.evidence)
					}
				}(i)
			}
			close(start)
			wg.Wait()
			var n int
			if f.store.db.QueryRowContext(ctx, `SELECT count(*) FROM orchestration.delivery_authorizations WHERE run_id=$1::uuid`, f.request.RunID).Scan(&n) != nil || n > 1 || !cancelRace && n != 1 {
				t.Fatal("concurrent authorization duplicated or vanished")
			}
			if cancelRace {
				if _, err := f.current(t); err == nil {
					t.Fatal("cancelled run remained authorized")
				}
			}
		})
	}
}
func TestDeliveryAuthorityPrivilegesAndAtomicRollback(t *testing.T) {
	f := deliveryReviewFixture(t)
	ctx := testContext(t)
	for _, statement := range []string{`UPDATE orchestration.delivery_policies SET policy_id=policy_id WHERE policy_id=$1`, `UPDATE orchestration.delivery_review_evidence SET evidence_id=evidence_id WHERE policy_id=$1`, `DELETE FROM orchestration.delivery_authorizations WHERE false AND $1::text IS NOT NULL`, `UPDATE orchestration.delivery_authorizations SET request_hash=request_hash WHERE false AND $1::text IS NOT NULL`, `INSERT INTO orchestration.delivery_policies(policy_id) VALUES($1)`} {
		_, err := f.store.db.ExecContext(ctx, statement, f.request.PolicyID)
		var denied *pgconn.PgError
		if !errors.As(err, &denied) || (denied.Code != "42501" && denied.Code != "P0001") {
			t.Fatal("runtime mutation was not rejected by PostgreSQL privileges/guard")
		}
	}
	for _, role := range []string{"asep_orch_authority", "asep_orch_migrator"} {
		_, err := f.store.db.ExecContext(ctx, "SET ROLE "+role)
		var denied *pgconn.PgError
		if !errors.As(err, &denied) || denied.Code != "42501" {
			t.Fatal("runtime escalated to protected authority")
		}
	}

	if _, err := f.issuer.ExecContext(ctx, `UPDATE orchestration.workflow_runs SET state='DELIVERY_PENDING' WHERE run_id=$1::uuid`, f.request.RunID); err == nil {
		t.Fatal("review issuer wrote workflow state")
	}
	if _, err := f.issuer.ExecContext(ctx, `UPDATE orchestration.delivery_review_evidence SET request_hash=request_hash WHERE evidence_id=$1::uuid`, f.evidence); err == nil {
		t.Fatal("issuer rewrote review identity")
	}
	migration := deliveryAuthorityDatabase(t, "asep_orch_migrator", "ASEP_DELIVERY_MIGRATOR_TEST_PASSWORD")
	name := "delivery_test_fail_" + strings.ReplaceAll(f.evidence, "-", "")
	_, err := migration.ExecContext(ctx, `CREATE FUNCTION orchestration.`+name+`() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.run_id='`+f.request.RunID+`'::uuid THEN RAISE EXCEPTION 'fixture audit failure'; END IF; RETURN NEW; END $$; CREATE TRIGGER `+name+` BEFORE INSERT ON orchestration.workflow_outbox FOR EACH ROW EXECUTE FUNCTION orchestration.`+name+`()`)
	if err != nil {
		t.Fatal("fault injection unavailable")
	}
	t.Cleanup(func() {
		_, _ = migration.ExecContext(context.Background(), `DROP TRIGGER IF EXISTS `+name+` ON orchestration.workflow_outbox; DROP FUNCTION IF EXISTS orchestration.`+name+`()`)
	})
	if _, err = f.store.AuthorizeDelivery(ctx, f.evidence); err == nil {
		t.Fatal("audit failure acknowledged authority")
	}
	var state, status string
	var version int64
	err = f.store.db.QueryRowContext(ctx, `SELECT r.state,r.run_version,t.status FROM orchestration.workflow_runs r JOIN orchestration.task_deliveries t ON t.task_id=$2::uuid WHERE r.run_id=$1::uuid`, f.request.RunID, f.task).Scan(&state, &version, &status)
	if err != nil || state != "REVIEWING" || version != 8 || status != "LEASED" {
		t.Fatal("transaction bundle did not roll back")
	}
}
func TestDeliveryAuthorityDatabaseFailure(t *testing.T) {
	f := deliveryReviewFixture(t)
	db, err := sql.Open("pgx", unavailableEndpoint(t))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	faulty := &Store{db: db}
	ctx, cancel := context.WithTimeout(t.Context(), time.Second)
	defer cancel()
	if _, err = faulty.AuthorizeDelivery(ctx, f.evidence); err == nil {
		t.Fatal("DB failure authorized transition")
	}
	if _, err = faulty.CurrentDeliveryAuthority(ctx, f.request.OperationID, f.request.RunID, f.request.AttemptID); err == nil {
		t.Fatal("DB failure returned authority")
	}
}

func cloneDeliveryReview(t *testing.T, f deliveryFixture, r DeliveryRequest, seconds int) string {
	t.Helper()
	id, err := freshUUID()
	if err != nil {
		t.Fatal(err)
	}
	hash, err := r.Hash()
	if err != nil {
		t.Fatal(err)
	}
	body, err := json.Marshal(r)
	if err != nil {
		t.Fatal(err)
	}
	_, err = f.issuer.ExecContext(testContext(t), `INSERT INTO orchestration.delivery_review_evidence(evidence_id,run_id,producer_attempt_id,review_attempt_id,task_id,claimant_id,lease_epoch,expected_run_version,policy_id,policy_epoch,request_hash,request_json,expires_at) SELECT $1::uuid,run_id,producer_attempt_id,review_attempt_id,task_id,claimant_id,lease_epoch,expected_run_version,policy_id,$6,$3,$4::jsonb,clock_timestamp()+($5*interval '1 second') FROM orchestration.delivery_review_evidence WHERE evidence_id=$2::uuid`, id, f.evidence, hash, string(body), seconds, r.PolicyEpoch)
	if err != nil {
		t.Fatal(err)
	}
	return id
}
func TestDeliveryAuthorityConcurrentDifferentOperations(t *testing.T) {
	f := deliveryReviewFixture(t)
	second := f.request
	second.OperationID, _ = freshUUID()
	second.OwnerGrantID, _ = freshUUID()
	second.CandidateCommitSHA = strings.Repeat("9", 40)
	otherEvidence := cloneDeliveryReview(t, f, second, 120)
	ctx := testContext(t)
	var wg sync.WaitGroup
	start := make(chan struct{})
	success := make(chan DeliveryAuthority, 2)
	for _, id := range []string{f.evidence, otherEvidence} {
		wg.Add(1)
		go func(id string) {
			defer wg.Done()
			<-start
			value, err := f.store.AuthorizeDelivery(ctx, id)
			if err == nil {
				success <- value
			}
		}(id)
	}
	close(start)
	wg.Wait()
	close(success)
	var winner DeliveryAuthority
	count := 0
	for value := range success {
		winner = value
		count++
	}
	if count != 1 {
		t.Fatal("different operations did not serialize to one winner")
	}
	if winner.OperationID != f.request.OperationID && winner.OperationID != second.OperationID {
		t.Fatal("unknown operation won")
	}
	loser := f.request.OperationID
	if winner.OperationID == loser {
		loser = second.OperationID
	}
	if _, err := f.store.CurrentDeliveryAuthority(ctx, loser, f.request.RunID, f.request.AttemptID); err == nil {
		t.Fatal("losing operation borrowed winner authority")
	}
}
func TestDeliveryAuthorityExpiryDuringLockedAcceptance(t *testing.T) {
	f := deliveryReviewFixture(t)
	short := cloneDeliveryReview(t, f, f.request, 2)
	ctx := testContext(t)
	blocker, blockerPID := holdHeartbeatRow(t, f.store, `SELECT task_id::text FROM orchestration.task_deliveries WHERE task_id=$1::uuid FOR UPDATE`, f.task)
	probe, pid := heartbeatProbe(t)
	reply := make(chan error, 1)
	go func() { _, err := probe.AuthorizeDelivery(ctx, short); reply <- err }()
	waitHeartbeatBlockedBy(t, f.store, pid, blockerPID)
	ticker := time.NewTicker(10 * time.Millisecond)
	defer ticker.Stop()
	for {
		var expired bool
		if err := f.store.db.QueryRowContext(ctx, `SELECT expires_at<=clock_timestamp() FROM orchestration.delivery_review_evidence WHERE evidence_id=$1::uuid`, short).Scan(&expired); err != nil {
			t.Fatal(err)
		}
		if expired {
			break
		}
		select {
		case <-ctx.Done():
			t.Fatal("expiry barrier deadline")
		case <-ticker.C:
		}
	}
	if blocker.Commit() != nil {
		t.Fatal("lock release failed")
	}
	select {
	case err := <-reply:
		if err == nil {
			t.Fatal("expired review accepted after lock wait")
		}
	case <-ctx.Done():
		t.Fatal("authorization did not unblock")
	}
	var state string
	if f.store.db.QueryRowContext(ctx, `SELECT state FROM orchestration.workflow_runs WHERE run_id=$1::uuid`, f.request.RunID).Scan(&state) != nil || state != "REVIEWING" {
		t.Fatal("expired review changed state")
	}
}
func TestDeliveryAuthorityCancellationWinsWhileAuthorizationWaits(t *testing.T) {
	f := deliveryReviewFixture(t)
	ctx := testContext(t)
	blocker, blockerPID := holdHeartbeatRow(t, f.store, `SELECT run_id::text FROM orchestration.workflow_runs WHERE run_id=$1::uuid FOR UPDATE`, f.request.RunID)
	cancelProbe, cancelPID := heartbeatProbe(t)
	authorization, authorizationPID := heartbeatProbe(t)
	cancelled := make(chan error, 1)
	authorized := make(chan error, 1)
	go func() { cancelled <- cancelProbe.CancelDelivery(ctx, f.request.RunID, 8) }()
	waitHeartbeatBlockedBy(t, f.store, cancelPID, blockerPID)
	go func() { _, err := authorization.AuthorizeDelivery(ctx, f.evidence); authorized <- err }()
	// Both wait on the run; PostgreSQL queues cancellation first.
	waitHeartbeatBlockedBy(t, f.store, authorizationPID, cancelPID)
	if blocker.Commit() != nil {
		t.Fatal("cancel gate release failed")
	}
	select {
	case err := <-cancelled:
		if err != nil {
			t.Fatal(err)
		}
	case <-ctx.Done():
		t.Fatal("cancellation deadline")
	}
	select {
	case err := <-authorized:
		if err == nil {
			t.Fatal("cancelled state authorized")
		}
	case <-ctx.Done():
		t.Fatal("authorization deadline")
	}
	if _, err := f.current(t); err == nil {
		t.Fatal("cancellation failed to invalidate authority")
	}
}
