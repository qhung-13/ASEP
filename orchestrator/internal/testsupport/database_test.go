//go:build integration

package testsupport

import (
	"database/sql"
	"errors"
	"net/url"
	"os"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5/pgconn"
)

func TestDisposableProofRejectsUnsafeTargetsBeforeMutation(t *testing.T) {
	// The negative fixtures are siblings on the already verified disposable
	// instance. They never use the persistent Compose database as a target.
	verified := Database(t)
	databaseURL := os.Getenv("ASEP_ORCHESTRATOR_DATABASE_URL")
	nonce := os.Getenv("ASEP_VN_T11_DISPOSABLE_NONCE")
	unmarked, err := url.Parse(databaseURL)
	if err != nil {
		t.Fatal("invalid disposable fixture URL")
	}
	unmarked.Path = strings.Replace(unmarked.Path, "/asep_vnt11_", "/asep_vnt11_unmarked_", 1)
	cases := []struct{ name, url, nonce string }{
		{"missing-url", "", nonce},
		{"missing-nonce", databaseURL, ""},
		{"mismatched-nonce", databaseURL, strings.Repeat("0", 64)},
		{"unmarked-database", unmarked.String(), nonce},
		{"unreadable-marker", os.Getenv("ASEP_VN_T11_UNREADABLE_DATABASE_URL"), nonce},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			db, err := Connect(Context(t), tc.url, tc.nonce)
			if err == nil {
				// Only disposable fixtures have this canary. A broken guard is
				// observable as a persisted write, not merely an unexpected error.
				_, _ = db.ExecContext(Context(t), "INSERT INTO public.guard_mutation_probe DEFAULT VALUES")
				_ = db.Close()
				t.Fatal("unsafe target passed the guard")
			}
			if db != nil || !errors.Is(err, ErrUnsafeDatabase) {
				t.Fatal("guard must return no connection and a sanitized rejection")
			}
			t.Log("B0: target rejected before mutation")
		})
	}
	t.Run("missing-opt-in", func(t *testing.T) {
		t.Setenv("ASEP_VN_T10_DISPOSABLE_DB", "")
		if db, err := Connect(Context(t), databaseURL, nonce); db != nil || !errors.Is(err, ErrUnsafeDatabase) {
			t.Fatal("missing explicit opt-in was accepted")
		}
	})
	var writes int
	if err := verified.QueryRowContext(Context(t), "SELECT count(*) FROM public.guard_mutation_probe").Scan(&writes); err != nil || writes != 0 {
		t.Fatal("negative guard checks changed the disposable mutation canary")
	}
	// Verify the unmarked sibling's canary independently, using a read only.
	sibling, err := sql.Open("pgx", unmarked.String())
	if err != nil {
		t.Fatal("unmarked disposable fixture unavailable")
	}
	defer sibling.Close()
	if err := sibling.QueryRowContext(Context(t), "SELECT count(*) FROM public.guard_mutation_probe").Scan(&writes); err != nil || writes != 0 {
		t.Fatal("unmarked disposable fixture was mutated")
	}
	t.Log("B0 observed: marked and unmarked mutation probes both contain 0 rows")
}

func TestDisposableProofRuntimeCannotModifyMarker(t *testing.T) {
	db := Database(t)
	for _, statement := range []string{
		"UPDATE asep_test_harness.instance SET nonce=nonce WHERE false",
		"DELETE FROM asep_test_harness.instance WHERE false",
		"INSERT INTO asep_test_harness.instance SELECT * FROM asep_test_harness.instance WHERE false",
	} {
		_, err := db.ExecContext(Context(t), statement)
		var pgErr *pgconn.PgError
		if !errors.As(err, &pgErr) || pgErr.Code != "42501" {
			t.Fatal("runtime marker mutation did not fail with insufficient_privilege")
		}
	}
	t.Log("B0 observed: runtime marker INSERT/UPDATE/DELETE all denied")
}
