package deliveryapi

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/qhung-13/ASEP/orchestrator/internal/workflow"
)

const ControllerIdentity = "spiffe://asep.local/candidate-controller"

type Runtime interface {
	Source
	AdmitControlledProbe(context.Context, workflow.ControlledStart) (workflow.ControlledReceipt, error)
	ClaimControlledProbe(context.Context, string) (workflow.TaskLease, error)
	HeartbeatControlledProbe(context.Context, workflow.TaskLease) error
	AdvanceControlledReview(context.Context, string) (workflow.ReviewReceipt, error)
	AuthorizeDelivery(context.Context, string) (workflow.DeliveryAuthority, error)
	CancelDelivery(context.Context, string, int64) error
	ClaimDeliveryIntent(context.Context) (workflow.DeliveryIntentEnvelope, error)
	ClaimDeliveryIntentFor(context.Context, string) (workflow.DeliveryIntentEnvelope, error)
	AcknowledgeDeliveryIntent(context.Context, workflow.DeliveryIntentAck) error
	AcceptDeliveryOutcome(context.Context, workflow.DeliveryOutcomeEnvelope) error
}
type sliceRuntime interface {
	AdvanceSliceReview(context.Context, string) (workflow.ReviewReceipt, error)
}

type Issuer interface {
	SubmitControlledEvidence(context.Context, workflow.ControlledSubmission) error
	IssueDeliveryReview(context.Context, string) error
}

func NewService(source Runtime, issuer Issuer) (http.Handler, error) {
	if source == nil {
		return nil, workflow.ErrDeliveryDenied
	}
	readOnly, _ := New(source)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == "GET" && strings.HasPrefix(r.URL.Path, "/internal/v1/delivery-authority/") {
			readOnly.ServeHTTP(w, r)
			return
		}
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Content-Type", "application/json")
		if r.Method != "POST" || r.URL.RawQuery != "" || r.URL.EscapedPath() != r.URL.Path {
			http.Error(w, "delivery route unavailable", 404)
			return
		}
		controller := strings.HasPrefix(r.URL.Path, "/internal/v1/controlled-")
		identity := ReaderIdentity
		if controller {
			identity = ControllerIdentity
		}
		if !serviceIdentity(r.TLS, identity) || controller && issuer == nil {
			http.Error(w, "delivery identity denied", 403)
			return
		}
		if r.Header.Get("Content-Type") != "application/json" || (r.Header.Get("Content-Encoding") != "" && r.Header.Get("Content-Encoding") != "identity") {
			http.Error(w, "invalid message", 415)
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
		defer cancel()
		decode := func(value any) bool {
			body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 8192))
			return err == nil && strictJSON(body, value)
		}
		var value any = map[string]bool{"accepted": true}
		var err error
		switch r.URL.Path {
		case "/internal/v1/controlled-admissions":
			var command workflow.ControlledStart
			if !decode(&command) {
				http.Error(w, "invalid message", 400)
				return
			}
			value, err = source.AdmitControlledProbe(ctx, command)
		case "/internal/v1/controlled-claim":
			var command struct {
				RunID string `json:"runId"`
			}
			if !decode(&command) {
				http.Error(w, "invalid message", 400)
				return
			}
			var lease workflow.TaskLease
			lease, err = source.ClaimControlledProbe(ctx, command.RunID)
			value = struct {
				RunID         string    `json:"runId"`
				TaskID        string    `json:"taskId"`
				AttemptID     string    `json:"attemptId"`
				Stage         string    `json:"stage"`
				ClaimantID    string    `json:"claimantId"`
				LeaseEpoch    int64     `json:"leaseEpoch"`
				DeliveryCount int       `json:"deliveryCount"`
				ExpiresAt     time.Time `json:"expiresAt"`
			}{lease.RunID, lease.TaskID, lease.AttemptID, lease.Stage, lease.ClaimantID, lease.LeaseEpoch, lease.DeliveryCount, lease.ExpiresAt}
		case "/internal/v1/controlled-heartbeat":
			var lease workflow.TaskLease
			if !decode(&lease) {
				http.Error(w, "invalid message", 400)
				return
			}
			err = source.HeartbeatControlledProbe(ctx, lease)
		case "/internal/v1/controlled-review":
			var command workflow.ControlledSubmission
			if !decode(&command) {
				http.Error(w, "invalid message", 400)
				return
			}
			err = issuer.SubmitControlledEvidence(ctx, command)
			if err == nil {
				_, err = source.AdvanceControlledReview(ctx, command.EvidenceID)
			}
			if err == nil {
				err = issuer.IssueDeliveryReview(ctx, command.EvidenceID)
			}
			if err == nil {
				value, err = source.AuthorizeDelivery(ctx, command.EvidenceID)
			}
		case "/internal/v1/controlled-slice-candidate":
			_, ok := source.(sliceRuntime)
			var command workflow.ControlledSubmission
			if !ok || !decode(&command) || command.Request.RunVersion <= 3 {
				http.Error(w, "synthetic slice unavailable", 400)
				return
			}
			// Registers existing controller evidence only. No task completion,
			// review transition, grant or delivery intent is issued here.
			err = issuer.SubmitControlledEvidence(ctx, command)
		case "/internal/v1/controlled-slice-review":
			runtime, ok := source.(sliceRuntime)
			var command workflow.ControlledSubmission
			if !ok || !decode(&command) {
				http.Error(w, "synthetic slice unavailable", 400)
				return
			}
			err = issuer.SubmitControlledEvidence(ctx, command)
			if err == nil {
				_, err = runtime.AdvanceSliceReview(ctx, command.EvidenceID)
			}
			if err == nil {
				err = issuer.IssueDeliveryReview(ctx, command.EvidenceID)
			}
			if err == nil {
				value, err = source.AuthorizeDelivery(ctx, command.EvidenceID)
			}
		case "/internal/v1/controlled-cancel":
			var command struct {
				RunID           string `json:"runId"`
				ExpectedVersion int64  `json:"expectedRunVersion"`
			}
			if !decode(&command) {
				http.Error(w, "invalid message", 400)
				return
			}
			err = source.CancelDelivery(ctx, command.RunID, command.ExpectedVersion)
		case "/internal/v1/delivery-intents/claim":
			var command struct {
				OperationID string `json:"operationId"`
			}
			if !decode(&command) {
				http.Error(w, "invalid message", 400)
				return
			}
			value, err = source.ClaimDeliveryIntentFor(ctx, command.OperationID)
			if errors.Is(err, workflow.ErrNoTask) {
				w.WriteHeader(204)
				return
			}
		case "/internal/v1/delivery-intents/ack":
			var command workflow.DeliveryIntentAck
			if !decode(&command) {
				http.Error(w, "invalid message", 400)
				return
			}
			err = source.AcknowledgeDeliveryIntent(ctx, command)
		case "/internal/v1/delivery-outcomes":
			var command workflow.DeliveryOutcomeEnvelope
			if !decode(&command) {
				http.Error(w, "invalid message", 400)
				return
			}
			err = source.AcceptDeliveryOutcome(ctx, command)
		default:
			http.Error(w, "delivery route unavailable", 404)
			return
		}
		if err != nil {
			http.Error(w, "delivery unavailable or denied", 409)
			return
		}
		_ = json.NewEncoder(w).Encode(value)
	}), nil
}
func serviceIdentity(state *tls.ConnectionState, identity string) bool {
	if state == nil || state.Version < tls.VersionTLS13 || len(state.VerifiedChains) == 0 || len(state.VerifiedChains[0]) == 0 || len(state.PeerCertificates) == 0 {
		return false
	}
	certificate := state.PeerCertificates[0]
	return state.VerifiedChains[0][0] == certificate && len(certificate.URIs) == 1 && certificate.URIs[0].String() == identity
}
