# ASEP hybrid K3s development foundation — PROPOSED / NOT INSTALLED

> Versioned reference profile, sanitized on 2026-11-03: `192.0.2.10` and
> `example0` are documentation-only stand-ins, not observed host settings.
> Historical host-specific inputs, review manifest and original reports are
> preserved locally in ignored `tmp/k3s-precommit-original-2026-11-03/`.
> Earlier reported test/host observations refer to that original profile, not
> fresh runtime evidence for these examples. The example manifest is not Owner
> approval. Adapt configuration, checker/helper constants and baseline together
> under separate review; never install/start this reference profile unchanged.

Review [ADR-VN-05](../../docs/architecture/adr/vnext/ADR-VN-05-HYBRID-K3S-DEPLOYMENT.md)
before installation. Owner selected the hybrid direction; this concrete plan is
still PROPOSED. It changes platform deployment timing, not VN-T14 functionality.
No command in this document has installed/started a cluster. No installer runs
automatically. Protected host/Docker delivery, migrations and `specs/` are untouched.

## 1. Reviewable contents and current checks

| File | Purpose |
| --- | --- |
| `versions.lock.json` | Explicit amd64 K3s 1.36.5+k3s1 artifact URLs/SHA256/sizes and pilot image digest |
| `config.yaml.example` | Proposed host address/interface, CIDRs, SQLite/containerd, encryption, disabled public/storage addons and audit wiring |
| `kubelet.conf` | Recommended kubelet drop-in: host swap retained, pods NoSwap |
| `audit-policy.yaml` | Metadata-only, bounded file audit; no request/response bodies |
| `namespaces.yaml` | Two version-pinned Restricted namespaces; created first so namespaced server dry-run can work |
| `baseline.yaml` | Quotas, explicit network policies, pilot SA/observer/tester RBAC; no Secrets |
| `pilot.yaml` | Three non-sensitive pods plus private ClusterIP; no host/PVC/API credentials |
| `check.py`, `preflight` | Offline checks plus optional read-only host snapshot; never read kubeconfig |
| `NETWORK-REVIEW.md` | Blocking API/firewall/VPN/split-DNS review and actual runtime acceptance matrix |
| `FIREWALL.md`, `firewall.nft.example`, `firewall` | Isolated IPv4/IPv6 INPUT guard, fixed-path root helper, installation/rollback and acceptance gates |
| `asep-k3s-firewall.service`, `k3s-firewall.conf` | Dedicated boot loader and mandatory live pre-start gate; no global nftables/UFW changes |
| `integrity.py`, `EXAMPLE-REVIEW.SHA256` | Unprivileged candidate-baseline review/check; NOT independent approval or a root trust anchor |
| `fetch` | Optional future public artifact download/checksum verification; never install/execute |
| `verify-pilot` | Future explicit Owner invocation of cluster/network/RBAC/server-dry-run checks |
| `test_verify_pilot.py` | Offline fake-CLI success/failure checks, no runtime/identity evidence; never run as root |
| `test_firewall.py` | Offline packet-model/live-JSON/start-order negative tests; no kernel/firewall commands |

From repository root, no network, credentials or cluster required:

```bash
bash deploy/k3s/preflight --offline
python3 -B deploy/k3s/check.py --self-test
python3 -B deploy/k3s/integrity.py --check
python3 -B deploy/k3s/integrity.py --self-test
python3 -B deploy/k3s/test_verify_pilot.py
python3 -B deploy/k3s/test_firewall.py
bash -n deploy/k3s/preflight deploy/k3s/verify-pilot
git diff --check
```

Requires Python 3 and existing PyYAML; no package installation is performed.
These are syntax/security-invariant checks with negative cases, **not Kubernetes
API-schema or runtime validation**. `kubectl`, `k3s`, `kubeconform` and ShellCheck
were unavailable during preparation. Use server dry-run after the Owner authorizes
installation. The pilot commands below have NOT run against a cluster.

## 2. Host readiness — first proposed step is read-only

```bash
bash deploy/k3s/preflight --host
```

Re-run on the actual Owner terminal with normal VPNs enabled. This rejects an
existing K3s install, CIDR/port collisions and changed node identity. It reads
address/routes, service state and cgroup metadata only; it does not use sudo or
read credentials. The snapshot cannot grant readiness: even without a detected
collision, it exits blocked for outstanding operational gates. Use
`bash deploy/k3s/preflight --diagnostics` to collect local metadata without a
readiness verdict. Permission-denied commands remain UNKNOWN.

Owner must resolve before host mutation/start:

- Confirm `192.0.2.10` on `example0` is reserved/stable; it was DHCP-derived.
  If changing addresses/CIDRs/interface, review config and checks together.
- Confirm 10.42.0.0/16 pods and 10.43.0.0/16 Services do not conflict with all
  usual VPN routes, including reconnect and split DNS. Current LAN and Docker
  networks do not overlap; disconnected VPN behavior remains UNKNOWN.
  The final host check observed `tun0` with full-tunnel `0.0.0.0/1` and
  `128.0.0.0/1` routes and stopped conservatively. Such routes are not proof that
  the VPN owns the proposed pod/service address space; they require an explicit
  coexistence/kill-switch/exclusion plan. Changing CIDRs alone cannot remove this
  full-tunnel uncertainty. Do not disconnect the VPN or bypass the check silently.
- Confirm `/run/systemd/resolve/resolv.conf` contains approved reachable upstream
  resolvers. This file flattens per-link split DNS: it cannot by itself preserve
  VPN domain routing. If split routing exists, STOP for a separately reviewed
  forwarding configuration; no public fallback or private-query leakage.
- Review effective UFW/nftables/Docker rules, including IPv6. Do not disable
  UFW/NetworkManager, flush firewall tables, replace Docker or republish PostgreSQL.
- Preserve PostgreSQL `127.0.0.1:55432`, inference `127.0.0.1:11434`, Docker bridges
  and their existing volumes. Record their status before/after and after rollback.
- Check pinned release security/Ubuntu 26.04/kernel 7 compatibility. Available
  hardware suggests pilot capacity, not a performance certification. Keep the
  pilot <=500m CPU/256Mi limits and initial apps <=2 CPU/2Gi limits; revise budgets
  explicitly before adding larger services. No GPU workloads/runtime changes.
- Confirm a private backup destination and a rehearsal recovery window.

Required firewall policy **before K3s start** (Owner must review actual rule order):

| Flow | Policy |
| --- | --- |
| Owner-local API use | Allow local loopback; admin kubeconfig stays root-only 0600 |
| Required local CNI/system pods to API 6443 | Allow only actual local pod/interface flows; test CoreDNS/controller access |
| LAN/WAN/other interfaces to API 6443 | Deny; do not publish via router, proxy, Docker or NodePort |
| Remote TCP 10250 and UDP 8472 | Deny for this single-node setup |
| Pod DNS | Only CoreDNS UDP/TCP 53 via explicit namespace policy |
| Pilot HTTP | Only allowed-client -> server TCP 8080; denied client must fail |
| Ordinary apps | DNS only until explicitly reviewed additional flows |

Config explicitly requests **IPv4 `0.0.0.0:6443`**, not privacy by binding. Loopback
and required local CNI/system flows must work while unauthorized LAN/VPN traffic
is denied. See the mandatory [network START gate](NETWORK-REVIEW.md) for rule-order
evidence, IPv4/IPv6, policy-routing/split-DNS acceptance and peer tests. No general firewall
rewrite or VPN bypass is provided. The dedicated [host guard](FIREWALL.md) must be
installed/activated/verified before any API start. **Do not START** without Owner-reviewed
effective policy; actual listener and unauthorized peer-denial tests follow the
first controlled start and block pilot/application acceptance. They cannot be
claimed before a cluster exists.

## 3. Reproducible public artifacts — future Owner download, no root

The selected minor's latest published patch found during review was 1.36.5+k3s1
(2026-09-30). Binary/airgap hashes were cross-checked against official release
checksums and asset metadata. Installer bytes were hashed from the exact tag.
BusyBox 1.37.0 is pinned to a manifest digest from public Docker Hub metadata.
No K3s executable or airgap archive was downloaded/executed during preparation.

After artifact/network review, choose a new user-owned output directory:

```bash
python3 -B deploy/k3s/fetch /tmp/asep-k3s-v1.36.5
python3 -B deploy/k3s/fetch --verify-only /tmp/asep-k3s-v1.36.5
```

The fetcher refuses root downloads/existing destinations, avoids ambient proxy
credentials and verifies bounded sizes/SHA256. Approved VPN/proxy requirements
must be resolved explicitly if direct public downloads are unavailable. It does
not fetch private registries or invoke an installer. Failed bundles are not usable;
retain for inspection or choose a different empty directory after review.

## 4. Independent integrity approval, then stopped installation — NOT AUTHORIZED

An attacker can replace a downloaded artifact AND its colocated `SHA256SUMS`.
`fetch --verify-only` detects corruption against its local lock, not independent
Owner approval. Neither copying the lock/checksums into root staging nor changing
their owner establishes that approval.

1. Freeze/review the source, lock, exact upstream installer bytes and every script,
   configuration and manifest listed in `EXAMPLE-REVIEW.SHA256`. Revalidate pins and
   provenance before installation; this revision performs no remote revalidation.
   `integrity.py --emit-candidate` can produce a new **candidate**, never approval.
   Any byte change requires a new review/baseline. Its check also pins all future
   root-run repository scripts, not just the K3s binary/installer.
2. The Owner must independently approve the exact 23-entry baseline (19 installation/
   review inputs plus four public artifacts; offline-only tests are not root-run),
   including its SHA256, through a trusted
   review/console outside the mutable download directory and agent checkout.
   On that separately authorized trusted console, create
   `/etc/asep-k3s-review` root:root 0700 and its `SHA256SUMS` root:root 0600 regular,
   single-link file from the **independently approved content**. Record the approved
   baseline digest separately. No such approval/file exists yet. Do not bootstrap
   it by blindly copying the candidate/download checksum or taking its digest from
   the same unreviewed directory. The privileged Owner terminal is trusted; an
   untrusted agent must not share its sudo/session/control capability.
3. Inspect ownership/modes, symlinks, hardlinks and ACLs of trust/staging paths and
   every ancestor. Only root can write; no extended/default ACL may grant write
   to others. If any path exists unexpectedly, STOP. These commands assume a fresh
   install, amd64 and approved paths. Existing installs need a recovery plan.

First staging mutation, after separate baseline provisioning/review (NOT executed):

```bash
sudo install -d -o root -g root -m 0700 /opt/asep-k3s-stage
```

Only AFTER the independent trust baseline exists, copy untrusted inputs into
fresh root-only staging. All copied bytes are still untrusted until verification.
Do not execute a checkout/download script as root to verify itself:

```bash
sudo install -o root -g root -m 0600 \
  /tmp/asep-k3s-v1.36.5/{k3s,install.sh,sha256sum-amd64.txt,k3s-airgap-images-amd64.tar.zst} \
  /opt/asep-k3s-stage/
sudo install -o root -g root -m 0600 \
  deploy/k3s/{README.md,NETWORK-REVIEW.md,FIREWALL.md,versions.lock.json,config.yaml.example,kubelet.conf,audit-policy.yaml,namespaces.yaml,baseline.yaml,pilot.yaml,check.py,preflight,fetch,verify-pilot,integrity.py,firewall.nft.example,firewall,asep-k3s-firewall.service,k3s-firewall.conf} \
  /opt/asep-k3s-stage/
```

Use trusted system tools and the **separately recorded Owner-approved digest**
before trusting the protected baseline. This literal placeholder fails closed;
do not populate it automatically from either mutable directory:

```bash
sudo /bin/sh -eu <<'ROOT'
approved_baseline=OWNER_APPROVED_64_HEX_DIGEST
test "${#approved_baseline}" -eq 64
case "$approved_baseline" in *[!0-9a-f]*) exit 2 ;; esac
test ! -L /etc/asep-k3s-review
test ! -L /etc/asep-k3s-review/SHA256SUMS
test ! -L /opt/asep-k3s-stage
test "$(stat -c '%u:%g:%a' /etc/asep-k3s-review)" = 0:0:700
test "$(stat -c '%u:%g:%a:%h' /etc/asep-k3s-review/SHA256SUMS)" = 0:0:600:1
test "$(stat -c '%u:%g:%a' /opt/asep-k3s-stage)" = 0:0:700
printf '%s  /etc/asep-k3s-review/SHA256SUMS\n' "$approved_baseline" | /usr/bin/sha256sum --check --strict -
cd /opt/asep-k3s-stage
for artifact in *; do
  test ! -L "$artifact"
  test -f "$artifact"
  test "$(stat -c '%u:%g:%a:%h' "$artifact")" = 0:0:600:1
done
/usr/bin/sha256sum --check --strict /etc/asep-k3s-review/SHA256SUMS
ROOT
```

Only continue if every anchored entry passes. Re-run this verification after any
staging change and before each root execution/apply. Root-only staging prevents an
unprivileged writer racing verified bytes. Manual parent/ACL checks above are
mandatory; file ownership alone does not establish the whole path trust boundary.
Copy ONLY from verified staging, never from the checkout after verification:

```bash
sudo install -d -o root -g root -m 0700 /etc/rancher/k3s
sudo install -o root -g root -m 0600 /opt/asep-k3s-stage/config.yaml.example /etc/rancher/k3s/config.yaml
sudo install -o root -g root -m 0600 /opt/asep-k3s-stage/audit-policy.yaml /etc/rancher/k3s/audit-policy.yaml
sudo install -d -o root -g root -m 0755 \
  /var/lib/rancher/k3s/agent/etc/kubelet.conf.d /var/lib/rancher/k3s/agent/images
sudo install -d -o root -g root -m 0700 \
  /var/lib/rancher/k3s/server /var/lib/rancher/k3s/server/logs
sudo install -o root -g root -m 0600 /opt/asep-k3s-stage/kubelet.conf \
  /var/lib/rancher/k3s/agent/etc/kubelet.conf.d/99-asep.conf
sudo install -o root -g root -m 0644 /opt/asep-k3s-stage/k3s-airgap-images-amd64.tar.zst \
  /var/lib/rancher/k3s/agent/images/k3s-airgap-images-amd64.tar.zst
sudo install -o root -g root -m 0755 /opt/asep-k3s-stage/k3s /usr/local/bin/k3s
sudo cmp --silent /opt/asep-k3s-stage/k3s /usr/local/bin/k3s
sudo cmp --silent /opt/asep-k3s-stage/config.yaml.example /etc/rancher/k3s/config.yaml
sudo cmp --silent /opt/asep-k3s-stage/audit-policy.yaml /etc/rancher/k3s/audit-policy.yaml
sudo cmp --silent /opt/asep-k3s-stage/kubelet.conf /var/lib/rancher/k3s/agent/etc/kubelet.conf.d/99-asep.conf
sudo cmp --silent /opt/asep-k3s-stage/k3s-airgap-images-amd64.tar.zst /var/lib/rancher/k3s/agent/images/k3s-airgap-images-amd64.tar.zst
sudo /usr/bin/env -i PATH=/usr/sbin:/usr/bin:/sbin:/bin:/usr/local/bin \
  INSTALL_K3S_VERSION=v1.36.5+k3s1 INSTALL_K3S_SKIP_DOWNLOAD=true \
  INSTALL_K3S_SKIP_ENABLE=true INSTALL_K3S_SKIP_START=true INSTALL_K3S_SYMLINK=skip \
  /bin/sh /opt/asep-k3s-stage/install.sh server --config /etc/rancher/k3s/config.yaml
```

This installs a **stopped, disabled** service with separately bundled containerd;
it does not use `--docker`, alter Docker configuration or overwrite CLI symlinks.
No install-time moving channel/download is used. Service bootstrap will later
generate privileged cluster credentials; this has NOT happened during preparation.
Before start, approve effective firewall/DNS/VPN policy and inspect the generated
unit/environment for the expected command and absence of inherited secrets.
Run `sudo systemctl daemon-reload` after inspecting the stopped unit; skip-enable
intentionally bypasses upstream service enable/start handling.
Generated service/helper files are derived output, not pre-approved scripts: review
the unit/ExecStart/drop-ins and hash-record outputs before start. Do not execute
generated killall/uninstall helpers without their own review/approved baseline.
The system shell, coreutils, systemd and root-controlled PATH remain trusted host
prerequisites; this procedure does not authenticate a compromised host.

**Mandatory intermediate step before section 5:** follow [FIREWALL.md](FIREWALL.md)
to install the verified root helper/rules, dedicated boot unit and K3s drop-in.
Activate ONLY the guard, verify its actual kernel table and systemd dependencies,
and test guard retention across a normal VPN reconnect while K3s is still stopped.
No API start may precede those checks. UFW is Owner-reported inactive; this plan
does not enable it or replace Docker/Pritunl rules.

## 5. Proposed start, health and isolated pilot — separate approval

Only after the independent baseline, installed-file comparison, generated-unit
review and ALL pre-start network gates have recorded Owner acceptance may the
Owner start (not enable at boot). There is no automatic bypass/approval flag.
The installed K3s drop-in must invoke the live firewall check on every start;
missing/mutated rules or a changed DHCP address must prevent ExecStart.
Immediately verify actual listeners/peer denial per NETWORK-REVIEW.md before
applying workloads; if exposure is unexpected, stop K3s and review:

```bash
sudo systemctl start k3s
sudo /usr/local/bin/k3s --version
sudo /usr/local/bin/k3s kubectl get --raw=/readyz
sudo /usr/local/bin/k3s kubectl get nodes -o wide
sudo /usr/local/bin/k3s kubectl -n kube-system get pods,svc
sudo /usr/local/bin/k3s secrets-encrypt status
sudo stat -c '%a %U:%G %n' /etc/rancher/k3s/k3s.yaml
```

Verify encryption actually Enabled, root-only kubeconfig, expected node/interface,
CoreDNS/network-policy readiness and absence of Traefik/ServiceLB/public ports.
Check effective kubelet config/metrics for failSwapOn=false and pod NoSwap; the
host's 8 GiB swap remains enabled. Do not print configz/credentials wholesale.
Host processes/control-plane memory can still swap; NoSwap is a pod policy.

Run server validation before persisting reviewed objects:

```bash
sudo /usr/local/bin/k3s kubectl apply --dry-run=server -f /opt/asep-k3s-stage/namespaces.yaml
sudo /usr/local/bin/k3s kubectl apply -f /opt/asep-k3s-stage/namespaces.yaml
sudo /usr/local/bin/k3s kubectl apply --dry-run=server -f /opt/asep-k3s-stage/baseline.yaml
sudo /usr/local/bin/k3s kubectl apply -f /opt/asep-k3s-stage/baseline.yaml
sudo /usr/local/bin/k3s kubectl apply --dry-run=server -f /opt/asep-k3s-stage/pilot.yaml
sudo /usr/local/bin/k3s kubectl apply -f /opt/asep-k3s-stage/pilot.yaml
```

Namespaces must be persisted first: server dry-run does not create a namespace
for subsequent namespaced dry-run requests. No application pods start in that step.

Re-run the independent anchored staging verification from section 4 and the
installed binary comparison before executing the already verified script/lock.
Do not replace them from the checkout or downloaded checksum file:

```bash
sudo /usr/bin/env -i HOME=/root PATH=/usr/sbin:/usr/bin:/sbin:/bin:/usr/local/bin \
  /bin/bash /opt/asep-k3s-stage/verify-pilot --run-after-owner-approval
```

Service TCP 80 maps to target Pod TCP 8080; policies allow the translated port
8080. The offline checker pins selectors/ports/listener/probes and both policy
directions. The runtime script checks actual Service/EndpointSlice relationships,
DNS and allow/deny HTTP through BOTH Service:80 and PodIP:8080. NAT/policy ordering
must pass on the pinned real stack; no offline PASS replaces this acceptance gate.
A failed kubectl exec cannot be mistaken for an in-pod connection denial.

The pilot has no credentials or persistent data. A trusted tester may exec only
inside its non-sensitive namespace; observer cannot exec. RoleBindings name groups
but provision no users/certificates. Impersonation tests evaluate RBAC, not actual
human identity. Negative admission uses server dry-run and requires explicit
PodSecurity errors, not any arbitrary command failure. Public pilot-image pulls
are a host-runtime egress prerequisite; NetworkPolicy does not govern CRI pulls.
No application Service is public. Optional Owner viewing uses loopback only:
`sudo /usr/local/bin/k3s kubectl -n asep-pilot port-forward --address=127.0.0.1 service/pilot-server 18080:80`.

After verification, delete only the stateless pilot namespace if desired. Keep apps
baseline empty until later review. Record actual results; never mark runtime PASS
from these proposed commands or from offline checks.

## 6. Backup, restore and data-safe rollback

**Initial datastore is SQLite.** Do not substitute etcd snapshot/cluster-reset
instructions. A successful tar/gzip operation is not a verified recovery. All steps
here require later explicit Owner authorization; no backup/credential was read or
created in this revision. No destructive restore on the development PC is allowed.

Before useful data/upgrade, approve a private destination, retention, offline
encryption/off-host copy and a disposable isolated recovery target. Preserve:

- The COMPLETE cold `/var/lib/rancher/k3s/server` tree, including `db/state.db`, any
  WAL/SHM companions, original token and bootstrap/encryption material. Never
  copy only a running database or pair a DB with a different/new token.
- Matching `/etc/rancher/k3s`, kubelet drop-in, pinned artifact bundle/baseline,
  generated service unit/environment and exact version. Do not omit encryption
  keys or rotate token/keys during backup. Treat the entire archive as credentials.
- A non-sensitive evidence record: version, node address, timestamps, resource
  counts/UIDs of the pilot, encryption status and prior passing verification.
  Check it again after restore without dumping Secret bodies/config/token values.

Example FUTURE cold backup after graceful stop of the known stateless pilot where
appropriate. Stopping K3s does not stop all containers, but the SQLite writer must
be inactive; verify no independently launched K3s server remains. Stop/retain and
escalate discrepancies rather than taking a "cold" backup from a running writer.
First verify destination/ancestor ownership, ACLs, free space and non-symlinks:

```bash
sudo systemctl stop k3s
sudo /bin/bash -eu <<'ROOT'
umask 077
test "$(systemctl show k3s --property=ActiveState --value)" = inactive
install -d -o root -g root -m 0700 /var/backups/asep-k3s
backup="/var/backups/asep-k3s/$(date -u +%Y%m%dT%H%M%SZ).tar.gz"
test ! -e "$backup"
paths=(etc/rancher/k3s var/lib/rancher/k3s/server
  var/lib/rancher/k3s/agent/etc/kubelet.conf.d/99-asep.conf
  opt/asep-k3s-stage etc/asep-k3s-review etc/systemd/system/k3s.service)
if test -f /etc/systemd/system/k3s.service.env; then
  paths+=(etc/systemd/system/k3s.service.env)
fi
tar --numeric-owner -czf "$backup" -C / "${paths[@]}"
gzip --test "$backup"
tar -tzf "$backup" >/dev/null
sha256sum "$backup" > "$backup.sha256"
chmod 0600 "$backup" "$backup.sha256"
ROOT
```

Restart the source only after verifying backup success and the pre-start network
gate still holds. On failure retain source state, fix the backup and record the
failure; do not silently delete partial evidence/restart/uninstall. Copy the
archive/digest to approved private offline storage, independently record its
digest, and test that copy as well. Colocated checksums detect corruption, not
authenticity if the whole backup location can be replaced.

**Mandatory restore rehearsal, separate disposable target only:**

1. Owner provisions an isolated disposable VM/host, never a same-host container
   with privileged host mounts. It must not reach the live development cluster,
   LAN/VPN peers or ASEP services; no duplicate node identity/IP may escape.
   Using the same IP inside an isolated network avoids rewriting restored state.
   No new infrastructure is installed by this foundation preparation.
2. Verify archive authenticity against the separately recorded digest, gzip/tar
   integrity and required-file inventory/ownership. Inspect archive member paths
   for absolute paths, traversal and unsafe links before root extraction. Extract
   only into the disposable target with K3s stopped and numeric owners preserved.
   On a separate working COPY of the cold DB directory, run SQLite `PRAGMA
   quick_check` read-only and require `ok`. Retain DB/WAL companions together;
   do not open/edit the original archive/source DB. Passing this alone does not
   prove bootstrap/encryption recovery.
3. Install the exact approved K3s version/bundle into that isolated target, leave
   it stopped, and restore the coherent server tree, original token/encryption
   material, configuration, unit/environment and drop-in together. Do not run
   reset/uninstall commands, regenerate tokens or mix backup generations.
   Supply an approved offline copy of the digest-pinned pilot image to that
   target's separate containerd: the K3s airgap bundle does not contain BusyBox,
   and this control-plane backup does not include the runtime image cache. Do
   not reconnect the clone to production/VPN just to pull a missing image.
4. After that target's own firewall/start approval, require local readyz, one
   expected Ready node, healthy system pods, original pilot resource UIDs/counts,
   encryption Enabled and no bootstrap/decryption errors. Run the staged pilot
   DNS, Service/Pod allow-deny, RBAC and admission checks. If a cryptographic
   recovery check needs Secret access, that is a separately authorized private
   Owner operation: do not claim it from `secrets-encrypt status` alone and do not
   disclose values. An untested decryption path remains explicitly UNKNOWN.
5. Record version, independent archive digest, target isolation, exact checks and
   results, no secret data. Retain the source unchanged; dispose of only the
   approved rehearsal target privately. Failed/incomplete rehearsal BLOCKS backup
   readiness and any useful application data/upgrade. An unavailable target is
   UNKNOWN, not PASS. No development-host restore is proposed here.

This backs up control-plane SQLite state only. External PostgreSQL, Docker volumes
and future application PVs need their own coherent data backups. K3s SQLite
recovery is not application-data recovery.

Default rollback is **stop/disable and retain**, not uninstall:

```bash
# While API is healthy: remove only this known stateless pilot first.
sudo /usr/local/bin/k3s kubectl delete -f /opt/asep-k3s-stage/pilot.yaml --wait=true
sudo systemctl stop k3s
sudo systemctl disable k3s
```

Stopping the service does not stop all K3s containers. If API deletion failed,
inspect only the K3s runtime with `sudo /usr/local/bin/k3s crictl ps` and review
the installed killall script before any runtime cleanup. Do not call rollback
complete while old workloads remain. Future non-pilot workloads need their own
graceful stop/data plan; never delete their namespaces or PVCs automatically.

Then verify existing Docker/PostgreSQL/inference/VPN and review K3s-specific network
rules against recorded baselines. Stopping may leave CNI/policy rules; do not flush
global tables, blindly restore stale iptables state, prune Docker or delete host
delivery/evidence directories. Removal of generated network rules is a separately
reviewed host change. The generated killall/uninstall scripts are not routine
rollback: inspect them and back up first. Uninstall deletes local datastore and
local PV data; future persistent workloads require an independent data plan.

## 7. Later ordinary Spring/Next deployment

Add reviewed non-root, digest-pinned application images and namespace-local
Services, probes, resource limits and specific network allowances. The ordinary
Spring API currently binds loopback: an explicitly reviewed pod configuration
can bind 0.0.0.0 behind ClusterIP/NetworkPolicy. Do not activate protected delivery.
Next/Spring application logic can be reused; this foundation does not certify
their authentication, integration or production readiness.

Pods cannot reach host PostgreSQL/inference/Go via their own 127.0.0.1. A later
proposal must address authenticated endpoints, TLS, DNS and firewall restrictions;
do not bypass protected loopback guards or use hostNetwork/socket mounts. Keep
operator credentials, controller evidence, live writer and untrusted agents out.
No Keycloak/agent/GPU/privileged-runtime relocation or new middleware is implied.
SQLite-to-etcd/multi-node expansion and persistent storage are later decisions.

## Sources and recorded verification

- [Pinned upstream release](https://github.com/k3s-io/k3s/releases/tag/v1.36.5%2Bk3s1)
- [Kubelet drop-in configuration](https://docs.k3s.io/installation/configuration)
- [K3s hardening](https://docs.k3s.io/security/hardening-guide)
- [Swap policy](https://kubernetes.io/docs/concepts/cluster-administration/swap-memory-management/)
- [Network components](https://docs.k3s.io/networking/networking-services)
- [Backup/restore](https://docs.k3s.io/datastore/backup-restore)
- [Uninstall data loss](https://docs.k3s.io/installation/uninstall)
- [Service stop versus container shutdown](https://docs.k3s.io/upgrades/killall)

Preparation verification, 2026-10-31:

- YAML parsing and static security invariants passed for six YAML/config files
  containing 19 Kubernetes objects plus the host/kubelet/audit configuration.
- Fourteen offline negative cases passed: 13 unsafe configuration/RBAC/workload
  changes rejected and one tampered public artifact rejected. No cluster involved.
- Python AST syntax, both shell scripts, the pinned upstream installer (syntax
  only) and all ten documented shell blocks passed syntax checks.
- Unflagged `verify-pilot` refused execution with exit 2 before invoking K3s or
  accessing credentials. Public release hashes/installer hash were checked.
- Read-only host preflight correctly stopped with exit 1 for active full-tunnel
  `tun0` routing. VPN coexistence, kill-switch behavior and effective firewall/DNS
  approval remain prerequisites; no route or CIDR was changed.
- Original-file SHA256 comparison found only the intentional `docs/INDEX.md`
  proposal-navigation edit. All seven untracked spec files, existing VN-T14 code,
  scripts, contracts and migrations were unchanged. No files were staged/committed.

ADR approval, installation/start, effective firewall, VPN/DNS compatibility,
Kubernetes schema/admission, scheduling, network enforcement, encryption/restore
and ordinary application operation remain NOT VERIFIED.

### Independent-review revision verification — 2026-11-01

- `python3 -B deploy/k3s/check.py --self-test`: PASS, **22 negative cases**
  (21 unsafe configurations including Service/Pod/policy port/selector mutations,
  plus one tampered artifact). No cluster.
- `python3 -B deploy/k3s/integrity.py --self-test`: PASS, **9 negative cases**.
  Includes tampered installer, pilot script and config despite replacement of
  colocated checksums; trusted system `sha256sum --check --strict` rejects them
  against a fixed synthetic baseline. This is not real Owner approval/root isolation.
- `python3 -B deploy/k3s/test_verify_pilot.py`: PASS, **3 tests / 9 fixture scenarios**
  (success, unflagged rejection and 7 failure paths). Transport errors, wrong
  Service/EndpointSlice ports, reachable denied client, unavailable server and
  unexpected admission results cannot produce PASS. The fake CLI exists only in
  a disposable script copy; the installed verifier has no override/fake mode.
- `bash deploy/k3s/preflight --offline` and `integrity.py --check`: PASS against
  the current **candidate**. No independently approved root baseline exists yet.
- Four Python ASTs, both shell scripts, **13 documented shell blocks**, **26 local
  Markdown links** (including the Index proposal and ADR/runbook links), whitespace
  and `git diff --check`: PASS. No Kubernetes API-schema validation is claimed.
- `bash deploy/k3s/preflight --diagnostics`: completed with explicit UNKNOWNs;
  sandbox denied network-route/system-resolver/service metadata. Empty sandbox
  listener output is not the actual host listener baseline. `preflight --host`
  exited **1 / UNKNOWN** on denied address inspection. No sudo/escalation was used.
- Original-file comparison preserves all seven `specs/` files and all existing
  VN-T14 code/contracts/migrations. Only this foundation, proposed ADR and Index
  navigation changed; index remains unstaged. No remote calls/install/credentials.

**Readiness at that revision:** prepared for Owner installation review, **NOT READY TO INSTALL OR
START**. Independently approved protected baseline, actual firewall/IPv4/IPv6 API
denials, stable node identity, full-tunnel/split-DNS coexistence and isolated backup
restore rehearsal remain blocking UNKNOWNs. Real pilot/Service-NAT/policy results
remain required runtime gates. ADR-VN-05 remains PROPOSED; protected VN-T14 stays
host/Docker, and real GitLab acceptance is still BLOCKED.

### Single-node firewall gate revision — 2026-11-01

The preceding counts are historical checkpoints. Current applicable checks:

- `python3 -B deploy/k3s/test_firewall.py`: **10 tests PASS**, including 68 packet
  model cases, 11 live-JSON tampering cases, DHCP change and fixed-path/root refusal,
  scoped replacement and systemd dependency/pre-start assertions. The restricted
  offline grammar and synthetic kernel JSON are not native enforcement evidence.
- `python3 -B deploy/k3s/check.py --self-test`: **25 negative cases PASS**, including
  new joint-review fences for node address, pod CIDR and Flannel backend. Config
  now explicitly pins the existing proposed VXLAN backend.
- `python3 -B deploy/k3s/integrity.py --self-test`: **9 negative cases PASS**.
  `python3 -B deploy/k3s/test_verify_pilot.py`: **3 tests / 9 fixture scenarios PASS**.
- Offline preflight / candidate integrity, six Python ASTs, both shell scripts,
  **17 documented shell blocks**, **31 local Markdown links**, whitespace and
  `git diff --check`: PASS. Candidate inventory is now 23 entries; independent
  Owner approval/root baseline provisioning is still outstanding.
- `nft --check --file deploy/k3s/firewall.nft.example`: **NOT VERIFIED**, exit 3; sandbox
  denied Netlink socket initialization. No rule was applied.
- Native `systemd-analyze verify --man=no` on disposable unit fixtures (missing
  installed helper replaced by `/usr/bin/true`, synthetic vendor unit) exited 1
  because sandbox socket setup was denied. Only offline unit-contract parsing
  passed; actual installed unit/dependency behavior remains NOT VERIFIED.

Only foundation/proposed-ADR files changed in this slice. All seven `specs/` files,
existing VN-T14 runtime/contracts/migrations and the Git index are preserved.
No privileged commands, service activation, packet probes or credential access.
Public upstream documentation was read; no GitHub/GitLab mutations occurred.

**Firewall gate: IMPLEMENTED / OFFLINE TESTED / NOT ACTIVATED.** Ready for Owner
review, **BLOCKED for host start** pending independent baseline approval, native
transaction/live-table proof, actual CNI/IPv4/IPv6 peer denial, VPN/DNS/reconnect
and boot verification. The [firewall plan](FIREWALL.md) specifies activation before
API exposure, dedicated persistence and rollback that retains protection/data.
This does not approve ADR-VN-05 or change VN-T14 acceptance.

## Repository-only versioning review — 2026-11-03

Concrete `config.yaml`, `firewall.nft` and `OWNER-REVIEW.SHA256` remain unchanged
locally and are ignored. The complete pre-conversion bundle is also retained in
ignored `tmp/k3s-precommit-original-2026-11-03/`, including its original documentation,
scripts and tests. No installed or active host file was modified.

The versioned config and firewall use `.example` names and a reserved documentation
node address/interface. Direct source/test lookups and the unprivileged candidate
manifest use those same examples. The fixed-path installed helper still requires
an independently approved protected baseline, exact file ownership and actual
address checks; adaptation must review its constants together with the examples.
This is a reference profile, not a generic installer or Owner authorization.

A fresh temporary tree containing only proposed versioned files, without the
ignored concrete inputs/original manifest/downloads/trial directories, passed:

```bash
bash preflight --offline
python3 -B check.py --self-test
python3 -B integrity.py --self-test
python3 -B test_verify_pilot.py
python3 -B test_firewall.py
bash -n preflight fetch verify-pilot
```

All exited0: offline foundation/candidate consistency,25 configuration negatives,
9 integrity negatives,3 CLI tests and10 firewall tests. These are offline checks;
Kubernetes schema/admission, kernel rules, peer reachability, boot behavior and
host activation were not exercised. The old ADR Markdown hard-break whitespace
warning was removed in this separately authorized review group.
