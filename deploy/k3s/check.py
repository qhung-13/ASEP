#!/usr/bin/env python3
"""Offline foundation checks. No kubeconfig, credentials, cluster or network access."""
import argparse
import copy
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import tempfile

import yaml

ROOT = Path(__file__).resolve().parent


def require(condition, message):
    if not condition:
        raise ValueError(message)


def load():
    return (json.loads((ROOT / 'versions.lock.json').read_text()),
            yaml.safe_load((ROOT / 'config.yaml.example').read_text()),
            yaml.safe_load((ROOT / 'kubelet.conf').read_text()),
            yaml.safe_load((ROOT / 'audit-policy.yaml').read_text()),
            list(yaml.safe_load_all((ROOT / 'namespaces.yaml').read_text())) + list(yaml.safe_load_all((ROOT / 'baseline.yaml').read_text())),
            list(yaml.safe_load_all((ROOT / 'pilot.yaml').read_text())))


def validate(lock, config, kubelet, audit, baseline, pilot):
    require(re.fullmatch(r'v1\.36\.\d+\+k3s\d+', lock['k3sVersion']) and lock['kubernetesMinor'] == 'v1.36', 'unreviewed release/minor')
    require(set(lock['artifacts']) == {'k3s', 'install.sh', 'sha256sum-amd64.txt', 'k3s-airgap-images-amd64.tar.zst'}, 'artifact inventory')
    for name, entry in lock['artifacts'].items():
        prefix = ('https://raw.githubusercontent.com/k3s-io/k3s/' + lock['k3sVersion'] + '/') if name == 'install.sh' else ('https://github.com/k3s-io/k3s/releases/download/' + lock['k3sVersion'].replace('+', '%2B') + '/')
        require(entry['url'] == prefix + name and re.fullmatch('[0-9a-f]{64}', entry['sha256']) and entry['size'] > 0, 'artifact provenance/pin')
    require(re.fullmatch(r'docker.io/library/busybox:1\.37\.0@sha256:[0-9a-f]{64}', lock['pilotImage']['reference']), 'pilot image pin')
    require(config['secrets-encryption'] is True and config['write-kubeconfig-mode'] == '0600', 'encryption/admin permissions')
    require(set(config['disable']) == {'traefik', 'servicelb', 'metrics-server', 'local-storage'}, 'disabled packaged components')
    require(not config.get('docker') and not config.get('disable-network-policy') and not config.get('cluster-init') and not config.get('datastore-endpoint'), 'runtime/datastore/network-policy boundary')
    require(config['tls-san-security'] is True and config['flannel-iface'] == 'example0', 'interface/TLS policy')
    require(config['bind-address'] == '0.0.0.0', 'unreviewed API listener; firewall pre-start gate required')
    require(config['node-ip'] == '192.0.2.10' and config['cluster-cidr'] == '10.42.0.0/16' and config['service-cidr'] == '10.43.0.0/16' and config['flannel-backend'] == 'vxlan', 'host firewall/CNI address contract requires joint review')
    require(config['resolv-conf'] == '/run/systemd/resolve/resolv.conf', 'unreviewed DNS fallback')
    networks = [ipaddress.ip_network(config[k]) for k in ['cluster-cidr', 'service-cidr']]
    require(not networks[0].overlaps(networks[1]), 'overlapping cluster/service CIDRs')
    require(ipaddress.ip_address(config['cluster-dns']) in networks[1], 'DNS outside service CIDR')
    address = ipaddress.ip_address(config['node-ip'])
    require(address.is_private and config['advertise-address'] == str(address) and all(address not in n for n in networks), 'node address')
    require(kubelet['failSwapOn'] is False and kubelet['memorySwap']['swapBehavior'] == 'NoSwap', 'host swap preservation / pod NoSwap')
    require(audit['kind'] == 'Policy' and audit['rules'][-1] == {'level': 'Metadata'} and all(r['level'] in ['None', 'Metadata'] for r in audit['rules']), 'audit payload disclosure')
    require('audit-policy-file=/etc/rancher/k3s/audit-policy.yaml' in config['kube-apiserver-arg'], 'audit policy wiring')

    objects = {(d['kind'], d['metadata'].get('namespace'), d['metadata']['name']): d for d in baseline}
    require(len(objects) == len(baseline) == 15, 'baseline inventory/duplicate object')
    allowed_kinds = {'Namespace', 'ResourceQuota', 'ServiceAccount', 'Role', 'RoleBinding', 'NetworkPolicy'}
    require(all(d['kind'] in allowed_kinds for d in baseline), 'unexpected privileged resource')
    for ns in ['asep-apps', 'asep-pilot']:
        labels = objects['Namespace', None, ns]['metadata']['labels']
        for mode in ['enforce', 'audit', 'warn']:
            require(labels['pod-security.kubernetes.io/' + mode] == 'restricted' and labels['pod-security.kubernetes.io/' + mode + '-version'] == lock['kubernetesMinor'], 'Pod Security admission')
        require(objects['NetworkPolicy', ns, 'default-deny']['spec'] == {'podSelector': {}, 'policyTypes': ['Ingress', 'Egress']}, 'default-deny policy')
        dns = objects['NetworkPolicy', ns, 'allow-cluster-dns']['spec']
        expected_to = [{'namespaceSelector': {'matchLabels': {'kubernetes.io/metadata.name': 'kube-system'}}, 'podSelector': {'matchLabels': {'k8s-app': 'kube-dns'}}}]
        require(dns == {'podSelector': {}, 'policyTypes': ['Egress'], 'egress': [{'to': expected_to, 'ports': [{'protocol': p, 'port': 53} for p in ['UDP', 'TCP']]}]}, 'unscoped DNS egress')
        hard = objects['ResourceQuota', ns, 'bounded-resources']['spec']['hard']
        require(hard == ({'pods': '4', 'limits.cpu': '500m', 'limits.memory': '256Mi'} if ns == 'asep-pilot' else {'pods': '6', 'limits.cpu': '2', 'limits.memory': '2Gi'}), 'resource budget')
    server = {'matchLabels': {'app': 'pilot-server'}}
    client = {'matchLabels': {'app': 'pilot-client', 'access': 'allowed'}}
    require(objects['NetworkPolicy', 'asep-pilot', 'allow-client-to-server']['spec'] == {'podSelector': client, 'policyTypes': ['Egress'], 'egress': [{'to': [{'podSelector': server}], 'ports': [{'protocol': 'TCP', 'port': 8080}]}]}, 'client egress scope')
    require(objects['NetworkPolicy', 'asep-pilot', 'allow-server-from-allowed-client']['spec'] == {'podSelector': server, 'policyTypes': ['Ingress'], 'ingress': [{'from': [{'podSelector': client}], 'ports': [{'protocol': 'TCP', 'port': 8080}]}]}, 'server ingress scope')
    require(objects['ServiceAccount', 'asep-pilot', 'pilot']['automountServiceAccountToken'] is False, 'service account token')
    for d in baseline:
        if d['kind'] != 'Namespace':
            require(d['metadata']['namespace'] in ['asep-apps', 'asep-pilot'], 'namespace scope')
        if d['kind'] == 'Role':
            require(d['metadata']['namespace'] == 'asep-pilot' and d['metadata']['name'] in ['pilot-observer', 'pilot-tester'], 'role scope')
            if d['metadata']['name'] == 'pilot-tester':
                require(d['rules'] == [{'apiGroups': [''], 'resources': ['pods/exec'], 'verbs': ['create']}], 'tester privilege boundary')
            for rule in d['rules']:
                permitted = set(rule['verbs']) <= {'get', 'list', 'watch'} or (rule['resources'] == ['pods/exec'] and rule['verbs'] == ['create'])
                require(permitted and not set(rule['resources']) & {'*', 'secrets', 'pods/attach', 'serviceaccounts/token'}, 'RBAC escalation')
                if d['metadata']['name'] == 'pilot-observer':
                    require(set(rule['verbs']) <= {'get', 'list', 'watch'} and 'pods/exec' not in rule['resources'], 'observer execution privilege')
        if d['kind'] == 'RoleBinding':
            require(d['metadata']['namespace'] == 'asep-pilot', 'role binding namespace')
            require(d['roleRef'] == {'apiGroup': 'rbac.authorization.k8s.io', 'kind': 'Role', 'name': d['metadata']['name']}, 'role binding scope')
            groups = {'asep-pilot-testers'} if d['metadata']['name'] == 'pilot-tester' else {'asep-pilot-observers', 'asep-pilot-testers'}
            require(all(s['kind'] == 'Group' for s in d['subjects']) and {s['name'] for s in d['subjects']} == groups, 'RBAC subject')
    require(len(pilot) == 4 and {d['kind'] for d in pilot} == {'Deployment', 'Service', 'Pod'}, 'pilot inventory')
    for d in pilot:
        require(d['metadata']['namespace'] == 'asep-pilot', 'pilot namespace')
        if d['kind'] == 'Service':
            require(d['spec']['type'] == 'ClusterIP' and not any(k in d['spec'] for k in ['externalIPs', 'loadBalancerIP', 'loadBalancerClass', 'externalName']) and all('nodePort' not in p for p in d['spec']['ports']), 'public service')
            require(d['metadata']['name'] == 'pilot-server' and d['spec']['selector'] == {'app': 'pilot-server'} and d['spec']['ports'] == [{'port': 80, 'targetPort': 8080, 'protocol': 'TCP'}], 'Service 80 -> Pod 8080 contract')
            continue
        if d['kind'] == 'Deployment':
            require(d['spec']['replicas'] == 1 and d['spec']['strategy']['type'] == 'Recreate', 'bounded scheduling')
            require(d['spec']['selector']['matchLabels'] == {'app': 'pilot-server'} and d['spec']['template']['metadata']['labels'] == {'app': 'pilot-server'}, 'server selector contract')
            container = d['spec']['template']['spec']['containers'][0]
            require(container['ports'] == [{'containerPort': 8080}] and container['readinessProbe']['httpGet']['port'] == container['livenessProbe']['httpGet']['port'] == 8080 and container['command'] == ['sh', '-ec', "printf '%s' asep-pilot > /www/index.html; exec httpd -f -p 8080 -h /www"], 'server listener/probe contract')
        else:
            access = {'pilot-allowed': 'allowed', 'pilot-denied': 'denied'}
            require(d['metadata']['labels'] == {'app': 'pilot-client', 'access': access[d['metadata']['name']]}, 'client policy selector contract')
        spec = d['spec']['template']['spec'] if d['kind'] == 'Deployment' else d['spec']
        require(spec['serviceAccountName'] == 'pilot' and spec['automountServiceAccountToken'] is False, 'workload API capability')
        require(not any(spec.get(k) for k in ['hostNetwork', 'hostPID', 'hostIPC', 'initContainers', 'ephemeralContainers', 'imagePullSecrets']), 'host/API capability')
        security = spec['securityContext']
        require(security['runAsNonRoot'] is True and security['runAsUser'] == security['runAsGroup'] == 10001 and security['seccompProfile']['type'] == 'RuntimeDefault', 'pod security context')
        require(all(set(v) == {'name', 'emptyDir'} for v in spec.get('volumes', [])), 'persistent/host/secret volume')
        for c in spec['containers']:
            require(c['image'] == lock['pilotImage']['reference'], 'unpinned image')
            sc = c['securityContext']
            require(sc.get('privileged', False) is False and sc['allowPrivilegeEscalation'] is False and sc['readOnlyRootFilesystem'] is True and sc['capabilities'] == {'drop': ['ALL']}, 'container privilege')
            require(not any(p.get('hostPort') for p in c.get('ports', [])) and not c.get('envFrom') and not c.get('env'), 'host port/credential environment')
            require(c['resources']['limits'] == {'cpu': '100m', 'memory': '64Mi'} and c['resources']['requests'] == {'cpu': '10m', 'memory': '16Mi'}, 'pilot resource budget')


def host(config):
    require(platform.system() == 'Linux' and platform.machine() == 'x86_64', 'amd64 Linux required')
    require(Path('/sys/fs/cgroup/cgroup.controllers').is_file(), 'cgroup v2 required')
    require(Path(config['resolv-conf']).is_file(), 'configured upstream resolver file missing')
    for path in ['/opt/asep-k3s-stage', '/etc/rancher/k3s', '/var/lib/rancher/k3s', '/etc/systemd/system/k3s.service', '/usr/local/bin/k3s']:
        try:
            os.lstat(path)
        except FileNotFoundError:
            continue
        raise ValueError('existing installation/state: ' + path)
    def output(*args):
        return subprocess.check_output(args, text=True, stderr=subprocess.PIPE)
    addresses = json.loads(output('ip', '-j', '-4', 'address', 'show', 'dev', config['flannel-iface']))
    require(any(a['local'] == config['node-ip'] for d in addresses for a in d['addr_info']), 'configured node address/interface changed')
    networks = [ipaddress.ip_network(config[k]) for k in ['cluster-cidr', 'service-cidr']]
    routes = json.loads(output('ip', '-j', '-4', 'route', 'show', 'table', 'all'))
    for route in routes:
        dst = route.get('dst', 'default')
        if dst == 'default':
            continue
        route_net = ipaddress.ip_network(dst, strict=False)
        if any(route_net.overlaps(n) for n in networks):
            require(route_net.prefixlen > 1, 'full-tunnel VPN route ' + dst + ': coexistence/exclusion review required; this is not proof of address-space collision')
            raise ValueError('route/VPN/CIDR collision: ' + dst)
    for line in output('ss', '-H', '-lntu').splitlines():
        require(line.split()[4].rsplit(':', 1)[-1] not in {'6443', '6444', '10250', '8472'}, 'K3s port already occupied')
    require(output('systemctl', 'is-active', 'docker.service').strip() == 'active', 'existing Docker not active')
    print('READ-ONLY HOST SNAPSHOT PASS: address/CIDRs/ports/cgroups/Docker; swap unchanged')
    raise ValueError('PRE-START BLOCKED: firewall IPv4/IPv6 enforcement, VPN/split DNS, stable address and backup rehearsal approval are not established by this snapshot')


def diagnostics():
    """Local metadata only; never sudo, connect/probe endpoints or read credentials."""
    commands = [
        ['ip', '-br', 'address'],
        ['ip', '-4', 'route', 'show', 'table', 'all'],
        ['ip', '-6', 'route', 'show', 'table', 'all'],
        ['ip', '-4', 'rule', 'show'], ['ip', '-6', 'rule', 'show'],
        ['ip', '-4', 'route', 'get', '10.42.0.1'],
        ['ip', '-4', 'route', 'get', '10.43.0.10'],
        ['ip', '-4', 'route', 'get', '192.0.2.10'],
        ['resolvectl', 'dns'], ['resolvectl', 'domain'], ['resolvectl', 'default-route'],
        ['ss', '-H', '-4', '-lntu'], ['ss', '-H', '-6', '-lntu'],
        ['systemctl', 'is-active', 'docker.service', 'ufw.service'],
    ]
    for command in commands:
        print('$ ' + ' '.join(command), flush=True)
        try:
            result = subprocess.run(command, text=True, capture_output=True, timeout=10, check=False)
            print(result.stdout, end='')
            if result.returncode:
                print('UNKNOWN (unavailable/denied): ' + result.stderr.strip())
        except (OSError, subprocess.TimeoutExpired) as error:
            print('UNKNOWN: ' + str(error))
    print('DIAGNOSTICS ONLY; no readiness verdict, firewall proof or VPN bypass. See NETWORK-REVIEW.md.')


def self_test(data):
    mutations = [
        lambda x: x[1].update({'secrets-encryption': False}),
        lambda x: x[1]['disable'].remove('traefik'),
        lambda x: x[1].update({'write-kubeconfig-mode': '0644'}),
        lambda x: x[1].update({'bind-address': '::'}),
        lambda x: x[1].update({'node-ip': '192.168.2.124', 'advertise-address': '192.168.2.124'}),
        lambda x: x[1].update({'cluster-cidr': '10.44.0.0/16'}),
        lambda x: x[1].update({'flannel-backend': 'host-gw'}),
        lambda x: x[2]['memorySwap'].update({'swapBehavior': 'LimitedSwap'}),
        lambda x: x[4][0]['metadata']['labels'].update({'pod-security.kubernetes.io/enforce': 'privileged'}),
        lambda x: next(d for d in x[4] if d['kind'] == 'NetworkPolicy' and d['metadata']['name'] == 'default-deny')['spec'].update({'policyTypes': ['Ingress']}),
        lambda x: x[5][1]['spec'].update({'type': 'NodePort'}),
        lambda x: x[5][1]['spec']['ports'][0].update({'targetPort': 80}),
        lambda x: x[5][1]['spec']['ports'][0].update({'port': 8080}),
        lambda x: x[5][1]['spec'].update({'selector': {'app': 'pilot-client'}}),
        lambda x: x[5][0]['spec']['template']['spec']['containers'][0]['ports'][0].update({'containerPort': 80}),
        lambda x: x[5][3]['metadata']['labels'].update({'access': 'allowed'}),
        lambda x: next(d for d in x[4] if d['kind'] == 'NetworkPolicy' and d['metadata']['name'] == 'allow-client-to-server')['spec']['egress'][0]['ports'][0].update({'port': 80}),
        lambda x: next(d for d in x[4] if d['kind'] == 'NetworkPolicy' and d['metadata']['name'] == 'allow-server-from-allowed-client')['spec']['ingress'][0]['ports'][0].update({'port': 80}),
        lambda x: x[5][0]['spec']['template']['spec'].update({'hostNetwork': True}),
        lambda x: x[5][2]['spec'].update({'automountServiceAccountToken': True}),
        lambda x: x[5][2]['spec']['containers'][0].update({'image': 'busybox:latest'}),
        lambda x: next(d for d in x[4] if d['kind'] == 'Role')['rules'].append({'apiGroups': ['*'], 'resources': ['*'], 'verbs': ['*']}),
        lambda x: next(d for d in x[4] if d['kind'] == 'Role' and d['metadata']['name'] == 'pilot-observer')['rules'].append({'apiGroups': [''], 'resources': ['pods/exec'], 'verbs': ['create']}),
        lambda x: next(d for d in x[4] if d['kind'] == 'RoleBinding' and d['metadata']['name'] == 'pilot-tester')['subjects'][0].update({'name': 'asep-pilot-observers'}),
    ]
    for mutation in mutations:
        bad = copy.deepcopy(data)
        mutation(bad)
        try:
            validate(*bad)
        except (ValueError, KeyError):
            continue
        raise ValueError('negative validation case unexpectedly accepted')
    import importlib.machinery
    import importlib.util
    loader = importlib.machinery.SourceFileLoader('asep_public_fetch', str(ROOT / 'fetch'))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    fetch = importlib.util.module_from_spec(spec); loader.exec_module(fetch)
    with tempfile.TemporaryDirectory(prefix='asep-k3s-check-') as directory:
        path = Path(directory); (path / 'artifact').write_bytes(b'public fixture')
        entries = {'artifact': {'size': 14, 'sha256': hashlib.sha256(b'public fixture').hexdigest()}}
        fetch.verify(path, entries)
        (path / 'artifact').write_bytes(b'tampered value')
        try:
            fetch.verify(path, entries)
        except ValueError:
            pass
        else:
            raise ValueError('tampered bundle accepted')
    print(f'{len(mutations) + 1} OFFLINE NEGATIVE CASES PASS ({len(mutations)} unsafe configurations + tampered public artifact)')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', action='store_true', help='additional read-only pre-installation snapshot; no kubeconfig')
    parser.add_argument('--self-test', action='store_true')
    parser.add_argument('--diagnostics', action='store_true', help='local network metadata only; never a readiness PASS')
    args = parser.parse_args()
    try:
        data = load(); validate(*data)
        print('OFFLINE FOUNDATION CHECKS PASS; Kubernetes API-schema/admission/runtime checks NOT EXECUTED')
        if args.self_test:
            self_test(data)
        if args.host:
            host(data[1])
        if args.diagnostics:
            diagnostics()
    except (ValueError, KeyError, OSError, subprocess.SubprocessError) as error:
        raise SystemExit('FAIL / UNKNOWN: ' + str(error))
