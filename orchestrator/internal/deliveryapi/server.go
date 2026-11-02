// Package deliveryapi is the separate read-only internal authority contract.
// No dev bearer, agent request flags or synthetic run endpoint authenticates it.
// It is deliberately not enabled by the existing development executable.
package deliveryapi

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"net/http"
	"regexp"
	"time"

	"github.com/qhung-13/ASEP/orchestrator/internal/workflow"
)

const ReaderIdentity = "spiffe://asep.local/code-delivery"

var pathPattern = regexp.MustCompile(`^/internal/v1/delivery-authority/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$`)

type Source interface {
	CurrentDeliveryAuthority(context.Context, string, string, string) (workflow.DeliveryAuthority, error)
}

// TLSConfig must be used on a dedicated internal listener with a protected
// service key and dedicated trust roots. Root/identity provisioning is not done
// here. Client certificates need both a valid chain and exact reader URI SAN.
func TLSConfig(certificate tls.Certificate, clients *x509.CertPool) (*tls.Config, error) {
	if len(certificate.Certificate) == 0 || certificate.PrivateKey == nil || clients == nil || len(clients.Subjects()) == 0 {
		return nil, workflow.ErrDeliveryDenied
	}
	return &tls.Config{MinVersion: tls.VersionTLS13, Certificates: []tls.Certificate{certificate}, ClientAuth: tls.RequireAndVerifyClientCert, ClientCAs: clients}, nil
}
func New(source Source) (http.Handler, error) {
	if source == nil {
		return nil, workflow.ErrDeliveryDenied
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Content-Type", "application/json")
		if !reader(r.TLS) {
			http.Error(w, "authority denied", http.StatusForbidden)
			return
		}
		match := pathPattern.FindStringSubmatch(r.URL.EscapedPath())
		if r.Method != http.MethodGet || len(match) != 4 || r.URL.RawQuery != "" {
			http.Error(w, "authority denied", http.StatusNotFound)
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
		defer cancel()
		value, err := source.CurrentDeliveryAuthority(ctx, match[1], match[2], match[3])
		if err != nil {
			http.Error(w, "authority unavailable or denied", http.StatusConflict)
			return
		}
		// Source contracts never return caller echoes as authorization.
		if value.OperationID != match[1] || value.RunID != match[2] || value.AttemptID != match[3] || !value.DeliveryPolicyApproved {
			http.Error(w, "authority denied", http.StatusConflict)
			return
		}
		_ = json.NewEncoder(w).Encode(value)
	}), nil
}
func reader(state *tls.ConnectionState) bool {
	if state == nil || state.Version < tls.VersionTLS13 || len(state.VerifiedChains) == 0 || len(state.VerifiedChains[0]) == 0 || len(state.PeerCertificates) == 0 {
		return false
	}
	leaf := state.PeerCertificates[0]
	if state.VerifiedChains[0][0] != leaf || len(leaf.URIs) != 1 {
		return false
	}
	return leaf.URIs[0].String() == ReaderIdentity
}
