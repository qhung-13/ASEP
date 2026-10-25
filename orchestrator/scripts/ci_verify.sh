#!/usr/bin/env bash
# Synthetic integration verification. Only recorded container IDs with this
# invocation's ownership label may be removed. Never use the Compose database.
# SIGKILL/host failure cannot run traps: inspect the test-run label and private
# cidfiles before manual orphan cleanup. Never delete resources by name prefix.
set +x
set -euo pipefail
umask 077

if [[ "${ASEP_VN_T10_DISPOSABLE_DB:-}" != "true" ]]; then
  echo 'ERROR: explicit ASEP_VN_T10_DISPOSABLE_DB=true is required' >&2
  exit 2
fi
for tool in docker go python3 timeout setsid; do
  command -v "$tool" >/dev/null || { echo "ERROR: required tool unavailable: $tool" >&2; exit 2; }
done
cd "$(dirname "${BASH_SOURCE[0]}")/.."
# proof-only and cleanup-failure exercise this harness, never production behavior.
mode="${ASEP_VN_T11_HARNESS_MODE:-all}"
case "$mode" in all|proof-only|cleanup-failure|heartbeat) ;; *) echo 'ERROR: invalid harness verification mode' >&2; exit 2 ;; esac
run_id="$(python3 -c 'import secrets; print(secrets.token_hex(16))')"
nonce="$(python3 -c 'import secrets; print(secrets.token_hex(32))')"
database="asep_vnt11_${run_id}"
unmarked_database="asep_vnt11_unmarked_${run_id}"
owner_label='io.github.qhung13.asep.test-run'
private_dir="$(mktemp -d "${TMPDIR:-/tmp}/asep-vnt11.XXXXXXXX")"
active_group=''
phase='setup'

fail() { echo "ERROR: disposable verification failed at $phase; sensitive diagnostics withheld" >&2; exit 1; }
docker_cmd() { timeout --kill-after=5s 45s docker "$@"; }
stop_active() {
  if [[ -n "$active_group" ]]; then
    kill -TERM -- "-$active_group" 2>/dev/null || true
    for _ in {1..20}; do
      kill -0 -- "-$active_group" 2>/dev/null || break
      sleep 0.1
    done
    kill -KILL -- "-$active_group" 2>/dev/null || true
    wait "$active_group" 2>/dev/null || true
    active_group=''
  fi
}
cleanup() {
  local result=$? cid actual cleanup_failed=false
  trap - EXIT INT TERM
  stop_active
  for resource in flyway postgres; do
    if [[ -s "$private_dir/$resource.cid" ]]; then
      read -r cid < "$private_dir/$resource.cid" || true
      [[ "$cid" =~ ^[0-9a-f]{64}$ ]] || { cleanup_failed=true; continue; }
      actual="$(docker_cmd inspect --format "{{ index .Config.Labels \"$owner_label\" }}" "$cid" 2>/dev/null)" || {
        cleanup_failed=true; continue;
      }
      if [[ "$actual" != "$run_id" ]]; then
        echo 'ERROR: refusing cleanup of container without matching ownership proof' >&2
        cleanup_failed=true
        continue
      fi
      docker_cmd rm -fv "$cid" >/dev/null 2>&1 || cleanup_failed=true
    fi
  done
  if [[ "$cleanup_failed" == true ]]; then
    echo "ERROR: owned-resource cleanup incomplete; private cidfiles retained at $private_dir" >&2
    result=1
  else
    rm -rf -- "$private_dir"
    echo 'B0 cleanup: recorded owned containers and private fixtures removed'
  fi
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'fail' ERR

# A separate, recorded process group bounds each command and its Go children.
run_bounded() {
  local seconds=$1
  shift
  setsid timeout --signal=TERM --kill-after=5s "$seconds" "$@" &
  active_group=$!
  local result=0
  wait "$active_group" || result=$?
  if [[ "$result" != 0 ]]; then stop_active; return "$result"; fi
  active_group=''
}

phase='image availability'
for image in postgres:17-alpine flyway/flyway:11; do
  if ! docker_cmd image inspect "$image" >/dev/null 2>&1; then
    run_bounded 180 docker pull "$image" >"$private_dir/image.log" 2>&1
  fi
  docker_cmd image inspect --format '{{json .RepoDigests}}' "$image"
done

# Private env files keep generated passwords off command arguments and output.
python3 - "$private_dir" <<'PY'
import os, secrets, sys
from pathlib import Path
p = Path(sys.argv[1])
for name in ('admin', 'migrator', 'runtime', 'probe'):
    (p / name).write_text(secrets.token_hex(32) + '\n')
(p / 'token').write_text(secrets.token_hex(32) + '\n')
for f in p.iterdir():
    os.chmod(f, 0o600)
PY
read -r admin_password < "$private_dir/admin"
read -r migrator_password < "$private_dir/migrator"
read -r runtime_password < "$private_dir/runtime"
read -r probe_password < "$private_dir/probe"
printf 'POSTGRES_USER=postgres\nPOSTGRES_DB=%s\nPOSTGRES_PASSWORD=%s\n' "$database" "$admin_password" > "$private_dir/postgres.env"

phase='disposable PostgreSQL creation'
docker_cmd create --cidfile "$private_dir/postgres.cid" \
  --name "asep-vnt11-pg-$run_id" --label "$owner_label=$run_id" \
  --network bridge --publish '127.0.0.1::5432' \
  --memory 768m --cpus 1.5 --pids-limit 128 \
  --env-file "$private_dir/postgres.env" postgres:17-alpine >"$private_dir/create.log" 2>&1
postgres_id="$(cat "$private_dir/postgres.cid")"
docker_cmd start "$postgres_id" >/dev/null
port="$(docker_cmd inspect --format '{{(index (index .NetworkSettings.Ports "5432/tcp") 0).HostPort}}' "$postgres_id")"
[[ "$port" =~ ^[0-9]+$ && "$port" -gt 0 ]] || fail
echo "B0: disposable PostgreSQL allocated loopback port $port (run $run_id)"

phase='PostgreSQL TCP readiness'
ready=false
for _ in {1..50}; do
  if timeout 3s docker exec "$postgres_id" pg_isready -h 127.0.0.1 -U postgres -d "$database" >/dev/null 2>&1; then
    ready=true; break
  fi
  sleep 1
done
[[ "$ready" == true ]] || fail

phase='harness-only instance proof and roles'
docker_cmd exec -i "$postgres_id" psql -X -v ON_ERROR_STOP=1 -q -U postgres -d "$database" >"$private_dir/provision.log" 2>&1 <<SQL
CREATE ROLE asep_orch_migrator LOGIN PASSWORD '${migrator_password}';
CREATE ROLE asep_orch_runtime LOGIN PASSWORD '${runtime_password}';
CREATE ROLE asep_orch_probe LOGIN PASSWORD '${probe_password}';
CREATE SCHEMA orchestration AUTHORIZATION asep_orch_migrator;
REVOKE ALL ON SCHEMA orchestration FROM PUBLIC;
CREATE SCHEMA asep_test_harness AUTHORIZATION postgres;
REVOKE ALL ON SCHEMA asep_test_harness FROM PUBLIC;
CREATE TABLE asep_test_harness.instance (
  singleton boolean PRIMARY KEY CHECK (singleton), nonce text NOT NULL,
  database_name name NOT NULL, server_started_at timestamptz NOT NULL
);
INSERT INTO asep_test_harness.instance
  SELECT true, '${nonce}', current_database(), pg_postmaster_start_time();
GRANT USAGE ON SCHEMA asep_test_harness TO asep_orch_runtime, asep_orch_probe;
GRANT SELECT ON asep_test_harness.instance TO asep_orch_runtime;
CREATE TABLE public.guard_mutation_probe (id integer GENERATED ALWAYS AS IDENTITY);
GRANT SELECT, INSERT ON public.guard_mutation_probe TO asep_orch_runtime, asep_orch_probe;
GRANT USAGE ON SEQUENCE public.guard_mutation_probe_id_seq TO asep_orch_runtime, asep_orch_probe;
CREATE DATABASE ${unmarked_database};
SQL
docker_cmd exec -i "$postgres_id" psql -X -v ON_ERROR_STOP=1 -q -U postgres -d "$unmarked_database" >"$private_dir/unmarked.log" 2>&1 <<SQL
CREATE TABLE public.guard_mutation_probe (id integer GENERATED ALWAYS AS IDENTITY);
GRANT SELECT, INSERT ON public.guard_mutation_probe TO asep_orch_runtime;
GRANT USAGE ON SEQUENCE public.guard_mutation_probe_id_seq TO asep_orch_runtime;
SQL

phase='Flyway V1-V4'
printf 'FLYWAY_URL=jdbc:postgresql://127.0.0.1:%s/%s\nFLYWAY_USER=asep_orch_migrator\nFLYWAY_PASSWORD=%s\nFLYWAY_SCHEMAS=orchestration\nFLYWAY_DEFAULT_SCHEMA=orchestration\nFLYWAY_BASELINE_ON_MIGRATE=false\n' \
  "$port" "$database" "$migrator_password" > "$private_dir/flyway.env"
docker_cmd create --cidfile "$private_dir/flyway.cid" \
  --name "asep-vnt11-flyway-$run_id" --label "$owner_label=$run_id" \
  --network host --env-file "$private_dir/flyway.env" \
  -v "$PWD/db/migrations:/flyway/sql:ro" flyway/flyway:11 migrate >"$private_dir/flyway-create.log" 2>&1
flyway_id="$(cat "$private_dir/flyway.cid")"
docker_cmd start "$flyway_id" >/dev/null
run_bounded 120 docker wait "$flyway_id" > "$private_dir/flyway-exit"
[[ "$(cat "$private_dir/flyway-exit")" == 0 ]] || fail

phase='migration and least-privilege assertions'
history="$(docker_cmd exec "$postgres_id" psql -X -qAt -U postgres -d "$database" -c \
  "SELECT string_agg(version,',' ORDER BY installed_rank) FROM orchestration.flyway_schema_history WHERE success")"
[[ "$history" == '1,2,3,4' ]] || fail
rights="$(docker_cmd exec "$postgres_id" psql -X -qAt -U postgres -d "$database" -c \
  "SELECT has_table_privilege('asep_orch_runtime','orchestration.workflow_runs','INSERT'),has_schema_privilege('asep_orch_runtime','orchestration','CREATE'),has_table_privilege('asep_orch_runtime','orchestration.flyway_schema_history','UPDATE'),has_table_privilege('asep_orch_runtime','orchestration.workflow_transitions','SELECT'),has_table_privilege('asep_orch_runtime','orchestration.workflow_transitions','INSERT'),has_table_privilege('asep_orch_runtime','orchestration.workflow_transitions','UPDATE'),has_table_privilege('asep_orch_runtime','orchestration.workflow_transitions','DELETE')")"
[[ "$rights" == 't|f|f|t|t|f|f' ]] || fail
index_count="$(docker_cmd exec "$postgres_id" psql -X -qAt -U postgres -d "$database" -c \
  "SELECT count(*) FROM pg_indexes WHERE schemaname='orchestration' AND indexname='uq_orch_one_active_canonical_subject'")"
[[ "$index_count" == 1 ]] || fail
docker_cmd exec "$postgres_id" postgres --version
docker_cmd logs "$flyway_id" 2>&1 | python3 -c 'import sys; print(next((s.strip() for s in sys.stdin if s.startswith("Flyway ")), "Flyway version unavailable"))'
go version
go list -m github.com/jackc/pgx/v5
git rev-parse HEAD

export ASEP_ORCHESTRATOR_DATABASE_URL="postgres://asep_orch_runtime:${runtime_password}@127.0.0.1:${port}/${database}?sslmode=disable"
export ASEP_VN_T11_UNREADABLE_DATABASE_URL="postgres://asep_orch_probe:${probe_password}@127.0.0.1:${port}/${database}?sslmode=disable"
export ASEP_VN_T11_DISPOSABLE_NONCE="$nonce"
export ASEP_VN_T10_TEST_PREFIX="synthetic:vn-t10-it-${run_id}"
export ASEP_ORCHESTRATOR_DEV_TOKEN_FILE="$private_dir/token"
export ASEP_VN_T10_GO_BIN="$(command -v go)"

phase='Go dependencies and format'
run_bounded 120 go mod download
[[ -z "$(gofmt -l cmd internal)" ]] || fail
phase='B0 proof enforcement before orchestration integration'
run_bounded 60 go test -race -count=1 -tags=integration -timeout=45s -v ./internal/testsupport
for package_test in './internal/workflow TestAdmissionReplayConflictAtomicBundle' './cmd/asep-orchestrator TestProcessCrashRestartKeepsRunAndLease'; do
  read -r package test_name <<< "$package_test"
  if ASEP_VN_T11_DISPOSABLE_NONCE=0000000000000000000000000000000000000000000000000000000000000000 \
      run_bounded 60 go test -count=1 -tags=integration -timeout=45s -run "^${test_name}$" "$package" > "$private_dir/rejected-test.log" 2>&1; then
    fail
  fi
  python3 - "$private_dir/rejected-test.log" <<'PY'
import sys
assert 'disposable PostgreSQL proof missing or invalid' in open(sys.argv[1]).read(), 'negative test failed for an unexpected reason'
PY
done
run_count="$(docker_cmd exec "$postgres_id" psql -X -qAt -U postgres -d "$database" -c 'SELECT count(*) FROM orchestration.workflow_runs')"
[[ "$run_count" == 0 ]] || fail
for probe_db in "$database" "$unmarked_database"; do
  probe_count="$(docker_cmd exec "$postgres_id" psql -X -qAt -U postgres -d "$probe_db" -c 'SELECT count(*) FROM public.guard_mutation_probe')"
  [[ "$probe_count" == 0 ]] || fail
done
echo 'B0 safety gate PASS: read-only proof verified; unsafe targets rejected; mutation probes=0'
if [[ "$mode" == proof-only ]]; then exit 0; fi
if [[ "$mode" == cleanup-failure ]]; then phase='intentional controlled cleanup check'; fail; fi

phase='Go vet and unit regressions'
run_bounded 120 go vet -tags=integration ./...
run_bounded 120 go test -race -count=1 -timeout=90s ./...
phase='integration test discovery'
run_bounded 60 go test -tags=integration -list '^Test' ./... > "$private_dir/discovery"
for expected in \
  TestAdmissionReplayConflictAtomicBundle \
  TestConcurrentClaimSingleOwner \
  TestAdvanceInvestigationAtomicSuccessor \
  TestProcessCrashRestartKeepsRunAndLease \
  TestDisposableProofRejectsUnsafeTargetsBeforeMutation \
  TestDisposableProofRuntimeCannotModifyMarker \
  TestRuntimeCannotRewriteTransitionAudit \
  TestConcurrentAdmissionReplayAndPayloadConflict \
  TestConcurrentAdmissionCanonicalSubjectSingleActiveRun \
  TestWorkerProcessCrashReclaimsSameTask \
  TestHeartbeatRejectsLeaseExpiredWhileWaitingForRowLock \
  TestHeartbeatTransitionLockOrdering \
  TestHeartbeatConcurrentClaim \
  TestHeartbeatDatabaseErrorsRemainDistinctFromLeaseLoss; do
  python3 - "$private_dir/discovery" "$expected" <<'PY'
import sys
assert sys.argv[2] in open(sys.argv[1]).read().splitlines(), 'required test missing'
PY
done
if [[ "$mode" == heartbeat ]]; then
  phase='focused heartbeat, claim and transition regressions'
  run_bounded 180 go test -race -count=1 -tags=integration -timeout=120s -v \
    -run 'Test(Heartbeat|ExpiredLease|ConcurrentClaim|AdvanceInvestigation)' ./internal/workflow
  phase='F08 repeated row-lock regression'
  run_bounded 90 go test -race -count=5 -tags=integration -timeout=60s -v \
    -run '^TestHeartbeatRejectsLeaseExpiredWhileWaitingForRowLock$' ./internal/workflow
  echo 'Focused heartbeat verification PASS: regressions and five F08 repetitions'
  exit 0
fi
phase='race-enabled PostgreSQL regression suite'
run_bounded 240 go test -race -count=1 -tags=integration -timeout=180s -v ./...
echo 'VN-T11 disposable verification PASS: proof guard, unchanged V1-V4, existing regressions'
