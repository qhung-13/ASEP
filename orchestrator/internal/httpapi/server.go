package httpapi

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"mime"
	"net/http"
	"regexp"
	"strings"
	"time"

	"github.com/qhung-13/ASEP/orchestrator/internal/workflow"
)

type Store interface {
	SyntheticRunStatus(context.Context, string, string) (workflow.RunStatus, error)
	ClaimForRun(context.Context, string, string, int) (workflow.TaskLease, error)
	Heartbeat(context.Context, workflow.TaskLease, int) (time.Time, error)
}

type API struct {
	store           Store
	syntheticPrefix string
	tokenDigest     [32]byte
	logger          *slog.Logger
}

var prefixPattern = regexp.MustCompile(`^synthetic:vn-t10-it-[0-9a-f]{32}$`)
var tokenPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)

// New exposes only loopback, synthetic-run-only, token-authenticated development
// endpoints. It is NOT the production Java/Go service authentication boundary.
func New(store Store, syntheticPrefix, token string, logger *slog.Logger) (http.Handler, error) {
	if store == nil || !prefixPattern.MatchString(syntheticPrefix) || !tokenPattern.MatchString(token) || logger == nil {
		return nil, errors.New("invalid dev-only API configuration")
	}
	api := &API{store: store, syntheticPrefix: syntheticPrefix, tokenDigest: sha256.Sum256([]byte(token)), logger: logger}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = io.WriteString(w, `{"status":"ok"}`)
	})
	mux.HandleFunc("GET /v1/runs/{run_id}", api.auth(api.readRun))
	mux.HandleFunc("POST /v1/tasks/claim", api.auth(api.claim))
	mux.HandleFunc("POST /v1/tasks/heartbeat", api.auth(api.heartbeat))
	return api.logRequests(mux), nil
}

func (a *API) auth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		raw := r.Header.Get("Authorization")
		// Compare digests to keep the comparison length fixed. A token is
		// generated outside the repo; a claim from an Agent is NOT authority.
		bearer := strings.TrimPrefix(raw, "Bearer ")
		digest := sha256.Sum256([]byte(bearer))
		if !strings.HasPrefix(raw, "Bearer ") || subtle.ConstantTimeCompare(digest[:], a.tokenDigest[:]) != 1 {
			w.Header().Set("WWW-Authenticate", `Bearer realm="asep-synthetic"`)
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		next(w, r)
	}
}

func respond(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}
func fail(w http.ResponseWriter, status int, message string) {
	respond(w, status, map[string]string{"error": message})
}
func parseJSON(w http.ResponseWriter, r *http.Request, target any) bool {
	contentType, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil || contentType != "application/json" {
		fail(w, 415, "JSON required")
		return false
	}
	r.Body = http.MaxBytesReader(w, r.Body, 2048)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	if err = decoder.Decode(target); err != nil {
		fail(w, 400, "invalid JSON request")
		return false
	}
	var extra any
	if err = decoder.Decode(&extra); err != io.EOF {
		fail(w, 400, "unexpected trailing data")
		return false
	}
	return true
}

func (a *API) scopedStatus(ctx context.Context, runID string) (workflow.RunStatus, error) {
	return a.store.SyntheticRunStatus(ctx, runID, a.syntheticPrefix)
}
func (a *API) readRun(w http.ResponseWriter, r *http.Request) {
	status, err := a.scopedStatus(r.Context(), r.PathValue("run_id"))
	if errors.Is(err, workflow.ErrRunOutsideSyntheticScope) {
		fail(w, 404, "run not found")
		return
	}
	if err != nil {
		fail(w, 503, "status unavailable")
		return
	}
	respond(w, 200, status)
}

type claimRequest struct {
	RunID        string `json:"run_id"`
	ClaimantID   string `json:"claimant_id"`
	LeaseSeconds int    `json:"lease_seconds"`
}

func (a *API) claim(w http.ResponseWriter, r *http.Request) {
	var request claimRequest
	if !parseJSON(w, r, &request) {
		return
	}
	if !strings.HasPrefix(request.ClaimantID, "synthetic-") || request.LeaseSeconds < 1 || request.LeaseSeconds > 300 {
		fail(w, 400, "invalid synthetic claim")
		return
	}
	if _, err := a.scopedStatus(r.Context(), request.RunID); err != nil {
		if errors.Is(err, workflow.ErrRunOutsideSyntheticScope) {
			fail(w, 404, "run not found")
		} else {
			fail(w, 503, "claim unavailable")
		}
		return
	}
	lease, err := a.store.ClaimForRun(r.Context(), request.RunID, request.ClaimantID, request.LeaseSeconds)
	if errors.Is(err, workflow.ErrNoTask) {
		fail(w, 409, "no eligible task")
		return
	}
	if err != nil {
		fail(w, 503, "claim unavailable")
		return
	}
	respond(w, 200, lease)
}

type heartbeatRequest struct {
	Lease        workflow.TaskLease `json:"lease"`
	LeaseSeconds int                `json:"lease_seconds"`
}

func (a *API) heartbeat(w http.ResponseWriter, r *http.Request) {
	var request heartbeatRequest
	if !parseJSON(w, r, &request) {
		return
	}
	if !strings.HasPrefix(request.Lease.ClaimantID, "synthetic-") || request.LeaseSeconds < 1 || request.LeaseSeconds > 300 {
		fail(w, 400, "invalid synthetic heartbeat")
		return
	}
	if _, err := a.scopedStatus(r.Context(), request.Lease.RunID); err != nil {
		if errors.Is(err, workflow.ErrRunOutsideSyntheticScope) {
			fail(w, 404, "run not found")
		} else {
			fail(w, 503, "heartbeat unavailable")
		}
		return
	}
	expires, err := a.store.Heartbeat(r.Context(), request.Lease, request.LeaseSeconds)
	if errors.Is(err, workflow.ErrLeaseLost) {
		fail(w, 409, "lease lost")
		return
	}
	if err != nil {
		fail(w, 503, "heartbeat unavailable")
		return
	}
	respond(w, 200, map[string]any{"lease_expires_at": expires})
}

type responseCapture struct {
	http.ResponseWriter
	status int
}

func (w *responseCapture) WriteHeader(status int) {
	w.status = status
	w.ResponseWriter.WriteHeader(status)
}
func (w *responseCapture) Write(buf []byte) (int, error) {
	if w.status == 0 {
		w.status = http.StatusOK
	}
	return w.ResponseWriter.Write(buf)
}
func safeRoute(path string) string {
	switch {
	case path == "/healthz":
		return "healthz"
	case path == "/v1/tasks/claim":
		return "task_claim"
	case path == "/v1/tasks/heartbeat":
		return "task_heartbeat"
	case strings.HasPrefix(path, "/v1/runs/"):
		return "run_status"
	default:
		return "unmatched"
	}
}
func (a *API) logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		capture := &responseCapture{ResponseWriter: w}
		next.ServeHTTP(capture, r)
		status := capture.status
		if status == 0 {
			status = 200
		}
		// Never log URLs, IDs, body, bearer headers, user-provided values or DB secrets.
		a.logger.Info("http_request", "method", r.Method, "route", safeRoute(r.URL.Path),
			"status", status, "elapsed_ms", time.Since(start).Milliseconds())
	})
}
