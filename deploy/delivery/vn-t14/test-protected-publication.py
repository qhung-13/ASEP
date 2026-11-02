#!/usr/bin/python3
"""Unprivileged tests: dummy data under /tmp only, no real credentials/network/DB."""
from contextlib import ExitStack
from datetime import datetime, timedelta, timezone
import errno
import importlib.util
import json
import os
from pathlib import Path
import pty
import select
import signal
import stat
import subprocess
import sys
import tempfile
import termios
import time
import unittest
from unittest.mock import patch
from protected_local_files import Refused, digest, directory, publish, strict_json

HERE = Path(__file__).parent

def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result

P = module('publisher', 'publish-delivery-evidence.py')
C = module('credentials', 'import-gitlab-credentials.py')


def encode(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':')) + '\n').encode()


def stamp(value):
    return value.isoformat(timespec='microseconds').replace('+00:00', 'Z')


class Fixture:
    def __init__(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='asep-vnt14-publication-')
        self.root = Path(self.temporary.name)
        self.context = P.Context(self.root)
        self.review = self.mkdir('/var/lib/asep/operator/private/review', 0o700)
        self.bundle = self.mkdir('/var/lib/asep/operator/private/review/enrollment', 0o700)
        self.evidence = self.mkdir('/var/lib/asep/operator/private/review/enrollment/evidence', 0o700)
        self.raw = self.mkdir('/var/lib/asep/operator/private/review/enrollment/raw', 0o700)
        self.source = self.mkdir('/var/lib/asep/controller/evidence/operations/' + P.OP, 0o750)
        self.target = self.mkdir('/etc/asep/delivery-enrollment', 0o755)
        self.credentials = self.mkdir('/var/lib/asep/executor/private', 0o700)
        self.request = dict(operationId=P.OP, runId='11111111-1111-4111-8111-111111111111',
            attemptId='22222222-2222-4222-8222-222222222222', runVersion=2,
            ownerGrantId='7834ded2-2251-433d-afe5-2eab964fc19b', policyId='vn-t14-probe-20261102',
            policyEpoch=1, baseSha=P.BASE, expectedTargetSha=P.BASE, candidateCommitSha='a'*40,
            candidateTreeSha='b'*40, manifestSha256='c'*64,
            sealReceiptId='33333333-3333-4333-8333-333333333333',
            verificationReceiptId='44444444-4444-4444-8444-444444444444',
            verifierProfileSha256=P.PROFILE, safeCiProfileSha256=P.SAFE_CI)
        self.request_body = encode(self.request)
        self.expected = P.request_hash(self.request)
        # Dummy receipts are deliberately not candidate verification evidence.
        bodies = [self.request_body, b'{"DUMMY_ONLY":"not a real seal"}\n', b'{"DUMMY_ONLY":"not a real verification"}\n']
        index = {}
        for filename, key, body in zip(('request.json', 'seal.json', 'verification.json'),
                ('requestSha256', 'sealSha256', 'verificationSha256'), bodies):
            self.write(self.source / filename, body, 0o640)
            index[key] = digest(body)
        self.write(self.source / 'index.json', encode(index), 0o640)
        self.read_token, self.write_token = b'DUMMY_READ_not_live_0123456789', b'DUMMY_WRITE_not_live_0123456789'
        self.write(self.credentials / C.NAMES[0], self.read_token)
        self.write(self.credentials / C.NAMES[1], self.write_token)
        now = datetime.now(timezone.utc)
        self.permit = dict(requestHash=self.expected, projectId=87786140,
            repositoryPath='qhung-13/asep-m01-sandbox', sourceBranch='test/vn-t14/' + P.OP,
            targetBranch='main', safeCiProfileSha256=P.SAFE_CI,
            readCredentialSha256=digest(self.read_token), writeCredentialSha256=digest(self.write_token),
            writeAccessLevel=30, writeScopes=['api', 'write_repository'],
            credentialExpiresAt=stamp(now + timedelta(hours=1)),
            inspectionStartedAt=stamp(now-timedelta(seconds=2)), inspectionCompletedAt=stamp(now),
            expiresAt=stamp(now+timedelta(seconds=240)), facts={})
        raw = b'DUMMY ONLY: synthetic capture. Never publish in live evidence paths.\n'
        h = digest(raw)
        self.write(self.raw / h, raw)
        self.notes = dict(requestHash=self.expected, subjects={})
        self.observations = {}
        for subject in P.SUBJECTS:
            o = dict(subject=subject, collector='DUMMY fixture independent collector', method='OWNER_UI',
                     httpStatus=0, complete=True, startedAt=self.permit['inspectionStartedAt'],
                     completedAt=self.permit['inspectionCompletedAt'], assessment='SAFE_FOR_EXACT_PROBE',
                     requestHash=self.expected, itemCount=0 if subject in P.COUNTS else None)
            self.observations[subject] = o
            self.notes['subjects'][subject] = dict(collector=o['collector'],
                rationale='DUMMY ONLY: exercises file contract without asserting actual safety.', sources=[h])
        self.save()

    def mkdir(self, name, mode):
        p = self.root / name.lstrip('/')
        current = self.root
        for part in p.relative_to(self.root).parts:
            current = current / part
            current.mkdir(mode=0o700, exist_ok=True)
        p.chmod(mode)
        return p

    @staticmethod
    def write(path, body, mode=0o600):
        path.write_bytes(body)
        path.chmod(mode)

    def save(self):
        for subject, o in self.observations.items():
            body = encode(o)
            h = digest(body)
            self.write(self.evidence / (h + '.json'), body)
            self.permit['facts'][subject] = dict(status='VERIFIED', evidenceSha256=h, provenance=o['collector'])
        self.write(self.bundle / 'permit.json', encode(self.permit))
        self.write(self.bundle / 'review-notes.json', encode(self.notes))

    def close(self):
        self.temporary.cleanup()


class PublicationTests(unittest.TestCase):
    def setUp(self):
        self.f = Fixture()
        self.addCleanup(self.f.close)

    def run_mode(self, mode='publish', apply=True, confirm=lambda _: True):
        return P.run(self.f.context, mode, self.f.expected, apply, confirm)

    def assert_no_permit(self):
        self.assertFalse((self.f.target / (P.OP + '.json')).exists())

    def test_inspect_request_reports_canonical_identity_without_writes(self):
        result = subprocess.run(['/usr/bin/python3', '-I', '-B', str(HERE / 'publish-delivery-evidence.py'),
            'inspect-request', '--fixture-root', str(self.f.root)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        metadata = json.loads(result.stdout)
        self.assertEqual(metadata['requestHash'], self.f.expected)
        self.assertEqual(metadata['requestFileSha256'], digest(self.f.request_body))
        self.assertFalse((self.f.review / 'request.json').exists())
        self.assertEqual(list(self.f.target.iterdir()), [])

    def test_check_only_never_writes(self):
        self.assertEqual(self.run_mode(apply=False), 'CHECKED')
        self.assertEqual(list(self.f.target.iterdir()), [])
        self.assertFalse((self.f.review / 'request.json').exists())

    def test_import_preserves_exact_indexed_request_and_no_overwrite(self):
        self.assertEqual(self.run_mode('import-request'), 'IMPORTED')
        path = self.f.review / 'request.json'
        self.assertEqual(path.read_bytes(), self.f.request_body)
        self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
        self.assertEqual(path.stat().st_nlink, 1)
        with self.assertRaises(Refused):
            self.run_mode('import-request')
        self.assertEqual(path.read_bytes(), self.f.request_body)
        self.assert_no_permit()

    def test_publish_all18_preserves_inputs_and_no_replay(self):
        before = (self.f.bundle / 'permit.json').read_bytes()
        self.assertEqual(self.run_mode(), 'PUBLISHED')
        self.assertEqual((self.f.target / (P.OP + '.json')).read_bytes(), before)
        self.assertEqual(len(list((self.f.target / 'evidence').iterdir())), 18)
        self.assertEqual(stat.S_IMODE((self.f.target / 'audit' / (P.OP + '.json')).stat().st_mode), 0o600)
        with self.assertRaises(Refused):
            self.run_mode()
        self.assertEqual((self.f.target / (P.OP + '.json')).read_bytes(), before)

    def assessment(self):
        permit = dict(self.f.permit); permit.pop('facts')
        draft = dict(permit=permit, subjects={subject: dict(status='VERIFIED', observation=o,
            review=self.f.notes['subjects'][subject]) for subject,o in self.f.observations.items()})
        self.f.write(self.f.bundle / 'assessment.json', encode(draft))
        (self.f.bundle / 'permit.json').unlink(); (self.f.bundle / 'review-notes.json').unlink()
        for path in self.f.evidence.iterdir():
            path.unlink()
        return draft

    def test_stage_explicit_decisions_then_publish_without_hand_hashing(self):
        self.assessment()
        self.assertEqual(self.run_mode('stage', apply=False), 'CHECKED')
        self.assertFalse((self.f.bundle / 'permit.json').exists())
        self.assertEqual(self.run_mode('stage'), 'STAGED')
        self.assert_no_permit()
        self.assertEqual(self.run_mode(), 'PUBLISHED')

    def test_stage_never_defaults_missing_or_unknown_status_to_pass(self):
        draft = self.assessment()
        draft['subjects']['auto-devops']['status'] = 'UNKNOWN'
        self.f.write(self.f.bundle / 'assessment.json', encode(draft))
        with self.assertRaises(Refused):
            self.run_mode('stage')
        self.assertEqual(list(self.f.evidence.iterdir()), [])
        self.assertFalse((self.f.bundle / 'permit.json').exists())

    def test_wrong_trusted_hash_and_tampered_controller_index(self):
        with self.assertRaises(Refused):
            P.run(self.f.context, 'import-request', '0'*64)
        self.f.write(self.f.source / 'request.json', self.f.request_body + b' ')
        with self.assertRaises(Refused):
            self.run_mode('import-request')
        self.assertFalse((self.f.review / 'request.json').exists())

    def test_scope_status_freshness_and_inventory_fail_closed(self):
        cases = [
            ('wrong project', lambda: self.f.permit.update(projectId=42)),
            ('wrong branch', lambda: self.f.permit.update(sourceBranch='main')),
            ('expired', lambda: self.f.permit.update(expiresAt=stamp(datetime.now(timezone.utc)-timedelta(seconds=1)))),
            ('future', lambda: self.f.permit.update(inspectionCompletedAt=stamp(datetime.now(timezone.utc)+timedelta(seconds=5)))),
            ('laundered start', lambda: self.f.permit.update(inspectionStartedAt=stamp(datetime.now(timezone.utc)))),
            ('too long', lambda: self.f.permit.update(expiresAt=stamp(datetime.now(timezone.utc)+timedelta(seconds=600)))),
            ('unknown count', lambda: self.f.observations['source-push-ci-behavior'].update(itemCount=None)),
            ('boolean count', lambda: self.f.observations['source-push-ci-behavior'].update(itemCount=False)),
            ('incomplete', lambda: self.f.observations['auto-devops'].update(complete=False)),
            ('403', lambda: self.f.observations['auto-devops'].update(method='GET', httpStatus=403)),
            ('unknown', lambda: self.f.observations['auto-devops'].update(assessment='UNKNOWN')),
            ('raw API POST', lambda: self.f.observations['auto-devops'].update(method='POST', httpStatus=200)),
            ('missing subject', lambda: self.f.permit['facts'].pop('auto-devops')),
            ('credential mismatch', lambda: self.f.permit.update(readCredentialSha256='a'*64)),
            ('same credential', lambda: self.f.permit.update(readCredentialSha256=self.f.permit['writeCredentialSha256'])),
        ]
        for label, change in cases:
            with self.subTest(case=label):
                original = self.f
                self.f = Fixture()
                try:
                    change()
                    if label != 'missing subject':
                        self.f.save()
                    else:
                        self.f.write(self.f.bundle / 'permit.json', encode(self.f.permit))
                    with self.assertRaises(Refused):
                        self.run_mode()
                    self.assertEqual(list(self.f.target.iterdir()), [])
                finally:
                    self.f.close()
                    self.f = original

    def test_duplicate_json_rejected(self):
        with self.assertRaises(Refused):
            strict_json(b'{"projectId":87786140,"projectId":42}')
        with self.assertRaises(Refused):
            strict_json(b'{"x":NaN}')

    def test_symlink_hardlink_fifo_and_writable_parent_rejected(self):
        path = self.f.bundle / 'permit.json'
        saved = path.read_bytes()
        path.unlink()
        path.symlink_to(self.f.root / 'missing')
        with self.assertRaises(OSError):
            self.run_mode()
        path.unlink()
        self.f.write(path, saved)
        os.link(path, self.f.root / 'hardlink')
        with self.assertRaises(Refused):
            self.run_mode()
        (self.f.root / 'hardlink').unlink()
        path.unlink()
        os.mkfifo(path, 0o600)
        with self.assertRaises(Refused):
            self.run_mode()
        path.unlink()
        self.f.write(path, saved)
        self.f.bundle.chmod(0o722)
        with self.assertRaises(Refused):
            self.run_mode()
        self.f.bundle.chmod(0o700)
        self.assertEqual(list(self.f.target.iterdir()), [])

    def test_destination_symlink_and_parent_symlink_refused(self):
        dest = self.f.review / 'request.json'
        victim = self.f.root / 'untouched'
        victim.write_bytes(b'sentinel')
        dest.symlink_to(victim)
        with self.assertRaises(Refused):
            self.run_mode('import-request')
        self.assertEqual(victim.read_bytes(), b'sentinel')
        dest.unlink()
        other = self.f.root / 'other'; other.mkdir()
        self.f.target.rmdir(); self.f.target.symlink_to(other)
        with self.assertRaises(OSError):
            self.run_mode()
        self.assertEqual(list(other.iterdir()), [])

    def test_publication_confirmation_and_post_review_revalidation(self):
        with self.assertRaises(Refused):
            self.run_mode(confirm=lambda _: False)
        def changed(_):
            self.f.write(self.f.credentials / C.NAMES[0], b'DUMMY_CHANGED_credential_0123456789')
            return True
        with self.assertRaises(Refused):
            self.run_mode(confirm=changed)
        self.assertEqual(list(self.f.target.iterdir()), [])

    def test_creation_race_does_not_overwrite(self):
        parent = directory(self.f.review, {0, os.geteuid()}, fixture=True, private=True)
        self.addCleanup(os.close, parent)
        actual = os.link
        def race(source, dest, **kwargs):
            self.f.write(self.f.review / dest, b'race-sentinel')
            return actual(source, dest, **kwargs)
        with patch('os.link', race), self.assertRaises(FileExistsError):
            publish(parent, 'request.json', b'DUMMY', os.geteuid(), os.getegid(), 0o600)
        self.assertEqual((self.f.review / 'request.json').read_bytes(), b'race-sentinel')
        self.assertFalse(list(self.f.review.glob('.pending-*')))

    def test_partial_batch_retained_without_publishable_permit(self):
        actual = P.publish
        count = 0
        def interrupted(*args):
            nonlocal count
            count += 1
            if count == 5:
                raise OSError('dummy disk failure')
            return actual(*args)
        with patch.object(P, 'publish', interrupted), self.assertRaises(OSError):
            self.run_mode()
        self.assert_no_permit()
        existing = list((self.f.target / 'evidence').iterdir())
        self.assertGreater(len(existing), 0)
        with self.assertRaises(Refused):
            self.run_mode()
        self.assertEqual(list((self.f.target / 'evidence').iterdir()), existing)

    def test_raw_capture_tamper_and_missing_notes_refused(self):
        raw = next(self.f.raw.iterdir())
        self.f.write(raw, b'DUMMY TAMPERED')
        with self.assertRaises(Refused):
            self.run_mode()
        self.assertEqual(list(self.f.target.iterdir()), [])

    def test_fixture_cannot_select_live_root(self):
        with self.assertRaises(Refused):
            P.Context('/var/lib/asep')
        with self.assertRaises(Refused):
            P.Context('/tmp/not-a-dedicated-fixture')

    def test_exact_request_source_changed_during_import_refused(self):
        def changed(_):
            self.f.write(self.f.source / 'request.json', b'{}')
            return True
        with self.assertRaises(Refused):
            self.run_mode('import-request', confirm=changed)
        self.assertFalse((self.f.review / 'request.json').exists())


class CredentialTests(unittest.TestCase):
    def setUp(self):
        self.f = Fixture(); self.addCleanup(self.f.close)
        for name in C.NAMES:
            (self.f.credentials / name).unlink()

    def test_check_only_does_not_prompt_or_create(self):
        self.assertEqual(C.run(self.f.context, True, lambda _: self.fail('prompted')), [])
        self.assertEqual(list(self.f.credentials.iterdir()), [])

    def test_confirmed_separate_tokens_and_no_overwrite(self):
        values = iter([self.f.read_token.decode()]*2 + [self.f.write_token.decode()]*2)
        result = C.run(self.f.context, False, lambda _: next(values))
        self.assertEqual([h for _, h in result], [digest(self.f.read_token), digest(self.f.write_token)])
        for name in C.NAMES:
            self.assertEqual(stat.S_IMODE((self.f.credentials / name).stat().st_mode), 0o600)
        with self.assertRaises(Refused):
            C.run(self.f.context, False, lambda _: self.fail('prompted before existing check'))

    def test_mismatch_same_token_control_chars_create_nothing(self):
        for values in ([self.f.read_token.decode(), 'DUMMY_OTHER_0123456789012345'],
                       [self.f.read_token.decode()]*4, ['DUMMY_token_with_newline\n0123456789']):
            with self.subTest(values='DUMMY_ONLY'):
                it = iter(values)
                with self.assertRaises(Refused):
                    C.run(self.f.context, False, lambda _: next(it))
                self.assertEqual(list(self.f.credentials.iterdir()), [])

    def credential_reports(self):
        now = datetime.now(timezone.utc)
        result = {}
        for kind in ('read', 'write'):
            report = dict(project_id=87786140, project_identity_matches_owner=True, identity_is_bot=True,
                credential_active=True, credential_revoked=False, credential_expiration_matches_owner=True,
                credential_scopes=['read_api', 'read_repository'] if kind == 'read' else ['api', 'write_repository'],
                effective_access_level=30, archived=False, target_protected=True, credential_can_push_target=False,
                target_head=P.BASE, mutations_performed=0, inspection_started_at=stamp(now-timedelta(seconds=2)),
                inspection_completed_at=stamp(now), requests={label: dict(http_status=200, complete=True)
                for label in ('identity','credential_metadata','project','target_branch')})
            self.f.write(self.f.credentials / ('gitlab-' + kind + '-preflight.json'), encode(report))
            result[kind] = report
        return result

    def test_sanitized_credential_reports_require_successful_actual_authentication(self):
        reports = self.credential_reports()
        self.assertTrue(C.verify_reports(self.f.context))
        for changes in [dict(effective_access_level=40), dict(credential_can_push_target=True),
                        dict(credential_active=False), dict(credential_scopes=['api']),
                        dict(credential_expiration_matches_owner=False)]:
            altered = dict(reports['read']); altered.update(changes)
            self.f.write(self.f.credentials / 'gitlab-read-preflight.json', encode(altered))
            with self.assertRaises(Refused):
                C.verify_reports(self.f.context)
        altered = dict(reports['read']); altered['requests'] = dict(altered['requests'])
        altered['requests']['identity'] = dict(http_status=401, complete=False)
        self.f.write(self.f.credentials / 'gitlab-read-preflight.json', encode(altered))
        with self.assertRaises(Refused):
            C.verify_reports(self.f.context)

    def test_hidden_entry_and_echo_failure_use_real_pty(self):
        for fail_echo in (False, True):
            with self.subTest(fail_echo=fail_echo):
                if not fail_echo:
                    pass
                pid, master = pty.fork()
                if pid == 0:
                    try:
                        sys.argv = [str(HERE / 'import-gitlab-credentials.py'), '--fixture-root', str(self.f.root)]
                        if fail_echo:
                            def broken(*_args, **_kwargs):
                                raise termios.error('DUMMY echo control failure')
                            termios.tcsetattr = broken
                        C.main()
                        sys.stdout.flush(); sys.stderr.flush(); os._exit(0)
                    except BaseException:
                        print('DUMMY-REFUSED', flush=True); os._exit(1)
                output = b''; sent = 0
                values = [self.f.read_token]*2 + [self.f.write_token]*2
                try:
                    deadline = time.monotonic() + 10
                    while time.monotonic() < deadline:
                        if not select.select([master], [], [], 0.1)[0]:
                            continue
                        try:
                            data = os.read(master, 8192)
                        except OSError as error:
                            if error.errno == errno.EIO:
                                break
                            raise
                        if not data:
                            break
                        output += data
                        while output.count(b'(hidden): ') > sent:
                            self.assertFalse(termios.tcgetattr(master)[3] & termios.ECHO)
                            os.write(master, values[sent] + b'\n'); sent += 1
                    else:
                        os.kill(pid, signal.SIGKILL); self.fail('PTY timeout')
                    _, status = os.waitpid(pid, 0)
                finally:
                    os.close(master)
                self.assertEqual(os.waitstatus_to_exitcode(status), 1 if fail_echo else 0, output)
                for value in values:
                    self.assertNotIn(value, output)
                if fail_echo:
                    self.assertEqual(sent, 0)
                    self.assertEqual(list(self.f.credentials.iterdir()), [])
                else:
                    self.assertEqual(sent, 4)
                    for name in C.NAMES:
                        (self.f.credentials / name).unlink()


class InstallationTests(unittest.TestCase):
    def setUp(self):
        import shutil
        self.f = Fixture(); self.addCleanup(self.f.close)
        shutil.rmtree(self.f.bundle)  # Owned dummy fixture only, never live staging.
        self.f.mkdir('/opt/asep/delivery', 0o755)
        self.installer = module('installer', 'install-review-tools.py')
        self.sources = self.installer.verified_sources(HERE)

    def test_check_then_install_exact_pins_and_refuse_reinstall(self):
        self.assertEqual(self.installer.install(self.sources, self.f.root), 'CHECKED')
        dest = self.f.root / 'opt/asep/delivery/review-tools'
        self.assertFalse(dest.exists())
        self.assertEqual(self.installer.install(self.sources, self.f.root, True), 'INSTALLED')
        for name, h in self.installer.PINS.items():
            self.assertEqual(digest((dest / name).read_bytes()), h)
            self.assertEqual(stat.S_IMODE((dest / name).stat().st_mode), 0o444)
        self.assertEqual(list((self.f.bundle / 'evidence').iterdir()), [])
        with self.assertRaisesRegex(Exception, 'destination already exists'):
            self.installer.install(self.sources, self.f.root, True)

    def test_changed_helper_refused_before_execution_or_writes(self):
        self.sources['protected_local_files.py'] = b'raise AssertionError("unreviewed code executed")'
        with self.assertRaises(ValueError):
            self.installer.install(self.sources, self.f.root, True)
        self.assertFalse((self.f.root / 'opt/asep/delivery/review-tools').exists())

    def test_symlink_destination_refused(self):
        dest = self.f.root / 'opt/asep/delivery/review-tools'
        dest.symlink_to(self.f.review)
        with self.assertRaisesRegex(Exception, 'destination already exists'):
            self.installer.install(self.sources, self.f.root, True)
        self.assertFalse(self.f.bundle.exists())

    def test_isolated_installed_cli_can_run_without_pythonpath(self):
        self.installer.install(self.sources, self.f.root, True)
        dest = self.f.root / 'opt/asep/delivery/review-tools/import-gitlab-credentials.py'
        for name in C.NAMES:
            (self.f.credentials / name).unlink()
        result = subprocess.run(['/usr/bin/python3', '-I', '-B', str(dest), '--fixture-root',
                                 str(self.f.root), '--check-only'], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('both fixed protected destinations absent', result.stdout)


if __name__ == '__main__':
    unittest.main(verbosity=2)
