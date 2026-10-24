#!/usr/bin/env bash
# VN-T10: disposable, synthetic-only PostgreSQL/Flyway/Go integration CI.
# Always provisions and removes its own dedicated PostgreSQL container.
# Safe to rehearse locally with explicit opt-in; never reuses Compose asep DB.
set -euo pipefail

if [[ "${ASEP_VN_T10_DISPOSABLE_DB:-}" != "true" ]]; then
  echo "ERROR: set ASEP_VN_T10_DISPOSABLE_DB=true to use a fresh disposable database" >&2
  exit 2
fi

command -v docker >/dev/null
command -v go >/dev/null
command -v python3 >/dev/null

cd "$(dirname "${BASH_SOURCE[0]}")/.."
run_id="$(python3 -c 'import secrets; print(secrets.token_hex(16))')"
container="asep-vn-t10-ci-${run_id:0:12}"
port=55439
admin_password="$(python3 -c 'import secrets; print(secrets.token_hex(32))')"
migrator_password="$(python3 -c 'import secrets; print(secrets.token_hex(32))')"
runtime_password="$(python3 -c 'import secrets; print(secrets.token_hex(32))')"
private_dir="$(mktemp -d)"
chmod 700 "$private_dir"
cleanup() {
  docker rm -f "$container" >/dev/null 2>&1 || true
  rm -rf -- "$private_dir"
}
trap cleanup EXIT

python3 - "$private_dir/token" <<'PY'
import os, secrets, sys
fd = os.open(sys.argv[1], os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
try:
    os.write(fd, (secrets.token_hex(32) + "\n").encode())
finally:
    os.close(fd)
PY

echo '===== VN-T10 CI: DISPOSABLE POSTGRESQL ====='
docker run --rm -d --name "$container" \
  --network bridge -p "127.0.0.1:${port}:5432" \
  --memory 768m --cpus 1.5 --pids-limit 128 \
  -e "POSTGRES_USER=postgres" -e "POSTGRES_DB=asep" \
  -e "POSTGRES_PASSWORD=$admin_password" \
  postgres:17-alpine >/dev/null

ready=false
for _ in $(seq 1 50); do
  if docker exec "$container" pg_isready -U postgres -d asep >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 1
done
if [[ "$ready" != "true" ]]; then
  echo 'ERROR: disposable PostgreSQL not healthy' >&2
  exit 1
fi

# Passwords are generated for this disposable CI database only. Never commit
# real service credentials or try to reuse these roles on the local ASEP DB.
docker exec -i "$container" psql -X -v ON_ERROR_STOP=1 -q -U postgres -d asep >/dev/null <<SQL
CREATE ROLE asep_orch_migrator LOGIN PASSWORD '${migrator_password}';
CREATE ROLE asep_orch_runtime LOGIN PASSWORD '${runtime_password}';
CREATE SCHEMA orchestration AUTHORIZATION asep_orch_migrator;
REVOKE ALL ON SCHEMA orchestration FROM PUBLIC;
SQL

echo '===== VN-T10 CI: FLYWAY V1-V4 ====='
docker run --rm --network host \
  -e "FLYWAY_URL=jdbc:postgresql://127.0.0.1:${port}/asep" \
  -e 'FLYWAY_USER=asep_orch_migrator' \
  -e "FLYWAY_PASSWORD=$migrator_password" \
  -e 'FLYWAY_SCHEMAS=orchestration' \
  -e 'FLYWAY_DEFAULT_SCHEMA=orchestration' \
  -e 'FLYWAY_BASELINE_ON_MIGRATE=false' \
  -v "$PWD/db/migrations:/flyway/sql:ro" \
  flyway/flyway:11 migrate

history="$(docker exec "$container" psql -X -qAt -U postgres -d asep -c \
  "SELECT string_agg(version,',' ORDER BY installed_rank) FROM orchestration.flyway_schema_history WHERE success")"
if [[ "$history" != '1,2,3,4' ]]; then
  echo "ERROR: unexpected migration history" >&2
  exit 1
fi
index_count="$(docker exec "$container" psql -X -qAt -U postgres -d asep -c \
  "SELECT count(*) FROM pg_indexes WHERE schemaname='orchestration' AND indexname='uq_orch_one_active_canonical_subject'")"
if [[ "$index_count" != 1 ]]; then
  echo 'ERROR: canonical subject index not installed' >&2
  exit 1
fi
rights="$(docker exec "$container" psql -X -qAt -U postgres -d asep -c \
  "SELECT has_table_privilege('asep_orch_runtime','orchestration.workflow_runs','INSERT'),has_schema_privilege('asep_orch_runtime','orchestration','CREATE'),has_table_privilege('asep_orch_runtime','orchestration.flyway_schema_history','UPDATE')")"
if [[ "$rights" != 't|f|f' ]]; then
  echo 'ERROR: runtime DB privileges unexpectedly broad or missing' >&2
  exit 1
fi

echo '===== VN-T10 CI: AUDIT WRITE-PROTECTION ====='
audit_rights="$(docker exec "$container" psql -X -qAt -U postgres -d asep -c \
  "SELECT has_table_privilege('asep_orch_runtime','orchestration.workflow_transitions','SELECT'),has_table_privilege('asep_orch_runtime','orchestration.workflow_transitions','INSERT'),has_table_privilege('asep_orch_runtime','orchestration.workflow_transitions','UPDATE'),has_table_privilege('asep_orch_runtime','orchestration.workflow_transitions','DELETE')")"
if [[ "$audit_rights" != 't|t|f|f' ]]; then
  echo 'ERROR: orchestration audit table privileges incorrect' >&2
  exit 1
fi
# Exercise the privilege boundary, not just metadata inspection.
if audit_output="$(docker exec "$container" psql -X -v ON_ERROR_STOP=1 -U postgres -d asep -c \
  "SET ROLE asep_orch_runtime; UPDATE orchestration.workflow_transitions SET from_state='FORBIDDEN' WHERE false;" 2>&1)"; then
  echo 'ERROR: restricted runtime unexpectedly allowed audit UPDATE' >&2
  exit 1
fi
if [[ "$audit_output" != *'permission denied for table workflow_transitions'* ]]; then
  echo 'ERROR: audit UPDATE failed for an unexpected reason' >&2
  exit 1
fi

echo '===== VN-T10 CI: GO STATIC + RACE + DB INTEGRATION ====='
export ASEP_ORCHESTRATOR_DATABASE_URL="postgres://asep_orch_runtime:${runtime_password}@127.0.0.1:${port}/asep?sslmode=disable"
export ASEP_VN_T10_TEST_PREFIX="synthetic:vn-t10-it-${run_id}"
export ASEP_ORCHESTRATOR_DEV_TOKEN_FILE="$private_dir/token"
export ASEP_VN_T10_GO_BIN="$(command -v go)"

go mod download
go vet -tags=integration ./...
go test -race -count=1 -tags=integration ./...

echo 'VN-T10 DISPOSABLE GO CI: PASS (Flyway V1-V4, audit ACLs, tagged race integration)'
