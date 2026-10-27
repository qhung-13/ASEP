//go:build integration

package testsupport

import (
	"bytes"
	"os"
	"os/exec"
	"regexp"
	"strings"
	"testing"
)

func TestHarnessVersionParserDrainsLog(t *testing.T) {
	script, err := os.ReadFile("../../scripts/ci_verify.sh")
	if err != nil {
		t.Fatal("verification runner unavailable")
	}
	match := regexp.MustCompile(`docker_cmd logs "\$flyway_id"[^\n]*python3 -c '([^']+)'`).FindSubmatch(script)
	if len(match) != 2 {
		t.Fatal("actual runner version parser not found")
	}
	// The controlled producer exceeds pipe capacity after the first version.
	// Returning at next(generator) deterministically breaks the writer pipe.
	input := "Flyway OSS Edition synthetic-parser-control\n" + strings.Repeat("synthetic migration detail\n", 65536)
	command := exec.CommandContext(Context(t), "python3", "-c", string(match[1]))
	command.Env = []string{"PYTHONNOUSERSITE=1"}
	var output bytes.Buffer
	command.Stdout = &output
	pipe, err := command.StdinPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err := command.Start(); err != nil {
		t.Fatal("controlled parser could not start")
	}
	written, writeErr := pipe.Write([]byte(input))
	_ = pipe.Close()
	waitErr := command.Wait() // Always reap, including the early-close failure case.
	if writeErr != nil || written != len(input) || waitErr != nil {
		t.Fatal("version parser closed its log input early")
	}
	if output.String() != "Flyway OSS Edition synthetic-parser-control\n" {
		t.Fatal("version parser changed sanitized output")
	}
}
