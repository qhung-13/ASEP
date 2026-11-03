//go:build integration && deliveryauthority

package workflow_test

import (
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"io"
	"io/fs"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/qhung-13/ASEP/orchestrator/internal/deliveryapi"
	"github.com/qhung-13/ASEP/orchestrator/internal/testsupport"
	"github.com/qhung-13/ASEP/orchestrator/internal/workflow"
)

type boundedFixture struct {
	ctx                      context.Context
	store                    *workflow.Store
	db, policyDB             *sql.DB
	peer                     *deliveryPeer
	ready                    map[string]any
	operation, grant, policy string
	sealedSHA                string
}

func newBoundedFixture(t *testing.T) *boundedFixture {
	t.Helper()
	ctx, cancel := context.WithTimeout(t.Context(), 70*time.Second)
	t.Cleanup(cancel)
	urlValue := testsupport.DatabaseURL(t)
	runtime, err := workflow.Open(ctx, urlValue)
	if err != nil {
		t.Fatal("runtime unavailable")
	}
	t.Cleanup(func() { _ = runtime.Close() })
	if runtime.ValidateDeliveryRuntime(ctx) != nil {
		t.Fatal("runtime role isolation check failed")
	}
	db := testsupport.Database(t)
	u, err := url.Parse(urlValue)
	if err != nil {
		t.Fatal(err)
	}
	u.User = url.UserPassword("asep_orch_authority", os.Getenv("ASEP_DELIVERY_AUTHORITY_TEST_PASSWORD"))
	issuerURL := u.String()
	issuer, err := workflow.OpenReviewIssuer(ctx, issuerURL)
	if err != nil {
		t.Fatal("protected fixture issuer unavailable")
	}
	t.Cleanup(func() { _ = issuer.Close() })
	policyDB, err := sql.Open("pgx", issuerURL)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = policyDB.Close() })
	directory := t.TempDir()
	// Only this test's recorded private root is made writable for final
	// cleanup; production sealed snapshots are never modified here.
	t.Cleanup(func() {
		err := filepath.WalkDir(directory, func(path string, entry fs.DirEntry, err error) error {
			if err != nil {
				return err
			}
			if entry.IsDir() {
				return os.Chmod(path, 0700)
			}
			return nil
		})
		if err != nil {
			t.Error("owned immutable snapshot cleanup failed")
		}
	})
	_, controllerCert := deliveryIdentity(t, directory, "controller", deliveryapi.ControllerIdentity)
	_, executorCert := deliveryIdentity(t, directory, "executor", deliveryapi.ReaderIdentity)
	roots := x509.NewCertPool()
	roots.AddCert(controllerCert)
	roots.AddCert(executorCert)
	handler, err := deliveryapi.NewService(runtime, issuer)
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		tracked := &deliveryStatus{ResponseWriter: w}
		handler.ServeHTTP(tracked, r)
		if tracked.code >= 400 {
			t.Logf("fixture internal route %s returned %d", r.URL.Path, tracked.code)
		}
	}))
	server.TLS = &tls.Config{MinVersion: tls.VersionTLS13, ClientAuth: tls.RequireAndVerifyClientCert, ClientCAs: roots}
	server.StartTLS()
	t.Cleanup(server.Close)
	if os.WriteFile(filepath.Join(directory, "server.crt"), pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: server.Certificate().Raw}), 0600) != nil {
		t.Fatal("server fixture certificate unavailable")
	}
	fingerprint := sha256.Sum256(server.Certificate().Raw)
	raw, err := os.ReadFile(os.Getenv("ASEP_DELIVERY_FIXTURE_CONFIG"))
	if err != nil {
		t.Fatal("cross-component fixture configuration missing")
	}
	var config map[string]any
	if json.Unmarshal(raw, &config) != nil {
		t.Fatal("invalid fixture configuration")
	}
	config["directory"] = filepath.Join(directory, "java-state")
	config["goOrigin"] = server.URL
	config["goPin"] = hex.EncodeToString(fingerprint[:])
	config["serverCa"] = filepath.Join(directory, "server.crt")
	config["controllerCertificate"] = filepath.Join(directory, "controller.crt")
	config["controllerKey"] = filepath.Join(directory, "controller.key")
	config["executorCertificate"] = filepath.Join(directory, "executor.crt")
	config["executorKey"] = filepath.Join(directory, "executor.key")
	peer := startDeliveryPeer(t, config)
	ready := peer.receive(t, "ready")
	f := &boundedFixture{ctx: ctx, store: runtime, db: db, policyDB: policyDB, peer: peer, ready: ready}
	if db.QueryRowContext(ctx, `SELECT gen_random_uuid()::text,gen_random_uuid()::text`).Scan(&f.operation, &f.grant) != nil {
		t.Fatal("fixture identities unavailable")
	}
	f.policy = "synthetic-slice-" + f.operation
	if _, err = policyDB.ExecContext(ctx, `INSERT INTO orchestration.delivery_policies(policy_id,policy_epoch,project_id,repository_path,target_branch,enabled,expires_at) VALUES($1,1,87786140,'qhung-13/asep-m01-sandbox','main',true,clock_timestamp()+interval '15 minutes')`, f.policy); err != nil {
		t.Fatal("fixture policy unavailable")
	}
	if _, err = policyDB.ExecContext(ctx, `INSERT INTO orchestration.delivery_verification_profiles VALUES($1,1,$2,$3,$4,2,300)`, f.policy, strings.Repeat("a", 64), ready["harnessSha256"], ready["imageSha256"]); err != nil {
		t.Fatal("fixture profile unavailable")
	}
	return f
}

func (f *boundedFixture) id(t *testing.T) string {
	t.Helper()
	var id string
	if f.db.QueryRowContext(f.ctx, `SELECT gen_random_uuid()::text`).Scan(&id) != nil {
		t.Fatal("ID unavailable")
	}
	return id
}
func admissionSlicePeer(t *testing.T, config map[string]any) *deliveryPeer {
	t.Helper()
	classpath, err := os.ReadFile(os.Getenv("ASEP_VN_T12_JAVA_CLASSPATH_FILE"))
	if err != nil {
		t.Fatal("Java classpath unavailable")
	}
	ctx, cancel := context.WithTimeout(t.Context(), 70*time.Second)
	t.Cleanup(cancel)
	cmd := exec.CommandContext(ctx, os.Getenv("ASEP_VN_T12_JAVA_BIN"), "-cp", string(classpath), "io.github.qhung13.asep.admission.ManualAdmissionProcessFixture")
	cmd.Env = []string{"LANG=C.UTF-8", "TZ=UTC"}
	cmd.Stderr = io.Discard
	cmd.WaitDelay = time.Second
	input, err := cmd.StdinPipe()
	if err != nil {
		t.Fatal(err)
	}
	output, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	if cmd.Start() != nil {
		t.Fatal("Java admission peer unavailable")
	}
	p := &deliveryPeer{cmd: cmd, input: input, encode: json.NewEncoder(input), decode: json.NewDecoder(io.LimitReader(output, 65536))}
	t.Cleanup(func() {
		_ = input.Close()
		if !p.waited {
			_ = cmd.Process.Kill()
			_ = cmd.Wait()
		}
	})
	p.send(t, config)
	return p
}
func (f *boundedFixture) admit(t *testing.T, issue string, limits workflow.SliceLimits) (workflow.Admission, workflow.AdmissionReceipt, *deliveryPeer) {
	t.Helper()
	// Actual Java fixture reader normalizes the selected synthetic Jira snapshot;
	// actual Java Owner/policy checks run before Go receives an admission command.
	body := `{"id":"` + issue + `","key":"SYN-15","fields":{"project":{"id":"10001"},"issuetype":{"name":"Task"},"status":{"name":"To Do"},"summary":"Add the fixed bounded probe","description":null}}`
	p := admissionSlicePeer(t, map[string]any{"issueId": issue, "projectId": "10001", "prefix": limits.ScopePrefix, "body": body, "repositoryBaseSha": f.ready["baseSha"], "policyId": f.policy})
	p.send(t, map[string]any{"action": "prepare", "slot": "original", "operationId": f.operation})
	prepared := p.receive(t, "prepared")
	raw, _ := json.Marshal(prepared["command"])
	var command workflow.Admission
	if json.Unmarshal(raw, &command) != nil || command.GitLabProjectID != "87786140" || command.PolicyID != f.policy {
		t.Fatal("Java command binding differs")
	}
	p.send(t, map[string]any{"action": "submit", "slot": "original"})
	p.receive(t, "submit")
	r, err := f.store.AdmitSyntheticSlice(f.ctx, command, limits)
	if err != nil {
		t.Fatal("bounded admission failed", err)
	}
	p.send(t, map[string]any{"state": "ACCEPTED", "receipt": map[string]any{"runId": r.RunID, "taskId": r.TaskID, "attemptId": r.AttemptID, "replayed": r.Replayed}})
	p.receive(t, "outcome")
	return command, r, p
}

type untrustedResult = workflow.AgentResultV1

func runSlicePython(t *testing.T, task workflow.AgentTaskV1) untrustedResult {
	t.Helper()
	script, err := filepath.Abs("../../../agent-runtime/asep_agent/worker.py")
	if err != nil {
		t.Fatal(err)
	}
	source, err := os.ReadFile(script)
	if err != nil {
		t.Fatal("adapter unavailable")
	}
	result, err := workflow.RunSyntheticAgent(t.Context(), task, script, workflow.SyntheticAgentScriptDigest(source))
	if err != nil {
		t.Fatal("supervised Python proposal rejected", err)
	}
	return result
}
func (f *boundedFixture) stage(t *testing.T, c workflow.Admission, run, stage, scenario string) (workflow.TaskLease, workflow.AgentTaskV1, untrustedResult) {
	t.Helper()
	claimant := "synthetic-python-supervisor"
	if stage == "IMPLEMENTING" {
		claimant = workflow.ControllerClaimant
	}
	lease, err := f.store.ClaimForRun(f.ctx, run, claimant, 60)
	if err != nil || lease.Stage != stage {
		t.Fatal("stage claim failed", err)
	}
	task, err := f.store.ReserveSliceAgentTask(f.ctx, lease, f.operation, scenario)
	if err != nil {
		t.Fatal("stage reservation failed", err)
	}
	r := runSlicePython(t, task)
	if r.Status != "PROPOSED" {
		t.Fatal("fixture model unavailable")
	}
	if stage == "INVESTIGATING" {
		if len(r.Coverage) != 2 || len(r.Proposal) != 2 {
			t.Fatal("investigation coverage gate rejected")
		}
	}
	if stage == "PLANNING" {
		paths, ok := r.Proposal["allowedPaths"].([]any)
		if !ok || len(paths) != 1 || paths[0] != "vn-t14-probe-"+f.operation+".txt" {
			t.Fatal("plan scope gate rejected")
		}
	}
	if stage == "IMPLEMENTING" {
		if r.Proposal["path"] != "vn-t14-probe-"+f.operation+".txt" {
			t.Fatal("implementation scope rejected")
		}
	}
	return lease, task, r
}
func (f *boundedFixture) advance(t *testing.T, lease workflow.TaskLease, task workflow.AgentTaskV1, result untrustedResult, sealID ...string) {
	t.Helper()
	raw, _ := json.Marshal(result)
	operation := f.id(t)
	first, err := f.store.AcceptSliceAgentResult(f.ctx, lease, task, operation, raw, sealID...)
	if err != nil {
		t.Fatal("stage advancement failed", err)
	}
	replay, err := f.store.AcceptSliceAgentResult(f.ctx, lease, task, operation, raw, sealID...)
	if err != nil || first.NextTaskID != replay.NextTaskID {
		t.Fatal("exact stage replay changed successor")
	}
}
func TestBoundedSliceAcrossJavaGoPython(t *testing.T) {
	f := newBoundedFixture(t)
	limits := workflow.SliceLimits{ScopePrefix: os.Getenv("ASEP_VN_T10_TEST_PREFIX"), ElapsedSeconds: 180, ModelTokenLimit: 512, MaximumModelCalls: 4}
	c, r, app := f.admit(t, "15001", limits)
	replay, err := f.store.AdmitSyntheticSlice(f.ctx, c, limits)
	if err != nil || !replay.Replayed || replay.RunID != r.RunID {
		t.Fatal("admission replay replaced original run")
	}
	other := c
	other.CommandID = f.id(t)
	other.JiraIssueID = "15999"
	other.SubjectKey = limits.ScopePrefix + ":manual:15999"
	if _, err = f.store.AdmitSyntheticSlice(f.ctx, other, limits); !errors.Is(err, workflow.ErrConflict) {
		t.Fatal("Owner-selected slice scope admitted another active run")
	}

	for _, stage := range []string{"INVESTIGATING", "PLANNING"} {
		lease, task, result := f.stage(t, c, r.RunID, stage, "success")
		f.advance(t, lease, task, result)
	}
	producer, task, result := f.stage(t, c, r.RunID, "IMPLEMENTING", "success")
	f.peer.send(t, map[string]any{"action": "prepare-bound", "operationId": f.operation, "grantId": f.grant, "runId": r.RunID, "attemptId": producer.AttemptID, "deliveryVersion": task.RunVersion + 3, "policyId": f.policy, "content": result.Proposal["content"]})
	candidate := f.peer.receive(t, "candidate-prepared")
	request := candidate["request"].(map[string]any)
	if request["runId"] != r.RunID || request["attemptId"] != producer.AttemptID {
		t.Fatal("sealed candidate lost producer/run binding")
	}
	f.pendingImplementation(t, producer)
	f.register(t, producer, "none", "candidate-registered")
	f.pendingImplementation(t, producer) // Registration alone cannot commit success.
	f.advance(t, producer, task, result, f.operation)
	verification, err := f.store.ClaimForRun(f.ctx, r.RunID, workflow.ControllerClaimant, 60)
	if err != nil || verification.Stage != "VERIFYING" {
		t.Fatal("protected verification claim failed", err)
	}
	var accepted time.Time
	if f.db.QueryRowContext(f.ctx, `SELECT accepted_at FROM orchestration.workflow_result_receipts WHERE run_id=$1::uuid AND attempt_id=$2::uuid`, r.RunID, producer.AttemptID).Scan(&accepted) != nil {
		t.Fatal("implementation receipt unavailable")
	}
	sealTime, err := time.Parse(time.RFC3339Nano, candidate["seal"].(map[string]any)["sealedAt"].(string))
	if err != nil || accepted.Before(sealTime) {
		t.Fatal("implementation committed before actual seal")
	}
	f.peer.send(t, map[string]any{"action": "submit-bound", "producerTaskId": producer.TaskID, "producerLeaseEpoch": producer.LeaseEpoch})
	var submission map[string]any
	if f.peer.decode.Decode(&submission) != nil || submission["kind"] != "bound-authorized" {
		for _, table := range []string{"controlled_candidate_evidence", "controlled_review_receipts", "delivery_review_evidence", "delivery_authorizations"} {
			var count int
			if f.db.QueryRowContext(f.ctx, "SELECT count(*) FROM orchestration."+table+" WHERE run_id=$1::uuid", r.RunID).Scan(&count) == nil {
				t.Logf("bounded handoff %s rows=%d", table, count)
			}
		}
		t.Fatal("bounded protected handoff denied; no replacement operation")
	}
	f.peer.send(t, map[string]any{"action": "approve", "seconds": 300})
	f.peer.receive(t, "approved") // TEST issuer only; never a live Owner grant.
	observed := f.peer.action(t, "execute")
	if observed["complete"] != true || observed["pushes"] != float64(1) || observed["mrCreates"] != float64(1) {
		t.Fatal("synthetic two-step delivery not confirmed")
	}
	f.peer.action(t, "publish")
	var state string
	if f.db.QueryRowContext(f.ctx, `SELECT state FROM orchestration.workflow_runs WHERE run_id=$1::uuid`, r.RunID).Scan(&state) != nil || state != "COMPLETED_DRAFT_MR" {
		t.Fatal("Go did not independently accept delivery outcome")
	}
	// Independent owning-domain queries, then Application authorization/projection.
	snapshot, err := f.store.ObserveSyntheticSlice(f.ctx, r.RunID, limits.ScopePrefix)
	if err != nil || snapshot.ModelCallsReserved != 3 || snapshot.ModelTokensReserved != 384 {
		t.Fatal("durable model reservations not observable")
	}
	f.peer.send(t, map[string]any{"action": "observe-delivery"})
	delivery := f.peer.receive(t, "delivery-observation")
	for _, role := range []string{"VIEWER", "WORKER"} {
		app.send(t, map[string]any{"action": "observe", "slot": "original", "runId": r.RunID, "role": role})
		app.receive(t, "observation-denied")
	}
	app.send(t, map[string]any{"action": "observe", "slot": "original", "runId": r.RunID, "role": "OWNER"})
	app.receive(t, "read-observation")
	app.send(t, map[string]any{"workflow": snapshot, "delivery": delivery["delivery"]})
	view := app.receive(t, "observation")["view"].(map[string]any)
	d := view["delivery"].(map[string]any)
	if d["ci"] != "NOT_OBSERVED" || d["humanMerge"] != "NOT_OBSERVED" || d["synthetic"] != true {
		t.Fatal("Draft MR conflated with CI/merge/live delivery")
	}
	app.finish(t)
	f.peer.finish(t)
}

func sliceLimits() workflow.SliceLimits {
	return workflow.SliceLimits{ScopePrefix: os.Getenv("ASEP_VN_T10_TEST_PREFIX"), ElapsedSeconds: 180, ModelTokenLimit: 512, MaximumModelCalls: 4}
}
func (f *boundedFixture) facts(t *testing.T) map[string]any {
	t.Helper()
	f.peer.send(t, map[string]any{"action": "remote-facts", "operationId": f.operation})
	return f.peer.receive(t, "remote-facts")
}
func (f *boundedFixture) noDelivery(t *testing.T, run, state string) {
	t.Helper()
	facts := f.facts(t)
	if facts["pushes"] != float64(0) || facts["mrCreates"] != float64(0) || facts["ledgerRows"] != float64(0) || facts["forbidden"] != float64(0) || len(facts["mrs"].([]any)) != 0 || facts["mainHead"] != f.ready["baseSha"] {
		t.Fatal("blocked workflow caused a remote/ledger mutation")
	}
	snapshot, err := f.store.ObserveSyntheticSlice(f.ctx, run, os.Getenv("ASEP_VN_T10_TEST_PREFIX"))
	if err != nil || snapshot.State != state {
		t.Fatal("terminal Go disposition not durable", err)
	}
	var pending, authorized int
	if f.db.QueryRowContext(f.ctx, `SELECT count(*) FROM orchestration.task_deliveries WHERE run_id=$1::uuid AND status IN ('QUEUED','LEASED')`, run).Scan(&pending) != nil || pending != 0 {
		t.Fatal("terminal outcome retained executable successor")
	}
	if f.db.QueryRowContext(f.ctx, `SELECT count(*) FROM orchestration.delivery_authorizations WHERE run_id=$1::uuid`, run).Scan(&authorized) != nil || authorized != 0 {
		t.Fatal("blocked workflow obtained delivery authority")
	}
}
func (f *boundedFixture) stop(t *testing.T, c workflow.Admission, lease workflow.TaskLease, state, reason string) {
	t.Helper()
	snapshot, err := f.store.ObserveSyntheticSlice(f.ctx, lease.RunID, os.Getenv("ASEP_VN_T10_TEST_PREFIX"))
	if err != nil {
		t.Fatal(err)
	}
	command := workflow.SliceAdvance{OperationID: f.id(t), Lease: lease, ExpectedRunVersion: snapshot.RunVersion, PolicyEpoch: c.PolicyEpoch, SourceFingerprint: c.SourceFingerprintSHA256, EvidenceRef: "synthetic:" + reason}
	first, err := f.store.StopSyntheticSlice(f.ctx, command, state)
	if err != nil {
		t.Fatal("bounded terminal decision failed", err)
	}
	again, err := f.store.StopSyntheticSlice(f.ctx, command, state)
	if err != nil || first.TransitionID != again.TransitionID || again.NextTaskID != "" {
		t.Fatal("terminal replay created another effect")
	}
}
func (f *boundedFixture) toImplementation(t *testing.T, issue string, limits workflow.SliceLimits) (workflow.Admission, workflow.AdmissionReceipt, *deliveryPeer) {
	t.Helper()
	c, r, app := f.admit(t, issue, limits)
	for _, stage := range []string{"INVESTIGATING", "PLANNING"} {
		lease, task, result := f.stage(t, c, r.RunID, stage, "success")
		f.advance(t, lease, task, result)
	}
	return c, r, app
}
func (f *boundedFixture) candidate(t *testing.T, run string, producer workflow.TaskLease, task workflow.AgentTaskV1, result untrustedResult, want string, overrideProducer string) map[string]any {
	t.Helper()
	attempt := producer.AttemptID
	if overrideProducer != "" {
		attempt = overrideProducer
	}
	f.peer.send(t, map[string]any{"action": "prepare-bound", "operationId": f.operation, "grantId": f.grant, "runId": run, "attemptId": attempt, "deliveryVersion": task.RunVersion + 3, "policyId": f.policy, "content": result.Proposal["content"]})
	response := f.peer.receive(t, want)
	if want == "candidate-prepared" {
		f.sealedSHA = response["request"].(map[string]any)["candidateCommitSha"].(string)
	}
	return response
}
func (f *boundedFixture) submit(t *testing.T, producer workflow.TaskLease, want string) {
	t.Helper()
	f.peer.send(t, map[string]any{"action": "submit-bound", "producerTaskId": producer.TaskID, "producerLeaseEpoch": producer.LeaseEpoch})
	f.peer.receive(t, want)
}
func (f *boundedFixture) confirmedDelivery(t *testing.T, run string, lost bool) {
	t.Helper()
	f.peer.send(t, map[string]any{"action": "approve", "seconds": 300})
	f.peer.receive(t, "approved")
	if lost {
		f.peer.send(t, map[string]any{"action": "fault", "mode": "lost-mr"})
		f.peer.receive(t, "fault-set")
	}
	first := f.peer.action(t, "execute")
	if first["pushes"] != float64(1) || first["mrCreates"] != float64(1) || first["mrs"] != float64(1) {
		t.Fatal("bounded delivery did not create exactly one effect")
	}
	// Read-only reconciliation of the original operation; never call execute again.
	if lost {
		f.peer.send(t, map[string]any{"action": "fault", "mode": "none"})
		f.peer.receive(t, "fault-set")
	}
	for i := 0; i < 2; i++ {
		reconciled := f.peer.action(t, "reconcile")
		if reconciled["complete"] != true || reconciled["pushes"] != first["pushes"] || reconciled["mrCreates"] != first["mrCreates"] {
			t.Fatal("read-only reconciliation repeated or lost remote effect")
		}
	}
	f.peer.action(t, "publish")
	facts := f.facts(t)
	mrs := facts["mrs"].([]any)
	if len(mrs) != 1 || facts["sourceHead"] != f.sealedSHA || facts["mainHead"] != f.ready["baseSha"] || facts["forbidden"] != float64(0) {
		t.Fatal("independent native remote facts differ")
	}
	mr := mrs[0].(map[string]any)
	if mr["draft"] != true || mr["state"] != "opened" || mr["target"] != "main" || mr["sha"] != facts["sourceHead"] {
		t.Fatal("remote MR identity/state/head is not the candidate")
	}
	f.peer.send(t, map[string]any{"action": "observe-delivery"})
	delivery := f.peer.receive(t, "delivery-observation")["delivery"].(map[string]any)
	if delivery["branchPush"] != "CONFIRMED" || delivery["draftMr"] != "CONFIRMED" || delivery["ci"] != "NOT_OBSERVED" {
		t.Fatal("durable ledger steps not independently confirmed")
	}
	snapshot, err := f.store.ObserveSyntheticSlice(f.ctx, run, os.Getenv("ASEP_VN_T10_TEST_PREFIX"))
	if err != nil || snapshot.State != "COMPLETED_DRAFT_MR" {
		t.Fatal("Go outcome not confirmed", err)
	}
}
func TestBoundedSliceSafetyMatrix(t *testing.T) {
	cases := []string{"failed-tests-block", "correction-success", "correction-budget-exhaustion", "policy-revoked", "stale-candidate", "model-token-budget", "model-call-budget", "elapsed-budget", "model-unavailable", "unknown-mr-response", "stale-source-result"}
	for index, scenario := range cases {
		t.Run(scenario, func(t *testing.T) {
			f := newBoundedFixture(t)
			limits := sliceLimits()
			issue := string([]byte{'1', '6', byte('0' + index/10), byte('0' + index%10), '1'})
			if scenario == "model-token-budget" {
				limits.ModelTokenLimit = 128
			}
			if scenario == "model-call-budget" {
				limits.MaximumModelCalls = 1
			}
			if scenario == "elapsed-budget" {
				limits.ElapsedSeconds = 1
			}
			if strings.Contains(scenario, "budget") && scenario != "correction-budget-exhaustion" {
				c, r, app := f.admit(t, issue, limits)
				if scenario != "elapsed-budget" {
					lease, task, result := f.stage(t, c, r.RunID, "INVESTIGATING", "success")
					f.advance(t, lease, task, result)
				}
				lease, err := f.store.ClaimForRun(f.ctx, r.RunID, "synthetic-python-supervisor", 60)
				if err != nil {
					t.Fatal(err)
				}
				if scenario == "elapsed-budget" {
					for {
						var expired bool
						if f.db.QueryRowContext(f.ctx, `SELECT expires_at<=clock_timestamp() FROM orchestration.workflow_slice_profiles WHERE run_id=$1::uuid`, r.RunID).Scan(&expired) != nil {
							t.Fatal("deadline observation failed")
						}
						if expired {
							break
						}
						select {
						case <-f.ctx.Done():
							t.Fatal("deadline wait unbounded")
						case <-time.After(20 * time.Millisecond):
						}
					}
				}
				if _, err = f.store.ReserveSliceAgentTask(f.ctx, lease, f.operation, "success"); !errors.Is(err, workflow.ErrSliceBudget) {
					t.Fatal("exhausted budget dispatched model", err)
				}
				f.stop(t, c, lease, "ESCALATED", scenario)
				f.noDelivery(t, r.RunID, "ESCALATED")
				app.finish(t)
				f.peer.finish(t)
				return
			}
			if scenario == "model-unavailable" || scenario == "policy-revoked" || scenario == "stale-source-result" {
				c, r, app := f.admit(t, issue, limits)
				lease, err := f.store.ClaimForRun(f.ctx, r.RunID, "synthetic-python-supervisor", 60)
				if err != nil {
					t.Fatal(err)
				}
				state := "BLOCKED"
				if scenario == "model-unavailable" {
					task, err := f.store.ReserveSliceAgentTask(f.ctx, lease, f.operation, "model-unavailable")
					if err != nil {
						t.Fatal(err)
					}
					result := runSlicePython(t, task)
					if result.Status != "MODEL_UNAVAILABLE" || result.Proposal != nil {
						t.Fatal("model unavailability invoked fallback")
					}
					state = "ESCALATED"
				} else if scenario == "policy-revoked" {
					if _, err = f.policyDB.ExecContext(f.ctx, `UPDATE orchestration.delivery_policies SET enabled=false WHERE policy_id=$1`, f.policy); err != nil {
						t.Fatal(err)
					}
					if _, err = f.store.ReserveSliceAgentTask(f.ctx, lease, f.operation, "success"); err == nil {
						t.Fatal("revoked policy dispatched model")
					}
				} else {
					task, err := f.store.ReserveSliceAgentTask(f.ctx, lease, f.operation, "success")
					if err != nil {
						t.Fatal(err)
					}
					_ = runSlicePython(t, task)
					stale := workflow.SliceAdvance{OperationID: f.id(t), Lease: lease, ExpectedRunVersion: task.RunVersion, PolicyEpoch: task.PolicyEpoch, SourceFingerprint: strings.Repeat("f", 64), EvidenceRef: "synthetic:stale-source"}
					if _, err = f.store.AdvanceSyntheticSlice(f.ctx, stale); !errors.Is(err, workflow.ErrTransitionRejected) {
						t.Fatal("stale source advanced")
					}
				}
				f.stop(t, c, lease, state, scenario)
				f.noDelivery(t, r.RunID, state)
				app.finish(t)
				f.peer.finish(t)
				return
			}
			c, r, app := f.toImplementation(t, issue, limits)
			mode := "bad-candidate"
			if scenario == "unknown-mr-response" {
				mode = "success"
			}
			producer, task, result := f.stage(t, c, r.RunID, "IMPLEMENTING", mode)
			if scenario == "unknown-mr-response" {
				f.candidate(t, r.RunID, producer, task, result, "candidate-prepared", "")
				f.pendingImplementation(t, producer)
				f.register(t, producer, "none", "candidate-registered")
				f.advance(t, producer, task, result, f.operation)
				if _, err := f.store.ClaimForRun(f.ctx, r.RunID, workflow.ControllerClaimant, 60); err != nil {
					t.Fatal(err)
				}
				f.submit(t, producer, "bound-authorized")
				f.confirmedDelivery(t, r.RunID, true)
				app.finish(t)
				f.peer.finish(t)
				return
			}
			rejected := f.candidate(t, r.RunID, producer, task, result, "candidate-rejected", "")
			if rejected["failed"].(float64) < 1 || rejected["discovered"] != float64(2) {
				t.Fatal("protected failure not actually observed")
			}
			if scenario == "failed-tests-block" {
				f.stop(t, c, producer, "BLOCKED", scenario)
				f.noDelivery(t, r.RunID, "BLOCKED")
				app.finish(t)
				f.peer.finish(t)
				return
			}
			original := producer
			correction := workflow.SliceAdvance{OperationID: f.id(t), Lease: producer, ExpectedRunVersion: task.RunVersion, PolicyEpoch: c.PolicyEpoch, SourceFingerprint: c.SourceFingerprintSHA256, EvidenceRef: limits.ScopePrefix + ":failed-tests:" + producer.TaskID + ":" + rejected["logsSha256"].(string)}
			corrected, err := f.store.CorrectSyntheticSlice(f.ctx, correction)
			if err != nil {
				t.Fatal("authorized bounded correction failed", err)
			}
			replay, err := f.store.CorrectSyntheticSlice(f.ctx, correction)
			if err != nil || replay.NextAttemptID != corrected.NextAttemptID {
				t.Fatal("correction replay replaced attempt")
			}
			mode = "success"
			if scenario == "correction-budget-exhaustion" {
				mode = "bad-candidate"
			}
			producer, task, result = f.stage(t, c, r.RunID, "IMPLEMENTING", mode)
			if producer.AttemptID == original.AttemptID {
				t.Fatal("correction reused stale producer")
			}
			if scenario == "correction-budget-exhaustion" {
				failedAgain := f.candidate(t, r.RunID, producer, task, result, "candidate-rejected", "")
				exhausted := workflow.SliceAdvance{OperationID: f.id(t), Lease: producer, ExpectedRunVersion: task.RunVersion, PolicyEpoch: c.PolicyEpoch, SourceFingerprint: c.SourceFingerprintSHA256, EvidenceRef: limits.ScopePrefix + ":failed-tests:" + producer.TaskID + ":" + failedAgain["logsSha256"].(string)}
				if _, err = f.store.CorrectSyntheticSlice(f.ctx, exhausted); !errors.Is(err, workflow.ErrTransitionRejected) {
					t.Fatal("semantic correction budget not finite", err)
				}
				f.stop(t, c, producer, "BLOCKED", scenario)
				f.noDelivery(t, r.RunID, "BLOCKED")
			} else if scenario == "stale-candidate" {
				// A previously successful implementation proposal cannot be rebound to the
				// new delivery version after a correction. The issuer resolves latest producer.
				f.candidate(t, r.RunID, producer, task, result, "candidate-prepared", original.AttemptID)
				f.register(t, original, "none", "candidate-denied")
				raw, _ := json.Marshal(result)
				if _, err = f.store.AcceptSliceAgentResult(f.ctx, producer, task, f.id(t), raw, f.operation); err == nil {
					t.Fatal("correction reused old producer seal")
				}
				f.pendingImplementation(t, producer)
				f.stop(t, c, producer, "BLOCKED", scenario)
				f.noDelivery(t, r.RunID, "BLOCKED")
			} else {
				f.candidate(t, r.RunID, producer, task, result, "candidate-prepared", "")
				f.register(t, producer, "none", "candidate-registered")
				f.pendingImplementation(t, producer)
				f.advance(t, producer, task, result, f.operation)
				if _, err = f.store.ClaimForRun(f.ctx, r.RunID, workflow.ControllerClaimant, 60); err != nil {
					t.Fatal(err)
				}
				f.submit(t, producer, "bound-authorized")
				f.confirmedDelivery(t, r.RunID, false)
				snapshot, err := f.store.ObserveSyntheticSlice(f.ctx, r.RunID, limits.ScopePrefix)
				if err != nil || snapshot.Corrections != 1 || snapshot.ModelCallsReserved != 4 || snapshot.ModelTokensReserved != 512 {
					t.Fatal("corrected candidate lost finite accounting")
				}
			}
			app.finish(t)
			f.peer.finish(t)
		})
	}
}

func (f *boundedFixture) register(t *testing.T, producer workflow.TaskLease, fault, want string) {
	t.Helper()
	f.peer.send(t, map[string]any{"action": "register-bound", "producerTaskId": producer.TaskID, "producerLeaseEpoch": producer.LeaseEpoch, "fault": fault})
	f.peer.receive(t, want)
}
func (f *boundedFixture) pendingImplementation(t *testing.T, producer workflow.TaskLease) {
	t.Helper()
	var state, attempt, task string
	var queued int
	err := f.db.QueryRowContext(f.ctx, `SELECT r.state,a.state,t.status,(SELECT count(*) FROM orchestration.task_deliveries v WHERE v.run_id=r.run_id AND v.stage='VERIFYING')
 FROM orchestration.workflow_runs r JOIN orchestration.stage_attempts a ON a.run_id=r.run_id AND a.attempt_id=$2::uuid JOIN orchestration.task_deliveries t ON t.task_id=$3::uuid WHERE r.run_id=$1::uuid`, producer.RunID, producer.AttemptID, producer.TaskID).Scan(&state, &attempt, &task, &queued)
	if err != nil || state != "IMPLEMENTING" || attempt != "RUNNING" || task != "LEASED" || queued != 0 {
		t.Fatal("unsealed implementation committed or verification queued", err)
	}
}
func (f *boundedFixture) handoffSnapshot(t *testing.T, run string) string {
	t.Helper()
	var snapshot string
	err := f.db.QueryRowContext(f.ctx, `SELECT jsonb_build_array(
 (SELECT to_jsonb(r) FROM orchestration.workflow_runs r WHERE run_id=$1::uuid),
 (SELECT jsonb_agg(to_jsonb(a) ORDER BY attempt_id) FROM orchestration.stage_attempts a WHERE run_id=$1::uuid),
 (SELECT jsonb_agg(to_jsonb(t) ORDER BY task_id) FROM orchestration.task_deliveries t WHERE run_id=$1::uuid),
 (SELECT jsonb_agg(to_jsonb(x) ORDER BY operation_id) FROM orchestration.workflow_result_receipts x WHERE run_id=$1::uuid),
 (SELECT jsonb_agg(to_jsonb(x) ORDER BY transition_id) FROM orchestration.workflow_transitions x WHERE run_id=$1::uuid),
 (SELECT jsonb_agg(to_jsonb(x) ORDER BY message_id) FROM orchestration.workflow_outbox x WHERE run_id=$1::uuid))::text`, run).Scan(&snapshot)
	if err != nil {
		t.Fatal("handoff snapshot unavailable", err)
	}
	return snapshot
}
func TestBoundedSliceSealRequiredBeforeImplementationSuccess(t *testing.T) {
	f := newBoundedFixture(t)
	c, r, app := f.toImplementation(t, "17001", sliceLimits())
	producer, task, result := f.stage(t, c, r.RunID, "IMPLEMENTING", "success")
	body, _ := json.Marshal(result)
	baseline := f.handoffSnapshot(t, r.RunID)
	for _, ref := range []string{"", f.id(t)} {
		if _, err := f.store.AcceptSliceAgentResult(f.ctx, producer, task, f.id(t), body, ref); err == nil {
			t.Fatal("missing/unissued seal accepted")
		}
		f.pendingImplementation(t, producer)
		if f.handoffSnapshot(t, r.RunID) != baseline {
			t.Fatal("denied handoff changed atomic bundle")
		}
	}
	f.candidate(t, r.RunID, producer, task, result, "candidate-prepared", "")
	f.pendingImplementation(t, producer)
	for _, fault := range []string{"run", "attempt", "policy", "source", "candidate", "not-stopped", "executor"} {
		f.register(t, producer, fault, "candidate-denied")
		if _, err := f.store.AcceptSliceAgentResult(f.ctx, producer, task, f.id(t), body, f.operation); err == nil {
			t.Fatal("untrusted/mismatched candidate accepted", fault)
		}
		if f.handoffSnapshot(t, r.RunID) != baseline {
			t.Fatal("rejected seal changed workflow", fault)
		}
	}
	f.register(t, producer, "none", "candidate-registered")
	f.pendingImplementation(t, producer)
	for _, fault := range []string{"attempt", "lease", "version", "policy", "source"} {
		command := workflow.SliceAdvance{OperationID: f.id(t), Lease: producer, ExpectedRunVersion: task.RunVersion, PolicyEpoch: task.PolicyEpoch, SourceFingerprint: task.SourceFingerprint, EvidenceRef: "synthetic:proposal:test", SealedCandidateEvidenceID: f.operation}
		switch fault {
		case "attempt":
			command.Lease.AttemptID = f.id(t)
		case "lease":
			command.Lease.LeaseEpoch++
		case "version":
			command.ExpectedRunVersion++
		case "policy":
			command.PolicyEpoch++
		case "source":
			command.SourceFingerprint = strings.Repeat("f", 64)
		}
		if _, err := f.store.AdvanceSyntheticSlice(f.ctx, command); err == nil {
			t.Fatal("mismatched durable seal binding accepted", fault)
		}
		if f.handoffSnapshot(t, r.RunID) != baseline {
			t.Fatal("fenced handoff changed atomic bundle", fault)
		}
	}
	f.advance(t, producer, task, result, f.operation)
	if _, err := f.store.ClaimForRun(f.ctx, r.RunID, workflow.ControllerClaimant, 60); err != nil {
		t.Fatal(err)
	}
	f.submit(t, producer, "bound-authorized")
	f.confirmedDelivery(t, r.RunID, false)
	app.finish(t)
	f.peer.finish(t)
}

func TestBoundedSliceIssuedSealCannotFollowCorrection(t *testing.T) {
	f := newBoundedFixture(t)
	c, r, app := f.toImplementation(t, "17002", sliceLimits())
	original, task, result := f.stage(t, c, r.RunID, "IMPLEMENTING", "success")
	f.candidate(t, r.RunID, original, task, result, "candidate-prepared", "")
	f.register(t, original, "none", "candidate-registered")
	var originalSeal string
	if f.db.QueryRowContext(f.ctx, `SELECT seal_json::text FROM orchestration.controlled_candidate_evidence WHERE evidence_id=$1::uuid`, f.operation).Scan(&originalSeal) != nil {
		t.Fatal("issued seal unavailable")
	}
	// Explicit synthetic REWORK fault decision exercises attempt fencing. This is
	// not a fabricated failed-verification receipt or a live correction approval.
	correction := workflow.SliceAdvance{OperationID: f.id(t), Lease: original, ExpectedRunVersion: task.RunVersion, PolicyEpoch: c.PolicyEpoch, SourceFingerprint: c.SourceFingerprintSHA256, EvidenceRef: sliceLimits().ScopePrefix + ":synthetic-seal-fencing-rework"}
	if _, err := f.store.CorrectSyntheticSlice(f.ctx, correction); err != nil {
		t.Fatal("synthetic correction fault unavailable", err)
	}
	current, currentTask, currentResult := f.stage(t, c, r.RunID, "IMPLEMENTING", "success")
	if current.AttemptID == original.AttemptID {
		t.Fatal("correction did not replace producer attempt")
	}
	baseline := f.handoffSnapshot(t, r.RunID)
	raw, _ := json.Marshal(currentResult)
	if _, err := f.store.AcceptSliceAgentResult(f.ctx, current, currentTask, f.id(t), raw, f.operation); err == nil {
		t.Fatal("trusted but stale issued seal followed correction")
	}
	if f.handoffSnapshot(t, r.RunID) != baseline {
		t.Fatal("stale issued seal changed workflow")
	}
	f.pendingImplementation(t, current)
	var retained string
	if f.db.QueryRowContext(f.ctx, `SELECT seal_json::text FROM orchestration.controlled_candidate_evidence WHERE evidence_id=$1::uuid`, f.operation).Scan(&retained) != nil || retained != originalSeal {
		t.Fatal("stale evidence deleted or rewritten")
	}
	f.stop(t, c, current, "BLOCKED", "stale-issued-seal")
	f.noDelivery(t, r.RunID, "BLOCKED")
	app.finish(t)
	f.peer.finish(t)
}
