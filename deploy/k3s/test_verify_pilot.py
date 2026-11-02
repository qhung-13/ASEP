#!/usr/bin/env python3
"""Offline CLI failure tests only. No real K3s, kubeconfig, network or Owner authority."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent
FAKE = r'''#!/usr/bin/python3
import json,os,sys
from pathlib import Path
args=sys.argv[1:]
mode=os.environ['FIXTURE_MODE']
with Path(os.environ['FIXTURE_CALLS']).open('a') as f: f.write('called\n')
if 'service' in args and 'get' in args:
    print(json.dumps({'spec':{'type':'ClusterIP','selector':{'app':'pilot-server'},'ports':[{'port':80,'targetPort':80 if mode=='wrong-service-port' else 8080,'protocol':'TCP'}]}}))
elif 'pods' in args and 'get' in args:
    print(json.dumps({'items':[{'status':{'podIP':'10.42.0.9'}}]}))
elif 'endpointslices' in args:
    print(json.dumps({'items':[{'ports':[{'port':80 if mode=='wrong-endpoint-port' else 8080,'protocol':'TCP'}],'endpoints':[{'conditions':{'ready':True},'addresses':['10.42.0.9']}]}]}))
elif 'exec' in args and 'wget' in args:
    if mode=='allowed-unavailable': sys.exit(1)
    print('asep-pilot')
elif 'exec' in args and 'pilot-denied' in args and 'sh' in args:
    if mode=='exec-failed': sys.exit(17)
    print('0' if mode=='denied-reached' else '1',end='')
elif 'can-i' in args:
    yes='pods/exec' in args and 'asep-pilot' in args
    print('yes' if yes else 'no')
    sys.exit(0 if yes else 1)
elif 'apply' in args:
    if mode=='admission-accepted': sys.exit(0)
    print('transport failure' if mode=='unrelated-admission-failure' else 'violates PodSecurity: privileged hostPath',file=sys.stderr)
    sys.exit(1)
'''


class PilotCliTest(unittest.TestCase):
    def run_fixture(self, mode, flagged=True):
        with tempfile.TemporaryDirectory(prefix='asep-pilot-offline-') as temporary:
            directory = Path(temporary)
            fake = directory / 'fake-k3s'
            fake.write_text(FAKE)
            fake.chmod(0o700)
            source = (ROOT / 'verify-pilot').read_text()
            self.assertEqual(source.count('/usr/local/bin/k3s'), 1)
            script = directory / 'verify-pilot'
            # ONLY this disposable script substitutes the absolute installed CLI.
            # The production script has no fake/override mode.
            script.write_text(source.replace('/usr/local/bin/k3s', str(fake)))
            (directory / 'versions.lock.json').write_bytes((ROOT / 'versions.lock.json').read_bytes())
            calls = directory / 'calls'
            env = {'PATH': os.defpath, 'HOME': str(directory), 'FIXTURE_MODE': mode,
                   'FIXTURE_CALLS': str(calls)}
            args = ['/bin/bash', str(script)]
            if flagged:
                args.append('--run-after-owner-approval')
            result = subprocess.run(args, capture_output=True, text=True, env=env, timeout=15)
            return result, calls.exists()

    def test_fixture_success(self):
        result, called = self.run_fixture('success')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(called)

    def test_unflagged_never_calls_cli(self):
        result, called = self.run_fixture('success', flagged=False)
        self.assertEqual(result.returncode, 2)
        self.assertFalse(called)

    def test_failures_never_report_pass(self):
        for mode in ['wrong-service-port', 'wrong-endpoint-port', 'allowed-unavailable',
                     'denied-reached', 'exec-failed', 'admission-accepted',
                     'unrelated-admission-failure']:
            with self.subTest(mode=mode):
                result, _ = self.run_fixture(mode)
                self.assertNotEqual(result.returncode, 0, mode)
                self.assertNotIn('CHECKS PASS', result.stdout)


if __name__ == '__main__':
    unittest.main(verbosity=2)
