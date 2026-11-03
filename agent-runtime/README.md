# ASEP AgentTask v1 — deterministic synthetic adapter

`asep_agent/worker.py --synthetic` reads one bounded JSON command on stdin and
writes one untrusted result on stdout. Go owns the run/task/attempt/version,
policy epoch, source/base binding, lease fencing, call/token reservation and
elapsed deadline. No field in the result is a test, review or delivery receipt.

This is a controlled-response CI adapter, **not successful local inference** or
an OpenHands deployment. No cloud provider, credentials, database, GitLab/Jira
transport, SDK download or fallback is implemented. The trusted supervisor must
start it with a cleared environment, enforce process/output/deadline bounds and
wait for termination before a protected controller seals candidate bytes.

Run its isolated contract tests from the repository root:

```bash
python3 -B -m unittest discover -s agent-runtime/tests -v
```

The adapter uses only the Python standard library (Python3.12+). CI uses the
Ubuntu24.04 system Python and checks its version explicitly; no model/SDK
service is installed or contacted.

The three roles are investigation, planning and implementation of the exact
single-file synthetic probe. Only the supervised proposal may be copied into the
candidate; protected tests/review run separately and bind their own evidence.
