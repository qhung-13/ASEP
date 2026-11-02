"""Small POSIX fd-relative primitives for reviewed Owner-terminal helpers.

No network, subprocess, credentials, shell evaluation, chmod of existing files,
or removal of a published file. Callers choose fixed trusted identities/paths.
"""
import hashlib
import json
import os
from pathlib import Path
import stat
import uuid


class Refused(Exception):
    pass


def require(condition, message):
    if not condition:
        raise Refused(message)


def digest(body):
    return hashlib.sha256(body).hexdigest()


def strict_json(body):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, 'duplicate JSON key')
            result[key] = value
        return result
    try:
        return json.loads(body, object_pairs_hook=pairs,
                          parse_constant=lambda _: (_ for _ in ()).throw(Refused('non-finite JSON')))
    except (ValueError, UnicodeError, RecursionError) as error:
        raise Refused('invalid JSON') from error


def directory(path, owners, *, fixture=False, private=False):
    """Walk from / with held directory fds; never traverse a symlink.

    Only a dummy fixture may traverse root-owned sticky /tmp. Live paths cannot.
    No ACL grants may be present (the mode alone need not describe effective ACLs).
    """
    path = Path(path)
    require(path.is_absolute() and '..' not in path.parts, 'absolute safe path required')
    fd = os.open('/', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        for part in path.parts[1:]:
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
            os.close(fd)
            fd = child
            meta = os.fstat(fd)
            is_tmp = fixture and part == 'tmp' and Path(os.readlink('/proc/self/fd/' + str(fd))) == Path('/tmp')
            require(meta.st_uid in owners, 'unexpected directory owner')
            require(not (meta.st_mode & 0o022) or
                    (is_tmp and meta.st_uid == 0 and stat.S_IMODE(meta.st_mode) == 0o1777),
                    'writable directory ancestor')
            require(not {'system.posix_acl_access', 'system.posix_acl_default'}.intersection(os.listxattr(fd)), 'directory ACL requires review')
        if private:
            require(stat.S_IMODE(os.fstat(fd).st_mode) == 0o700, 'private directory must be0700')
        return fd
    except BaseException:
        os.close(fd)
        raise


def read_at(parent, name, owner, bound, *, private=False):
    require('/' not in name and name not in ('', '.', '..'), 'invalid leaf name')
    fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent)
    try:
        before = os.fstat(fd)
        require(stat.S_ISREG(before.st_mode) and before.st_nlink == 1 and
                before.st_uid in (0, owner), 'unsafe input file identity/type/links')
        require(not (before.st_mode & (0o077 if private else 0o022)), 'unsafe input file permissions')
        require(not {'system.posix_acl_access', 'system.posix_acl_default'}.intersection(os.listxattr(fd)), 'file ACL requires review')
        require(before.st_size <= bound, 'input exceeds bound')
        body = bytearray()
        while len(body) <= bound:
            chunk = os.read(fd, min(8192, bound + 1 - len(body)))
            if not chunk:
                break
            body.extend(chunk)
        after = os.fstat(fd)
        require(len(body) <= bound and (before.st_size, before.st_mtime_ns, before.st_ctime_ns) ==
                (after.st_size, after.st_mtime_ns, after.st_ctime_ns), 'input changed or exceeds bound')
        return bytes(body)
    finally:
        os.close(fd)


def absent(parent, name):
    try:
        os.stat(name, dir_fd=parent, follow_symlinks=False)
    except FileNotFoundError:
        return
    raise Refused('destination already exists; inspect, never overwrite: ' + name)


def publish(parent, name, body, uid, gid, mode):
    """Fully write/chown/fsync a private temporary inode, then link exclusively.

    linkat is atomic and cannot replace an existing final name. A failure can
    leave published artifacts; retain/inspect them, never infer batch rollback.
    """
    absent(parent, name)
    temporary = '.pending-' + str(uuid.uuid4())
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                 0o600, dir_fd=parent)
    try:
        if os.geteuid() == 0:
            os.fchown(fd, uid, gid)
        else:
            require(uid == os.geteuid() and gid == os.getegid(), 'fixture identity mismatch')
        os.fchmod(fd, mode)
        view = memoryview(body)
        while view:
            written = os.write(fd, view)
            require(written > 0, 'short write')
            view = view[written:]
        os.fsync(fd)
        os.link(temporary, name, src_dir_fd=parent, dst_dir_fd=parent, follow_symlinks=False)
        os.fsync(parent)
    finally:
        os.close(fd)
        os.unlink(temporary, dir_fd=parent)
        os.fsync(parent)


def child_directory(parent, name, uid, gid, mode):
    created = False
    try:
        os.mkdir(name, mode, dir_fd=parent)
        created = True
    except FileExistsError:
        pass
    fd = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent)
    if created:
        if os.geteuid() == 0:
            os.fchown(fd, uid, gid)
        else:
            require((uid, gid) == (os.geteuid(), os.getegid()), 'fixture directory identity mismatch')
        os.fchmod(fd, mode)
        os.fsync(parent)
    meta = os.fstat(fd)
    if (meta.st_uid, meta.st_gid, stat.S_IMODE(meta.st_mode)) != (uid, gid, mode):
        os.close(fd)
        raise Refused('existing directory metadata differs; no automatic repair')
    require(not {'system.posix_acl_access', 'system.posix_acl_default'}.intersection(os.listxattr(fd)), 'directory ACL requires review')
    return fd
