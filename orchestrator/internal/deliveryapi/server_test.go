package deliveryapi

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"net/http/httptest"
	"net/url"
	"testing"

	"github.com/qhung-13/ASEP/orchestrator/internal/workflow"
)

type sourceStub struct {
	calls int
	err   error
}

func (s *sourceStub) CurrentDeliveryAuthority(_ context.Context, op, run, attempt string) (workflow.DeliveryAuthority, error) {
	s.calls++
	return workflow.DeliveryAuthority{OperationID: op, RunID: run, AttemptID: attempt, DeliveryPolicyApproved: true}, s.err
}
func TestAuthorityReaderDeniesUnverifiedAndWrongIdentity(t *testing.T) {
	uri, _ := url.Parse(ReaderIdentity)
	wrong, _ := url.Parse("spiffe://asep.local/agent")
	valid := &x509.Certificate{URIs: []*url.URL{uri}}
	bad := &x509.Certificate{URIs: []*url.URL{wrong}}
	for _, state := range []*tls.ConnectionState{nil, {}, {Version: tls.VersionTLS13, PeerCertificates: []*x509.Certificate{valid}}, {Version: tls.VersionTLS12, PeerCertificates: []*x509.Certificate{valid}, VerifiedChains: [][]*x509.Certificate{{valid}}}, {Version: tls.VersionTLS13, PeerCertificates: []*x509.Certificate{bad}, VerifiedChains: [][]*x509.Certificate{{bad}}}} {
		source := &sourceStub{}
		handler, _ := New(source)
		r := httptest.NewRequest("GET", "https://internal/internal/v1/delivery-authority/a/b/c", nil)
		r.TLS = state
		r.Header.Set("Authorization", "Bearer synthetic")
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, r)
		if w.Code != 403 || source.calls != 0 {
			t.Fatal("untrusted identity reached authority")
		}
	}
}
func TestAuthorityReaderExactRouteAndFailClosed(t *testing.T) {
	uri, _ := url.Parse(ReaderIdentity)
	leaf := &x509.Certificate{URIs: []*url.URL{uri}}
	state := &tls.ConnectionState{Version: tls.VersionTLS13, PeerCertificates: []*x509.Certificate{leaf}, VerifiedChains: [][]*x509.Certificate{{leaf}}}
	id := "00000000-0000-4000-8000-000000000001"
	path := "https://internal/internal/v1/delivery-authority/" + id + "/" + id + "/" + id
	for _, tc := range []struct {
		method, suffix string
		failure        bool
		status         int
	}{{"GET", "", false, 200}, {"GET", "?approved=true", false, 404}, {"POST", "", false, 404}, {"GET", "", true, 409}} {
		source := &sourceStub{}
		if tc.failure {
			source.err = workflow.ErrDeliveryDenied
		}
		h, _ := New(source)
		r := httptest.NewRequest(tc.method, path+tc.suffix, nil)
		r.TLS = state
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if w.Code != tc.status || w.Header().Get("Cache-Control") != "no-store" {
			t.Fatalf("unexpected authority result: %d", w.Code)
		}
	}
}
func TestAuthorityTLSRequiresProtectedInputs(t *testing.T) {
	if _, err := TLSConfig(tls.Certificate{}, x509.NewCertPool()); err == nil {
		t.Fatal("empty TLS configuration accepted")
	}
}
