//go:build integration

package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/qhung-13/ASEP/orchestrator/internal/testsupport"
	"github.com/qhung-13/ASEP/orchestrator/internal/workflow"
)

func randomUUID(t *testing.T) string {
	t.Helper()
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		t.Fatal(err)
	}
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}
func makeRequest(t *testing.T, client *http.Client, method, url, token string, body any) (int, []byte) {
	t.Helper()
	var reader io.Reader
	if body != nil {
		payload, err := json.Marshal(body)
		if err != nil {
			t.Fatal(err)
		}
		reader = bytes.NewReader(payload)
	}
	request, err := http.NewRequestWithContext(testsupport.Context(t), method, url, reader)
	if err != nil {
		t.Fatal(err)
	}
	if token != "" {
		request.Header.Set("Authorization", "Bearer "+token)
	}
	if body != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	response, err := client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	result, err := io.ReadAll(io.LimitReader(response.Body, 4096))
	if err != nil {
		t.Fatal(err)
	}
	return response.StatusCode, result
}

type childService struct {
	process  *exec.Cmd
	rootURL  string
	logFile  *os.File
	done     chan struct{}
	stopOnce sync.Once
	cancel   context.CancelFunc
	t        *testing.T
}

func buildChild(t *testing.T) string {
	t.Helper()
	goBin := os.Getenv("ASEP_VN_T10_GO_BIN")
	if goBin == "" {
		t.Fatal("integration Go tool missing")
	}
	root, err := filepath.Abs("../..")
	if err != nil {
		t.Fatal("module directory unavailable")
	}
	binary := filepath.Join(t.TempDir(), "asep-orchestrator")
	ctx, cancel := context.WithTimeout(t.Context(), 60*time.Second)
	defer cancel()
	build := exec.CommandContext(ctx, goBin, "build", "-o", binary, "./cmd/asep-orchestrator")
	build.Dir = root
	build.Env = testsupport.Environment()
	build.WaitDelay = 3 * time.Second
	if err := build.Run(); err != nil {
		t.Fatal("bounded child build failed")
	}
	return binary
}

func startChild(t *testing.T, binary, prefix string) *childService {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal("loopback fixture listener unavailable")
	}
	address := l.Addr().String()
	_ = l.Close()
	logFile, err := os.CreateTemp(t.TempDir(), "server-*.log")
	if err != nil {
		t.Fatal("private child log unavailable")
	}
	ctx, cancel := context.WithTimeout(t.Context(), 90*time.Second)
	process := exec.CommandContext(ctx, binary, "serve")
	process.Env = append(testsupport.Environment(), "ASEP_ORCHESTRATOR_BIND="+address,
		"ASEP_ORCHESTRATOR_ALLOWED_SUBJECT_PREFIX="+prefix,
		"ASEP_ORCHESTRATOR_DATABASE_URL="+os.Getenv("ASEP_ORCHESTRATOR_DATABASE_URL"),
		"ASEP_ORCHESTRATOR_DEV_TOKEN_FILE="+os.Getenv("ASEP_ORCHESTRATOR_DEV_TOKEN_FILE"))
	process.Stdout, process.Stderr = logFile, logFile
	process.WaitDelay = 3 * time.Second
	if err = process.Start(); err != nil {
		cancel()
		_ = logFile.Close()
		t.Fatal("child Go service could not start")
	}
	service := &childService{process: process, rootURL: "http://" + address, logFile: logFile,
		done: make(chan struct{}), cancel: cancel, t: t}
	t.Cleanup(service.kill)
	go func() { _ = process.Wait(); close(service.done) }()
	client := &http.Client{Timeout: 300 * time.Millisecond}
	readyCtx, readyCancel := context.WithTimeout(t.Context(), 5*time.Second)
	defer readyCancel()
	ticker := time.NewTicker(25 * time.Millisecond)
	defer ticker.Stop()
	for {
		request, _ := http.NewRequestWithContext(readyCtx, "GET", service.rootURL+"/healthz", nil)
		response, e := client.Do(request)
		if e == nil {
			_ = response.Body.Close()
			if response.StatusCode == 200 {
				select {
				case <-service.done:
					t.Fatal("child exited during readiness")
				default:
				}
				return service
			}
		}
		select {
		case <-readyCtx.Done():
			t.Fatal("child service readiness deadline exceeded")
		case <-service.done:
			t.Fatal("child service exited before readiness")
		case <-ticker.C:
		}
	}
}
func (s *childService) kill() {
	s.stopOnce.Do(func() {
		_ = s.process.Process.Kill()
		s.cancel()
		select {
		case <-s.done:
		case <-time.After(4 * time.Second):
			s.t.Error("owned child was not reaped within deadline")
		}
		_ = s.logFile.Close()
	})
}
func TestProcessCrashRestartKeepsRunAndLease(t *testing.T) {
	prefix := os.Getenv("ASEP_VN_T10_TEST_PREFIX")
	dbURL := testsupport.DatabaseURL(t)
	tokenPath := os.Getenv("ASEP_ORCHESTRATOR_DEV_TOKEN_FILE")
	goBin := os.Getenv("ASEP_VN_T10_GO_BIN")
	if prefix == "" || dbURL == "" || tokenPath == "" || goBin == "" {
		t.Fatal("integration environment missing")
	}
	rawToken, err := os.ReadFile(tokenPath)
	if err != nil {
		t.Fatal("dev token unreadable")
	}
	token := strings.TrimSpace(string(rawToken))
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	store, err := workflow.Open(ctx, dbURL)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	id := randomUUID(t)
	cmd := workflow.Admission{
		CommandID:  randomUUID(t),
		SubjectKey: prefix + ":process-restart:" + id,
		ActorID:    "synthetic-owner", JiraSiteID: "synthetic-site", JiraProjectID: "synthetic-project",
		JiraIssueID: "synthetic-ticket-" + id, GitLabProjectID: "synthetic-gitlab",
		RepositoryBaseSHA: strings.Repeat("0", 40), TargetBranch: "main", ObservedTargetHeadSHA: strings.Repeat("1", 40),
		SourceFingerprintSHA256: strings.Repeat("b", 64), PolicyID: "synthetic-policy", PolicyEpoch: 1, BudgetLimit: 3,
	}
	receipt, err := store.Admit(testsupport.Context(t), cmd)
	if err != nil {
		t.Fatal(err)
	}
	// Controlled fixture mutation, NOT a production budget-debit path.
	// A nonzero durable counter prevents a false-positive restart test that
	// merely checks default budget_used=0 before and after a crash.
	fixtureDB := testsupport.Database(t)
	if _, err = fixtureDB.ExecContext(testsupport.Context(t),
		`UPDATE orchestration.workflow_runs SET budget_used=1 WHERE run_id=$1::uuid`, receipt.RunID); err != nil {
		t.Fatal(err)
	}
	binary := buildChild(t)

	first := startChild(t, binary, prefix)
	client := &http.Client{Timeout: 3 * time.Second}
	url := first.rootURL + "/v1/runs/" + receipt.RunID
	status, _ := makeRequest(t, client, "GET", url, "", nil)
	if status != 401 {
		first.kill()
		t.Fatalf("unauthenticated status: %d", status)
	}
	status, _ = makeRequest(t, client, "GET", url, "badtoken", nil)
	if status != 401 {
		first.kill()
		t.Fatalf("invalid bearer: %d", status)
	}
	status, body := makeRequest(t, client, "GET", url, token, nil)
	var before workflow.RunStatus
	if status != 200 || json.Unmarshal(body, &before) != nil || before.State != "INVESTIGATING" ||
		before.RunVersion != 1 || before.BudgetUsed != 1 || before.BudgetLimit != 3 || before.TaskCount != 1 || before.AttemptCount != 1 {
		first.kill()
		t.Fatalf("initial run status incorrect: code=%d body=%s", status, body)
	}
	status, _ = makeRequest(t, client, "POST", first.rootURL+"/v1/tasks/claim", "", map[string]any{
		"run_id": receipt.RunID, "claimant_id": "synthetic-runtime-worker", "lease_seconds": 60,
	})
	if status != 401 {
		first.kill()
		t.Fatalf("unauthorized claim: %d", status)
	}
	status, body = makeRequest(t, client, "POST", first.rootURL+"/v1/tasks/claim", token, map[string]any{
		"run_id": receipt.RunID, "claimant_id": "synthetic-runtime-worker", "lease_seconds": 60,
	})
	var lease workflow.TaskLease
	if status != 200 || json.Unmarshal(body, &lease) != nil || lease.LeaseEpoch != 1 || lease.TaskID != receipt.TaskID || lease.Stage != "INVESTIGATING" {
		first.kill()
		t.Fatalf("claim failed: status=%d body=%s", status, body)
	}
	// Hard-kill process, no graceful shutdown. State must remain durable.
	first.kill()
	second := startChild(t, binary, prefix)
	defer second.kill()
	url = second.rootURL + "/v1/runs/" + receipt.RunID
	status, body = makeRequest(t, client, "GET", url, token, nil)
	var after workflow.RunStatus
	if status != 200 || json.Unmarshal(body, &after) != nil || after.State != "INVESTIGATING" ||
		after.RunVersion != 1 || after.BudgetUsed != 1 || after.BudgetLimit != 3 || after.MaxLeaseEpoch != 1 || after.TaskCount != 1 {
		t.Fatalf("restart durability failed: status=%d body=%s", status, body)
	}
	fake := lease
	fake.Stage = "PLANNING"
	status, _ = makeRequest(t, client, "POST", second.rootURL+"/v1/tasks/heartbeat", token, map[string]any{
		"lease": fake, "lease_seconds": 60,
	})
	if status != 409 {
		t.Fatalf("tampered lease stage heartbeat accepted: %d", status)
	}
	status, body = makeRequest(t, client, "POST", second.rootURL+"/v1/tasks/heartbeat", token, map[string]any{
		"lease": lease, "lease_seconds": 60,
	})
	if status != 200 {
		t.Fatalf("durable heartbeat after restart rejected: %d %s", status, body)
	}
	status, body = makeRequest(t, client, "GET", url, token, nil)
	if status != 200 || json.Unmarshal(body, &after) != nil || after.MaxLeaseEpoch != 1 || after.BudgetUsed != 1 {
		t.Fatalf("heartbeat mutated authority or budget: %d %s", status, body)
	}
}
