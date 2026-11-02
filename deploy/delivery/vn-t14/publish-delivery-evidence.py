#!/usr/bin/python3
"""Owner-terminal publication/import only; never an evidence issuer or approval.

Default is check-only. --apply needs an actual terminal and a publication/import
challenge, independent of the Java human grant. Fixture mode cannot target live
paths, run as root, or read live credentials. No remote calls or DB operations.
"""
import argparse
import json
from contextlib import ExitStack
from datetime import datetime, timedelta, timezone
import os
import resource
from pathlib import Path
import re
import struct
import sys
import uuid
# Installed entry points run with Python -I; only their protected sibling modules are added.
sys.path.insert(0, str(Path(__file__).absolute().parent))
from protected_local_files import (Refused, absent, child_directory, digest, directory,
                                   publish, read_at, require, strict_json)

OP = '6706bbed-47be-453d-bb81-171d63dc1139'
PROFILE = '9030ce44fc6c0786a28a5c24c4d5ff08042eb8d87ec93041373e556902beb5c3'
SAFE_CI = '6b85c50ffaa94e7c6091fa5d32df0bcc666ec4e33b77cbe6440c8787a3268d1e'
BASE = 'cd0b93035954142346b7cba18f29657ee877163b'
SUBJECTS = frozenset(('project-namespace-permissions inherited-group-compliance '
    'project-group-instance-runners runner-execution-protection variable-metadata-all-levels '
    'candidate-ci-includes pipeline-security-policies auto-devops protected-branches '
    'environments-deployment-capabilities hooks-integrations-deploy-keys-tokens '
    'job-token-capabilities source-push-ci-behavior draft-mr-ci-behavior '
    'isolated-controller-verifier isolated-operator-executor credential-lifecycle-revocation-plan '
    'controlled-fault-window').split())
COUNTS = frozenset(('protected-branches project-group-instance-runners variable-metadata-all-levels '
                    'environments-deployment-capabilities source-push-ci-behavior').split())
REQUEST_FIELDS = ('operationId runId attemptId runVersion ownerGrantId policyId policyEpoch baseSha '
                  'expectedTargetSha candidateCommitSha candidateTreeSha manifestSha256 sealReceiptId '
                  'verificationReceiptId verifierProfileSha256 safeCiProfileSha256').split()


def sha(value, length=64):
    require(isinstance(value, str) and re.fullmatch('[0-9a-f]{' + str(length) + '}', value), 'invalid digest')
    return value


def instant(value):
    require(isinstance(value, str) and value.endswith('Z'), 'UTC Z timestamp required')
    try:
        result = datetime.fromisoformat(value[:-1] + '+00:00')
    except ValueError as error:
        raise Refused('invalid timestamp') from error
    return result


def request_hash(r):
    require(isinstance(r, dict) and set(r) == set(REQUEST_FIELDS), 'request schema differs')
    for field in ('operationId', 'runId', 'attemptId', 'ownerGrantId', 'sealReceiptId', 'verificationReceiptId'):
        try:
            require(str(uuid.UUID(r[field])) == r[field], 'non-canonical UUID')
        except (ValueError, TypeError, AttributeError) as error:
            raise Refused('invalid UUID') from error
    for field in ('runVersion', 'policyEpoch'):
        require(type(r[field]) is int and 1 <= r[field] <= 2**63 - 1, 'invalid identity version')
    for field in ('baseSha', 'expectedTargetSha', 'candidateCommitSha', 'candidateTreeSha'):
        sha(r[field], 40)
    for field in ('manifestSha256', 'verifierProfileSha256', 'safeCiProfileSha256'):
        sha(r[field])
    require(r['operationId'] == OP and r['ownerGrantId'] == '7834ded2-2251-433d-afe5-2eab964fc19b' and
            r['policyId'] == 'vn-t14-probe-20261102' and r['policyEpoch'] == 1 and
            r['baseSha'] == r['expectedTargetSha'] == BASE and
            r['verifierProfileSha256'] == PROFILE and r['safeCiProfileSha256'] == SAFE_CI and
            r['sealReceiptId'] != r['verificationReceiptId'], 'request differs from reviewed job')
    fields = ['asep-vn-t14-test-project-request-v1', r['operationId'], r['runId'], r['attemptId'],
              str(r['runVersion']), r['ownerGrantId'], r['policyId'], str(r['policyEpoch']), '87786140',
              r['baseSha'], 'test/vn-t14/' + OP, 'main', r['expectedTargetSha'], r['candidateCommitSha'],
              r['candidateTreeSha'], r['manifestSha256'], r['sealReceiptId'], r['verificationReceiptId'],
              r['verifierProfileSha256'], r['safeCiProfileSha256']]
    body = b''.join(struct.pack('>I', len(v.encode())) + v.encode() for v in fields)
    return digest(body)


class Context:
    def __init__(self, fixture=None):
        self.fixture = fixture is not None
        if fixture is not None:
            require(os.geteuid() != 0, 'dummy fixtures must not run as root')
            root = Path(fixture)
            require(root.parent == Path('/tmp') and root.name.startswith('asep-vnt14-publication-'),
                    'fixture must be a dedicated /tmp/asep-vnt14-publication-* directory')
            fd = directory(root, {0, os.geteuid()}, fixture=True, private=True)
            os.close(fd)
            self.root = root
            self.controller = self.operator = self.executor = self.root_owner = os.geteuid()
            self.group = self.operator_group = os.getegid()
        else:
            require(os.geteuid() == 0, 'Owner sudo terminal required')
            self.root = Path('/')
            self.controller, self.operator, self.executor, self.root_owner = 993, 992, 991, 0
            self.group, self.operator_group = 0, 969

    def path(self, path):
        return self.root / path.lstrip('/')

    def open_dir(self, stack, path, owner, private=False):
        fd = directory(self.path(path), {0, owner}, fixture=self.fixture, private=private)
        stack.callback(os.close, fd)
        return fd

    def source(self, stack, expected):
        parent = self.open_dir(stack, '/var/lib/asep/controller/evidence/operations/' + OP, self.controller)
        index = strict_json(read_at(parent, 'index.json', self.controller, 2048))
        require(isinstance(index, dict) and set(index) == {'requestSha256', 'sealSha256', 'verificationSha256'}, 'index schema differs')
        result = None
        for name, key in [('request.json', 'requestSha256'), ('seal.json', 'sealSha256'),
                          ('verification.json', 'verificationSha256')]:
            body = read_at(parent, name, self.controller, 8192)
            require(digest(body) == sha(index[key]), 'controller index mismatch')
            if name == 'request.json':
                result = body
        request = strict_json(result)
        actual = request_hash(request)
        if expected is not None:
            require(actual == sha(expected), 'trusted canonical request hash mismatch')
        return result, request


def validate_bundle(permit, observations, notes, request, expected, now):
    fields = set(('requestHash projectId repositoryPath sourceBranch targetBranch safeCiProfileSha256 '
                  'readCredentialSha256 writeCredentialSha256 writeAccessLevel writeScopes credentialExpiresAt '
                  'inspectionStartedAt inspectionCompletedAt expiresAt facts').split())
    require(isinstance(permit, dict) and set(permit) == fields, 'permit schema differs')
    require(permit['requestHash'] == expected and type(permit['projectId']) is int and permit['projectId'] == 87786140 and
            permit['repositoryPath'] == 'qhung-13/asep-m01-sandbox' and
            permit['sourceBranch'] == 'test/vn-t14/' + OP and permit['targetBranch'] == 'main' and
            permit['safeCiProfileSha256'] == request['safeCiProfileSha256'], 'permit scope mismatch')
    require(sha(permit['readCredentialSha256']) != sha(permit['writeCredentialSha256']) and
            type(permit['writeAccessLevel']) is int and permit['writeAccessLevel'] == 30 and
            sorted(permit['writeScopes']) == ['api', 'write_repository'], 'credential binding mismatch')
    start, end, expiry = map(instant, (permit['inspectionStartedAt'], permit['inspectionCompletedAt'], permit['expiresAt']))
    require(start <= end <= now < expiry <= start + timedelta(seconds=300) and
            instant(permit['credentialExpiresAt']) > now, 'permit stale/future/invalid interval')
    require(isinstance(permit['facts'], dict) and set(permit['facts']) == SUBJECTS and
            set(observations) == SUBJECTS, 'all18 actual subjects required')
    require(isinstance(notes, dict) and set(notes) == {'requestHash', 'subjects'} and
            notes['requestHash'] == expected and isinstance(notes['subjects'], dict) and
            set(notes['subjects']) == SUBJECTS, 'all18 independent review notes required')
    for subject in sorted(SUBJECTS):
        fact, body = permit['facts'][subject], observations[subject]
        require(isinstance(fact, dict) and set(fact) == {'status', 'evidenceSha256', 'provenance'}, 'fact schema differs')
        require(fact['status'] == 'VERIFIED' and sha(fact['evidenceSha256']) == digest(body), 'fact missing or digest mismatch')
        provenance = fact['provenance']
        require(isinstance(provenance, str) and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9 ./_:=-]{0,255}', provenance), 'invalid collector provenance')
        o = strict_json(body)
        require(isinstance(o, dict) and set(o) == set(('subject collector method httpStatus complete startedAt completedAt assessment requestHash itemCount').split()), 'observation schema differs')
        require(o['subject'] == subject and o['collector'] == provenance and o['complete'] is True and
                o['assessment'] == 'SAFE_FOR_EXACT_PROBE' and o['requestHash'] == expected and
                ((o['method'] == 'GET' and type(o['httpStatus']) is int and o['httpStatus'] == 200) or
                 (o['method'] == 'OWNER_UI' and type(o['httpStatus']) is int and o['httpStatus'] == 0)), 'incomplete/unknown/untrusted observation')
        require(start <= instant(o['startedAt']) <= instant(o['completedAt']) <= end, 'observation outside actual collection interval')
        count = o['itemCount']
        require(count is None or type(count) is int and 0 <= count <= 100000, 'invalid inventory count')
        require(subject not in COUNTS or count is not None, 'actual inventory count required')
        note = notes['subjects'][subject]
        require(isinstance(note, dict) and set(note) == {'collector', 'rationale', 'sources'} and
                note['collector'] == provenance and isinstance(note['rationale'], str) and
                20 <= len(note['rationale']) <= 8192 and isinstance(note['sources'], list) and
                1 <= len(note['sources']) <= 100, 'independent rationale/source references required')
        for source in note['sources']:
            require(isinstance(source, str) and re.fullmatch('[0-9a-f]{64}', source), 'raw source digest required')


def run(context, mode, expected, apply=False, confirm=None):
    with ExitStack() as stack:
        request_bytes, request = context.source(stack, expected)
        if mode == 'import-request':
            parent = context.open_dir(stack, '/var/lib/asep/operator/private/review', context.operator, private=True)
            absent(parent, 'request.json')
            if apply:
                require(confirm is not None and confirm('IMPORT ' + expected), 'request import not confirmed')
                # Revalidate the protected source after the review interval.
                require(context.source(stack, expected)[0] == request_bytes, 'controller request changed')
                publish(parent, 'request.json', request_bytes, context.operator, context.operator_group, 0o600)
            return 'IMPORTED' if apply else 'CHECKED'
        bundle = context.open_dir(stack, '/var/lib/asep/operator/private/review/enrollment', context.operator, private=True)
        if mode == 'stage':
            draft = strict_json(read_at(bundle, 'assessment.json', context.operator, 1048576, private=True))
            require(isinstance(draft, dict) and set(draft) == {'permit', 'subjects'} and
                    isinstance(draft['permit'], dict) and 'facts' not in draft['permit'] and
                    isinstance(draft['subjects'], dict) and set(draft['subjects']) == SUBJECTS,
                    'explicit independently reviewed assessment required')
            permit = dict(draft['permit']); permit['facts'] = {}
            observations = {}; notes = {'requestHash': expected, 'subjects': {}}
            for subject, reviewed in draft['subjects'].items():
                require(isinstance(reviewed, dict) and set(reviewed) == {'status', 'observation', 'review'},
                        'explicit status/observation/review required')
                body = (json.dumps(reviewed['observation'], sort_keys=True, separators=(',', ':'), allow_nan=False) + '\n').encode()
                require(len(body) <= 65536, 'observation exceeds reader bound')
                observations[subject] = body
                permit['facts'][subject] = {'status': reviewed['status'], 'evidenceSha256': digest(body),
                                            'provenance': reviewed['observation']['collector']}
                notes['subjects'][subject] = reviewed['review']
            validate_bundle(permit, observations, notes, request, expected, datetime.now(timezone.utc))
            bodies = {'permit.json': (json.dumps(permit, sort_keys=True, separators=(',', ':')) + '\n').encode(),
                      'review-notes.json': (json.dumps(notes, sort_keys=True, separators=(',', ':')) + '\n').encode()}
            require(len(bodies['permit.json']) <= 16384 and len(bodies['review-notes.json']) <= 1048576, 'serialized report exceeds bound')
            evidence_out = context.open_dir(stack, '/var/lib/asep/operator/private/review/enrollment/evidence', context.operator, private=True)
            for name in bodies:
                absent(bundle, name)
            for body in observations.values():
                absent(evidence_out, digest(body) + '.json')
            if apply:
                require(confirm is not None and confirm('STAGE ' + expected), 'assessment staging not confirmed')
                validate_bundle(permit, observations, notes, request, expected, datetime.now(timezone.utc))
                for body in observations.values():
                    publish(evidence_out, digest(body) + '.json', body, context.operator, context.operator_group, 0o600)
                for name, body in bodies.items():
                    publish(bundle, name, body, context.operator, context.operator_group, 0o600)
            return 'STAGED' if apply else 'CHECKED'
        evidence_in = context.open_dir(stack, '/var/lib/asep/operator/private/review/enrollment/evidence', context.operator, private=True)
        raw_in = context.open_dir(stack, '/var/lib/asep/operator/private/review/enrollment/raw', context.operator, private=True)
        permit_body = read_at(bundle, 'permit.json', context.operator, 16384, private=True)
        permit = strict_json(permit_body)
        require(isinstance(permit, dict) and isinstance(permit.get('facts'), dict) and set(permit['facts']) == SUBJECTS, 'all18 facts required')
        observations = {}
        for subject, fact in permit['facts'].items():
            require(isinstance(fact, dict), 'invalid fact')
            observations[subject] = read_at(evidence_in, sha(fact.get('evidenceSha256')) + '.json', context.operator, 65536, private=True)
        notes_body = read_at(bundle, 'review-notes.json', context.operator, 1048576, private=True)
        notes = strict_json(notes_body)
        now = datetime.now(timezone.utc)
        validate_bundle(permit, observations, notes, request, expected, now)
        sources = {}
        source_bytes = 0
        for note in notes['subjects'].values():
            for h in note['sources']:
                if h not in sources:
                    sources[h] = read_at(raw_in, h, context.operator, 1048576, private=True)
                    source_bytes += len(sources[h])
                    require(len(sources) <= 128 and source_bytes <= 8 * 1048576, 'supporting capture batch exceeds bound')
                    require(digest(sources[h]) == h, 'raw source digest mismatch')
        credentials = context.open_dir(stack, '/var/lib/asep/executor/private', context.executor, private=True)
        for kind in ('read', 'write'):
            token = read_at(credentials, 'gitlab-' + kind + '.credential', context.executor, 4096, private=True)
            require(digest(token) == permit[kind + 'CredentialSha256'], 'actual credential file fingerprint mismatch')
        target = context.open_dir(stack, '/etc/asep/delivery-enrollment', context.root_owner)
        absent(target, OP + '.json')
        # Check every final path before creating anything; permit is always last.
        for dirname in ('evidence', 'audit', 'sources'):
            try:
                child = context.open_dir(stack, '/etc/asep/delivery-enrollment/' + dirname, context.root_owner)
            except FileNotFoundError:
                continue
            names = ([digest(b) + '.json' for b in observations.values()] if dirname == 'evidence'
                     else [OP + '.json'] if dirname == 'audit' else list(sources))
            for name in names:
                absent(child, name)
        def revalidate():
            validate_bundle(permit, observations, notes, request, expected, datetime.now(timezone.utc))
            require(context.source(stack, expected)[0] == request_bytes, 'controller request changed')
            for kind in ('read', 'write'):
                require(digest(read_at(credentials, 'gitlab-' + kind + '.credential', context.executor, 4096, private=True)) ==
                        permit[kind + 'CredentialSha256'], 'credential changed during review')
        if apply:
            require(confirm is not None and confirm('PUBLISH ' + expected), 'independent publication not confirmed')
            revalidate()
            children = {}
            for dirname in ('evidence', 'audit', 'sources'):
                child = child_directory(target, dirname, context.root_owner, context.group, 0o755)
                stack.callback(os.close, child)
                children[dirname] = child
            # Raw supporting captures/notes can contain private metadata. Keep
            # them root-only; runtime needs only sanitized observations + permit.
            for h, body in sources.items():
                publish(children['sources'], h, body, context.root_owner, context.group, 0o600)
            publish(children['audit'], OP + '.json', notes_body, context.root_owner, context.group, 0o600)
            for body in observations.values():
                publish(children['evidence'], digest(body) + '.json', body, context.root_owner, context.group, 0o644)
            revalidate()
            publish(target, OP + '.json', permit_body, context.root_owner, context.group, 0o644)
        return 'PUBLISHED' if apply else 'CHECKED'


def main():
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('inspect-request', 'import-request', 'stage', 'publish'))
    parser.add_argument('--request-hash', help='actual trusted Go canonical hash; not JSON-file SHA')
    parser.add_argument('--apply', action='store_true', help='requires Owner terminal confirmation; never creates a human grant')
    parser.add_argument('--fixture-root', help='non-root dummy rehearsal only')
    args = parser.parse_args()
    context = Context(args.fixture_root)
    if args.mode == 'inspect-request':
        require(not args.apply, 'inspection cannot apply')
        with ExitStack() as stack:
            body, request = context.source(stack, args.request_hash)
        print(json.dumps(dict(requestHash=request_hash(request), requestFileSha256=digest(body),
                              **{k: request[k] for k in ('operationId','runId','attemptId','runVersion','sealReceiptId','verificationReceiptId')}), sort_keys=True))
        return
    sha(args.request_hash)
    require(not args.apply or sys.stdin.isatty() and sys.stdout.isatty(), 'actual terminal required for apply')
    def confirm(challenge):
        print('This imports/publishes independently reviewed inputs, NOT an Owner delivery approval.')
        return input('Type ' + challenge + ': ') == challenge
    result = run(context, args.mode, args.request_hash, args.apply, confirm)
    print(result + ': ' + args.mode + '; no approval, DB, service or GitLab operation')


if __name__ == '__main__':
    try:
        main()
    except (Refused, OSError, ValueError, TypeError, KeyError, EOFError) as error:
        # No exception values/JSON/request/credential bytes in error output.
        print('STOP: protected publication/import refused (' + type(error).__name__ +
              '); inspect state before retry; published files were not rolled back', file=sys.stderr)
        raise SystemExit(1)
