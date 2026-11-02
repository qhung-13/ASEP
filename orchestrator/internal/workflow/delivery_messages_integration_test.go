//go:build integration && deliveryauthority

package workflow

import (
	"strings"
	"sync"
	"testing"
)

func authorizedDeliveryFixture(t *testing.T) deliveryFixture {
	t.Helper()
	f := deliveryReviewFixture(t)
	if _, err := f.store.AuthorizeDelivery(testContext(t), f.evidence); err != nil {
		t.Fatal(err)
	}
	return f
}
func confirmedCallback(f deliveryFixture, revision int64) DeliveryCallback {
	head := f.request.CandidateCommitSHA
	return DeliveryCallback{callbackID(f.request.OperationID, revision), f.request.OperationID, f.request.RunID, f.request.RunVersion, mustRequestHash(f.request), revision, "CONFIRMED", "CONFIRMED", true, true, &head, []int64{17}, false}
}
func mustRequestHash(r DeliveryRequest) string { hash, _ := r.Hash(); return hash }
func sendCallback(t *testing.T, f deliveryFixture, c DeliveryCallback) {
	t.Helper()
	if err := f.store.AcceptDeliveryOutcome(testContext(t), DeliveryOutcomeEnvelope{c, c.hash()}); err != nil {
		t.Fatal("callback rejected", err)
	}
}
func TestDeliveryMessagesTransportAckIsNotCompletion(t *testing.T) {
	f := authorizedDeliveryFixture(t)
	ctx := testContext(t)
	envelope, err := f.store.ClaimDeliveryIntentFor(ctx, f.request.OperationID)
	if err != nil {
		t.Fatal(err)
	}
	if envelope.OperationID != f.request.OperationID || envelope.MessageHash != intentMessageHash(envelope.MessageID, envelope.OperationID, envelope.RequestHash) || envelope.Request != f.request {
		t.Fatal("unbound intent")
	}
	ack := DeliveryIntentAck{envelope.MessageID, envelope.MessageHash, envelope.ClaimToken}
	if f.store.AcknowledgeDeliveryIntent(ctx, ack) != nil || f.store.AcknowledgeDeliveryIntent(ctx, ack) != nil {
		t.Fatal("lost ACK replay failed")
	}
	var state string
	if f.store.db.QueryRowContext(ctx, `SELECT state FROM orchestration.workflow_runs WHERE run_id=$1::uuid`, f.request.RunID).Scan(&state) != nil || state != "DELIVERY_PENDING" {
		t.Fatal("transport ACK claimed remote completion")
	}
}
func TestDeliveryMessagesIntentReclaimFencesOldAck(t *testing.T) {
	f := authorizedDeliveryFixture(t)
	ctx := testContext(t)
	first, err := f.store.ClaimDeliveryIntentFor(ctx, f.request.OperationID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = f.store.db.ExecContext(ctx, `UPDATE orchestration.delivery_dispatches SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE message_id=$1::uuid`, first.MessageID); err != nil {
		t.Fatal(err)
	}
	second, err := f.store.ClaimDeliveryIntentFor(ctx, f.request.OperationID)
	if err != nil || first.MessageID != second.MessageID || first.RequestHash != second.RequestHash || first.ClaimToken == second.ClaimToken {
		t.Fatal("reclaim changed semantic intent")
	}
	if f.store.AcknowledgeDeliveryIntent(ctx, DeliveryIntentAck{first.MessageID, first.MessageHash, first.ClaimToken}) == nil {
		t.Fatal("stale transport token acknowledged")
	}
	if f.store.AcknowledgeDeliveryIntent(ctx, DeliveryIntentAck{second.MessageID, second.MessageHash, second.ClaimToken}) != nil {
		t.Fatal("current transport token rejected")
	}
}
func TestDeliveryMessagesCompletionReplayAndReorderedCallbacks(t *testing.T) {
	f := authorizedDeliveryFixture(t)
	ctx := testContext(t)
	current := confirmedCallback(f, 5)
	sendCallback(t, f, current)
	sendCallback(t, f, current)
	old := DeliveryCallback{callbackID(f.request.OperationID, 2), f.request.OperationID, f.request.RunID, f.request.RunVersion, mustRequestHash(f.request), 2, "UNKNOWN", "PLANNED", true, false, nil, []int64{}, false}
	sendCallback(t, f, old)
	var state string
	var version, revision int64
	var ids []byte
	var race bool
	err := f.store.db.QueryRowContext(ctx, `SELECT r.state,r.run_version,o.revision,o.known_mr_iids,o.residual_race FROM orchestration.workflow_runs r JOIN orchestration.delivery_observations o ON o.operation_id=$2::uuid WHERE r.run_id=$1::uuid`, f.request.RunID, f.request.OperationID).Scan(&state, &version, &revision, &ids, &race)
	if err != nil || state != "COMPLETED_DRAFT_MR" || version != f.request.RunVersion+1 || revision != 5 || string(ids) != "[17]" || race {
		t.Fatal("reordered callback rewrote durable completion", err)
	}
	var transitions int
	if f.store.db.QueryRowContext(ctx, `SELECT count(*) FROM orchestration.workflow_transitions WHERE run_id=$1::uuid AND to_state='COMPLETED_DRAFT_MR'`, f.request.RunID).Scan(&transitions) != nil || transitions != 1 {
		t.Fatal("duplicate completion transition")
	}
}
func TestDeliveryMessagesUnknownCancellationAndResidualEffects(t *testing.T) {
	f := authorizedDeliveryFixture(t)
	ctx := testContext(t)
	unknown := DeliveryCallback{callbackID(f.request.OperationID, 2), f.request.OperationID, f.request.RunID, f.request.RunVersion, mustRequestHash(f.request), 2, "UNKNOWN", "PLANNED", true, false, nil, []int64{}, false}
	sendCallback(t, f, unknown)
	var state string
	_ = f.store.db.QueryRowContext(ctx, `SELECT state FROM orchestration.workflow_runs WHERE run_id=$1::uuid`, f.request.RunID).Scan(&state)
	if state != "DELIVERY_PENDING" {
		t.Fatal("UNKNOWN became completed")
	}
	if f.store.CancelDelivery(ctx, f.request.RunID, f.request.RunVersion) != nil {
		t.Fatal("cancel failed")
	}
	sendCallback(t, f, confirmedCallback(f, 5))
	var race bool
	var raw []byte
	if f.store.db.QueryRowContext(ctx, `SELECT r.state,o.residual_race,o.known_mr_iids FROM orchestration.workflow_runs r JOIN orchestration.delivery_observations o ON o.operation_id=$2::uuid WHERE r.run_id=$1::uuid`, f.request.RunID, f.request.OperationID).Scan(&state, &race, &raw) != nil || state != "CANCEL_REQUESTED" || !race || string(raw) != "[17]" {
		t.Fatal("cancelled mutation lost actual identity or claimed rollback")
	}
}
func TestDeliveryMessagesRejectsForgedAndWrongScopeOutcomes(t *testing.T) {
	for _, fault := range []string{"hash", "callback-id", "request-hash", "version", "inconsistent-mr", "head", "duplicates"} {
		t.Run(fault, func(t *testing.T) {
			f := authorizedDeliveryFixture(t)
			c := confirmedCallback(f, 5)
			switch fault {
			case "callback-id":
				c.CallbackID, _ = freshUUID()
			case "request-hash":
				c.RequestHash = strings.Repeat("0", 64)
			case "version":
				c.ExpectedRunVersion++
			case "inconsistent-mr":
				c.PushClaimed = false
			case "head":
				head := strings.Repeat("9", 40)
				c.LastConfirmedPushHead = &head
			case "duplicates":
				c.KnownMRIIDs = []int64{17, 17}
			}
			hash := c.hash()
			if fault == "hash" {
				hash = strings.Repeat("0", 64)
			}
			if f.store.AcceptDeliveryOutcome(testContext(t), DeliveryOutcomeEnvelope{c, hash}) == nil {
				t.Fatal("forged callback accepted")
			}
		})
	}
}
func TestDeliveryMessagesConcurrentCallbacksKeepOneCompletion(t *testing.T) {
	f := authorizedDeliveryFixture(t)
	ctx := testContext(t)
	callback := confirmedCallback(f, 5)
	start := make(chan struct{})
	var wg sync.WaitGroup
	failures := make(chan error, 8)
	for range 8 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			failures <- f.store.AcceptDeliveryOutcome(ctx, DeliveryOutcomeEnvelope{callback, callback.hash()})
		}()
	}
	close(start)
	wg.Wait()
	close(failures)
	for err := range failures {
		if err != nil {
			t.Fatal(err)
		}
	}
	var n int
	if f.store.db.QueryRowContext(ctx, `SELECT count(*) FROM orchestration.delivery_outcome_inbox WHERE operation_id=$1::uuid`, f.request.OperationID).Scan(&n) != nil || n != 1 {
		t.Fatal("callback duplicated")
	}
}
func TestDeliveryMessagesRollbackOnOutcomeAuditFailure(t *testing.T) {
	f := authorizedDeliveryFixture(t)
	ctx := testContext(t)
	migration := deliveryAuthorityDatabase(t, "asep_orch_migrator", "ASEP_DELIVERY_MIGRATOR_TEST_PASSWORD")
	name := "outcome_fail_" + strings.ReplaceAll(f.evidence, "-", "")
	_, err := migration.ExecContext(ctx, `CREATE FUNCTION orchestration.`+name+`() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.run_id='`+f.request.RunID+`'::uuid AND NEW.event_type='DELIVERY_OUTCOME_RECORDED' THEN RAISE EXCEPTION 'fixture failure'; END IF; RETURN NEW; END $$; CREATE TRIGGER `+name+` BEFORE INSERT ON orchestration.workflow_outbox FOR EACH ROW EXECUTE FUNCTION orchestration.`+name+`()`)
	if err != nil {
		t.Fatal("fault setup failed")
	}
	defer migration.ExecContext(ctx, `DROP TRIGGER `+name+` ON orchestration.workflow_outbox; DROP FUNCTION orchestration.`+name+`()`) // owned fixture only
	c := confirmedCallback(f, 5)
	if f.store.AcceptDeliveryOutcome(ctx, DeliveryOutcomeEnvelope{c, c.hash()}) == nil {
		t.Fatal("audit failure acknowledged")
	}
	var state string
	var n int
	if f.store.db.QueryRowContext(ctx, `SELECT state,(SELECT count(*) FROM orchestration.delivery_outcome_inbox WHERE operation_id=$2::uuid) FROM orchestration.workflow_runs WHERE run_id=$1::uuid`, f.request.RunID, f.request.OperationID).Scan(&state, &n) != nil || state != "DELIVERY_PENDING" || n != 0 {
		t.Fatal("outcome bundle failed to roll back")
	}
}

func TestDeliveryMessagesOlderIdentityCannotBeLostAfterNewerCompletion(t *testing.T) {
	f := authorizedDeliveryFixture(t)
	ctx := testContext(t)
	sendCallback(t, f, confirmedCallback(f, 5))
	old := confirmedCallback(f, 3)
	old.MergeRequest = "UNKNOWN"
	old.KnownMRIIDs = []int64{19}
	sendCallback(t, f, old)
	var ids []byte
	var state string
	var revision int64
	var race bool
	if f.store.db.QueryRowContext(ctx, `SELECT r.state,o.revision,o.known_mr_iids,o.residual_race FROM orchestration.workflow_runs r JOIN orchestration.delivery_observations o ON o.operation_id=$2::uuid WHERE r.run_id=$1::uuid`, f.request.RunID, f.request.OperationID).Scan(&state, &revision, &ids, &race) != nil || state != "RECONCILIATION_REQUIRED" || revision != 5 || string(ids) != "[17, 19]" || !race {
		t.Fatal("reordering discarded a real remote identity")
	}
}

func TestDeliveryMessagesNewerDriftReopensReconciliationWithoutFalseRaceOnUnchangedFacts(t *testing.T) {
	f := authorizedDeliveryFixture(t)
	ctx := testContext(t)
	sendCallback(t, f, confirmedCallback(f, 5))
	sendCallback(t, f, confirmedCallback(f, 6))
	var state string
	var race bool
	if f.store.db.QueryRowContext(ctx, `SELECT r.state,o.residual_race FROM orchestration.workflow_runs r JOIN orchestration.delivery_observations o ON o.operation_id=$2::uuid WHERE r.run_id=$1::uuid`, f.request.RunID, f.request.OperationID).Scan(&state, &race) != nil || state != "COMPLETED_DRAFT_MR" || race {
		t.Fatal("unchanged higher revision invented a race")
	}
	authority := deliveryAuthorityDatabase(t, "asep_orch_authority", "ASEP_DELIVERY_AUTHORITY_TEST_PASSWORD")
	if _, err := authority.ExecContext(ctx, `UPDATE orchestration.delivery_policies SET enabled=false WHERE policy_id=$1`, f.request.PolicyID); err != nil {
		t.Fatal("post-completion revocation failed")
	}
	sendCallback(t, f, confirmedCallback(f, 7))
	if f.store.db.QueryRowContext(ctx, `SELECT r.state,o.residual_race FROM orchestration.workflow_runs r JOIN orchestration.delivery_observations o ON o.operation_id=$2::uuid WHERE r.run_id=$1::uuid`, f.request.RunID, f.request.OperationID).Scan(&state, &race) != nil || state != "COMPLETED_DRAFT_MR" || race {
		t.Fatal("historical completion became a race after later revocation")
	}
	changed := confirmedCallback(f, 8)
	changed.MergeRequest = "BLOCKED"
	changed.ResidualRace = true
	sendCallback(t, f, changed)
	if f.store.db.QueryRowContext(ctx, `SELECT r.state,o.residual_race FROM orchestration.workflow_runs r JOIN orchestration.delivery_observations o ON o.operation_id=$2::uuid WHERE r.run_id=$1::uuid`, f.request.RunID, f.request.OperationID).Scan(&state, &race) != nil || state != "RECONCILIATION_REQUIRED" || !race {
		t.Fatal("new mismatch left workflow complete")
	}
}
func TestDeliveryMessagesOlderRaceAndConfirmedHeadAreMonotonic(t *testing.T) {
	for _, kind := range []string{"race", "head"} {
		t.Run(kind, func(t *testing.T) {
			f := authorizedDeliveryFixture(t)
			ctx := testContext(t)
			if kind == "race" {
				sendCallback(t, f, confirmedCallback(f, 5))
				old := confirmedCallback(f, 3)
				old.MergeRequest = "PLANNED"
				old.MRClaimed = false
				old.KnownMRIIDs = []int64{}
				old.ResidualRace = true
				sendCallback(t, f, old)
			} else {
				newer := confirmedCallback(f, 5)
				newer.Push = "UNKNOWN"
				newer.MergeRequest = "UNKNOWN"
				newer.LastConfirmedPushHead = nil
				newer.KnownMRIIDs = []int64{17}
				sendCallback(t, f, newer)
				old := confirmedCallback(f, 3)
				old.MergeRequest = "PLANNED"
				old.MRClaimed = false
				old.KnownMRIIDs = []int64{}
				sendCallback(t, f, old)
			}
			var state string
			var head *string
			var race bool
			if f.store.db.QueryRowContext(ctx, `SELECT r.state,o.last_confirmed_push_head,o.residual_race FROM orchestration.workflow_runs r JOIN orchestration.delivery_observations o ON o.operation_id=$2::uuid WHERE r.run_id=$1::uuid`, f.request.RunID, f.request.OperationID).Scan(&state, &head, &race) != nil || head == nil || *head != f.request.CandidateCommitSHA {
				t.Fatal("older confirmed identity discarded")
			}
			if kind == "race" && (state != "RECONCILIATION_REQUIRED" || !race) {
				t.Fatal("older race discarded")
			}
			if kind == "head" && (state != "DELIVERY_PENDING" || race) {
				t.Fatal("historical head invented completion/race")
			}
		})
	}
}
