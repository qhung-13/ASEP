//go:build integration && deliveryauthority

package workflow_test

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"io"
	"io/fs"
	"math/big"
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

type deliveryStatus struct {
	http.ResponseWriter
	code int
}

func (w *deliveryStatus) WriteHeader(code int) { w.code = code; w.ResponseWriter.WriteHeader(code) }

type deliveryPeer struct {
	cmd    *exec.Cmd
	input  io.WriteCloser
	encode *json.Encoder
	decode *json.Decoder
	waited bool
}

func (p *deliveryPeer) send(t *testing.T, value any) {
	t.Helper()
	if p.encode.Encode(value) != nil {
		t.Fatal("private trusted peer input failed")
	}
}
func (p *deliveryPeer) receive(t *testing.T, kind string) map[string]any {
	t.Helper()
	var message map[string]any
	if p.decode.Decode(&message) != nil || message["kind"] != kind {
		t.Fatalf("private trusted peer failed at %s", kind)
	}
	return message
}
func (p *deliveryPeer) action(t *testing.T, action string) map[string]any {
	t.Helper()
	p.send(t, map[string]any{"action": action})
	kind := map[string]string{"execute": "observed", "reconcile": "observed", "publish": "published", "receive": "received", "revoke": "revoked", "await-expiry": "expired"}[action]
	return p.receive(t, kind)
}
func (p *deliveryPeer) finish(t *testing.T) {
	t.Helper()
	_ = p.input.Close()
	if p.cmd.Wait() != nil {
		p.waited = true
		t.Fatal("trusted peer exit failed")
	}
	p.waited = true
}
func deliveryIdentity(t *testing.T, directory, name, identity string) (tls.Certificate, *x509.Certificate) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	uri, _ := url.Parse(identity)
	template := &x509.Certificate{SerialNumber: big.NewInt(time.Now().UnixNano()), Subject: pkix.Name{CommonName: "fixture-" + name}, NotBefore: time.Now().Add(-time.Minute), NotAfter: time.Now().Add(time.Hour), KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth}, URIs: []*url.URL{uri}}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	private, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	certificate := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	encoded := pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: private})
	if os.WriteFile(filepath.Join(directory, name+".crt"), certificate, 0600) != nil || os.WriteFile(filepath.Join(directory, name+".key"), encoded, 0600) != nil {
		t.Fatal("fixture key file unavailable")
	}
	pair, err := tls.X509KeyPair(certificate, encoded)
	clear(private)
	clear(encoded)
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	return pair, parsed
}
func startDeliveryPeer(t *testing.T, config map[string]any) *deliveryPeer {
	t.Helper()
	classpath, err := os.ReadFile(os.Getenv("ASEP_VN_T12_JAVA_CLASSPATH_FILE"))
	if err != nil || len(classpath) == 0 {
		t.Fatal("trusted Java fixture classpath missing")
	}
	ctx, cancel := context.WithTimeout(t.Context(), 90*time.Second)
	t.Cleanup(cancel)
	cmd := exec.CommandContext(ctx, os.Getenv("ASEP_VN_T12_JAVA_BIN"), "-cp", string(classpath), "io.github.qhung13.asep.codedelivery.DeliveryProcessFixture")
	cmd.Env = []string{"LANG=C.UTF-8", "TZ=UTC"}
	cmd.Stderr = os.Stderr // Peer emits only fixed stage names and sanitized exception class, never raw causes.
	cmd.WaitDelay = 2 * time.Second
	input, err := cmd.StdinPipe()
	if err != nil {
		t.Fatal(err)
	}
	output, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	if cmd.Start() != nil {
		t.Fatal("trusted Java peer unavailable")
	}
	peer := &deliveryPeer{cmd: cmd, input: input, encode: json.NewEncoder(input), decode: json.NewDecoder(io.LimitReader(output, 262144))}
	t.Cleanup(func() {
		_ = input.Close()
		if !peer.waited {
			_ = cmd.Process.Kill()
			_ = cmd.Wait()
		}
	})
	peer.send(t, config)
	return peer
}
func TestDeliveryPipelineAcrossActualJavaGoContracts(t *testing.T) {
	for _, scenario := range []string{"success-restart", "missing-grant", "revoked-grant", "lost-push", "lost-mr", "source-drift", "cancel-mr", "callback-ack", "rate-limit", "expired-grant", "stale-policy", "cancel-before", "target-moved", "existing-branch", "wrong-project", "producer-restart"} {
		t.Run(scenario, func(t *testing.T) {
			ctx, cancel := context.WithTimeout(t.Context(), 70*time.Second)
			defer cancel()
			urlValue := testsupport.DatabaseURL(t)
			runtime, err := workflow.Open(ctx, urlValue)
			if err != nil {
				t.Fatal("runtime unavailable")
			}
			defer runtime.Close()
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
			defer issuer.Close()
			policyDB, err := sql.Open("pgx", issuerURL)
			if err != nil {
				t.Fatal(err)
			}
			defer policyDB.Close()
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
			defer server.Close()
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
			// Independent protected fixture policy provisioning, never runtime SQL state seeding.
			var operation, grant string
			if db.QueryRowContext(ctx, `SELECT gen_random_uuid()::text,gen_random_uuid()::text`).Scan(&operation, &grant) != nil {
				t.Fatal("fixture IDs unavailable")
			}
			policy := "pipeline-" + operation
			if _, err = policyDB.ExecContext(ctx, `INSERT INTO orchestration.delivery_policies(policy_id,policy_epoch,project_id,repository_path,target_branch,enabled,expires_at) VALUES($1,1,87786140,'qhung-13/asep-m01-sandbox','main',true,clock_timestamp()+interval '15 minutes')`, policy); err != nil {
				t.Fatal("policy provisioning failed")
			}
			if _, err = policyDB.ExecContext(ctx, `INSERT INTO orchestration.delivery_verification_profiles VALUES($1,1,$2,$3,$4,2,300)`, policy, strings.Repeat("a", 64), ready["harnessSha256"], ready["imageSha256"]); err != nil {
				t.Fatal("profile provisioning failed")
			}
			peer.send(t, map[string]any{"action": "prepare", "operationId": operation, "grantId": grant, "policyId": policy})
			prepared := peer.receive(t, "prepared")
			request := prepared["request"].(map[string]any)
			run := request["runId"].(string)
			if scenario == "producer-restart" {
				before, _ := json.Marshal(request)
				peer.finish(t)
				peer = startDeliveryPeer(t, config)
				peer.receive(t, "ready")
				peer.send(t, map[string]any{"action": "prepare", "operationId": operation, "grantId": grant, "policyId": policy})
				again := peer.receive(t, "prepared")
				after, _ := json.Marshal(again["request"])
				if string(before) != string(after) {
					t.Fatal("producer restart reissued or replaced exact evidence")
				}
			}
			if !peer.action(t, "receive")["received"].(bool) {
				t.Fatal("authenticated intent not delivered")
			}
			if scenario != "missing-grant" {
				peer.send(t, map[string]any{"action": "approve", "seconds": map[bool]int{true: 1, false: 300}[scenario == "expired-grant"]})
				peer.receive(t, "approved")
			}
			if scenario == "expired-grant" {
				peer.action(t, "await-expiry")
			}
			if scenario == "stale-policy" {
				if _, err := policyDB.ExecContext(ctx, `UPDATE orchestration.delivery_policies SET enabled=false WHERE policy_id=$1`, policy); err != nil {
					t.Fatal("policy revocation failed")
				}
			}
			if scenario == "revoked-grant" {
				peer.action(t, "revoke")
			}
			if scenario != "success-restart" && scenario != "missing-grant" && scenario != "revoked-grant" && scenario != "expired-grant" && scenario != "stale-policy" && scenario != "producer-restart" {
				peer.send(t, map[string]any{"action": "fault", "mode": scenario})
				peer.receive(t, "fault-set")
			}
			observed := peer.action(t, "execute")
			if scenario == "lost-push" {
				if observed["mrs"].(float64) != 0 {
					t.Fatal("lost push chained MR")
				}
				peer.send(t, map[string]string{"action": "fault", "mode": "none"})
				peer.receive(t, "fault-set")
				observed = peer.action(t, "execute")
			}
			if scenario == "missing-grant" || scenario == "revoked-grant" || scenario == "expired-grant" || scenario == "stale-policy" || scenario == "cancel-before" || scenario == "target-moved" || scenario == "wrong-project" {
				if !observed["denied"].(bool) || observed["pushes"].(float64) != 0 || observed["mrs"].(float64) != 0 {
					t.Fatal("missing/revoked Owner grant reached writer")
				}
			}
			if scenario == "existing-branch" && (observed["pushes"].(float64) != 0 || observed["mrCreates"].(float64) != 0 || observed["sourceHead"] != ready["baseSha"]) {
				t.Fatal("preexisting source overwritten")
			}
			// Clearing a fault is not permission to retry a previously claimed send.
			if scenario == "rate-limit" || scenario == "source-drift" || scenario == "existing-branch" || scenario == "lost-mr" {
				peer.send(t, map[string]string{"action": "fault", "mode": "none"})
				peer.receive(t, "fault-set")
				replay := peer.action(t, "execute")
				if replay["pushes"] != observed["pushes"] || replay["mrCreates"] != observed["mrCreates"] || replay["mrs"] != observed["mrs"] {
					t.Fatal("replay duplicated a claimed remote mutation")
				}
				if scenario == "source-drift" && (replay["complete"].(bool) || !replay["race"].(bool)) {
					t.Fatal("replay erased source mismatch")
				}
			}
			published := peer.action(t, "publish")
			if scenario == "callback-ack" {
				if !published["unknown"].(bool) {
					t.Fatal("lost callback ACK not exercised")
				}
				peer.action(t, "publish")
			}
			var state string
			if db.QueryRowContext(ctx, `SELECT state FROM orchestration.workflow_runs WHERE run_id=$1::uuid`, run).Scan(&state) != nil {
				t.Fatal("workflow observation failed")
			}
			success := scenario == "producer-restart" || scenario == "success-restart" || scenario == "lost-push" || scenario == "lost-mr" || scenario == "callback-ack"
			if success && state != "COMPLETED_DRAFT_MR" {
				t.Fatalf("real local contract chain did not complete: %s", state)
			}
			if !success && state == "COMPLETED_DRAFT_MR" {
				t.Fatal("failure case falsely completed")
			}
			if scenario == "source-drift" || scenario == "cancel-mr" {
				var race bool
				var ids []byte
				if db.QueryRowContext(ctx, `SELECT residual_race,known_mr_iids FROM orchestration.delivery_observations WHERE operation_id=$1::uuid`, operation).Scan(&race, &ids) != nil || !race || len(ids) < 3 {
					t.Fatal("actual MR effect/race not retained")
				}
			}
			if scenario == "success-restart" {
				peer.finish(t)
				peer = startDeliveryPeer(t, config)
				peer.receive(t, "ready")
				peer.send(t, map[string]any{"action": "resume", "operationId": operation})
				peer.receive(t, "resumed")
				replay := peer.action(t, "reconcile")
				if !replay["complete"].(bool) || replay["pushes"].(float64) != 0 || replay["mrCreates"].(float64) != 0 || replay["mrs"].(float64) != 1 {
					t.Fatal("consumer process restart lost identity or repeated mutation")
				}
				peer.action(t, "publish")
			}
			peer.finish(t)
			t.Log("Actual controller -> independent container -> Go authorization -> JDBC grant -> mTLS intent -> native Git/MR -> durable Go callback; identities are fixture identities, not operational enrollment")
		})
	}
}
