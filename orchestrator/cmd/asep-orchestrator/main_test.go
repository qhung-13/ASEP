package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestRefusesNonLoopbackBind(t *testing.T) {
	for _, address := range []string{"0.0.0.0:8080", "192.0.2.1:8080", ":8080", "127.0.0.1:0"} {
		t.Run(address, func(t *testing.T) {
			t.Setenv("ASEP_ORCHESTRATOR_BIND", address)
			if err := serve(); err == nil {
				t.Fatal("unsafe listener configuration accepted")
			}
		})
	}
}
func TestDevTokenRejectsSymlinkAndLoosePermissions(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "token")
	if err := os.WriteFile(path, []byte(strings.Repeat("a", 64)+"\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := readPrivateDevToken(path); err == nil {
		t.Fatal("world-readable token accepted")
	}
	if err := os.Chmod(path, 0o600); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(dir, "alias")
	if err := os.Symlink(path, link); err != nil {
		t.Fatal(err)
	}
	if _, err := readPrivateDevToken(link); err == nil {
		t.Fatal("symlink token accepted")
	}
}
func TestPrivateDevTokenIsAccepted(t *testing.T) {
	path := filepath.Join(t.TempDir(), "token")
	if err := os.WriteFile(path, []byte(strings.Repeat("b", 64)+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	value, err := readPrivateDevToken(path)
	if err != nil || value != strings.Repeat("b", 64) {
		t.Fatalf("owner-only token rejected: %v", err)
	}
}
