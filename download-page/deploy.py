"""Run on the authorized VPS after uploading index.html and release files to /tmp/salom-deploy.
Preserves existing proxy routes and backs up the current site/config before publishing.
"""
from pathlib import Path
import datetime
import hashlib
import json
import re
import shutil
import subprocess
import sys

incoming = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('/tmp/salom-deploy')
release_input = json.loads((incoming / 'release.json').read_text(encoding='utf-8'))
version = release_input.get('version', '')
display_name = release_input.get('displayName', '')
version_code = release_input.get('versionCode')
min_sdk = release_input.get('minSdk', 24)
release_notes = release_input.get('releaseNotes', [])
assert isinstance(version, str) and re.fullmatch(r'\d+\.\d+\.\d+', version)
assert isinstance(display_name, str) and len(display_name) < 24 and re.fullmatch(r'[A-Za-z0-9 -]*', display_name)
assert isinstance(version_code, int) and version_code > 0
assert isinstance(min_sdk, int) and 24 <= min_sdk <= 100
assert isinstance(release_notes, list) and len(release_notes) <= 5 and all(isinstance(note, str) and len(note) <= 240 for note in release_notes)
apk = incoming / f'salom-{version}.apk'
assert apk.is_file() and apk.stat().st_size > 100_000
windows_file = release_input.get('windowsFile')
windows_size = None
windows_sha256 = None
if windows_file:
    assert isinstance(windows_file, str) and re.fullmatch(r'SALOM-\d+\.\d+\.\d+-(?:AllowCookie|CallFix|Release)-Setup\.exe', windows_file)
    windows_installer = incoming / windows_file
    assert windows_installer.is_file() and windows_installer.stat().st_size > 1_000_000
    windows_size = windows_installer.stat().st_size
    windows_sha256 = hashlib.sha256(windows_installer.read_bytes()).hexdigest()
    assert release_input.get('windowsSha256', windows_sha256) == windows_sha256, 'Uploaded installer checksum differs'
android_variants = []
for abi, expected in release_input.get('androidVariants', {}).items():
    assert abi in ('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')
    variant = incoming / f'salom-{version}-{abi}.apk'
    actual = hashlib.sha256(variant.read_bytes()).hexdigest()
    assert actual == expected, f'Uploaded APK checksum differs for {abi}'
    android_variants.append((abi, variant, actual))
assert (incoming / 'index.html').is_file()
page_contents = (incoming / 'index.html').read_text(encoding='utf-8')
page_script = re.search(r'\bsrc="/(site-\d+\.js)"', page_contents)
release_date = release_input.get('releaseDate')
if release_date is not None:
    assert datetime.date.fromisoformat(release_date).isoformat() == release_date
config = Path('/etc/nginx/sites-available/salom-site.conf')
page = Path('/var/www/salom-site/index.html')
assert 'server_name mukimov.site;' in config.read_text()
assert 'alias /var/www/salom/;' in config.read_text(), 'Nginx must already serve /salom/ from /var/www/salom/'
assert 'alias /var/www/salom/version.json;' in config.read_text(), 'Nginx must already serve /salom/version.json'
backup = Path('/var/backups') / ('salom-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
backup.mkdir(mode=0o700, parents=True, exist_ok=False)
shutil.copy2(config, backup / 'nginx.conf')
shutil.copy2(page, backup / 'index.html')
target = Path('/var/www/salom')
target.mkdir(parents=True, exist_ok=True)
for filename in ('salom-latest.apk', 'version.json'):
    if (target / filename).exists(): shutil.copy2(target / filename, backup / filename)
if windows_file and (target / windows_file).exists():
    shutil.copy2(target / windows_file, backup / windows_file)
sha256 = hashlib.sha256(apk.read_bytes()).hexdigest()
if 'sha256' in release_input:
    assert release_input['sha256'] == sha256, 'Uploaded APK does not match the verified APK'
old_metadata_path = target / 'version.json'
old_metadata = {}
if old_metadata_path.is_file():
    old_metadata = json.loads(old_metadata_path.read_text(encoding='utf-8'))
    old_code = old_metadata.get('versionCode', 0)
    assert version_code >= old_code, 'Published versionCode must never go backwards'
    if version_code == old_code:
        assert version == old_metadata.get('version') and sha256 == old_metadata.get('sha256'), 'A published build is immutable; use a higher versionCode for any new APK'
    old_artifacts = [
        old_metadata.get('downloadUrl', '').rsplit('/', 1)[-1],
        old_metadata.get('windowsFile', ''),
        *(variant.get('downloadUrl', '').rsplit('/', 1)[-1] for variant in old_metadata.get('androidVariants', [])),
    ]
    for filename in set(old_artifacts):
        if re.fullmatch(r'[A-Za-z0-9._-]+\.(?:apk|exe)', filename) and (target / filename).is_file():
            shutil.copy2(target / filename, backup / filename)
if (target / apk.name).exists():
    assert hashlib.sha256((target / apk.name).read_bytes()).hexdigest() == sha256, 'A published version must never change bytes'
for abi, variant, digest in android_variants:
    if (target / variant.name).exists():
        assert hashlib.sha256((target / variant.name).read_bytes()).hexdigest() == digest, f'A published {abi} APK must never change bytes'
if windows_file and (target / windows_file).exists():
    assert hashlib.sha256((target / windows_file).read_bytes()).hexdigest() == windows_sha256, 'A published Windows installer must never change bytes'
for filename in (apk.name, 'salom-latest.apk'):
    temporary = target / (filename + '.tmp')
    shutil.copy2(apk, temporary)
    temporary.chmod(0o644)
    temporary.replace(target / filename)
if windows_file:
    temporary = target / (windows_file + '.tmp')
    shutil.copy2(windows_installer, temporary)
    temporary.chmod(0o644)
    temporary.replace(target / windows_file)
for _, variant, _ in android_variants:
    temporary = target / (variant.name + '.tmp')
    shutil.copy2(variant, temporary)
    temporary.chmod(0o644)
    temporary.replace(target / variant.name)
metadata = {'version': version, 'displayName': display_name, 'versionCode': version_code, 'minSdk': min_sdk, 'downloadUrl': 'https://mukimov.site/salom/' + apk.name, 'size': apk.stat().st_size, 'sha256': sha256,
            'releaseNotes': release_notes}
if release_date:
    metadata['releaseDate'] = release_date
if windows_file:
    metadata.update({'windowsFile': windows_file, 'windowsSize': windows_size, 'windowsSha256': windows_sha256})
else:
    for key in ('windowsFile', 'windowsSize', 'windowsSha256'):
        if key in old_metadata:
            metadata[key] = old_metadata[key]
if android_variants:
    metadata['androidVariants'] = [
        {'abi': abi, 'downloadUrl': 'https://mukimov.site/salom/' + variant.name,
         'size': variant.stat().st_size, 'sha256': digest}
        for abi, variant, digest in android_variants
    ]
for file in target.iterdir():
    if file.is_file(): file.chmod(0o644)
try:
    subprocess.run(['nginx', '-t'], check=True)
    if page_script:
        asset = page.with_name(page_script[1])
        source = incoming / page_script[1]
        if source.is_file():
            if asset.exists():
                assert asset.read_bytes() == source.read_bytes(), 'Published versioned website asset is immutable'
            else:
                pending_asset = asset.with_suffix('.js.pending')
                shutil.copy2(source, pending_asset)
                pending_asset.chmod(0o644)
                pending_asset.replace(asset)
        assert asset.is_file(), 'Landing page JavaScript is missing'
    pending_page = page.with_suffix('.html.pending')
    shutil.copy2(incoming / 'index.html', pending_page)
    pending_page.chmod(0o644)
    pending_page.replace(page)
    # Advertise the release only after its immutable APK URL is live.
    temporary = target / 'version.json.tmp'
    temporary.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n')
    temporary.chmod(0o644)
    temporary.replace(target / 'version.json')
except Exception:
    shutil.copy2(backup / 'index.html', page)
    for filename in ('salom-latest.apk', 'version.json'):
        if (backup / filename).exists(): shutil.copy2(backup / filename, target / filename)
    if windows_file and (backup / windows_file).exists(): shutil.copy2(backup / windows_file, target / windows_file)
    raise
print(json.dumps({'backup': str(backup), 'sha256': metadata['sha256'], 'size': metadata['size']}))
