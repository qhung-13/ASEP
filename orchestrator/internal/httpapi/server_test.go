package httpapi

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/qhung-13/ASEP/orchestrator/internal/workflow"
)

type fakeStore struct {
	statuses   int
	claims     int
	heartbeats int
}

func (f *fakeStore) SyntheticRunStatus(_ context.Context, _, _ string) (workflow.RunStatus, error) {
	f.statuses++
	return workflow.RunStatus{RunID: "fake", State: "INVESTIGATING", RunVersion: 1, BudgetLimit: 3}, nil
}
func (f *fakeStore) ClaimForRun(_ context.Context, _, _ string, _ int) (workflow.TaskLease, error) {
	f.claims++
	return workflow.TaskLease{RunID: "fake", LeaseEpoch: 1}, nil
}
func (f *fakeStore) Heartbeat(_ context.Context, _ workflow.TaskLease, _ int) (time.Time, error) {
	f.heartbeats++
	return time.Now(), nil
}

func newTestHandler(t *testing.T, f *fakeStore) (http.Handler, string) {
	t.Helper()
	token := strings.Repeat("c", 64)
	handler, err := New(f, "synthetic:vn-t10-it-"+strings.Repeat("d", 32), token, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatal(err)
	}
	return handler, token
}
func send(h http.Handler, method, path, auth, body string) *httptest.ResponseRecorder {
	request := httptest.NewRequest(method, path, strings.NewReader(body))
	if auth != "" {
		request.Header.Set("Authorization", auth)
	}
	if body != "" {
		request.Header.Set("Content-Type", "application/json")
	}
	recorder := httptest.NewRecorder()
	h.ServeHTTP(recorder, request)
	return recorder
}
func TestSyntheticHandlerAuthAndHealth(t *testing.T) {
	f := &fakeStore{}
	handler, token := newTestHandler(t, f)
	if got := send(handler, "GET", "/healthz", "", "").Code; got != 200 {
		t.Fatalf("health: %d", got)
	}
	if got := send(handler, "GET", "/v1/runs/test", "", "").Code; got != 401 {
		t.Fatalf("missing token: %d", got)
	}
	if got := send(handler, "GET", "/v1/runs/test", "Bearer wrong", "").Code; got != 401 {
		t.Fatalf("wrong token: %d", got)
	}
	if f.statuses != 0 {
		t.Fatal("unauthorized request reached store")
	}
	if got := send(handler, "GET", "/v1/runs/test", "Bearer "+token, "").Code; got != 200 {
		t.Fatalf("valid token: %d", got)
	}
}
func TestSyntheticHandlerRejectsInvalidJSON(t *testing.T) {
	f := &fakeStore{}
	handler, token := newTestHandler(t, f)
	for _, body := range []string{
		`{"run_id":"test","claimant_id":"synthetic-worker","lease_seconds":30,"extra":"bad"}`,
		`{"run_id":"test","claimant_id":"synthetic-worker","lease_seconds":30} {}`,
		`{"run_id":"test","claimant_id":"synthetic-worker","lease_seconds":30} ` + strings.Repeat("X", 2200),
	} {
		if got := send(handler, "POST", "/v1/tasks/claim", "Bearer "+token, body).Code; got != 400 {
			t.Fatalf("invalid JSON accepted: status=%d", got)
		}
	}
	if f.claims != 0 {
		t.Fatal("invalid claim reached store")
	}
}
func TestSyntheticHandlerRejectsNonSyntheticClaimant(t *testing.T) {
	f := &fakeStore{}
	handler, token := newTestHandler(t, f)
	if got := send(handler, "POST", "/v1/tasks/claim", "Bearer "+token,
		`{"run_id":"test","claimant_id":"untrusted-agent","lease_seconds":30}`).Code; got != 400 {
		t.Fatalf("untrusted claimant accepted: %d", got)
	}
	if f.claims != 0 {
		t.Fatal("untrusted claimant reached store")
	}
}
func TestSyntheticHandlerNoResultOrTransitionAPI(t *testing.T) {
	f := &fakeStore{}
	handler, token := newTestHandler(t, f)
	for _, url := range []string{"/v1/tasks/result", "/v1/runs/test/advance", "/v1/tasks/complete"} {
		if got := send(handler, "POST", url, "Bearer "+token, `{"success":true}`).Code; got != 404 {
			t.Fatalf("unexpected success/result API %q: %d", url, got)
		}
	}
}
func TestSyntheticHandlerConfigValidation(t *testing.T) {
	f := &fakeStore{}
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	if _, err := New(f, "synthetic:vn-t10-it-NOT-HEX", strings.Repeat("a", 64), logger); err == nil {
		t.Fatal("bad prefix accepted")
	}
	if _, err := New(f, "synthetic:vn-t10-it-"+strings.Repeat("a", 32), "weak", logger); err == nil {
		t.Fatal("weak token accepted")
	}
}
