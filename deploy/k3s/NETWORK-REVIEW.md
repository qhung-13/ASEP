# K3s network START gate — PROPOSED / BLOCKED until Owner evidence

> Versioned reference profile, sanitized on 2026-11-03: `192.0.2.10` and
> `example0` are documentation-only stand-ins, not observed host settings.
> Historical host-specific inputs, review manifest and original reports are
> preserved locally in ignored `tmp/k3s-precommit-original-2026-11-03/`.
> Earlier reported test/host observations refer to that original profile, not
> fresh runtime evidence for these examples. The example manifest is not Owner
> approval. Adapt configuration, checker/helper constants and baseline together
> under separate review; never install/start this reference profile unchanged.

This review is part of ADR-VN-05, not permission to modify routes, firewall,
Pritunl, DNS, Docker or the host. The [isolated INPUT guard](FIREWALL.md) is prepared
for review only; no general firewall rewrite or VPN-check bypass exists.
NetworkPolicy controls pod traffic, not host listeners, image pulls or VPN rules.
All runtime/probe steps below are FUTURE acceptance, not executed evidence.

## Collect local metadata, without credentials or elevated access

```bash
bash deploy/k3s/preflight --diagnostics
bash deploy/k3s/preflight --host
```

Diagnostics use `ip` addresses, IPv4/IPv6 route tables and policy rules, local
`route get` lookups (no packets), `resolvectl` DNS/domain/default-route metadata,
`ss` listeners and Docker/UFW service state. No NetworkManager/Pritunl profiles,
environment dumps, kubeconfig, tokens or private key files are read. In restricted
sandboxes, denied commands are UNKNOWN; repeat on the actual Owner terminal.
Neither diagnostic completion nor active UFW service proves packet enforcement.
`--host` deliberately cannot grant pre-start approval, even if static checks pass.

Record dated results with the normal VPN connected, then repeat after an
Owner-controlled normal reconnect. Do not disconnect/disable VPN just to pass.
Use the original host snapshot for comparison, not as current evidence.

Owner subsequently reported UFW inactive and Docker NAT/FORWARD chains without an
observed INPUT hook, with the same LAN/Docker networks and active full-tunnel
`tun0`. This supersedes any assumption that an active UFW service enforces rules;
it is not independent runtime proof. The proposed guard installs its OWN table
and boot unit without enabling UFW or the global nftables service.

## Pre-start blocking checklist

- **Identity/interfaces:** reserve `192.0.2.10`; `node-ip` and advertise address
  remain that address, and Flannel uses physical `example0`, never `tun0`. Confirm
  VPN interface names, IPv6 addresses, policy routing and return paths. Address
  changes require config, integrity-baseline and Owner review again.
- **Full tunnel:** two /1 routes seen previously are a review condition, not proof
  of a CIDR collision. Inventory all route tables/rules, VPN-pushed networks and
  kill-switch behavior. Confirm real private address allocations do not overlap
  pod `10.42.0.0/16`, Service `10.43.0.0/16`, LAN or Docker bridges. Before CNI
  exists, `route get` can legitimately select `tun0`; this is not runtime proof.
  After start, pod routes and Service translation must stay local. Require a
  reviewed VPN-compatible routing/firewall plan with no forced VPN bypass.
  A full-tunnel `--host` failure remains a blocker; there is no allow/skip flag.
  Any later refinement of that conservative check requires its own reviewed
  evidence and tests, not ignoring the exit code.
- **Split DNS:** collect per-link DNS servers, routing/search domains and default
  routes. `/run/systemd/resolve/resolv.conf` flattens systemd-resolved per-link
  routing; pointing CoreDNS at it does NOT preserve split DNS semantics. If DNS
  scope differs by link/domain, stop: approve explicit domain-aware forwarding
  and reachability first. No such CoreDNS amendment is included/implicitly
  authorized here. No loopback resolver inside pods, public fallback or leaks of
  private queries to public DNS. Later compare host/pod resolution of an
  Owner-selected non-sensitive public name and approved private canary, before
  and after VPN reconnect. Canaries/expected resolvers are currently UNKNOWN.
- **Coexistence:** inventory effective nftables AND any iptables legacy/nft rules,
  UFW IPv6 coverage/default policies and Pritunl-managed chains. Root-only
  inspection must be supplied later by the Owner under separate authorization;
  do not invoke sudo here. Review INPUT/FORWARD, Docker, CNI and network-policy
  rule order, interface filtering, return traffic and IPv6. Service status or
  UFW summaries alone cannot satisfy this gate. Never flush global rules, disable
  UFW/Pritunl, change Docker iptables settings or automatically add routes.
- **Operational approval:** review/install the dedicated guard only after Owner
  approval, and record exact effective policy/rule-order evidence,
  required flow allowlist, responsible Owner and review time before START. No
  activated/enforced policy evidence is available yet. Stop if privileged inspection is
  unavailable; no `systemctl start k3s` authorization follows from offline PASS.

## API listener and required connections

Config explicitly requests **IPv4 `0.0.0.0:6443`**, with node/advertise
`192.0.2.10`. This wildcard is NOT private by itself; firewall enforcement is
mandatory before start. TLS SANs/authentication/RBAC do not make an open socket
private. Do not use loopback-only binding that breaks local CNI access.

| Flow | Required enforcement and later evidence |
| --- | --- |
| Trusted host -> `127.0.0.1:6443` and local node address | Allow; local health/API works with root-only admin config |
| Necessary local system/CNI pods -> node API / Kubernetes Service `443` -> `6443` | Allow only reviewed local interfaces and real pod sources; CoreDNS list/watch and health work; do not allow arbitrary LAN/VPN packets merely claiming pod source IPs |
| Ordinary pilot/apps -> Kubernetes API | No API token, default-deny egress; no broad API allow added to these namespaces |
| LAN, VPN (`tun0`), WAN, Docker bridge ingress -> host API | Deny TCP 6443, regardless of source IPv4/IPv6; no DNAT/proxy/port publication |
| Remote kubelet / overlay | Deny TCP 10250, UDP 8472 on external/VPN interfaces; no remote node exists |
| Local node/CNI DNS and pod forwarding | Preserve required traffic; verify policy controller, Docker and VPN chains do not bypass namespace isolation |

The proposed host guard allows local IPv4 `cni0` pod sources to node:6443, not
arbitrary pod-CIDR claims on LAN/VPN. Local host traffic arrives through `lo`;
external 10250/8472 is unnecessary on one node. It leaves FORWARD/OUTPUT/NAT alone.
K3s must depend on guard activation and the installed live-check pre-start; file
existence or service-active state is insufficient. See FIREWALL.md for the full
port/interface matrix, atomic replacement, DHCP, persistence and safe rollback.

After separately approved start, BEFORE applying the pilot, record actual
`ss -H -4 -lnt` and `ss -H -6 -lnt` results, process ownership and effective rules.
Expected API is IPv4 wildcard port 6443 with no IPv6 API listener; unexpected
address/port/family blocks acceptance and requires stopping K3s/review. Recheck
10250, 8472 and local supervisor listeners too. Do not infer listeners from YAML.
An IPv6 listener being absent does not waive IPv6 firewall review/deny policy.

The first controlled start needs an already reviewed firewall; the actual K3s
listener cannot be observed before installation/start. That is a post-start
acceptance gate, not fabricated pre-start evidence. If any unauthorized TCP API
connection succeeds, stop K3s and review policy before continuing.

Later, from separately authorized LAN and VPN peers (IPv4 and any routable IPv6),
probe host API reachability **while local authenticated health succeeds**. A TCP
handshake or TLS/401/403 response is an exposure FAILURE, not a successful denial.
Timeout/refusal with healthy local API plus matching effective rule/counter
evidence is required. Probes from the host itself do not prove peer denial.
If peers/IPv6 paths are unavailable, record UNKNOWN and do not claim isolation
verified. No credential is required for the remote TCP denial probes.

## Pilot and coexistence acceptance

`pilot-server` Service TCP **80** selects only `app=pilot-server` and translates to
Pod TCP **8080**. The process, probes, containerPort, ingress and allowed-client
egress policies use 8080. NetworkPolicy normally sees the translated pod target
with this stack, but Kubernetes permits implementation-specific Service/NAT
ordering: static checks alone cannot certify it. Do not broaden policies to all
destinations/ports to make a test pass.

The future `verify-pilot` checks Service port/target/selector, EndpointSlice port,
both clients' DNS, allowed and denied HTTP via **Service:80 AND PodIP:8080**, with
successful allowed requests before/after denials. This catches an unavailable
server being mistaken for enforcement. It also checks RBAC/admission denials.
Any failed or inconclusive check blocks acceptance; inspect actual kube-proxy /
policy rules rather than adding port 80 speculatively. A reviewed policy correction
would require rerunning both paths and all denials on the real cluster.

Repeat after a normal VPN reconnect. Record host and pod canary DNS results,
local/CNI API health and unauthorized peer denials, plus Docker workloads,
PostgreSQL loopback `55432`, inference `11434`, developer connectivity and firewall
rule differences. No DB login, inference prompt or GitLab operation is needed.
No public Service, host port, VPN reconfiguration or new infrastructure is added.

All effective firewall, API listener/peer denial, CNI policy, split-DNS and
reconnect results remain **UNKNOWN / NOT VERIFIED** until those actual tests run.
