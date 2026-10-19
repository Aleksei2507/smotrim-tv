#!/usr/bin/env python3
"""Package an already built, locally signed APK and publish an atomic GitHub release.

Never reads the signing key or sends credentials to the TV. Use --dry-run first.
"""
import argparse
import hashlib
import re
import shutil
import subprocess
import zipfile
from pathlib import Path

REPOSITORY = 'Aleksei2507/smotrim-tv'
CERTIFICATE = '5d98d8ec77e3de61c241d19dbf0c3ef9a5dbb6cc268acba25dbf07b537e309d2'
PROJECT = Path(__file__).resolve().parents[1]
ROOT_FILES = ['README.md', 'NOTICE.md', 'TESTING.md', '.gitignore', 'build.gradle',
              'settings.gradle', 'gradle.properties', 'gradlew', 'gradlew.bat']
SOURCE_DIRS = ['app', 'gradle', 'licenses', 'third-party', 'ui-tests', 'tools', 'catalog', '.github']
EXCLUDED = {'build', '.gradle', '.signing', '.git', '.DS_Store', '__pycache__'}

def sources():
    paths = [PROJECT / name for name in ROOT_FILES if (PROJECT / name).is_file()]
    for dirname in SOURCE_DIRS:
        paths.extend(p for p in (PROJECT / dirname).rglob('*')
                     if p.is_file() and not EXCLUDED.intersection(p.relative_to(PROJECT).parts))
    for path in sorted(paths):
        if path.is_symlink() or path.suffix in {'.jks', '.keystore', '.pem', '.key'} or path.name in {'local.properties', 'release.properties'}:
            raise SystemExit('Unexpected private file in source archive: ' + str(path.relative_to(PROJECT)))
        yield path

def run(*args):
    return subprocess.check_output(args, text=True).strip()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build-tools', type=Path, required=True, help='Android SDK build-tools/35.0.0')
    parser.add_argument('--notes', type=Path, required=True, help='Release notes Markdown file')
    parser.add_argument('--torrserver-source', type=Path, required=True, help='Corresponding TorrServer MatriX.147 sources')
    parser.add_argument('--output', type=Path, default=PROJECT.parent / 'deliverables')
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()
    apk = PROJECT / 'app/build/outputs/apk/release/app-release.apk'
    cert = run(str(args.build_tools / 'apksigner'), 'verify', '--print-certs', str(apk))
    if 'certificate SHA-256 digest: ' + CERTIFICATE not in cert:
        raise SystemExit('APK signing certificate differs from the installed release')
    badging = run(str(args.build_tools / 'aapt2'), 'dump', 'badging', str(apk))
    match = re.search(r"package: name='ru.smotrim.tv' versionCode='([0-9]+)' versionName='([0-9]+(?:\.[0-9]+){1,3})'", badging)
    if not match or 'application-debuggable' in badging:
        raise SystemExit('Expected a non-debuggable Smotrim release APK')
    version_code, version = match.groups()
    if not args.notes.is_file() or not args.torrserver_source.is_file():
        raise SystemExit('Release notes and corresponding TorrServer sources are required')
    with zipfile.ZipFile(apk) as z:
        assets = PROJECT / 'app/src/main/assets'
        for path in assets.rglob('*'):
            if path.is_file() and z.read('assets/' + path.relative_to(assets).as_posix()) != path.read_bytes():
                raise SystemExit('APK assets differ from source: ' + str(path))
        if any(name.endswith('fixture.js') or ('libtorrent4j' in name and name.endswith('.so')) for name in z.namelist()):
            raise SystemExit('Test fixture found inside release APK')
    args.output.mkdir(parents=True, exist_ok=True)
    target = args.output / ('Smotrim-TV-' + version + '.apk')
    shutil.copyfile(apk, target)
    archive = args.output / ('Smotrim-TV-' + version + '-source.zip')
    with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for path in sources():
            z.write(path, 'smotrim-android/' + path.relative_to(PROJECT).as_posix())
    with zipfile.ZipFile(archive) as z:
        if z.testzip() is not None:
            raise SystemExit('Source archive is damaged')
    checks = args.output / ('SHA256SUMS-' + version + '.txt')
    payloads = [target, archive, args.torrserver_source, args.notes, PROJECT / 'TESTING.md']
    checks.write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest() + '  ' + p.name + '\n' for p in payloads))
    print('Verified version ' + version + ', code ' + version_code + ', release certificate and source assets')
    print('APK: ' + str(target))
    if args.dry_run:
        print('Dry run: no GitHub mutations')
        return
    tag = 'v' + version
    existing = subprocess.run(['gh', 'api', 'repos/' + REPOSITORY + '/releases/tags/' + tag], capture_output=True, text=True)
    if existing.returncode == 0:
        raise SystemExit('Release already exists. Refusing to overwrite: ' + tag)
    if '404' not in existing.stderr:
        raise SystemExit('Cannot safely check whether release exists: ' + existing.stderr)
    # A draft is invisible to the TV until every artifact has been uploaded.
    subprocess.run(['gh', 'release', 'create', tag, '--repo', REPOSITORY, '--draft', '--title', 'Смотрим TV ' + version, '--notes-file', str(args.notes)], check=True)
    subprocess.run(['gh', 'release', 'upload', tag, '--repo', REPOSITORY, *map(str, payloads), str(checks)], check=True)
    subprocess.run(['gh', 'release', 'edit', tag, '--repo', REPOSITORY, '--draft=false', '--latest'], check=True)
    print('Published: https://github.com/' + REPOSITORY + '/releases/tag/' + tag)

if __name__ == '__main__':
    main()
