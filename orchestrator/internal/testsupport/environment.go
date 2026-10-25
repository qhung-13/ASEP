//go:build integration

package testsupport

import "os"

// Environment passes toolchain settings, never arbitrary host/service secrets.
// API and synthetic worker fixtures add only their explicit scoped capabilities.
func Environment() []string {
	var result []string
	for _, key := range []string{"PATH", "HOME", "TMPDIR", "GOROOT", "GOPATH", "GOCACHE", "GOMODCACHE", "GOTOOLCHAIN", "GOPROXY", "GOSUMDB", "CGO_ENABLED", "CC"} {
		if value, exists := os.LookupEnv(key); exists {
			result = append(result, key+"="+value)
		}
	}
	return result
}
