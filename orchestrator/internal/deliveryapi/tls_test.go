package deliveryapi

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"math/big"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
	"time"
)

func fixtureClient(t *testing.T, identity string) (tls.Certificate, *x509.Certificate) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	uri, _ := url.Parse(identity)
	template := &x509.Certificate{SerialNumber: big.NewInt(time.Now().UnixNano()), Subject: pkix.Name{CommonName: "fixture-only"}, NotBefore: time.Now().Add(-time.Minute), NotAfter: time.Now().Add(time.Hour), ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth}, KeyUsage: x509.KeyUsageDigitalSignature, URIs: []*url.URL{uri}}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	encoded, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	pair, err := tls.X509KeyPair(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: encoded}))
	if err != nil {
		t.Fatal(err)
	}
	certificate, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	return pair, certificate
}
func TestAuthorityRealTLSHandshakeAndCallerIdentity(t *testing.T) {
	good, goodCert := fixtureClient(t, ReaderIdentity)
	wrong, wrongCert := fixtureClient(t, "spiffe://asep.local/agent")
	roots := x509.NewCertPool()
	roots.AddCert(goodCert)
	roots.AddCert(wrongCert)
	source := &sourceStub{}
	handler, _ := New(source)
	server := httptest.NewUnstartedServer(handler)
	// httptest supplies an ephemeral server certificate; this is fixture trust only.
	server.TLS = &tls.Config{MinVersion: tls.VersionTLS13, ClientAuth: tls.RequireAndVerifyClientCert, ClientCAs: roots}
	server.StartTLS()
	defer server.Close()
	trustServer := x509.NewCertPool()
	trustServer.AddCert(server.Certificate())
	id := "00000000-0000-4000-8000-000000000001"
	path := server.URL + "/internal/v1/delivery-authority/" + id + "/" + id + "/" + id
	for _, tc := range []struct {
		name string
		pair []tls.Certificate
		code int
	}{{"missing", nil, 0}, {"wrong-role", []tls.Certificate{wrong}, 403}, {"exact", []tls.Certificate{good}, 200}} {
		t.Run(tc.name, func(t *testing.T) {
			transport := &http.Transport{TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS13, RootCAs: trustServer, Certificates: tc.pair}}
			defer transport.CloseIdleConnections()
			client := &http.Client{Transport: transport, Timeout: time.Second}
			response, err := client.Get(path)
			if tc.code == 0 {
				if err == nil {
					response.Body.Close()
					t.Fatal("unauthenticated TLS accepted")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			defer response.Body.Close()
			if response.StatusCode != tc.code {
				t.Fatalf("wrong authenticated role result: %d", response.StatusCode)
			}
		})
	}
	if source.calls != 1 {
		t.Fatal("untrusted caller read workflow authority")
	}
	configuration, err := TLSConfig(server.TLS.Certificates[0], roots)
	if err != nil || configuration.ClientAuth != tls.RequireAndVerifyClientCert || configuration.MinVersion != tls.VersionTLS13 {
		t.Fatal("dedicated listener TLS policy weakened")
	}
}
