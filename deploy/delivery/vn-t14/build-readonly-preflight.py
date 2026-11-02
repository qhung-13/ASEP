#!/usr/bin/python3
"""Offline packaging of the existing reviewed Java GET-only harness.
Reads the pinned installed JAR; compiles unchanged test-source main. No network,
credential, DB, service, policy, evidence or candidate operation.
"""
import hashlib
from pathlib import Path
import subprocess
import tempfile
import zipfile

BASE = Path(__file__).absolute().parent
REPO = BASE.parents[2]
JAR = Path('/opt/asep/delivery/asep-backend.jar')
SOURCE = REPO / 'backend/src/test/java/io/github/qhung13/asep/integration/internal/gitlab/VnT14ReadOnlyPreflight.java'
PIN = 'a4fb0f1a787a10a8a86ba522c2ce1e02421e24fb4f39df138603201ed2d145b1'
assert hashlib.sha256(JAR.read_bytes()).hexdigest() == PIN, 'installed artifact changed'
assert hashlib.sha256(SOURCE.read_bytes()).hexdigest() == '541820471a8901e458c4d6115050bb1d3f4374759f64b585de2cf465c80a8e6e', 'reviewed preflight source changed'
output = BASE / 'readonly-preflight.jar'
with tempfile.TemporaryDirectory(prefix='asep-vnt14-preflight-build-') as tmp:
    folder = Path(tmp)
    with zipfile.ZipFile(JAR) as jar:
        for entry in jar.infolist():
            if entry.filename.startswith(('BOOT-INF/classes/', 'BOOT-INF/lib/')) and not entry.is_dir():
                target = folder / entry.filename
                assert '..' not in Path(entry.filename).parts
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(jar.read(entry))
    classes = folder / 'compiled'; classes.mkdir()
    subprocess.run(['/usr/lib/jvm/java-21-openjdk-amd64/bin/javac', '--release', '21',
                    '-cp', str(folder / 'BOOT-INF/classes') + ':' + str(folder / 'BOOT-INF/lib/*'),
                    '-d', str(classes), str(SOURCE)], check=True)
    # Reproducible archive metadata. No source edits or app JAR rebuild/pin changes.
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_STORED) as jar:
        for path in sorted(classes.rglob('*.class')):
            entry = zipfile.ZipInfo(path.relative_to(classes).as_posix(), (1980, 1, 1, 0, 0, 0))
            entry.external_attr = 0o100444 << 16
            jar.writestr(entry, path.read_bytes())
print('PACKAGED:', output.name, 'SHA256=' + hashlib.sha256(output.read_bytes()).hexdigest())
print('SOURCE SHA256=' + hashlib.sha256(SOURCE.read_bytes()).hexdigest())
