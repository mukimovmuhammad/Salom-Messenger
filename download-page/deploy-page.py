"""Publish the static SALOM site from a verified staging directory."""
from datetime import datetime, timezone
from hashlib import sha256
import json
import os
from pathlib import Path
import shutil
import sys
from tempfile import NamedTemporaryFile

root = Path('/var/www/salom-site')
paths = (
    'site-161.css',
    'site-161.js',
    'privacy/index.html',
    'terms/index.html',
    'index.html',
)


def install(source, target):
    with NamedTemporaryFile(dir=target.parent, prefix='.salom-site-', delete=False) as staged:
        temp = Path(staged.name)
    try:
        shutil.copyfile(source, temp)
        temp.chmod(0o644)
        os.replace(temp, target)
    finally:
        temp.unlink(missing_ok=True)


def deploy(source):
    for name in paths:
        path = source / name
        if not path.is_file() or not path.stat().st_size:
            raise ValueError(f'Missing site file: {name}')
    page = (source / 'index.html').read_text(encoding='utf-8')
    for marker in ('href="/site-161.css"', 'src="/site-161.js"', 'data-abi="arm64-v8a"', 'data-abi="armeabi-v7a"'):
        if marker not in page:
            raise ValueError(f'Missing landing page marker: {marker}')

    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    backup = Path('/var/backups') / ('salom-site-' + stamp)
    backup.mkdir(mode=0o700)
    existing = []
    for name in paths:
        current = root / name
        if current.is_file():
            saved = backup / name
            saved.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(current, saved)
            existing.append(name)
    applied = []
    try:
        for name in paths:
            install(source / name, root / name)
            applied.append(name)
    except Exception:
        for name in reversed(applied):
            if name in existing:
                install(backup / name, root / name)
            else:
                (root / name).unlink()
        raise
    return {
        'backup': str(backup),
        'sha256': {name: sha256((root / name).read_bytes()).hexdigest() for name in paths},
    }


if __name__ == '__main__':
    print(json.dumps(deploy(Path(sys.argv[1]))))
