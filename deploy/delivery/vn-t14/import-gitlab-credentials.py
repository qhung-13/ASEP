#!/usr/bin/python3
"""Private Owner-terminal import of two already authorized GitLab credentials.

No creation/revocation/network, argv/env token, echo fallback, overwrite, database
password access or configuration change. Do not run before Owner authorization.
"""
import argparse
from contextlib import ExitStack
import getpass
import os
import resource
import sys
import warnings
from pathlib import Path
# Installed entry points run with Python -I; only their protected sibling modules are added.
sys.path.insert(0, str(Path(__file__).absolute().parent))
from protected_local_files import Refused, absent, digest, publish, read_at, require, strict_json
import importlib.util

spec = importlib.util.spec_from_file_location('publication', Path(__file__).with_name('publish-delivery-evidence.py'))
publication = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publication)
NAMES = ('gitlab-read.credential', 'gitlab-write.credential')


def token(value):
    require(isinstance(value, str) and 20 <= len(value) <= 4096 and
            all(33 <= ord(c) <= 126 for c in value), 'invalid credential format')
    return value.encode('ascii')  # Deliberately no newline: fingerprint exact file bytes.


def run(context, check_only, prompt):
    with ExitStack() as stack:
        parent = context.open_dir(stack, '/var/lib/asep/executor/private', context.executor, private=True)
        for name in NAMES:
            absent(parent, name)
        if check_only:
            return []
        values = []
        for name in NAMES:
            first = token(prompt(name + ' (hidden): '))
            require(first == token(prompt('Repeat ' + name + ' (hidden): ')), 'credential confirmation differs')
            values.append(first)
        require(values[0] != values[1], 'separate read/write credentials required')
        for name in NAMES:
            absent(parent, name)
        result = []
        for name, value in zip(NAMES, values):
            publish(parent, name, value, context.executor,
                    context.group if context.fixture else 968, 0o600)
            result.append((name, digest(value)))
        return result



def verify_reports(context):
    from datetime import datetime, timedelta, timezone
    now = datetime.now(timezone.utc)
    with ExitStack() as stack:
        parent = context.open_dir(stack, '/var/lib/asep/executor/private', context.executor, private=True)
        for kind in ('read', 'write'):
            report = strict_json(read_at(parent, 'gitlab-' + kind + '-preflight.json', context.executor, 1048576, private=True))
            require(isinstance(report, dict), 'invalid preflight report')
            for label in ('identity', 'credential_metadata', 'project', 'target_branch'):
                result = report.get('requests', {}).get(label, {})
                require(result.get('http_status') == 200 and result.get('complete') is True,
                        'actual authentication/project/branch inspection failed')
            expected_scopes = ['read_api', 'read_repository'] if kind == 'read' else ['api', 'write_repository']
            require(report.get('project_id') == 87786140 and report.get('project_identity_matches_owner') is True and
                    report.get('identity_is_bot') is True and report.get('credential_active') is True and
                    report.get('credential_revoked') is False and report.get('credential_expiration_matches_owner') is True and
                    report.get('credential_scopes') is not None and sorted(report['credential_scopes']) == expected_scopes and
                    report.get('effective_access_level') == 30 and report.get('archived') is False and
                    report.get('target_protected') is True and report.get('credential_can_push_target') is False and
                    report.get('target_head') == publication.BASE and report.get('mutations_performed') == 0,
                    'credential metadata differs from reviewed batch')
            start = publication.instant(report.get('inspection_started_at'))
            end = publication.instant(report.get('inspection_completed_at'))
            require(start <= end <= now and now-start <= timedelta(seconds=300), 'preflight metadata stale/future')
    return True

def main():
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument('--check-only', action='store_true')
    modes.add_argument('--verify-reports', action='store_true', help='only validate sanitized setup preflight reports, no token read')
    parser.add_argument('--fixture-root', help='non-root dummy rehearsal only')
    args = parser.parse_args()
    context = publication.Context(args.fixture_root)
    if args.verify_reports:
        verify_reports(context)
        print('VERIFIED: two fresh credential metadata reports, exact scopes/expiry/Developer/main denial; not Safe-CI or live acceptance')
        return
    if args.check_only:
        run(context, True, None)
        print('PASS: both fixed protected destinations absent; safe parents; no secret read/created')
        return
    require(sys.stdin.isatty() and sys.stdout.isatty(), 'actual Owner terminal required')
    # getpass input is its own /dev/tty fd; output must be write-only on a tty.
    with open('/dev/tty', 'w') as tty, warnings.catch_warnings():
        require(tty.isatty(), 'controlling terminal required')
        warnings.simplefilter('error', getpass.GetPassWarning)
        result = run(context, False, lambda message: getpass.getpass(message, stream=tty))
    for name, fingerprint in result:
        print('IMPORTED: ' + name + '; SHA256=' + fingerprint + '; value not displayed')
    print('DONE: two exclusive0600 files; no credentials created/revoked or settings changed')


if __name__ == '__main__':
    try:
        main()
    except (Refused, OSError, ValueError, TypeError, AttributeError, EOFError, getpass.GetPassWarning):
        print('STOP: credential import refused; inspect any partial files; no overwrite/reset or automatic deletion', file=sys.stderr)
        raise SystemExit(1)
