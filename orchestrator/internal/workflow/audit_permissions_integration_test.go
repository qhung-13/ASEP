//go:build integration

package workflow

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgconn"
)

// Verifies the actual connection uses the restricted runtime role and that
// even a zero-row UPDATE is rejected by PostgreSQL. Business transitions
// continue to use INSERT on the audit table in existing integration tests.
func TestRuntimeCannotRewriteTransitionAudit(t *testing.T) {
	s := storeForTest(t)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	var role string
	var canSelect, canInsert, canUpdate, canDelete bool
	err := s.db.QueryRowContext(ctx, `
        SELECT current_user,
            has_table_privilege(current_user, 'orchestration.workflow_transitions', 'SELECT'),
            has_table_privilege(current_user, 'orchestration.workflow_transitions', 'INSERT'),
            has_table_privilege(current_user, 'orchestration.workflow_transitions', 'UPDATE'),
            has_table_privilege(current_user, 'orchestration.workflow_transitions', 'DELETE')
    `).Scan(&role, &canSelect, &canInsert, &canUpdate, &canDelete)
	if err != nil {
		t.Fatal(err)
	}
	if role != "asep_orch_runtime" || !canSelect || !canInsert || canUpdate || canDelete {
		t.Fatalf("runtime audit rights invalid: role=%s select=%v insert=%v update=%v delete=%v",
			role, canSelect, canInsert, canUpdate, canDelete)
	}

	_, err = s.db.ExecContext(ctx, `
        UPDATE orchestration.workflow_transitions
        SET from_state = from_state
        WHERE FALSE
    `)
	var pgErr *pgconn.PgError
	if !errors.As(err, &pgErr) || pgErr.Code != "42501" {
		t.Fatalf("expected PostgreSQL insufficient_privilege (42501), got %v", err)
	}
}
