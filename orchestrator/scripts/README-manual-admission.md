# Private VN-T12 admission fixture

Run from `orchestrator/` with Java 21+, Maven wrapper prerequisites, Go, Docker,
Python 3 and the existing disposable PostgreSQL/Flyway images available:

```bash
ASEP_VN_T10_DISPOSABLE_DB=true \
ASEP_VN_T11_HARNESS_MODE=manual-admission bash scripts/ci_verify.sh
```

For a provisioned offline dependency cache, add `ASEP_VN_T12_MAVEN_OFFLINE=true`.
The default `all` mode also builds and runs this fixture. CI provisions Java 21
in the orchestrator job. Compilation/test failures are fatal; the new integration
test is explicitly checked during discovery. Focused VN-T11 modes retain their
existing dependencies.

The runner compiles `ManualAdmissionProcessFixture` from Java test sources and
exports Maven's resolved test classpath into its private temporary directory.
The Go integration test starts bounded JVM processes with private stdin/stdout
pipes and an allowlisted environment containing only LANG/TZ. Java receives
synthetic fixture data and trusted synthetic policy, never Go database credentials,
Docker access, service tokens or disposable-instance proof. No listener or
production authentication bypass is installed. The fixture is absent from the
backend production classes/JAR.

Java executes the actual normalization and manual admission coordinator. Go
executes the existing bounded durable admission/reconciliation implementation
against the proven disposable database. A separate PostgreSQL connection counts
runs, attempts, tasks, transitions and outbox rows for every scenario and checks
the accepted actor, MANUAL trigger, source fingerprint, policy and budget metadata.

Coverage includes valid admission and duplicate replay, denied project, missing
mapping, missing/revoked/unavailable policy before reading and after preparation,
changed snapshot, conflicting repository payload, transport EOF before admission,
and transport EOF after Go COMMIT. The last case restarts a real JVM, reloads the
original command from a test-owned journal and reconciles through a fresh Go
connection. A changed snapshot on restart preserves UNKNOWN. Rejected scenarios
have zero durable effects; replay/conflict/recovery retain one logical bundle.

DENIED is definitive local refusal before submission. CONFLICT is emitted only
for Go's definitive conflict result. Transport EOF and unavailable reconciliation
remain UNKNOWN with no receipt. The parent independently proves whether the test
injected the EOF before admission or after COMMIT; Java cannot infer rollback from
that EOF. Recovery never allocates a replacement command. This journal is a test
fixture, not production Java request persistence.

`GuardedJiraHttpReaderTest` separately exercises the actual HTTP boundary with
owned JDK loopback servers and redirect/link canaries. Its sole enrollment factory
accepts literal `http://127.0.0.1:<port>` origins, disables proxies and redirects,
constructs GET issue paths from validated numeric IDs, fixes the requested fields,
and bounds response allocation plus complete-response time. The offline and HTTP
readers share normalization without changing the fixture-v1 fingerprint codec.
There is no live Jira activation path; real origins, credentials and runtime policy
verification require separately authorized implementation. No real Jira is used.

The existing runner bounds process groups, verifies database ownership and removes
only its recorded owned containers. Java peers are deadline-bounded and reaped on
success or failure; test journals use Go's temporary-directory cleanup.
