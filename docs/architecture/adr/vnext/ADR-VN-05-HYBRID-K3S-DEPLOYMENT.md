# ADR-VN-05 — Hybrid single-node K3s deployment foundation

**Date:** 2026-10-31
**Status:** PROPOSED — Owner selected the hybrid direction; this ADR, concrete
configuration and installation plan still require Owner review. NOT DEPLOYED.
**Review revision:** 2026-11-01; independent archive findings addressed in the
preparation artifacts, without approving this ADR or performing host operations.
The same-day host firewall gate revision remains PROPOSED / NOT ACTIVATED.

## Authority and scope

This proposes a deployment-platform/timing amendment to the initial Compose
candidate in TSE-06C / `docs/architecture/ARCHITECTURE.md`. It preserves accepted
ADR-VN-01 Java/Application, Go/workflow and Python/agent boundaries, ADR-002 durable
claims, domain ownership and all VN-T14 delivery/evidence/security contracts.
ADR-VN-04's open deployment details are not silently declared approved.

The Owner explicitly selected K3s for ordinary trusted applications now, retaining
the protected host/Docker delivery topology. This is not new VN-T14 functionality,
ticket completion, permission to install or approval for any external operation.
Historical decisions remain intact. Approval of this document is a separate gate
from operation-specific host, credential, deployment and live acceptance approvals.

## Context and evidence

The Codex feasibility review in this chat established that current protected Java
and Go launch guards bind fixed installed paths, numeric UIDs, strict regular files
and loopback endpoints. `IsolatedProbeVerifier` binds Docker IDs, labels, image IDs
and host paths. These are not ordinary Kubernetes Deployments or Jobs unchanged.
The checked-in Compose deployment supplies PostgreSQL/Flyway, not the complete
ASEP workflow. VN-T14 local checkpoint `c15b022` and assessment `a667da4` remain
valid for the existing topology only; real GitLab acceptance remains BLOCKED.

Read-only host findings: Ubuntu 26.04, amd64/kernel 7.0, cgroup v2, 12 CPUs,
approximately 30 GiB RAM and 813 GiB available NVMe, active Docker/containerd,
existing PostgreSQL at host loopback port 55432, 8 GiB swap, NetworkManager/UFW
services active, and RTX 5060 Ti with host inference on loopback port 11434.
K3s and ASEP protected installation roots were absent. No active VPN appeared;
disconnected VPN routes, effective firewall and split DNS remain UNKNOWN.
During final preparation a later snapshot found active `tun0` and two /1
full-tunnel routes. The preflight intentionally stops for coexistence review;
this is not evidence of specific VPN-owned pod/service CIDR collision.
The ordinary Owner account has sudo/Docker/LXD privileges and must not be the
untrusted agent identity. Findings are a snapshot, not installation-grade proof.

## Proposed decision

- One native single-node K3s server with bundled containerd and default SQLite.
  K3s control-plane data is separate from ASEP's PostgreSQL business data.
- Initially run only the non-sensitive scheduling/network/security pilot. Ordinary
  Spring/Next applications are later candidates after their own deployment review.
- Retain Go authority, delivery controller/executor, interactive Owner approval,
  Docker verifier, PostgreSQL and local inference outside Kubernetes. No protected
  host paths, Docker/CRI sockets, writer credentials or agent execution enter pods.
- Disable Traefik, ServiceLB, metrics-server and local-storage initially. Use private
  ClusterIP Services and explicit Owner-local port forwarding only. No public
  Ingress, LoadBalancer or NodePort is supplied. Keep CoreDNS and network policies.
- Pin K3s `v1.36.5+k3s1`, the latest published 1.36 patch found in the upstream
  release review, installer/artifact SHA256 and the BusyBox pilot image digest.
  Never resolve a moving channel/tag during installation. Re-review security fixes
  and pins if installation is delayed; this is not a CVE certification.
- Preserve host swap; a supported kubelet drop-in explicitly sets `failSwapOn:
  false` and `memorySwap.swapBehavior: NoSwap`. This retains host-process swap;
  disk/swap physical protection and node pressure remain Owner responsibilities.
- Proposed pod/service CIDRs are 10.42.0.0/16 and 10.43.0.0/16. They do not overlap
  observed LAN 192.168.2.0/24 or Docker 172.17–19.0.0/16. Bind the CNI to the
  physical interface, recheck with every normal VPN enabled, and reserve the node
  address before installation. Never silently choose a new address or bypass DNS.

## Security and operational implications

Root, K3s/kubelet administrators, admission and packaged system components join
the trusted computing base. Namespace isolation alone is not an untrusted-code
sandbox. Application namespaces enforce version-pinned Restricted Pod Security,
default-deny ingress/egress, scoped DNS, quotas and token automount disabled.
The pilot's RBAC groups only observe or exec into non-sensitive pilot containers;
they cannot create pods/services, access Secrets or operate other namespaces.
No user credential or Kubernetes Secret is created by the repository preparation.

Enable Secret encryption at rest, root-only admin kubeconfig and metadata-only
bounded audit logging. RBAC and Kubernetes identities do not replace application
mTLS identities or genuine interactive human authorization. Protected service
credentials stay outside the cluster. Their later migration would require a new
deployment trust/regular-file/ownership design and negative tests, not removal of
current guards or copying fixture evidence into live stores.

API port 6443 must be reachable only by required local/CNI system flows; remote
LAN/WAN access, kubelet 10250 and VXLAN 8472 must be blocked for this single-node
setup, including IPv6. Effective UFW/nftables/Docker interactions require Owner
review before start. Do not disable UFW, NetworkManager, VPN, swap or Docker.
App pod-to-host PostgreSQL/inference/authority connections are not provided by
loopback addresses; any later listener/TLS/allowlist change needs separate review.

The configuration explicitly requests IPv4 `0.0.0.0:6443` for loopback and local
CNI reachability. This requires an effective, Owner-reviewed IPv4/IPv6 firewall
BEFORE start; authentication/SANs do not establish private network access. Actual
listening addresses and unauthorized LAN/VPN TCP denial must be verified after
the first controlled start. Missing rule-order/peer evidence blocks acceptance.
Full-tunnel routing, Pritunl kill-switch interactions and split DNS remain open.
The host resolver file flattens per-link routing; it does not establish correct
CoreDNS split forwarding. See the blocking network review in the runbook.

Owner-reported UFW inactive / Docker NAT-FORWARD without an observed INPUT hook
requires a dedicated guard before exposing any K3s listener. The proposed ASEP
`inet` INPUT table drops external TCP 6443/10250 and UDP 8472 in both families,
allows loopback and narrowly scoped local `cni0` IPv4 pod -> node API traffic,
and leaves NAT/FORWARD/OUTPUT/Docker/Pritunl untouched. A dedicated boot unit and
mandatory live-rule/address check gate K3s ExecStart; failed/missing rules block
start. No global firewall service is enabled or tables flushed. Rule retention
across VPN reconnect, actual CNI flows, peers and reboot require runtime proof;
oneshot ordering is not a continuous monitor for another root manager's flush.
See the [firewall proposal](../../../../deploy/k3s/FIREWALL.md). DHCP changes require
stop/review, never automatic relaxation. Safe rollback retains protection/data.

Root installation/execution requires a separately Owner-approved, root-protected
integrity baseline with its digest recorded outside the mutable checkout/download
directory. Downloaded checksums and the repository candidate are not approval.
Trusted system tools verify the baseline and every staged script/artifact/config
before privileged use; only verified root-controlled copies may be installed or
applied. Generated service/helper files need subsequent review, not blind execution.

## Alternatives

| Alternative | Disposition |
| --- | --- |
| Finish host deployment and defer all K3s work | Lowest immediate effort, but does not meet Owner's selected application-platform timing |
| Hybrid trusted-app K3s + protected host/Docker | Selected proposal; reuses completed delivery and existing developer services |
| Move protected services/verifier into K3s now | Deferred: host-bound launch/file/network guards, Job evidence provenance and recovery need code and acceptance changes |
| Use hostNetwork/privileged pods/socket mounts to avoid adaptation | Rejected as the ordinary application baseline; enlarges privileges and defeats Restricted admission |
| Add HA, external cluster DB, mesh, GitOps or replicated storage now | Unnecessary for one-node stateless foundation |

## Migration, rollback and verification gates

1. Owner reviews this ADR, pins, CIDRs, host readiness and runbook. No auto-install.
2. Independently approve/protect the integrity baseline, verify all public and
   repository artifacts, then separately approve protected staging, stopped
   installation and effective firewall/VPN/DNS. Only then approve cluster start.
3. Apply namespace baselines and the stateless pilot. Verify scheduling, DNS,
   allowed/denied HTTP, denied RBAC and actual server-side admission denials.
   Offline syntax/invariant checks alone cannot establish these results.
4. Verify Docker/PostgreSQL/inference/VPN before and after start and rehearsal
   rollback. Validate encryption, node swap settings and health. Rehearse coherent
   SQLite/token/encryption-material recovery on an isolated disposable target,
   never by destructive restore on the development PC. Archive integrity alone
   cannot prove recovery. No rehearsal target is provisioned by this proposal.
5. Later package ordinary Spring/Next services with reviewed config, probes and
   resource budgets. No protected delivery move follows implicitly.
6. A later protected-runtime proposal must preserve mTLS/grants/claims/fencing,
   independent verification and UNKNOWN recovery and reproduce existing fault
   suites on the proposed platform. Human approval remains interactive.

Rollback initially stops/disables K3s and retains all data/configuration. Back up
SQLite, server token and encryption/bootstrap material privately; do not print or
commit it. Uninstall is a separately approved destructive operation because it
deletes local cluster/PV data. Existing Docker PostgreSQL volumes and host evidence
must never be removed or reset. No global firewall flush or Docker prune.
Remove the known stateless pilot while the API is healthy before stopping K3s;
service stop alone leaves containers running. Other workloads require their own
approved graceful shutdown/data plan, never automatic namespace/PVC deletion.
Growth can add workers, then explicitly convert SQLite to embedded etcd before
adding control-plane servers. Single-node storage/control plane is not HA.

## Artifacts and references

- [Foundation runbook and validation](../../../../deploy/k3s/README.md)
- [Existing protected delivery deployment](../../../../deploy/delivery/README.md)
- [VN-T14 local assessment](../../../verification/vnext/VN-T14_LOCAL_COMPLETENESS_2026-10-31.md)
- [K3s release](https://github.com/k3s-io/k3s/releases/tag/v1.36.5%2Bk3s1)
- [Configuration/drop-ins](https://docs.k3s.io/installation/configuration)
- [Hardening](https://docs.k3s.io/security/hardening-guide)
- [Swap](https://kubernetes.io/docs/concepts/cluster-administration/swap-memory-management/)
- [Backup/restore](https://docs.k3s.io/datastore/backup-restore)
- [Uninstall consequences](https://docs.k3s.io/installation/uninstall)

Owner review: PENDING. Installation, runtime, network isolation, backup restore and
ordinary application deployment: NOT VERIFIED. No VN-T14 acceptance is implied.

## Versioning boundary — 2026-11-03

The committed K3s configuration/firewall inputs are reference examples using a
documentation-only node address and interface. Their matching offline checker,
helper and candidate manifest use the same example profile. Original concrete
host inputs and reports remain local and ignored. The examples are not a usable
host configuration or approval; adaptation and runtime enforcement still require
independent review. No K3s service was installed or activated for versioning.
