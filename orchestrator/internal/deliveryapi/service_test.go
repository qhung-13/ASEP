package deliveryapi

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"github.com/qhung-13/ASEP/orchestrator/internal/workflow"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
)

// Embedding the interface panics on any unintended route invocation. Only the
// exercised entry point is implemented: denies must precede domain access.
type serviceStub struct {
	Runtime
	calls int
}

func (s *serviceStub) ClaimDeliveryIntentFor(context.Context, string) (workflow.DeliveryIntentEnvelope, error) {
	s.calls++
	return workflow.DeliveryIntentEnvelope{}, workflow.ErrNoTask
}
func serviceTLS(identity string) *tls.ConnectionState {
	uri, _ := url.Parse(identity)
	leaf := &x509.Certificate{URIs: []*url.URL{uri}}
	return &tls.ConnectionState{Version: tls.VersionTLS13, PeerCertificates: []*x509.Certificate{leaf}, VerifiedChains: [][]*x509.Certificate{{leaf}}}
}
func TestDeliveryServiceRejectsCrossRoleAndMalformedMessages(t *testing.T) {
	cases := []struct {
		path, identity, body, content string
		status                        int
	}{
		{"/delivery-intents/claim", ControllerIdentity, `{}`, "application/json", 403},
		{"/controlled-admissions", ReaderIdentity, `{}`, "application/json", 403},
		{"/delivery-outcomes", "spiffe://asep.local/agent", `{}`, "application/json", 403},
		{"/delivery-intents/claim", ReaderIdentity, `{"approved":true}`, "application/json", 400},
		{"/delivery-intents/claim", ReaderIdentity, `{"operationId":"a","OperationId":"b"}`, "application/json", 400},
		{"/delivery-intents/claim", ReaderIdentity, `{} {}`, "application/json", 400},
		{"/delivery-intents/claim", ReaderIdentity, strings.Repeat(" ", 8193) + `{}`, "application/json", 400},
		{"/delivery-intents/claim", ReaderIdentity, `{}`, "text/plain", 415},
		{"/delivery-intents/claim?approved=true", ReaderIdentity, `{}`, "application/json", 404},
		{"/delivery-intents/claim", ReaderIdentity, `{}`, "application/json", 204},
	}
	for _, tc := range cases {
		t.Run(tc.path+tc.identity+tc.content, func(t *testing.T) {
			source := &serviceStub{}
			handler, _ := NewService(source, nil)
			r := httptest.NewRequest("POST", "https://internal/internal/v1"+tc.path, strings.NewReader(tc.body))
			r.TLS = serviceTLS(tc.identity)
			r.Header.Set("Content-Type", tc.content)
			w := httptest.NewRecorder()
			handler.ServeHTTP(w, r)
			if w.Code != tc.status || source.calls != map[bool]int{true: 1, false: 0}[tc.status == 204] {
				t.Fatalf("status=%d calls=%d", w.Code, source.calls)
			}
		})
	}
}
func TestProtectedListenerDoesNotStartFromCheckout(t *testing.T) {
	if _, err := loadProtectedSettings(); err == nil {
		t.Fatal("unprovisioned checkout acquired protected service capability")
	}
}
