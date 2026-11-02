#!/usr/bin/python3
"""Install only reviewed Owner review helpers/staging directories; never a secret/gate.
Default check-only. Run --apply only after separate Owner authorization.
"""
import argparse
import hashlib
import os
from pathlib import Path
import stat
import sys
import types

PINS = {'protected_local_files.py': '39d398c5186327b76897cfe27a0ce00ed34f57d140db9870b8d917d4ea476ca0', 'publish-delivery-evidence.py': '9e924e552d859e97452c4da6b66335e47ca792869acef667865baa1564de91ac', 'import-gitlab-credentials.py': 'dc79a005267f25c4081b8572e5e3429eb6b05ffb641d9e4f8ea5453ea6c6c86a', 'readonly-preflight.jar': 'bf07cacfdbf181bafb0d1f5a95bf9df6ad5ff93e8dba64961f2f84dc09f5a2bf'}


def verified_sources(folder):
    result = {}
    for name, expected in PINS.items():
        fd = os.open(folder / name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        try:
            meta = os.fstat(fd)
            if not stat.S_ISREG(meta.st_mode) or meta.st_nlink != 1 or meta.st_size > 65536:
                raise ValueError('unsafe helper source')
            body = os.read(fd, 65537)
            if hashlib.sha256(body).hexdigest() != expected:
                raise ValueError('reviewed helper pin mismatch')
            result[name] = body
        finally:
            os.close(fd)
    return result


def install(sources, fixture=None, apply=False):
    # Execute only already hash-verified library bytes, never re-import a mutable
    # checkout copy after validation. No third-party modules are used.
    if set(sources) != set(PINS) or any(hashlib.sha256(sources[n]).hexdigest() != h for n,h in PINS.items()):
        raise ValueError('reviewed helper pin mismatch')
    namespace = {'__name__': 'reviewed_primitives'}
    exec(compile(sources['protected_local_files.py'], '<pinned protected file primitives>', 'exec'), namespace)
    fs = types.SimpleNamespace(**namespace)
    fs.require(set(sources) == set(PINS) and all(hashlib.sha256(sources[n]).hexdigest() == h for n,h in PINS.items()),
               'reviewed helper pin mismatch')
    if fixture:
        root = Path(fixture)
        fs.require(os.geteuid() != 0 and root.parent == Path('/tmp') and root.name.startswith('asep-vnt14-publication-'),
                   'dummy installer requires dedicated non-root fixture')
        owner = os.geteuid(); group = os.getegid(); operator = owner; operator_group = group
    else:
        fs.require(os.geteuid() == 0, 'Owner sudo terminal required')
        root = Path('/'); owner = group = 0; operator = 992; operator_group = 969
        import pwd, grp
        fs.require(pwd.getpwnam('asep-operator').pw_uid == 992 and grp.getgrnam('asep-operator').gr_gid == 969 and
                   pwd.getpwnam('asep-executor').pw_uid == 991 and grp.getgrnam('asep-executor').gr_gid == 968,
                   'reviewed service identities differ')
    base = fs.directory(root / 'opt/asep/delivery', {0, owner}, fixture=bool(fixture))
    review = fs.directory(root / 'var/lib/asep/operator/private/review', {0, operator}, fixture=bool(fixture), private=True)
    try:
        fs.absent(base, 'review-tools')
        # Existing staging must be inspected, not re-owned/cleared by installation.
        fs.absent(review, 'enrollment')
        if not apply:
            return 'CHECKED'
        target = fs.child_directory(base, 'review-tools', owner, group, 0o755)
        try:
            for name, body in sources.items():
                fs.publish(target, name, body, owner, group, 0o444)
        finally:
            os.close(target)
        staging = fs.child_directory(review, 'enrollment', operator, operator_group, 0o700)
        try:
            for name in ('evidence', 'raw'):
                child = fs.child_directory(staging, name, operator, operator_group, 0o700)
                os.close(child)
        finally:
            os.close(staging)
        return 'INSTALLED'
    finally:
        os.close(base); os.close(review)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    sources = verified_sources(Path(__file__).absolute().parent)
    print(install(sources, apply=args.apply) + ': four pinned0444 tooling files and empty0700 staging; no credentials/evidence/grant/config/service changed')


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('STOP: review-tool installation refused; inspect any partial installation; no overwrite or automatic cleanup', file=sys.stderr)
        raise SystemExit(1)
