#!/usr/bin/env bash
# Private VN-T13 fixture. Never use the developer/Compose database or a live GitLab.
set +x
set -euo pipefail
umask 077
[[ "${ASEP_VN_T13_DISPOSABLE_DB:-}" == true ]] || { echo 'ERROR: ASEP_VN_T13_DISPOSABLE_DB=true required' >&2; exit 2; }
for tool in docker java python3 timeout setsid; do
  command -v "$tool" >/dev/null || { echo "ERROR: missing $tool" >&2; exit 2; }
done
cd "$(dirname "${BASH_SOURCE[0]}")/.."
run_id="$(python3 -c 'import secrets; print(secrets.token_hex(16))')"
database="asep_delivery_test_$run_id"
label='io.github.qhung13.asep.delivery-test-run'
private_dir="$(mktemp -d "${TMPDIR:-/tmp}/asep-delivery.XXXXXXXX")"
active_group=''
phase='setup'
docker_cmd() { timeout --kill-after=5s 45s docker "$@"; }
stop_active() {
  if [[ -n "$active_group" ]]; then
    kill -TERM -- "-$active_group" 2>/dev/null || true
    for _ in {1..20}; do kill -0 -- "-$active_group" 2>/dev/null || break; sleep 0.1; done
    kill -KILL -- "-$active_group" 2>/dev/null || true
    wait "$active_group" 2>/dev/null || true
    active_group=''
  fi
}
cleanup() {
  local status=$? resource cid actual failed=false
  trap - EXIT INT TERM
  stop_active
  for resource in flyway flyway_validate postgres; do
    if [[ -s "$private_dir/$resource.cid" ]]; then
      read -r cid < "$private_dir/$resource.cid" || true
      if [[ ! "$cid" =~ ^[0-9a-f]{64}$ ]]; then failed=true; continue; fi
      actual="$(docker_cmd inspect --format "{{ index .Config.Labels \"$label\" }}" "$cid" 2>/dev/null)" || { failed=true; continue; }
      if [[ "$actual" != "$run_id" ]]; then failed=true; continue; fi
      docker_cmd rm -fv "$cid" >/dev/null 2>&1 || failed=true
    fi
  done
  if [[ "$failed" == true ]]; then
    echo "ERROR: owned cleanup incomplete; cidfiles retained in $private_dir" >&2
    status=1
  else
    rm -rf -- "$private_dir"
    echo "VN-T13 owned cleanup PASS (run $run_id)"
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'echo "ERROR: VN-T13 fixture failed at $phase; sensitive diagnostics withheld" >&2' ERR
run_bounded() {
  local seconds=$1 result=0
  shift
  setsid timeout --signal=TERM --kill-after=5s "$seconds" "$@" &
  active_group=$!
  wait "$active_group" || result=$?
  if [[ "$result" != 0 ]]; then stop_active; return "$result"; fi
  active_group=''
}
phase='existing image availability'
# Provision images separately. Never pull unreviewed artifacts as an executor capability.
for image in postgres:17-alpine flyway/flyway:11; do
  docker_cmd image inspect --format '{{json .RepoDigests}}' "$image"
done
python3 - "$private_dir" <<'PY'
from pathlib import Path
import secrets,sys
p=Path(sys.argv[1])
for name in ('admin','migration','runtime','nonce'):
    (p/name).write_text(secrets.token_hex(32)+'\n')
PY
read -r admin_password < "$private_dir/admin"
read -r migration_password < "$private_dir/migration"
read -r runtime_password < "$private_dir/runtime"
read -r nonce < "$private_dir/nonce"
printf 'POSTGRES_USER=postgres\nPOSTGRES_DB=%s\nPOSTGRES_PASSWORD=%s\n' "$database" "$admin_password" > "$private_dir/postgres.env"
phase='owned disposable PostgreSQL'
docker_cmd create --cidfile "$private_dir/postgres.cid" --label "$label=$run_id" \
  --network bridge --publish '127.0.0.1::5432' --memory 512m --cpus 1.5 --pids-limit 128 \
  --env-file "$private_dir/postgres.env" postgres:17-alpine > "$private_dir/create.log" 2>&1
postgres_id="$(cat "$private_dir/postgres.cid")"
docker_cmd start "$postgres_id" >/dev/null
port="$(docker_cmd inspect --format '{{(index (index .NetworkSettings.Ports "5432/tcp") 0).HostPort}}' "$postgres_id")"
[[ "$port" =~ ^[0-9]+$ && "$port" -gt 0 && "$port" -le 65535 ]]
ready=false
for _ in {1..60}; do
  if docker_cmd exec "$postgres_id" pg_isready -h 127.0.0.1 -U postgres -d "$database" > "$private_dir/ready.log" 2>&1; then ready=true; break; fi
  sleep 0.2
done
[[ "$ready" == true ]]
phase='least privilege roles and read-only instance proof'
docker_cmd exec -i "$postgres_id" psql -X -v ON_ERROR_STOP=1 -q -U postgres -d "$database" > "$private_dir/provision.log" 2>&1 <<SQL
CREATE ROLE asep_delivery_migrator LOGIN PASSWORD '${migration_password}';
CREATE ROLE asep_delivery_runtime LOGIN PASSWORD '${runtime_password}';
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA code_delivery AUTHORIZATION asep_delivery_migrator;
CREATE SCHEMA orchestration AUTHORIZATION postgres;
CREATE TABLE orchestration.delivery_owner_guard(id integer);
CREATE SCHEMA asep_delivery_test_proof AUTHORIZATION postgres;
REVOKE ALL ON SCHEMA asep_delivery_test_proof FROM PUBLIC;
CREATE TABLE asep_delivery_test_proof.instance(singleton boolean PRIMARY KEY CHECK(singleton),
  nonce text NOT NULL, database_name name NOT NULL, server_started_at timestamptz NOT NULL);
INSERT INTO asep_delivery_test_proof.instance VALUES(true,'${nonce}',current_database(),pg_postmaster_start_time());
GRANT USAGE ON SCHEMA asep_delivery_test_proof TO asep_delivery_runtime;
GRANT SELECT ON asep_delivery_test_proof.instance TO asep_delivery_runtime;
SQL
printf 'FLYWAY_URL=jdbc:postgresql://127.0.0.1:%s/%s\nFLYWAY_USER=asep_delivery_migrator\nFLYWAY_PASSWORD=%s\nFLYWAY_SCHEMAS=code_delivery\nFLYWAY_DEFAULT_SCHEMA=code_delivery\nFLYWAY_BASELINE_ON_MIGRATE=false\n' \
  "$port" "$database" "$migration_password" > "$private_dir/flyway.env"
phase='Java-owned V1-V3 migrations'
for resource in flyway flyway_validate; do
  docker_cmd create --cidfile "$private_dir/$resource.cid" --label "$label=$run_id" \
    --network host --env-file "$private_dir/flyway.env" -v "$PWD/db/migrations:/flyway/sql:ro" \
    flyway/flyway:11 migrate > "$private_dir/$resource-create.log" 2>&1
  cid="$(cat "$private_dir/$resource.cid")"
  docker_cmd start -a "$cid" > "$private_dir/$resource.log" 2>&1
  [[ "$(docker_cmd inspect --format '{{.State.ExitCode}}' "$cid")" == 0 ]]
done
history="$(docker_cmd exec "$postgres_id" psql -X -qAt -U postgres -d "$database" -c "SELECT string_agg(version,',' ORDER BY installed_rank) FROM code_delivery.flyway_schema_history WHERE success")"
[[ "$history" == 1,2,3 ]]
echo "VN-T13 V1-V3 migrate + repeat/validate PASS (run $run_id)"
export ASEP_DELIVERY_DISPOSABLE=true ASEP_DELIVERY_DB_USER=asep_delivery_runtime
export ASEP_DELIVERY_JDBC_URL="jdbc:postgresql://127.0.0.1:$port/$database"
export ASEP_DELIVERY_DB_PASSWORD="$runtime_password" ASEP_DELIVERY_NONCE="$nonce"
maven_options=(--batch-mode --no-transfer-progress)
if [[ "${ASEP_VN_T13_MAVEN_OFFLINE:-false}" == true ]]; then maven_options+=(--offline); fi
phase='durable fake GitLab RF-12..RF-16 tests'
run_bounded 180 ./mvnw "${maven_options[@]}" -Dasep.delivery.postgres=true \
  -Dtest=CodeDeliveryContractTest,CodeDeliveryPostgresTest test
phase='test discovery and skip audit'
python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
p=Path('target/surefire-reports/TEST-io.github.qhung13.asep.codedelivery.CodeDeliveryPostgresTest.xml')
r=ET.parse(p).getroot()
assert int(r.get('tests','0')) >= 40
assert all(int(r.get(k,'0'))==0 for k in ('errors','failures','skipped'))
print('VN-T13 PostgreSQL discovery/no-skip PASS:',r.get('tests'),'executed tests')
PY
phase='repeated concurrent replay'
for repetition in {1..5}; do
  run_bounded 90 ./mvnw "${maven_options[@]}" -Dasep.delivery.postgres=true \
    '-Dtest=CodeDeliveryPostgresTest#concurrentExactIntentsHaveOneSendPerStep' test > "$private_dir/replay-$repetition.log" 2>&1
  python3 - "$repetition" <<'PY'
from pathlib import Path
import sys,xml.etree.ElementTree as ET
r=ET.parse(Path('target/surefire-reports/TEST-io.github.qhung13.asep.codedelivery.CodeDeliveryPostgresTest.xml')).getroot()
assert int(r.get('tests','0'))==1 and all(int(r.get(k,'0'))==0 for k in ('errors','failures','skipped'))
print('VN-T13 concurrent replay repetition',sys.argv[1],'PASS')
PY
done
echo 'VN-T13 private ledger/fake GitLab verification PASS; live delivery/authentication not exercised'
