# ASEP single-node host firewall gate — PROPOSED / NOT ACTIVATED

> Versioned reference profile, sanitized on 2026-11-03: `192.0.2.10` and
> `example0` are documentation-only stand-ins, not observed host settings.
> Historical host-specific inputs, review manifest and original reports are
> preserved locally in ignored `tmp/k3s-precommit-original-2026-11-03/`.
> Earlier reported test/host observations refer to that original profile, not
> fresh runtime evidence for these examples. The example manifest is not Owner
> approval. Adapt configuration, checker/helper constants and baseline together
> under separate review; never install/start this reference profile unchanged.

ADR-VN-05 remains PROPOSED. This is an additive host INPUT guard for the three
K3s ports, not a whole-host firewall or permission to install/start anything.
Protected VN-T14 remains host/Docker; no delivery contract or migration changes.
Commands below are FUTURE Owner operations requiring separate approval. None
has been run against the host during this preparation.

## 1. Evidence and smallest policy

Owner-supplied observations, 2026-11-01: UFW **inactive**, Docker NAT/FORWARD chains
present, no observed nft INPUT hook; Docker 172.17/16, 172.18/16, 172.19/16; physical
`example0` / `192.0.2.10/24`; full-tunnel `tun0` /1 routes. These are supplied
observations, not fresh privileged inspection or proof of all effective hooks.
Do not activate/reconfigure UFW or the distribution's global nftables service.

`firewall.nft.example` owns ONLY `table inet asep_k3s_guard`, with one INPUT base chain at
priority 10 and default accept. No NAT, FORWARD, OUTPUT, route, sysctl, existing
table or VPN changes. Accept here does not override another chain's denial; drop
is final. Existing Docker/CNI/VPN filtering still applies.

| INPUT flow | Verdict in this guard |
| --- | --- |
| `lo` -> TCP 6443/10250, UDP 8472, either IP family | Allow local host/control-plane access, including own node address through loopback |
| `cni0`, IPv4 source `10.42.0.0/16`, destination `192.0.2.10`, TCP 6443 | Allow local pod -> node API, including Kubernetes Service 443 after DNAT |
| All remaining TCP 6443/10250 or UDP 8472, IPv4 AND IPv6 | Drop, including LAN, VPN, Docker bridges, wrong/IPv6 pod sources and other interfaces |
| Any other INPUT flow | Unchanged by this guard, including SSH/developer access, DNS/DHCP, PostgreSQL/inference |

There is deliberately no broad established/related accept before the port drops:
old unauthorized connections must not bypass the guard. No broad pod-CIDR trust
on physical/VPN interfaces; spoofed pod sources there are denied. Single-node
Flannel has no remote peer, so external VXLAN is unnecessary; TCP 10250 is needed
only from the local host with metrics-server disabled. No pod 10250 allowance is
added. Future metrics/multi-node/dual-stack requires another reviewed policy.

The host cannot identify pod namespaces from `cni0` alone. The narrow API allowance
supports CoreDNS/system controllers; workload NetworkPolicy, disabled tokens and
API authorization remain mandatory. It grants no identity, credential or API
authorization. Ordinary app/pilot API egress remains denied by their policies.
Flannel's local bridge must actually be `cni0`, with source in the configured CIDR;
if actual packet paths differ, STOP/review, never broaden to all pod traffic.

No public proxy, DNAT, NodePort, hostNetwork pod or port-forward may relay these
ports through allowed local traffic. Root/local privileged users can bypass host
controls; they are trusted, and cannot be the untrusted agent identity.

## 2. Reviewed files and future installation

- `firewall.nft.example`: static five-rule guard.
- `firewall`: stdlib-only, isolated Python root helper. Fixed installed paths;
  refuses checkout execution, writable/symlink/hardlinked inputs or mismatching
  independently approved root baseline. No arbitrary table/config/command flags.
- `asep-k3s-firewall.service`: oneshot persistent boot loader; ordered after any
  already-starting global nftables/UFW units without pulling them in; no removal
  on stop. Other managers/boot ordering still require inspection.
- `k3s-firewall.conf`: BindsTo + After dependency and appended live-check pre-start.
- `test_firewall.py`: offline grammar/model/JSON/start-contract tests, never root.

Follow the runbook's independent baseline approval and trusted system checksum
bootstrap FIRST. The updated candidate includes the four executable/configuration
inputs and this document. It is still NOT Owner approval. Use only verified
root-protected staging. Inspect every destination/ancestor for ownership, symlinks,
hardlinks and ACL writes before copying; unexpected existing files require review.
Installer-generated K3s must remain disabled/stopped until all gates pass.

Future installation, after separately authorized stopped K3s installation:

```bash
sudo install -d -o root -g root -m 0755 /usr/local/libexec /etc/systemd/system/k3s.service.d
sudo install -d -o root -g root -m 0700 /etc/asep-k3s-firewall
sudo install -o root -g root -m 0755 /opt/asep-k3s-stage/firewall /usr/local/libexec/asep-k3s-firewall
sudo install -o root -g root -m 0600 /opt/asep-k3s-stage/firewall.nft.example /etc/asep-k3s-firewall/firewall.nft
sudo install -o root -g root -m 0644 /opt/asep-k3s-stage/asep-k3s-firewall.service /etc/systemd/system/asep-k3s-firewall.service
sudo install -o root -g root -m 0644 /opt/asep-k3s-stage/k3s-firewall.conf /etc/systemd/system/k3s.service.d/20-asep-firewall.conf
sudo cmp --silent /opt/asep-k3s-stage/firewall /usr/local/libexec/asep-k3s-firewall
sudo cmp --silent /opt/asep-k3s-stage/firewall.nft.example /etc/asep-k3s-firewall/firewall.nft
sudo cmp --silent /opt/asep-k3s-stage/asep-k3s-firewall.service /etc/systemd/system/asep-k3s-firewall.service
sudo cmp --silent /opt/asep-k3s-stage/k3s-firewall.conf /etc/systemd/system/k3s.service.d/20-asep-firewall.conf
sudo systemctl daemon-reload
sudo systemd-analyze verify /etc/systemd/system/asep-k3s-firewall.service /etc/systemd/system/k3s.service
```

Installation is repeatable with identical reviewed bytes; it is not authorization
to overwrite an independently changed configuration. Existing `k3s.service.d`
drop-ins must be inspected for conflicting dependency/pre-start resets. Do not
overwrite/reset vendor ExecStart/ExecStartPre or enable K3s here.

## 3. Enforce BEFORE any API start; boot and DHCP

Confirm no pre-existing API/listener, and record effective rules/chain counters
before activation under later Owner authorization. The helper uses one checked
nft transaction to add-if-absent, delete and recreate ONLY the ASEP table. Atomic
replacement avoids an unfiltered delete/load gap and duplicate rules; failed
transactions retain old state. Kernel/native validation is required on this host.
It checks the live JSON table's family, flags, hook, priority, policy, exact rule
expressions AND order; checking comments, file content or unit state alone cannot
satisfy the gate. Handles/counter values may vary. Other table names are untouched.
Use only guarded `systemctl start k3s`, never a direct `k3s server` launch that
bypasses the drop-in. Root-launched alternative units/jobs must not run the API.

```bash
# K3s is still stopped. This enables only the firewall boot loader.
sudo systemctl enable --now asep-k3s-firewall.service
sudo /usr/local/libexec/asep-k3s-firewall check
sudo systemctl show k3s.service --property=BindsTo --property=After --property=ExecStartPre
sudo /usr/sbin/nft list table inet asep_k3s_guard
```

If native parser/transaction/live-JSON verification fails, DO NOT start K3s; retain
any old protection and investigate the error. No "continue anyway" option exists.
Apply twice while K3s is stopped and confirm one table/chain, five rules and no
changes to other tables (compare stable structure; counters legitimately move).
Do not reload/restore a captured global ruleset over Docker/VPN.

Before first API start, with ONLY the guard active, verify a normal VPN reconnect
does not flush/disable the table. Resolve Pritunl/global-rule loader behavior
first if uncertain. Full-tunnel/split-DNS gates from NETWORK-REVIEW.md still apply;
this table never bypasses that preflight or installs route exclusions.

K3s service startup waits for successful guard activation; every K3s start/restart
checks actual kernel rules, protected installed inputs and the configured address
before ExecStart. Missing/changed rules or address blocks startup even if the
oneshot guard unit is marked active. BindsTo stops K3s if the guard service becomes
inactive; stopping that service retains its rules. The guard loads without needing
the DHCP lease, but K3s pre-start requires `192.0.2.10` on `example0`.

Reserve the DHCP address. If the lease changes, do NOT rewrite rules automatically:
K3s pre-start fails and the interface-independent external drops remain active.
If it changes while running, stop K3s and review config/TLS/address/DNS and guard
allowlist together, regenerate the candidate, obtain new baseline approval and
retest before restart. Never remove external drops to recover API access.

Enable only this dedicated guard service for reboot persistence; leave K3s disabled
until separate boot approval. When K3s is later enabled, its dependency/pre-start
ordering still applies. An Owner-authorized reboot rehearsal must verify the
guard is present before any API listener and developer/VPN services recover.
No generic `nftables.service` enable/restart or `/etc/nftables.conf` replacement.

**Limit:** oneshot/systemd ordering is not a continuous monitor for another root
process flushing rules while K3s runs. Root firewall managers must preserve this
table; an unknown/full-table-flush manager BLOCKS start. After any firewall/VPN
manager change, recheck before API operation; on loss, stop K3s and re-establish
protection under Owner control. No unproven continuous-enforcement claim is made.

## 4. Actual verification — required, NOT EXECUTED

After all pre-start reviews, use the main runbook's separate controlled-start
approval. Record actual IPv4/IPv6 listeners, not assumptions from YAML. The guard
covers both families even though the planned CNI/control-plane is IPv4-only.

| Gate | Required evidence |
| --- | --- |
| Local/API | Local root-admin readyz and node Ready; successful loopback/local-node API traffic; do not expose or copy kubeconfig |
| Local kubelet | Local host TCP connectivity on 10250; authenticated node/control-plane health; authentication errors alone are not health proof |
| CNI/system pods | CoreDNS Ready, Kubernetes Service endpoint watch works, actual `cni0` source/destination and `asep:cni-api` counter evidence; no blocked required flows |
| LAN/VPN denial | Authorized independent peers test TCP 6443 AND 10250 over LAN/VPN IPv4 and any routable IPv6 while local health passes; no TCP handshake/TLS/401/403 response allowed; matching drop counters/effective rules |
| Overlay denial | Owner-authorized UDP 8472 probes from those peers increment `asep:deny-overlay`; a UDP timeout by itself is not proof of filtering; do not inject fabricated VXLAN workloads |
| Pilot/DNS/policy | Staged verify-pilot passes DNS and Service:80/PodIP:8080 allow/deny plus RBAC/admission; approved private/public DNS canaries preserve VPN split scope with no leaks |
| Docker/DB/development | Existing Docker containers/bridges/volumes remain healthy; PostgreSQL remains loopback 55432 with existing readiness; DNS, inference loopback 11434 and existing development access work; no DB login/token needed |
| Reconnect/reboot | Repeat guard live check, local/CNI health, peer denials, DNS, Docker/DB after normal Pritunl reconnect and separately approved reboot; compare unrelated rules' structure |

No available peer/IPv6 path/counter/native-CNI evidence means UNKNOWN, not PASS.
Do not install packet generators or change networking to manufacture evidence.
The host source/destination API exception models Service DNAT to node:6443; this
must be observed on the pinned CNI/kube-proxy stack. Other INPUT chains can still
deny allowed traffic, and namespace policies still control pod flows. No claim
that local model tests prove actual kernel/CNI coexistence is permitted.

## 5. Safe rollback / failures

The safe default is **stop K3s and retain the guard/data**, not remove protection:

```bash
sudo systemctl stop k3s.service
sudo systemctl disable k3s.service
```

Stopping K3s leaves containers running: remove only the known stateless pilot
while its API is healthy as described in the main runbook. Confirm no API/kubelet
listener or unintended workload remains before calling rollback complete. Keep
all cluster/database/volume/config/evidence state. On DNS/CNI breakage, investigate
the narrowly scoped allow rule and other chains; do not enable broad pod trust.

Only if the Owner explicitly approves complete guard removal AFTER K3s is stopped,
disabled, no protected listener remains and no restart job is pending:

```bash
sudo systemctl disable --now asep-k3s-firewall.service
sudo /usr/sbin/nft delete table inet asep_k3s_guard
```

Keep the drop-in, guard unit and protected reviewed files/data for inspection.
Do not remove the K3s dependency/pre-start guard: a later explicit K3s start must
reactivate protection first or fail, even though both services are now disabled.
If that table is already absent,
confirm absence, do not delete anything else. Never `flush ruleset`, restore a
stale global dump, prune Docker, uninstall K3s or change VPN/UFW. Reinstallation/
restart requires Owner review and restored guard activation first. For a
policy revision, retain protection and reapply only newly reviewed bytes; do not
use automatic timed deletion rollback that could expose the API.

## Sources / current evidence

- [Netfilter chain traversal](https://wiki.nftables.org/wiki-nftables/index.php/Configuring_chains)
- [nft commands / atomic batches / JSON](https://netfilter.org/projects/nftables/manpage.html)
- [K3s network requirements](https://docs.k3s.io/installation/requirements)
- [systemd BindsTo and ordering](https://www.freedesktop.org/software/systemd/man/latest/systemd.unit.html)

Static/model tests and actual execution evidence are recorded in the runbook.
No privileged command, packet probe, service activation or credential access has
occurred. Effective firewall, native parser/transactions, CNI, peers, Pritunl
reconnect and reboot persistence remain **NOT VERIFIED / Owner gate**.
