package deliveryapi

import (
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/qhung-13/ASEP/orchestrator/internal/workflow"
)

var errDeployment = errors.New("protected delivery authority deployment unavailable")

type protectedSettings struct {
	Bind                    string `json:"bind"`
	ServiceUID              uint32 `json:"serviceUid"`
	AgentUID                uint32 `json:"agentUid"`
	ControllerUID           uint32 `json:"controllerUid"`
	ExecutorUID             uint32 `json:"executorUid"`
	OperatorUID             uint32 `json:"operatorUid"`
	BinarySHA256            string `json:"binarySha256"`
	ServerCertificate       string `json:"serverCertificate"`
	ServerCertificateSHA256 string `json:"serverCertificateSha256"`
	ServerKey               string `json:"serverKey"`
	ClientCA                string `json:"clientCa"`
	ClientCASHA256          string `json:"clientCaSha256"`
	DatabaseCA              string `json:"databaseCa"`
	RuntimeDSNFile          string `json:"runtimeDsnFile"`
	IssuerDSNFile           string `json:"issuerDsnFile"`
}

func privateFile(path string, owner uint32, secret bool, limit int64) ([]byte, error) {
	if !filepath.IsAbs(path) || filepath.Clean(path) != path {
		return nil, errDeployment
	}
	for parent := filepath.Dir(path); ; parent = filepath.Dir(parent) {
		info, err := os.Lstat(parent)
		if err != nil || !info.IsDir() || info.Mode().Perm()&0022 != 0 {
			return nil, errDeployment
		}
		stat, ok := info.Sys().(*syscall.Stat_t)
		if !ok || stat.Uid != 0 && stat.Uid != owner {
			return nil, errDeployment
		}
		if parent == "/" {
			break
		}
	}
	info, err := os.Lstat(path)
	if err != nil || !info.Mode().IsRegular() || info.Size() > limit {
		return nil, errDeployment
	}
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok || stat.Nlink != 1 || stat.Uid != 0 && stat.Uid != owner || info.Mode().Perm()&0022 != 0 || secret && info.Mode().Perm()&0077 != 0 {
		return nil, errDeployment
	}
	fd, err := syscall.Open(path, syscall.O_RDONLY|syscall.O_NOFOLLOW|syscall.O_CLOEXEC, 0)
	if err != nil {
		return nil, errDeployment
	}
	file := os.NewFile(uintptr(fd), "protected-service-file")
	defer file.Close()
	opened, err := file.Stat()
	if err != nil || !os.SameFile(info, opened) {
		return nil, errDeployment
	}
	body, err := io.ReadAll(io.LimitReader(file, limit+1))
	if err != nil || int64(len(body)) > limit {
		return nil, errDeployment
	}
	return body, nil
}
func hashMatches(bytes []byte, expected string) bool {
	digest := sha256.Sum256(bytes)
	return len(expected) == 64 && hex.EncodeToString(digest[:]) == expected
}
func loadProtectedSettings() (protectedSettings, error) {
	var settings protectedSettings
	body, err := privateFile("/etc/asep/delivery-authority.json", 0, false, 8192)
	if err != nil {
		return settings, errDeployment
	}
	if !strictJSON(body, &settings) {
		return settings, errDeployment
	}
	seen := map[uint32]bool{}
	for _, uid := range []uint32{settings.ServiceUID, settings.AgentUID, settings.ControllerUID, settings.ExecutorUID, settings.OperatorUID} {
		if uid == 0 || seen[uid] {
			return settings, errDeployment
		}
		seen[uid] = true
	}
	if uint32(os.Getuid()) != settings.ServiceUID || uint32(os.Geteuid()) != settings.ServiceUID {
		return settings, errDeployment
	}
	host, port, err := net.SplitHostPort(settings.Bind)
	number, _ := strconv.Atoi(port)
	if err != nil || host != "127.0.0.1" || number < 1 || number > 65535 {
		return settings, errDeployment
	}
	executable, err := os.Executable()
	if err != nil || executable != "/opt/asep/delivery/bin/asep-orchestrator" {
		return settings, errDeployment
	}
	binary, err := privateFile(executable, 0, false, 67108864)
	if err != nil || !hashMatches(binary, settings.BinarySHA256) {
		return settings, errDeployment
	}
	return settings, nil
}
func databaseURL(path, role string, c protectedSettings) (string, error) {
	body, err := privateFile(path, c.ServiceUID, true, 4096)
	if err != nil {
		return "", errDeployment
	}
	defer clear(body)
	value := strings.TrimSpace(string(body))
	uri, err := url.Parse(value)
	if err != nil || uri.Scheme != "postgres" || uri.Hostname() != "127.0.0.1" || uri.User == nil || uri.User.Username() != role || uri.Fragment != "" || uri.RawPath != "" || !regexp.MustCompile(`^/[a-z][a-z0-9_]{0,62}$`).MatchString(uri.Path) {
		return "", errDeployment
	}
	password, exists := uri.User.Password()
	if !exists || password == "" {
		return "", errDeployment
	}
	port, err := strconv.Atoi(uri.Port())
	if err != nil || port < 1 || port > 65535 {
		return "", errDeployment
	}
	parameters := uri.Query()
	for key, values := range parameters {
		if len(values) != 1 {
			return "", errDeployment
		}
		if key != "sslmode" && key != "sslrootcert" && key != "connect_timeout" {
			return "", errDeployment
		}
	}
	if parameters.Get("sslmode") != "verify-full" || parameters.Get("sslrootcert") != c.DatabaseCA {
		return "", errDeployment
	}
	parameters.Set("connect_timeout", "3")
	uri.RawQuery = parameters.Encode()
	return uri.String(), nil
}

// RunProtected is an explicit opt-in command, never the existing synthetic server.
// It cannot start from the agent checkout or default/missing configuration.
func RunProtected(ctx context.Context) error {
	ctx, stop := context.WithCancel(ctx)
	defer stop()
	c, err := loadProtectedSettings()
	if err != nil {
		return errDeployment
	}
	certificate, err := privateFile(c.ServerCertificate, 0, false, 65536)
	if err != nil || !hashMatches(certificate, c.ServerCertificateSHA256) {
		return errDeployment
	}
	key, err := privateFile(c.ServerKey, c.ServiceUID, true, 16384)
	if err != nil {
		return errDeployment
	}
	defer clear(key)
	ca, err := privateFile(c.ClientCA, 0, false, 65536)
	if err != nil || !hashMatches(ca, c.ClientCASHA256) {
		return errDeployment
	}
	if _, err = privateFile(c.DatabaseCA, 0, false, 65536); err != nil {
		return errDeployment
	}
	pair, err := tls.X509KeyPair(certificate, key)
	clear(key)
	if err != nil {
		return errDeployment
	}
	clients := x509.NewCertPool()
	if !clients.AppendCertsFromPEM(ca) {
		return errDeployment
	}
	configuration, err := TLSConfig(pair, clients)
	if err != nil {
		return errDeployment
	}
	runtimeURL, err := databaseURL(c.RuntimeDSNFile, "asep_orch_runtime", c)
	if err != nil {
		return errDeployment
	}
	issuerURL, err := databaseURL(c.IssuerDSNFile, "asep_orch_authority", c)
	if err != nil {
		return errDeployment
	}
	startup, cancel := context.WithTimeout(ctx, 8*time.Second)
	defer cancel()
	store, err := workflow.Open(startup, runtimeURL)
	if err != nil {
		return errDeployment
	}
	defer store.Close()
	if store.ValidateDeliveryRuntime(startup) != nil {
		return errDeployment
	}
	issuer, err := workflow.OpenReviewIssuer(startup, issuerURL)
	if err != nil {
		return errDeployment
	}
	defer issuer.Close()
	handler, err := NewService(store, issuer)
	if err != nil {
		return errDeployment
	}
	listener, err := net.Listen("tcp4", c.Bind)
	if err != nil {
		return errDeployment
	}
	defer listener.Close()
	server := &http.Server{Handler: handler, TLSConfig: configuration, ReadHeaderTimeout: 2 * time.Second, ReadTimeout: 4 * time.Second, WriteTimeout: 4 * time.Second, IdleTimeout: 15 * time.Second, MaxHeaderBytes: 8192}
	go func() {
		<-ctx.Done()
		shutdown, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = server.Shutdown(shutdown)
	}()
	err = server.Serve(tls.NewListener(listener, configuration))
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return errDeployment
}
