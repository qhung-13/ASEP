#!/usr/bin/env python3
"""Unprivileged OFFLINE review aid. Its output is NOT Owner approval or a root trust anchor."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent
MANIFEST = 'EXAMPLE-REVIEW.SHA256'
# Flat, explicit inventory used by the root staging procedure. No globs/paths.
FILES = ('README.md', 'NETWORK-REVIEW.md', 'versions.lock.json', 'config.yaml.example',
         'kubelet.conf', 'audit-policy.yaml', 'namespaces.yaml', 'baseline.yaml',
         'pilot.yaml', 'check.py', 'preflight', 'fetch', 'verify-pilot', 'integrity.py',
         'FIREWALL.md', 'firewall.nft.example', 'firewall', 'asep-k3s-firewall.service', 'k3s-firewall.conf')


def digest(path):
    if path.is_symlink() or not path.is_file():
        raise ValueError('missing/non-regular review input: ' + path.name)
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def candidate():
    lock = json.loads((ROOT / 'versions.lock.json').read_text())
    entries = {name: digest(ROOT / name) for name in FILES}
    for name, entry in lock['artifacts'].items():
        if name in entries or not re.fullmatch(r'[A-Za-z0-9_.-]+', name):
            raise ValueError('unsafe/duplicate artifact name')
        entries[name] = entry['sha256']
    return entries


def parse(text):
    entries = {}
    for line in text.splitlines():
        match = re.fullmatch(r'([0-9a-f]{64})  ([A-Za-z0-9_.-]+)', line)
        if not match or match[2] in entries or match[2] in {'.', '..'}:
            raise ValueError('invalid/duplicate/path-bearing review baseline entry')
        entries[match[2]] = match[1]
    return entries


def verify(directory, entries):
    for name, expected in entries.items():
        if digest(directory / name) != expected:
            raise ValueError('integrity mismatch: ' + name)


def self_test():
    rejected = 0
    for invalid in ['0' * 64 + '  ../install.sh\n',
                    '0' * 64 + '  install.sh\n' + '1' * 64 + '  install.sh\n',
                    '0' * 64 + '  /install.sh\n', 'invalid\n']:
        try:
            parse(invalid)
        except ValueError:
            rejected += 1
        else:
            raise ValueError('unsafe baseline accepted')
    with tempfile.TemporaryDirectory(prefix='asep-integrity-') as temporary:
        directory = Path(temporary)
        # Verify anchored entries, never an attacker-supplied colocated checksum file.
        entries = {}
        for name in ['install.sh', 'verify-pilot', 'config.yaml']:
            (directory / name).write_bytes(b'reviewed public fixture')
            entries[name] = digest(directory / name)
        verify(directory, entries)
        anchor = directory / 'independently-approved-fixture'
        anchor.write_text(''.join(f'{sha}  {name}\n' for name, sha in entries.items()))
        def coreutils_check(manifest):
            return subprocess.run(['/usr/bin/sha256sum', '--check', '--strict', str(manifest)],
                                  cwd=directory, capture_output=True, check=False).returncode
        if coreutils_check(anchor) != 0:
            raise ValueError('trusted coreutils fixture baseline failed')
        for name in entries:
            (directory / name).write_bytes(b'tampered public fixture')
            (directory / 'SHA256SUMS').write_text(
                f'{digest(directory / name)}  {name}\n')
            if coreutils_check(directory / 'SHA256SUMS') != 0 or coreutils_check(anchor) == 0:
                raise ValueError('coreutils did not distinguish replaced checksum from fixed anchor')
            try:
                verify(directory, entries)
            except ValueError:
                rejected += 1
            else:
                raise ValueError('tampered script/config accepted')
            (directory / name).write_bytes(b'reviewed public fixture')
        (directory / 'config.yaml').unlink()
        try:
            verify(directory, entries)
        except ValueError:
            rejected += 1
        else:
            raise ValueError('missing config accepted')
        (directory / 'config.yaml').symlink_to(directory / 'install.sh')
        try:
            verify(directory, entries)
        except ValueError:
            rejected += 1
        else:
            raise ValueError('symlink input accepted')
    print(f'{rejected} INTEGRITY NEGATIVE CASES PASS; no real artifacts/approval used')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--emit-candidate', action='store_true')
    mode.add_argument('--check', action='store_true')
    mode.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        self_test()
    elif args.emit_candidate:
        print(''.join(f'{sha}  {name}\n' for name, sha in sorted(candidate().items())), end='')
    elif parse((ROOT / MANIFEST).read_text()) != candidate():
        raise ValueError('candidate baseline stale/incomplete; re-review before regeneration')
    else:
        print('OFFLINE CANDIDATE CONSISTENT; NOT Owner-approved/root-protected integrity evidence')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError) as error:
        raise SystemExit('FAIL: ' + str(error))
