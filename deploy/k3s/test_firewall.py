#!/usr/bin/env python3
"""Offline policy/model and start-gate tests. NEVER call nft, systemd, ip or root paths."""
import configparser
import copy
import importlib.machinery
import importlib.util
import ipaddress
import json
from pathlib import Path
import shlex
from unittest import mock
import unittest

ROOT = Path(__file__).resolve().parent
loader = importlib.machinery.SourceFileLoader('asep_firewall', str(ROOT / 'firewall'))
spec = importlib.util.spec_from_loader(loader.name, loader)
guard = importlib.util.module_from_spec(spec)
loader.exec_module(guard)


def parse_policy():
    # Intentionally restricted offline parser for THIS small checked-in grammar;
    # not nft's native parser and not kernel enforcement evidence.
    lines = [line.strip() for line in (ROOT / 'firewall.nft.example').read_text().splitlines()
             if line.strip() and not line.lstrip().startswith('#')]
    assert lines[:3] == ['table inet asep_k3s_guard {', 'chain input {',
                         'type filter hook input priority 10; policy accept;']
    assert lines[-2:] == ['}', '}']
    rules = []
    for line in lines[3:-2]:
        tokens = shlex.split(line)
        matches = []
        while tokens[0] != 'counter':
            head = tokens.pop(0)
            if head == 'iifname':
                left, right = {'meta': {'key': head}}, tokens.pop(0)
            elif head == 'ip':
                field, value = tokens.pop(0), tokens.pop(0)
                assert field in ['saddr', 'daddr']
                left = {'payload': {'protocol': 'ip', 'field': field}}
                if '/' in value:
                    net = ipaddress.ip_network(value)
                    right = {'prefix': {'addr': str(net.network_address), 'len': net.prefixlen}}
                else:
                    right = str(ipaddress.ip_address(value))
            else:
                assert head in ['tcp', 'udp'] and tokens.pop(0) == 'dport'
                left = {'payload': {'protocol': head, 'field': 'dport'}}
                value = tokens.pop(0)
                if value == '{':
                    ports = []
                    while tokens[0] != '}':
                        ports.append(int(tokens.pop(0).rstrip(',')))
                    tokens.pop(0)
                    right = {'set': ports}
                else:
                    right = int(value)
            matches.append({'match': {'op': '==', 'left': left, 'right': right}})
        assert len(tokens) == 4 and tokens[0] == 'counter' and tokens[1] in ['accept', 'drop'] and tokens[2] == 'comment'
        rules.append(dict(family='inet', table=guard.TABLE, chain='input', comment=tokens[3],
                          expr=matches + [{'counter': {'packets': 7, 'bytes': 700}}, {tokens[1]: None}]))
    return rules


def live_fixture():
    return {'nftables': [
        {'metainfo': {'json_schema_version': 1}},
        {'table': dict(family='inet', name=guard.TABLE, handle=1)},
        {'chain': dict(family='inet', table=guard.TABLE, name='input', type='filter',
                       hook='input', prio=10, policy='accept', handle=2)},
        *({'rule': rule} for rule in parse_policy())]}


def verdict(interface, source, destination, protocol, port):
    packet = {'iifname': interface, 'saddr': source, 'daddr': destination, 'dport': port}
    for rule in parse_policy():
        matched = True
        for expr in rule['expr'][:-2]:
            match = expr['match']
            left = match['left']
            key = left.get('meta', {}).get('key') or left['payload']['field']
            if 'payload' in left:
                family = left['payload']['protocol']
                if (family == 'ip' and ipaddress.ip_address(source).version != 4) or (family in ['tcp', 'udp'] and protocol != family):
                    matched = False
                    break
            right = match['right']
            if isinstance(right, dict) and 'set' in right:
                matched &= packet[key] in right['set']
            elif isinstance(right, dict) and 'prefix' in right:
                prefix = right['prefix']
                matched &= ipaddress.ip_address(packet[key]) in ipaddress.ip_network(f"{prefix['addr']}/{prefix['len']}")
            else:
                matched &= packet[key] == right
        if matched:
            return next(iter(rule['expr'][-1]))
    return 'accept'  # Only this INPUT chain's policy, not a global verdict.


class FirewallTest(unittest.TestCase):
    def test_policy_and_live_shape_agree(self):
        guard.validate_live(live_fixture())

    def test_local_tcp_and_overlay(self):
        for address in ['127.0.0.1', '192.0.2.10', '::1']:
            for protocol, port in [('tcp', 6443), ('tcp', 10250), ('udp', 8472)]:
                with self.subTest(address=address, port=port):
                    self.assertEqual(verdict('lo', address, address, protocol, port), 'accept')

    def test_external_and_spoofed_sources_denied_in_both_families(self):
        for interface in ['example0', 'tun0', 'docker0', 'br-test', 'flannel.1']:
            for source in ['192.168.2.9', '10.42.0.9', 'fd00::9']:
                for protocol, port in [('tcp', 6443), ('tcp', 10250), ('udp', 8472)]:
                    with self.subTest(interface=interface, source=source, port=port):
                        destination = 'fd00::1' if ':' in source else '192.0.2.10'
                        self.assertEqual(verdict(interface, source, destination, protocol, port), 'drop')

    def test_cni_api_only(self):
        self.assertEqual(verdict('cni0', '10.42.0.9', guard.NODE_IP, 'tcp', 6443), 'accept')
        for source, destination, protocol, port in [
            ('10.42.0.9', guard.NODE_IP, 'tcp', 10250),
            ('10.42.0.9', guard.NODE_IP, 'udp', 8472),
            ('172.19.0.9', guard.NODE_IP, 'tcp', 6443),
            ('10.42.0.9', '192.168.2.124', 'tcp', 6443),
            ('fd00::9', 'fd00::1', 'tcp', 6443),
        ]:
            with self.subTest(source=source, port=port):
                self.assertEqual(verdict('cni0', source, destination, protocol, port), 'drop')

    def test_unrelated_input_is_unchanged(self):
        for protocol, port in [('tcp', 22), ('udp', 67), ('udp', 68), ('udp', 53),
                               ('tcp', 53), ('tcp', 55432), ('tcp', 11434), ('tcp', 8080)]:
            with self.subTest(port=port):
                self.assertEqual(verdict('tun0', '192.168.2.9', guard.NODE_IP, protocol, port), 'accept')

    def test_live_tampering_fails_closed(self):
        mutations = [
            lambda x: x['nftables'][1]['table'].update(flags=['dormant']),
            lambda x: x['nftables'][1]['table'].update(family='ip'),
            lambda x: x['nftables'][2]['chain'].update(hook='forward'),
            lambda x: x['nftables'][2]['chain'].update(prio=0),
            lambda x: x['nftables'][2]['chain'].update(policy='drop'),
            lambda x: x['nftables'].pop(),
            lambda x: x['nftables'].reverse(),  # reversed rule order exposes ports
            lambda x: x['nftables'][6]['rule']['expr'][-1].update(accept=None),
            lambda x: x['nftables'][5]['rule']['expr'][0]['match'].update(right='tun0'),
            lambda x: x['nftables'][5]['rule']['expr'].pop(1),
            lambda x: x['nftables'].append({'set': {'name': 'unexpected'}}),
        ]
        for index, mutation in enumerate(mutations):
            with self.subTest(mutation=index):
                fixture = live_fixture()
                mutation(fixture)
                with self.assertRaises((ValueError, KeyError)):
                    guard.validate_live(fixture)

    def test_reload_is_one_scoped_transaction(self):
        text = (ROOT / 'firewall.nft.example').read_text()
        batch = guard.transaction(text)
        self.assertTrue(batch.startswith('add table inet asep_k3s_guard\ndelete table inet asep_k3s_guard\n'))
        self.assertEqual(batch.count('delete table'), 1)
        for unsafe in ['flush ruleset', 'hook forward', 'hook output', 'hook prerouting',
                       'ct state', 'include ', 'table ip ', 'table ip6 ']:
            self.assertNotIn(unsafe, batch)

    def test_dhcp_change_blocks_start(self):
        for local in [guard.NODE_IP, '192.168.2.124']:
            with self.subTest(local=local), mock.patch.object(guard, 'command', return_value=json.dumps([{'addr_info': [{'local': local}]}])):
                if local == guard.NODE_IP:
                    guard.check_address()
                else:
                    with self.assertRaises(ValueError):
                        guard.check_address()

    def test_systemd_start_and_stop_contract(self):
        unit = configparser.ConfigParser(interpolation=None)
        unit.read(ROOT / 'asep-k3s-firewall.service')
        self.assertEqual(unit['Service']['Type'], 'oneshot')
        self.assertEqual(unit['Service']['RemainAfterExit'], 'yes')
        self.assertNotIn('ExecStop', unit['Service'])
        self.assertEqual(unit['Service']['ExecStart'], str(guard.HELPER) + ' apply')
        self.assertEqual(set(unit['Unit']['After'].split()), {'nftables.service', 'ufw.service'})
        self.assertNotIn('Wants', unit['Unit'])
        dropin = configparser.ConfigParser(interpolation=None)
        dropin.read(ROOT / 'k3s-firewall.conf')
        self.assertIn('asep-k3s-firewall.service', dropin['Unit']['BindsTo'].split())
        self.assertIn('asep-k3s-firewall.service', dropin['Unit']['After'].split())
        self.assertEqual(dropin['Service']['ExecStartPre'], str(guard.HELPER) + ' check')

    def test_checkout_never_has_live_authority(self):
        with mock.patch.object(guard, 'command') as command:
            with self.assertRaises(ValueError):
                guard.trusted_inputs()
            command.assert_not_called()


if __name__ == '__main__':
    unittest.main(verbosity=2)
