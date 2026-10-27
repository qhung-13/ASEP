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
case "$mode" in all|proof-only|cleanup-failure|heartbeat|b2|rf06-process|rf07a|rf07b|rf08|rf09|rf10|manual-admission) ;; *) echo 'ERROR: invalid harness verification mode' >&2; exit 2 ;; esac
# The cross-component fixture is required in full and manual-admission runs.
# Other focused VN-T11 modes keep their existing toolchain requirements.
if [[ "$mode" == all || "$mode" == manual-admission ]]; then
  command -v java >/dev/null || { echo 'ERROR: Java 21+ required for private admission fixture' >&2; exit 2; }
fi
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
  for resource in flyway_v10 flyway_v9 flyway_v8 flyway_v7 flyway_v6 flyway postgres; do
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

if [[ "$mode" == all || "$mode" == manual-admission ]]; then
  phase='compile private Java admission pipe peer'
  export ASEP_VN_T12_JAVA_BIN="$(command -v java)"
  export ASEP_VN_T12_JAVA_CLASSPATH_FILE="$private_dir/java-classpath"
  maven_options=(--batch-mode --no-transfer-progress)
  if [[ "${ASEP_VN_T12_MAVEN_OFFLINE:-false}" == true ]]; then maven_options+=(--offline); fi
  # Maven owns dependency resolution; no host-wide jar scanning or temporary overlays.
  cd ../backend
  run_bounded 180 ./mvnw "${maven_options[@]}" -Dtest=ManualAdmissionProcessFixtureTest \
    "-Dasep.manualBridge.classpathFile=$ASEP_VN_T12_JAVA_CLASSPATH_FILE" test
  cd ../orchestrator
  [[ -s "$ASEP_VN_T12_JAVA_CLASSPATH_FILE" ]] || fail
fi

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

phase='Flyway V1-V5'
printf 'FLYWAY_URL=jdbc:postgresql://127.0.0.1:%s/%s\nFLYWAY_USER=asep_orch_migrator\nFLYWAY_PASSWORD=%s\nFLYWAY_SCHEMAS=orchestration\nFLYWAY_DEFAULT_SCHEMA=orchestration\nFLYWAY_BASELINE_ON_MIGRATE=false\n' \
  "$port" "$database" "$migrator_password" > "$private_dir/flyway.env"
docker_cmd create --cidfile "$private_dir/flyway.cid" \
  --name "asep-vnt11-flyway-$run_id" --label "$owner_label=$run_id" \
  --network host --env-file "$private_dir/flyway.env" \
  -v "$PWD/db/migrations:/flyway/sql:ro" flyway/flyway:11 -target=5 migrate >"$private_dir/flyway-create.log" 2>&1
flyway_id="$(cat "$private_dir/flyway.cid")"
docker_cmd start "$flyway_id" >/dev/null
run_bounded 120 docker wait "$flyway_id" > "$private_dir/flyway-exit"
[[ "$(cat "$private_dir/flyway-exit")" == 0 ]] || fail

phase='migration and least-privilege assertions'
history="$(docker_cmd exec "$postgres_id" psql -X -qAt -U postgres -d "$database" -c \
  "SELECT string_agg(version,',' ORDER BY installed_rank) FROM orchestration.flyway_schema_history WHERE success")"
[[ "$history" == '1,2,3,4,5' ]] || fail
rights="$(docker_cmd exec "$postgres_id" psql -X -qAt -U postgres -d "$database" -c \
  "SELECT has_table_privilege('asep_orch_runtime','orchestration.workflow_runs','INSERT'),has_schema_privilege('asep_orch_runtime','orchestration','CREATE'),has_table_privilege('asep_orch_runtime','orchestration.flyway_schema_history','UPDATE'),has_table_privilege('asep_orch_runtime','orchestration.workflow_transitions','SELECT'),has_table_privilege('asep_orch_runtime','orchestration.workflow_transitions','INSERT'),has_table_privilege('asep_orch_runtime','orchestration.workflow_transitions','UPDATE'),has_table_privilege('asep_orch_runtime','orchestration.workflow_transitions','DELETE')")"
[[ "$rights" == 't|f|f|t|t|f|f' ]] || fail
receipt_rights="$(docker_cmd exec "$postgres_id" psql -X -qAt -U postgres -d "$database" -c \
  "SELECT has_table_privilege('asep_orch_runtime','orchestration.workflow_result_receipts','SELECT'),has_table_privilege('asep_orch_runtime','orchestration.workflow_result_receipts','INSERT'),has_table_privilege('asep_orch_runtime','orchestration.workflow_result_receipts','UPDATE'),has_table_privilege('asep_orch_runtime','orchestration.workflow_result_receipts','DELETE'),has_table_privilege('asep_orch_runtime','orchestration.workflow_result_receipts','TRUNCATE'),has_table_privilege('asep_orch_runtime','orchestration.workflow_result_receipts','REFERENCES'),has_table_privilege('asep_orch_runtime','orchestration.workflow_result_receipts','TRIGGER')")"
[[ "$receipt_rights" == 't|t|f|f|f|f|f' ]] || fail
index_count="$(docker_cmd exec "$postgres_id" psql -X -qAt -U postgres -d "$database" -c \
  "SELECT count(*) FROM pg_indexes WHERE schemaname='orchestration' AND indexname='uq_orch_one_active_canonical_subject'")"
[[ "$index_count" == 1 ]] || fail
docker_cmd exec "$postgres_id" postgres --version
# Drain the bounded migrator log before exiting; an early next(generator)
# closes Docker's output pipe and can fail this safety gate under pipefail.
docker_cmd logs "$flyway_id" 2>&1 | python3 -c 'import sys; print(next(iter([s.strip() for s in sys.stdin if s.startswith("Flyway ")]), "Flyway version unavailable"))'
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

phase='populated V5 compatibility baseline'
run_bounded 90 go test -race -count=1 -tags=integration -timeout=60s -v \
  -run '^TestResultReplayAndLostApplicationACK$' ./internal/workflow
receipt_count="$(docker_cmd exec "$postgres_id" psql -X -qAt -U postgres -d "$database" -c \
  'SELECT count(*) FROM orchestration.workflow_result_receipts')"
[[ "$receipt_count" == 1 ]] || fail
# Compare every legacy row/field, excluding only V6's newly added NULL columns.
# This SQL and its digest stay private; fixture content/credentials are not logged.
cat > "$private_dir/legacy-snapshot.sql" <<'SQL'
SELECT md5(jsonb_build_array(
  (SELECT jsonb_agg(to_jsonb(r) ORDER BY run_id) FROM orchestration.workflow_runs r),
  (SELECT jsonb_agg(to_jsonb(a) ORDER BY attempt_id) FROM orchestration.stage_attempts a),
  (SELECT jsonb_agg(to_jsonb(t) ORDER BY task_id) FROM orchestration.task_deliveries t),
  (SELECT jsonb_agg(to_jsonb(t) - ARRAY['control_operation_id','control_kind','control_digest_version',
    'control_payload_sha256','control_task_id','control_claimant_id','control_lease_epoch',
    'control_policy_epoch','control_source_fingerprint_sha256'] ORDER BY transition_id)
    FROM orchestration.workflow_transitions t),
  (SELECT jsonb_agg(to_jsonb(o) ORDER BY message_id) FROM orchestration.workflow_outbox o),
  (SELECT jsonb_agg(to_jsonb(r) ORDER BY operation_id) FROM orchestration.workflow_result_receipts r)
)::text);
SQL
legacy_before="$(docker_cmd exec -i "$postgres_id" psql -X -v ON_ERROR_STOP=1 -qAt -U postgres -d "$database" < "$private_dir/legacy-snapshot.sql")"
phase='Flyway V6 populated upgrade'
docker_cmd create --cidfile "$private_dir/flyway_v6.cid" \
  --name "asep-vnt11-flyway-v6-$run_id" --label "$owner_label=$run_id" \
  --network host --env-file "$private_dir/flyway.env" \
  -v "$PWD/db/migrations:/flyway/sql:ro" flyway/flyway:11 -target=6 migrate >"$private_dir/flyway-v6-create.log" 2>&1
flyway_v6_id="$(cat "$private_dir/flyway_v6.cid")"
docker_cmd start "$flyway_v6_id" >/dev/null
run_bounded 120 docker wait "$flyway_v6_id" > "$private_dir/flyway-v6-exit"
[[ "$(cat "$private_dir/flyway-v6-exit")" == 0 ]] || fail
history="$(docker_cmd exec "$postgres_id" psql -X -qAt -U postgres -d "$database" -c \
  "SELECT string_agg(version,',' ORDER BY installed_rank) FROM orchestration.flyway_schema_history WHERE success")"
[[ "$history" == '1,2,3,4,5,6' ]] || fail
legacy_after="$(docker_cmd exec -i "$postgres_id" psql -X -v ON_ERROR_STOP=1 -qAt -U postgres -d "$database" < "$private_dir/legacy-snapshot.sql")"
[[ "$legacy_before" == "$legacy_after" ]] || fail
echo 'RF-07A populated V5 -> V6 compatibility PASS: all legacy rows/fields/receipts preserved'

phase='populated V6 cancellation compatibility baseline'
run_bounded 90 go test -race -count=1 -tags=integration -timeout=60s -v \
  -run '^TestCancellationReplayAndFencing$' ./internal/workflow
control_count="$(docker_cmd exec "$postgres_id" psql -X -qAt -U postgres -d "$database" -c \
  "SELECT count(*) FROM orchestration.workflow_transitions WHERE control_kind='CANCEL_REQUEST'")"
[[ "$control_count" == 1 ]] || fail
cat > "$private_dir/v6-snapshot.sql" <<'SQL'
SELECT md5(jsonb_build_array(
  (SELECT jsonb_agg(to_jsonb(r) ORDER BY run_id) FROM orchestration.workflow_runs r),
  (SELECT jsonb_agg(to_jsonb(a) ORDER BY attempt_id) FROM orchestration.stage_attempts a),
  (SELECT jsonb_agg(to_jsonb(t) ORDER BY task_id) FROM orchestration.task_deliveries t),
  (SELECT jsonb_agg(to_jsonb(t) - ARRAY['control_request_transition_id','control_inventory_sha256',
    'control_cleanup_sha256'] ORDER BY transition_id) FROM orchestration.workflow_transitions t),
  (SELECT jsonb_agg(to_jsonb(o) ORDER BY message_id) FROM orchestration.workflow_outbox o),
  (SELECT jsonb_agg(to_jsonb(r) ORDER BY operation_id) FROM orchestration.workflow_result_receipts r)
)::text);
SQL
v6_before="$(docker_cmd exec -i "$postgres_id" psql -X -v ON_ERROR_STOP=1 -qAt -U postgres -d "$database" < "$private_dir/v6-snapshot.sql")"
phase='Flyway V7 populated upgrade'
docker_cmd create --cidfile "$private_dir/flyway_v7.cid" \
  --name "asep-vnt11-flyway-v7-$run_id" --label "$owner_label=$run_id" \
  --network host --env-file "$private_dir/flyway.env" \
  -v "$PWD/db/migrations:/flyway/sql:ro" flyway/flyway:11 -target=7 migrate >"$private_dir/flyway-v7-create.log" 2>&1
flyway_v7_id="$(cat "$private_dir/flyway_v7.cid")"
docker_cmd start "$flyway_v7_id" >/dev/null
run_bounded 120 docker wait "$flyway_v7_id" > "$private_dir/flyway-v7-exit"
[[ "$(cat "$private_dir/flyway-v7-exit")" == 0 ]] || fail
history="$(docker_cmd exec "$postgres_id" psql -X -qAt -U postgres -d "$database" -c \
  "SELECT string_agg(version,',' ORDER BY installed_rank) FROM orchestration.flyway_schema_history WHERE success")"
[[ "$history" == '1,2,3,4,5,6,7' ]] || fail
v6_after="$(docker_cmd exec -i "$postgres_id" psql -X -v ON_ERROR_STOP=1 -qAt -U postgres -d "$database" < "$private_dir/v6-snapshot.sql")"
[[ "$v6_before" == "$v6_after" ]] || fail
inventory_count="$(docker_cmd exec "$postgres_id" psql -X -qAt -U postgres -d "$database" -c \
  'SELECT count(*) FROM orchestration.workflow_cancellation_inventories')"
[[ "$inventory_count" == 0 ]] || fail
echo 'RF-07B populated V6 -> V7 compatibility PASS: legacy cancellation stays pending, no fabricated inventory; original hashes/receipts/rows retained'

phase='populated V7 cancellation compatibility baseline'
run_bounded 90 go test -race -count=1 -tags=integration -timeout=60s -v \
  -run '^TestCancellationFinalizationProcesses$/^TERM$' ./internal/workflow
python3 - "$private_dir/v7-snapshot.sql" <<'PY'
from pathlib import Path
import sys
tables = ('workflow_runs','stage_attempts','task_deliveries','workflow_transitions',
          'workflow_outbox','workflow_result_receipts','workflow_execution_inventories',
          'workflow_execution_scopes','workflow_execution_bindings',
          'workflow_cancellation_inventories','workflow_execution_cleanups')
Path(sys.argv[1]).write_text('\n'.join(
    "SELECT md5(coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text,'null')) FROM orchestration." + table + " t;"
    for table in tables))
PY
v7_before="$(docker_cmd exec -i "$postgres_id" psql -X -v ON_ERROR_STOP=1 -qAt -U postgres -d "$database" < "$private_dir/v7-snapshot.sql")"
phase='Flyway V8 populated upgrade'
docker_cmd create --cidfile "$private_dir/flyway_v8.cid" \
  --name "asep-vnt11-flyway-v8-$run_id" --label "$owner_label=$run_id" \
  --network host --env-file "$private_dir/flyway.env" \
  -v "$PWD/db/migrations:/flyway/sql:ro" flyway/flyway:11 -target=8 migrate >"$private_dir/flyway-v8-create.log" 2>&1
flyway_v8_id="$(cat "$private_dir/flyway_v8.cid")"
docker_cmd start "$flyway_v8_id" >/dev/null
run_bounded 120 docker wait "$flyway_v8_id" > "$private_dir/flyway-v8-exit"
[[ "$(cat "$private_dir/flyway-v8-exit")" == 0 ]] || fail
history="$(docker_cmd exec "$postgres_id" psql -X -qAt -U postgres -d "$database" -c \
  "SELECT string_agg(version,',' ORDER BY installed_rank) FROM orchestration.flyway_schema_history WHERE success")"
[[ "$history" == '1,2,3,4,5,6,7,8' ]] || fail
v7_after="$(docker_cmd exec -i "$postgres_id" psql -X -v ON_ERROR_STOP=1 -qAt -U postgres -d "$database" < "$private_dir/v7-snapshot.sql")"
[[ "$v7_before" == "$v7_after" ]] || fail
profile_count="$(docker_cmd exec "$postgres_id" psql -X -qAt -U postgres -d "$database" -c \
  'SELECT count(*) FROM orchestration.workflow_synthetic_profiles')"
[[ "$profile_count" == 0 ]] || fail
echo 'RF-08 populated V7 -> V8 compatibility PASS: all V1-V7 facts unchanged; no invented legacy retry profile'

phase='populated V8 retry compatibility baseline'
run_bounded 90 go test -race -count=1 -tags=integration -timeout=60s -v \
  -run '^TestRetrySemanticBudgetRecovery$' ./internal/workflow
cp "$private_dir/v7-snapshot.sql" "$private_dir/v8-snapshot.sql"
cat >> "$private_dir/v8-snapshot.sql" <<'SQL'

SELECT md5(coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text,'null')) FROM orchestration.workflow_synthetic_profiles t;
SELECT md5(coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text,'null')) FROM orchestration.workflow_synthetic_policies t;
SELECT md5(coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text,'null')) FROM orchestration.workflow_synthetic_gate_evidence t;
SELECT md5(coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text,'null')) FROM orchestration.workflow_retry_receipts t;
SQL
v8_before="$(docker_cmd exec -i "$postgres_id" psql -X -v ON_ERROR_STOP=1 -qAt -U postgres -d "$database" < "$private_dir/v8-snapshot.sql")"
phase='Flyway V9 populated upgrade'
docker_cmd create --cidfile "$private_dir/flyway_v9.cid" \
  --name "asep-vnt11-flyway-v9-$run_id" --label "$owner_label=$run_id" \
  --network host --env-file "$private_dir/flyway.env" \
  -v "$PWD/db/migrations:/flyway/sql:ro" flyway/flyway:11 -target=9 migrate >"$private_dir/flyway-v9-create.log" 2>&1
flyway_v9_id="$(cat "$private_dir/flyway_v9.cid")"
docker_cmd start "$flyway_v9_id" >/dev/null
run_bounded 120 docker wait "$flyway_v9_id" > "$private_dir/flyway-v9-exit"
[[ "$(cat "$private_dir/flyway-v9-exit")" == 0 ]] || fail
history="$(docker_cmd exec "$postgres_id" psql -X -qAt -U postgres -d "$database" -c \
  "SELECT string_agg(version,',' ORDER BY installed_rank) FROM orchestration.flyway_schema_history WHERE success")"
[[ "$history" == '1,2,3,4,5,6,7,8,9' ]] || fail
v8_after="$(docker_cmd exec -i "$postgres_id" psql -X -v ON_ERROR_STOP=1 -qAt -U postgres -d "$database" < "$private_dir/v8-snapshot.sql")"
[[ "$v8_before" == "$v8_after" ]] || fail
echo 'RF-09 populated V8 -> V9 compatibility PASS: original message identities, codecs, receipts and retry accounting retained'

phase='populated V9 sender/receiver compatibility baseline'
run_bounded 90 go test -race -count=1 -tags=integration -timeout=60s -v \
  -run '^TestMessagingDuplicateAndReorderedDelivery$' ./internal/workflow
cp "$private_dir/v8-snapshot.sql" "$private_dir/v9-snapshot.sql"
cat >> "$private_dir/v9-snapshot.sql" <<'SQL'

SELECT md5(coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text,'null')) FROM orchestration.workflow_synthetic_delivery_policies t;
SELECT md5(coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text,'null')) FROM orchestration.workflow_synthetic_deliveries t;
SELECT md5(coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text,'null')) FROM orchestration.workflow_synthetic_delivery_attempts t;
SELECT md5(coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text,'null')) FROM orchestration.workflow_synthetic_inbox t;
SELECT md5(coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text,'null')) FROM orchestration.workflow_synthetic_effects t;
SELECT md5(coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text,'null')) FROM orchestration.workflow_synthetic_delivery_receipts t;
SQL
v9_before="$(docker_cmd exec -i "$postgres_id" psql -X -v ON_ERROR_STOP=1 -qAt -U postgres -d "$database" < "$private_dir/v9-snapshot.sql")"
phase='Flyway V10 populated upgrade'
docker_cmd create --cidfile "$private_dir/flyway_v10.cid" \
  --name "asep-vnt11-flyway-v10-$run_id" --label "$owner_label=$run_id" \
  --network host --env-file "$private_dir/flyway.env" \
  -v "$PWD/db/migrations:/flyway/sql:ro" flyway/flyway:11 -target=10 migrate >"$private_dir/flyway-v10-create.log" 2>&1
flyway_v10_id="$(cat "$private_dir/flyway_v10.cid")"
docker_cmd start "$flyway_v10_id" >/dev/null
run_bounded 120 docker wait "$flyway_v10_id" > "$private_dir/flyway-v10-exit"
[[ "$(cat "$private_dir/flyway-v10-exit")" == 0 ]] || fail
history="$(docker_cmd exec "$postgres_id" psql -X -qAt -U postgres -d "$database" -c \
  "SELECT string_agg(version,',' ORDER BY installed_rank) FROM orchestration.flyway_schema_history WHERE success")"
[[ "$history" == '1,2,3,4,5,6,7,8,9,10' ]] || fail
v9_after="$(docker_cmd exec -i "$postgres_id" psql -X -v ON_ERROR_STOP=1 -qAt -U postgres -d "$database" < "$private_dir/v9-snapshot.sql")"
[[ "$v9_before" == "$v9_after" ]] || fail
disposition_count="$(docker_cmd exec "$postgres_id" psql -X -qAt -U postgres -d "$database" -c \
  'SELECT count(*) FROM orchestration.workflow_synthetic_sender_dispositions')"
[[ "$disposition_count" == 0 ]] || fail
echo 'A01 populated V9 -> V10 compatibility PASS: all original facts unchanged; no invented unsent disposition or receiver acknowledgement'

phase='Go vet and unit regressions'
run_bounded 120 go vet -tags=integration,postgresrestart ./...
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
  TestHarnessVersionParserDrainsLog \
  TestRuntimeCannotRewriteTransitionAudit \
  TestConcurrentAdmissionReplayAndPayloadConflict \
  TestConcurrentAdmissionCanonicalSubjectSingleActiveRun \
  TestWorkerProcessCrashReclaimsSameTask \
  TestHeartbeatRejectsLeaseExpiredWhileWaitingForRowLock \
  TestHeartbeatTransitionLockOrdering \
  TestHeartbeatConcurrentClaim \
  TestHeartbeatDatabaseErrorsRemainDistinctFromLeaseLoss \
  TestResultLockedAcceptance \
  TestResultReplayAndLostApplicationACK \
  TestResultProcessCrashAfterCommitBeforeACK \
  TestResultConcurrentSubmissions \
  TestResultOperationIdentityAcrossRuns \
  TestResultCommitResponseLoss \
  TestResultReconciliationDeadline \
  TestResultReceiptConstraintsAndPermissions \
  TestResultCanonicalPayload \
  TestResultIdentityValidation \
  TestCancellationCanonicalPayload \
  TestCancellationIdentityValidation \
  TestCancellationCompletionRace \
  TestCancellationReplayAndFencing \
  TestCancellationHistoricalCompletionReplay \
  TestCancellationRejectsStaleBindings \
  TestCancellationConcurrentOperationIdentity \
  TestCancellationRollbackOnAuditFailure \
  TestCancellationCommitResponseLoss \
  TestCancellationReconciliationDeadline \
  TestCancellationAuditConstraintsAndPermissions \
  TestCancellationFinalizationProcesses \
  TestExecutionCancellationStartup \
  TestExecutionUnboundAndOlderEpochs \
  TestExecutionCleanupEvidence \
  TestExecutionStopRejectsInvalidWaitEvidence \
  TestCancellationFinalizationLegacy \
  TestCancellationFinalizationConcurrency \
  TestCancellationFinalizationControllerCrashes \
  TestCancellationFinalizationRollback \
  TestCancellationFinalizationCommitResponseLoss \
  TestCancellationFinalizationPreservesAcceptedResult \
  TestExecutionCommitResponseLoss \
  TestExecutionCanonicalEvidence \
  TestExecutionRejectsIncompleteProof \
  TestExecutionAppendOnlyAndFinalizationConstraints \
  TestRetryBoundedInfrastructure \
  TestRetryAdmissionProfileIdentity \
  TestRetryConcurrentDecisions \
  TestRetryCanonicalIdentity \
  TestRetrySemanticBudgetRecovery \
  TestRetryRejectsStaleAuthority \
  TestRetryCompletionAndCancellationRaces \
  TestRetryRollbackAndPermissions \
  TestRetryCommitResponseLoss \
  TestRetryAdmissionReconciliation \
  TestRetryAdmissionCommitResponseLoss \
  TestManualTicketJavaGoContractVector \
  TestManualTicketDurableReplayAndCancellation \
  TestManualTicketConcurrentCanonicalSubject \
  TestManualTicketUncertainCommitRecovery \
  TestManualTicketJavaGoFailClosedIntegration \
  TestManualTicketAdmissionHasNoExposedEndpoint \
  TestMessagingDuplicateAndReorderedDelivery \
  TestMessagingRejectsConflictsAndAuthority \
  TestMessagingDispatchBoundsAndLateEvidence \
  TestMessagingDeferredAndForgedCallback \
  TestMessagingWrongTransportReceipt \
  TestMessagingCancellationDuringDelivery \
  TestMessagingCommitResponseLoss \
  TestMessagingInboxConstraintsAndPermissions \
  TestMessagingReceiptTimestampIdentity \
  TestMessagingProcessCrashBeforeACK \
  TestMessagingObsoleteUnsentIntent \
  TestMessagingObsoleteDispatchContention \
  TestMessagingObsoletePreservesUncertainty \
  TestMessagingObsoleteRequiresImmutableHistory \
  TestMessagingObsoleteCommitResponseLoss \
  TestMessagingObsoleteConstraintsAndPermissions \
  TestOutageBeforeOperationAndReconciliation \
  TestOutagePolicyLookupFailsClosed \
  TestOutageMidTransactionRollbackEvidence \
  TestOutageRepeatedRecoveryPreservesReceipt \
  TestOutagePolicyRevocationSerialization; do
  python3 - "$private_dir/discovery" "$expected" <<'PY'
import sys
assert sys.argv[2] in open(sys.argv[1]).read().splitlines(), 'required test missing'
PY
done
phase='RF-10 controlled restart test discovery'
run_bounded 60 go test -tags=integration,postgresrestart -list '^TestOutageDatabaseRestart$' ./internal/workflow > "$private_dir/restart-discovery"
python3 - "$private_dir/restart-discovery" <<'PY'
import sys
assert 'TestOutageDatabaseRestart' in open(sys.argv[1]).read().splitlines(), 'required restart test missing'
PY
if [[ "$mode" == all || "$mode" == rf10 ]]; then
  phase='RF-10 actual owned PostgreSQL restart and B0 re-proof'
  mkdir -m 700 "$private_dir/outage-control"
  # Only this trusted runner, never a worker/controller child, receives lifecycle
  # authority. Recheck the exact recorded ID and ownership before each action.
  wait_restart_flag() {
    local flag=$1
    for _ in {1..150}; do
      [[ -f "$private_dir/outage-control/$flag" && ! -L "$private_dir/outage-control/$flag" ]] && return 0
      kill -0 "$active_group" 2>/dev/null || return 1
      sleep 0.1
    done
    return 1
  }
  setsid timeout --signal=TERM --kill-after=5s 120s env \
    ASEP_VN_T11_OUTAGE_CONTROL="$private_dir/outage-control" \
    go test -race -count=1 -tags=integration,postgresrestart -timeout=90s -v \
      -run '^TestOutageDatabaseRestart$' ./internal/workflow &
  active_group=$!
  wait_restart_flag ready
  actual="$(docker_cmd inspect --format "{{ index .Config.Labels \"$owner_label\" }}" "$postgres_id")"
  [[ "$actual" == "$run_id" ]] || fail
  docker_cmd stop --time 5 "$postgres_id" > "$private_dir/restart-stop.log" 2>&1
  [[ "$(docker_cmd inspect --format '{{.State.Running}}' "$postgres_id")" == false ]] || fail
  printf 'offline\n' > "$private_dir/outage-control/offline"
  wait_restart_flag observed
  actual="$(docker_cmd inspect --format "{{ index .Config.Labels \"$owner_label\" }}" "$postgres_id")"
  [[ "$actual" == "$run_id" ]] || fail
  docker_cmd start "$postgres_id" > "$private_dir/restart-start.log" 2>&1
  for _ in {1..100}; do
    docker_cmd exec "$postgres_id" pg_isready -U postgres -d "$database" > "$private_dir/restart-ready.log" 2>&1 && break
    sleep 0.1
  done
  docker_cmd exec "$postgres_id" pg_isready -U postgres -d "$database" > "$private_dir/restart-ready.log" 2>&1
  prior_port="$port"
  port="$(docker_cmd inspect --format '{{(index (index .NetworkSettings.Ports "5432/tcp") 0).HostPort}}' "$postgres_id")"
  [[ "$port" =~ ^[0-9]+$ && "$port" -gt 0 && "$port" -le 65535 ]] || fail
  printf '%s\n' "$port" > "$private_dir/outage-control/server-port"
  export ASEP_ORCHESTRATOR_DATABASE_URL="postgres://asep_orch_runtime:${runtime_password}@127.0.0.1:${port}/${database}?sslmode=disable"
  export ASEP_VN_T11_UNREADABLE_DATABASE_URL="postgres://asep_orch_probe:${probe_password}@127.0.0.1:${port}/${database}?sslmode=disable"
  if [[ "$prior_port" != "$port" ]]; then echo 'RF-10: Docker reassigned the owned loopback port; fresh B0 proof remains mandatory'; fi
  printf 'server-up\n' > "$private_dir/outage-control/server-up"
  wait_restart_flag stale-proof-rejected
  # A restart intentionally invalidates B0's server-start proof. The test must
  # first demonstrate rejection and denied runtime UPDATE. Only the trusted
  # owner-verified harness may re-enroll this same disposable instance/nonce.
  actual="$(docker_cmd inspect --format "{{ index .Config.Labels \"$owner_label\" }}" "$postgres_id")"
  [[ "$actual" == "$run_id" ]] || fail
  docker_cmd exec -i "$postgres_id" psql -X -v ON_ERROR_STOP=1 -qAt -U postgres -d "$database" > "$private_dir/reproof-result" <<SQL
UPDATE asep_test_harness.instance SET server_started_at=pg_postmaster_start_time()
  WHERE singleton AND nonce='${nonce}' AND database_name=current_database()
    AND server_started_at<>pg_postmaster_start_time() RETURNING 1;
SQL
  [[ "$(cat "$private_dir/reproof-result")" == 1 ]] || fail
  printf 'restored\n' > "$private_dir/outage-control/restored"
  wait "$active_group"
  active_group=''
  echo 'RF-10 actual PostgreSQL restart PASS: stale B0 proof rejected before trusted same-container re-enrollment; runtime privileges unchanged'
fi
if [[ "$mode" == rf10 ]]; then
  phase='RF-10 connection, policy, uncertain COMMIT and process recovery'
  run_bounded 240 go test -race -count=1 -tags=integration -timeout=180s -v \
    -run '^Test(Outage|ResultCommitResponseLoss|RetryCommitResponseLoss|MessagingCommitResponseLoss|CancellationCommitResponseLoss|CancellationFinalizationCommitResponseLoss|ExecutionCommitResponseLoss|MessagingProcessCrashBeforeACK|ResultProcessCrashAfterCommitBeforeACK|WorkerProcessCrashReclaimsSameTask)' ./...
  phase='RF-10 repeated policy serialization and restored lookup'
  run_bounded 120 go test -race -count=5 -tags=integration -timeout=90s -v \
    -run '^TestOutage(PolicyRevocationSerialization|RepeatedRecoveryPreservesReceipt)$' ./internal/workflow
  echo 'RF-10 focused verification PASS'
  exit 0
fi
if [[ "$mode" == manual-admission ]]; then
  phase='private fixture ticket admission contract, durability and COMMIT recovery'
  run_bounded 180 go test -race -count=1 -tags=integration -timeout=120s -v \
    -run '^TestManualTicket' ./...
  phase='repeated canonical manual-admission concurrency'
  run_bounded 120 go test -race -count=5 -tags=integration -timeout=90s -v \
    -run '^TestManualTicketConcurrentCanonicalSubject$' ./internal/workflow
  echo 'VN-T12 private fixture verification PASS; production Jira/service authentication not exercised'
  exit 0
fi
if [[ "$mode" == rf09 ]]; then
  phase='RF-09 durable synthetic sender/receiver recovery'
  run_bounded 180 go test -race -count=1 -tags=integration -timeout=120s -v \
    -run '^TestMessaging' ./internal/workflow
  phase='RF-09 repeated duplicate delivery and process crash'
  run_bounded 120 go test -race -count=5 -tags=integration -timeout=90s -v \
    -run '^TestMessaging(DuplicateAndReorderedDelivery|ProcessCrashBeforeACK|ObsoleteDispatchContention|ObsoletePreservesUncertainty)$' ./internal/workflow
  echo 'RF-09 focused verification PASS'
  exit 0
fi
if [[ "$mode" == rf08 ]]; then
  phase='RF-08 bounded retries, semantic budgets and recovery'
  run_bounded 180 go test -race -count=1 -tags=integration -timeout=120s -v \
    -run '^TestRetry' ./internal/workflow
  phase='RF-08 repeated semantic/cancellation races'
  run_bounded 120 go test -race -count=5 -tags=integration -timeout=90s -v \
    -run '^TestRetry(CompletionAndCancellationRaces|ConcurrentDecisions|AdmissionReconciliation)$' ./internal/workflow
  echo 'RF-08 focused verification PASS'
  exit 0
fi
if [[ "$mode" == rf07b ]]; then
  phase='RF-07B owned termination, cleanup and durable finalization'
  run_bounded 180 go test -race -count=1 -tags=integration -timeout=120s -v \
    -run '^Test(Execution|CancellationFinalization)' ./internal/workflow
  phase='RF-07B repeated registration/startup and finalization races'
  run_bounded 120 go test -race -count=5 -tags=integration -timeout=90s -v \
    -run '^Test(ExecutionCancellationStartup|CancellationFinalizationConcurrency|CancellationFinalizationControllerCrashes)$' ./internal/workflow
  echo 'RF-07B focused verification PASS: owned termination/reap, immutable evidence, restart and finalization faults'
  exit 0
fi
if [[ "$mode" == rf07a ]]; then
  phase='RF-07A durable cancellation request regressions'
  run_bounded 120 go test -race -count=1 -tags=integration -timeout=90s -v \
    -run '^TestCancellation' ./internal/workflow
  phase='RF-07A repeated cancellation/completion and control-identity races'
  run_bounded 120 go test -race -count=5 -tags=integration -timeout=90s -v \
    -run '^TestCancellation(CompletionRace|ConcurrentOperationIdentity)$' ./internal/workflow
  echo 'RF-07A focused verification PASS: durable stop intent, replay, fencing, COMMIT faults and repeated races'
  exit 0
fi
if [[ "$mode" == rf06-process ]]; then
  phase='RF-06 repeated process crash after result COMMIT before application ACK'
  run_bounded 120 go test -race -count=5 -tags=integration -timeout=90s -v \
    -run '^TestResultProcessCrashAfterCommitBeforeACK$' ./internal/workflow
  echo 'RF-06 process verification PASS: five repetitions, checkpoint control and B0 rejection'
  exit 0
fi
if [[ "$mode" == b2 ]]; then
  phase='B2 locked acceptance, result replay and commit uncertainty'
  run_bounded 180 go test -race -count=1 -tags=integration -timeout=120s -v \
    -run '^Test(Result|AdvanceInvestigation|Heartbeat)' ./internal/workflow
  echo 'B2 focused verification PASS: locked acceptance, receipts, replay and commit uncertainty'
  exit 0
fi
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
echo 'VN-T11 disposable verification PASS: proof guard, V1-V10, B0/B1/B2/RF-06/RF-07/RF-08/RF-09/RF-10 regressions'
