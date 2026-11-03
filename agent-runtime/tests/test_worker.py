import json
from pathlib import Path
import subprocess
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from asep_agent.worker import execute, strict_json


def task():
    return dict(schema='asep.agent.v1', runId='11111111-1111-4111-8111-111111111111',
        taskId='22222222-2222-4222-8222-222222222222',attemptId='33333333-3333-4333-8333-333333333333',
        operationId='44444444-4444-4444-8444-444444444444',runVersion=1,leaseEpoch=1,
        policyId='synthetic-bounded',policyEpoch=1,sourceFingerprint='a'*64,baseSha='b'*40,
        stage='IMPLEMENTING',tokenReservation=128,remainingSeconds=120,scenario='success')


class WorkerTest(unittest.TestCase):
    def test_bound_untrusted_output_all_stages(self):
        for stage in ('INVESTIGATING','PLANNING','IMPLEMENTING'):
            t=task();t['stage']=stage;r=execute(t)
            self.assertEqual(r['binding']['taskId'],t['taskId'])
            self.assertEqual(r['binding']['leaseEpoch'],1)
            self.assertEqual(r['classification'],'UNTRUSTED_PROPOSAL')
            self.assertNotIn('verified',r)

    def test_strict_scope_and_finite_limits(self):
        for field,value in [('stage','DELIVERY_PENDING'),('tokenReservation',0),('tokenReservation',129),
                            ('policyId','live-policy'),('runVersion',True),('remainingSeconds',301)]:
            t=task();t[field]=value
            with self.subTest(field=field,value=value),self.assertRaises(ValueError):execute(t)
        t=task();t['credential']='not-accepted'
        with self.assertRaises(ValueError):execute(t)
        with self.assertRaises(ValueError):strict_json('{"schema":1,"schema":2}')

    def test_unavailable_does_not_fallback(self):
        t=task();t['scenario']='model-unavailable';r=execute(t)
        self.assertEqual(r['status'],'MODEL_UNAVAILABLE');self.assertIsNone(r['proposal'])
        self.assertEqual(r['model'],'deterministic-local-fixture/v1')

    def test_actual_process_boundary_and_explicit_synthetic_mode(self):
        script=Path(__file__).resolve().parents[1]/'asep_agent/worker.py'
        r=subprocess.run([sys.executable,'-I','-B',str(script),'--synthetic'],input=json.dumps(task()),text=True,capture_output=True,check=True)
        self.assertEqual(json.loads(r.stdout)['binding']['attemptId'],task()['attemptId'])
        r=subprocess.run([sys.executable,'-I','-B',str(script)],input=json.dumps(task()),text=True,capture_output=True)
        self.assertEqual(r.returncode,2)


if __name__=='__main__':unittest.main()
