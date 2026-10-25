//go:build integration

package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"

	"github.com/qhung-13/ASEP/orchestrator/internal/testsupport"
	"github.com/qhung-13/ASEP/orchestrator/internal/workflow"
)

// The child is this trusted test binary acting only as an HTTP client, not a
// production worker or agent runtime. It receives no DB URL or Docker access.
func TestMain(m *testing.M) {
	if os.Getenv("ASEP_VN_T11_WORKER_CHILD") == "true" {
		os.Exit(runSyntheticWorker())
	}
	os.Exit(m.Run())
}

type workerConfiguration struct {
	APIURL, RunID, ClaimantID, TokenFile string
}

func runSyntheticWorker() int {
	// An inherited environment flag cannot initiate API mutations. Configuration
	// must arrive on the private pipe from the parent that verified the DB proof.
	input, err := os.Stdin.Stat()
	if err != nil || input.Mode()&os.ModeNamedPipe == 0 {
		return 2
	}
	var config workerConfiguration
	if json.NewDecoder(io.LimitReader(os.Stdin, 4096)).Decode(&config) != nil {
		return 2
	}
	root, err := url.Parse(config.APIURL)
	if err != nil || root.Scheme != "http" || root.Hostname() != "127.0.0.1" || root.Port() == "" ||
		root.User != nil || root.Path != "" || root.RawQuery != "" || root.Fragment != "" ||
		os.Getenv("ASEP_ORCHESTRATOR_DATABASE_URL") != "" {
		return 2
	}
	token, err := readPrivateDevToken(config.TokenFile)
	if err != nil {
		return 2
	}
	body, err := json.Marshal(map[string]any{
		"run_id":      config.RunID,
		"claimant_id": config.ClaimantID, "lease_seconds": 3,
	})
	if err != nil {
		return 2
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	request, err := http.NewRequestWithContext(ctx, "POST", root.String()+"/v1/tasks/claim", strings.NewReader(string(body)))
	if err != nil {
		return 2
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Authorization", "Bearer "+token)
	client := &http.Client{Timeout: 4 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	response, err := client.Do(request)
	if err != nil {
		return 3
	}
	defer response.Body.Close()
	var lease workflow.TaskLease
	if response.StatusCode != 200 || json.NewDecoder(io.LimitReader(response.Body, 4096)).Decode(&lease) != nil {
		return 3
	}
	// This goes to a private parent pipe, never to test logs. A 200 response is
	// sent by the real API only after ClaimForRun's transaction has committed.
	if json.NewEncoder(os.Stdout).Encode(lease) != nil {
		return 3
	}
	_, _ = io.Copy(io.Discard, os.Stdin) // Parent keeps this pipe open until kill.
	return 0
}

type workerChild struct {
	process  *exec.Cmd
	lease    workflow.TaskLease
	done     chan struct{}
	waitErr  error
	stopOnce sync.Once
	cancel   context.CancelFunc
	control  io.WriteCloser
	logFile  *os.File
	t        *testing.T
}

func startWorker(t *testing.T, apiURL, runID, name string) *workerChild {
	t.Helper()
	ctx, cancel := context.WithTimeout(t.Context(), 30*time.Second)
	command := exec.CommandContext(ctx, os.Args[0], "-test.run=^$")
	command.Env = []string{"PATH=" + os.Getenv("PATH"), "ASEP_VN_T11_WORKER_CHILD=true"}
	command.WaitDelay = 3 * time.Second
	control, err := command.StdinPipe()
	if err != nil {
		cancel()
		t.Fatal("worker control pipe unavailable")
	}
	output, err := command.StdoutPipe()
	if err != nil {
		cancel()
		_ = control.Close()
		t.Fatal("worker acknowledgement pipe unavailable")
	}
	logFile, err := os.CreateTemp(t.TempDir(), "worker-*.log")
	if err != nil {
		cancel()
		_ = control.Close()
		_ = output.Close()
		t.Fatal("worker private log unavailable")
	}
	command.Stderr = logFile
	if err := command.Start(); err != nil {
		cancel()
		_ = control.Close()
		_ = output.Close()
		_ = logFile.Close()
		t.Fatal("synthetic worker could not start")
	}
	worker := &workerChild{process: command, done: make(chan struct{}), cancel: cancel, control: control, logFile: logFile, t: t}
	t.Cleanup(worker.kill)
	go func() { worker.waitErr = command.Wait(); close(worker.done) }()
	if err := json.NewEncoder(control).Encode(workerConfiguration{apiURL, runID, name, os.Getenv("ASEP_ORCHESTRATOR_DEV_TOKEN_FILE")}); err != nil {
		t.Fatal("worker private configuration handoff failed")
	}
	type acknowledgement struct {
		lease workflow.TaskLease
		err   error
	}
	ack := make(chan acknowledgement, 1)
	go func() {
		var lease workflow.TaskLease
		err := json.NewDecoder(io.LimitReader(output, 4096)).Decode(&lease)
		ack <- acknowledgement{lease, err}
	}()
	select {
	case received := <-ack:
		if received.err != nil || received.lease.RunID != runID || received.lease.ClaimantID != name {
			t.Fatal("worker failed to acknowledge a committed scoped claim")
		}
		worker.lease = received.lease
	case <-time.After(8 * time.Second):
		t.Fatal("worker claim acknowledgement deadline exceeded")
	}
	return worker
}

func (w *workerChild) kill() {
	w.stopOnce.Do(func() {
		_ = w.process.Process.Kill()
		w.cancel()
		_ = w.control.Close()
		select {
		case <-w.done:
		case <-time.After(4 * time.Second):
			w.t.Error("owned worker was not reaped within deadline")
		}
		_ = w.logFile.Close()
	})
}

type workerSnapshot struct {
	state, taskState, attemptState, claimant string
	version, epoch                           int64
	used, limit, deliveries, semanticAttempt int
	tasks, attempts, transitions, outbox     int
	expires                                  time.Time
}

func workerState(t *testing.T, db *sql.DB, receipt workflow.AdmissionReceipt) workerSnapshot {
	t.Helper()
	var z workerSnapshot
	err := db.QueryRowContext(testsupport.Context(t), `
        SELECT r.state,r.run_version,r.budget_used,r.budget_limit,
          t.status,t.claimant_id,t.lease_epoch,t.delivery_count,t.lease_expires_at,a.state,a.semantic_attempt_no,
          (SELECT count(*) FROM orchestration.task_deliveries WHERE run_id=r.run_id),
          (SELECT count(*) FROM orchestration.stage_attempts WHERE run_id=r.run_id),
          (SELECT count(*) FROM orchestration.workflow_transitions WHERE run_id=r.run_id),
          (SELECT count(*) FROM orchestration.workflow_outbox WHERE run_id=r.run_id)
        FROM orchestration.workflow_runs r
        JOIN orchestration.task_deliveries t ON t.run_id=r.run_id AND t.task_id=$2::uuid
        JOIN orchestration.stage_attempts a ON a.run_id=r.run_id AND a.attempt_id=$3::uuid
        WHERE r.run_id=$1::uuid`, receipt.RunID, receipt.TaskID, receipt.AttemptID).Scan(
		&z.state, &z.version, &z.used, &z.limit, &z.taskState, &z.claimant, &z.epoch, &z.deliveries, &z.expires,
		&z.attemptState, &z.semanticAttempt, &z.tasks, &z.attempts, &z.transitions, &z.outbox)
	if err != nil {
		t.Fatal("worker persisted-state snapshot unavailable")
	}
	return z
}

func TestWorkerProcessCrashReclaimsSameTask(t *testing.T) {
	dbURL := testsupport.DatabaseURL(t)
	db := testsupport.Database(t)
	store, err := workflow.Open(testsupport.Context(t), dbURL)
	if err != nil {
		t.Fatal("verified disposable store unavailable")
	}
	t.Cleanup(func() { _ = store.Close() })
	prefix := os.Getenv("ASEP_VN_T10_TEST_PREFIX")
	id := randomUUID(t)
	command := workflow.Admission{
		CommandID: randomUUID(t), SubjectKey: prefix + ":worker-crash:" + id,
		ActorID: "synthetic-owner", JiraSiteID: "synthetic-site", JiraProjectID: "synthetic-project",
		JiraIssueID: "synthetic-ticket-" + id, GitLabProjectID: "synthetic-gitlab",
		RepositoryBaseSHA: strings.Repeat("0", 40), TargetBranch: "main", ObservedTargetHeadSHA: strings.Repeat("1", 40),
		SourceFingerprintSHA256: strings.Repeat("b", 64), PolicyID: "synthetic-policy", PolicyEpoch: 1, BudgetLimit: 3,
	}
	receipt, err := store.Admit(testsupport.Context(t), command)
	if err != nil {
		t.Fatal("synthetic worker fixture admission failed")
	}
	api := startChild(t, buildChild(t), prefix)
	first := startWorker(t, api.rootURL, receipt.RunID, "synthetic-crash-worker-a")
	before := workerState(t, db, receipt)
	if first.lease.TaskID != receipt.TaskID || first.lease.AttemptID != receipt.AttemptID || before.epoch != 1 || before.deliveries != 1 || before.claimant != first.lease.ClaimantID {
		t.Fatal("RF-04: initial worker did not persist the expected claim")
	}
	first.kill()
	exit, ok := first.waitErr.(*exec.ExitError)
	if !ok {
		t.Fatal("RF-04: worker did not exit from hard termination")
	}
	status, ok := exit.Sys().(syscall.WaitStatus)
	if !ok || !status.Signaled() || status.Signal() != syscall.SIGKILL {
		t.Fatal("RF-04: expected observed SIGKILL termination")
	}
	if after := workerState(t, db, receipt); after != before {
		t.Fatal("RF-04: worker crash changed durable state")
	}
	select {
	case <-api.done:
		t.Fatal("RF-04: API service died instead of the worker")
	default:
	}
	t.Log("RF-04 checkpoint: committed claim acknowledged over pipe; worker A SIGKILL observed and reaped; API remains alive; DB snapshot unchanged")
	testsupport.WaitForLeaseExpiry(t, db, receipt.TaskID)
	token, err := readPrivateDevToken(os.Getenv("ASEP_ORCHESTRATOR_DEV_TOKEN_FILE"))
	if err != nil {
		t.Fatal("synthetic API credential unavailable")
	}
	client := &http.Client{Timeout: 3 * time.Second}
	code, _ := makeRequest(t, client, "POST", api.rootURL+"/v1/tasks/heartbeat", token,
		map[string]any{"lease": first.lease, "lease_seconds": 3})
	if code != 409 || workerState(t, db, receipt) != before {
		t.Fatal("RF-04/05: expired worker heartbeat changed state before reclaim")
	}
	second := startWorker(t, api.rootURL, receipt.RunID, "synthetic-crash-worker-b")
	after := workerState(t, db, receipt)
	if second.lease.TaskID != receipt.TaskID || second.lease.AttemptID != receipt.AttemptID ||
		after.epoch != before.epoch+1 || after.deliveries != before.deliveries+1 ||
		after.epoch != second.lease.LeaseEpoch || after.claimant != second.lease.ClaimantID ||
		after.state != "INVESTIGATING" || after.version != 1 || after.taskState != "LEASED" ||
		after.attemptState != "RUNNING" || after.semanticAttempt != 1 || after.used != before.used || after.limit != before.limit ||
		after.tasks != 1 || after.attempts != 1 || after.transitions != 1 || after.outbox != 1 {
		t.Fatalf("RF-04: incorrect recovered durable state: %+v", after)
	}
	code, _ = makeRequest(t, client, "POST", api.rootURL+"/v1/tasks/heartbeat", token,
		map[string]any{"lease": first.lease, "lease_seconds": 3})
	if code != 409 || workerState(t, db, receipt) != after {
		t.Fatal("RF-04/05: old worker regained authority after reclaim")
	}
	t.Logf("RF-04 observed: same task/attempt; epoch=%d deliveries=%d semantic_attempt=%d budget=%d run_version=%d tasks=%d attempts=%d transitions=%d outbox=%d; no false completion", after.epoch, after.deliveries, after.semanticAttempt, after.used, after.version, after.tasks, after.attempts, after.transitions, after.outbox)
}
