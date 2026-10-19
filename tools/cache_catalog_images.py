#!/usr/bin/env python3
"""Mirror bounded, decoded JPEG artwork beside the public catalog, without changing the APK."""
import argparse
import copy
import datetime as dt
import hashlib
import io
import json
import re
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from PIL import Image, ImageOps

import auto_catalog as catalog
import build_catalog as build

ROOT = Path(__file__).resolve().parents[1]
BASE = 'https://raw.githubusercontent.com/Aleksei2507/smotrim-tv/main/catalog/v1/images/'
SOURCE_HOSTS = {'images.metahub.space', 'live.metahub.space', 'image.tmdb.org',
                'st.kp.yandex.net', 'avatars.mds.yandex.net', 'kinopoiskapiunofficial.tech'}
INPUT_LIMIT = 5 * 1024 * 1024
OUTPUT_LIMIT = 160 * 1024
PIXEL_LIMIT = 24_000_000
BOUNDS = {'poster': (480, 720), 'background': (960, 540)}


def source_allowed(url):
    try:
        parsed = urllib.parse.urlsplit(url)
        return (parsed.scheme == 'https' and parsed.hostname in SOURCE_HOSTS
                and parsed.port in (None, 443) and parsed.username is None and parsed.password is None)
    except ValueError:
        return False


class ImageRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not source_allowed(newurl):
            raise ValueError('Unapproved artwork redirect')
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def download(url):
    if not source_allowed(url):
        raise ValueError('Unapproved artwork URL')
    request = urllib.request.Request(url, headers={'User-Agent': catalog.USER_AGENT, 'Accept': 'image/*'})
    with urllib.request.build_opener(ImageRedirect).open(request, timeout=20) as response:
        if not response.headers.get('Content-Type', '').lower().startswith('image/'):
            raise ValueError('Artwork response is not an image')
        data = response.read(INPUT_LIMIT + 1)
    if len(data) > INPUT_LIMIT:
        raise ValueError('Artwork download too large')
    return data


def jpeg(data, kind):
    if len(data) > INPUT_LIMIT:
        raise ValueError('Artwork download too large')
    with Image.open(io.BytesIO(data), formats=['JPEG', 'PNG', 'WEBP']) as original:
        width, height = original.size
        if min(width, height) < 32 or width * height > PIXEL_LIMIT:
            raise ValueError('Unexpected artwork dimensions')
        picture = ImageOps.exif_transpose(original).convert('RGB')
        picture.thumbnail(BOUNDS[kind], Image.Resampling.LANCZOS)
        for quality in (82, 72, 62, 50):
            out = io.BytesIO()
            picture.save(out, 'JPEG', quality=quality, optimize=True, progressive=False)
            if out.tell() <= OUTPUT_LIMIT:
                return out.getvalue()
    raise ValueError('Artwork remains too large')


def local_path(directory, url):
    if not isinstance(url, str) or not url.startswith(BASE):
        return None
    filename = url[len(BASE):]
    if not re.fullmatch(r'[a-f0-9]{64}\.jpg', filename):
        raise ValueError('Invalid mirrored artwork URL')
    return directory / 'images' / filename


def verify_image(path):
    if not path.is_file() or not 0 < path.stat().st_size <= OUTPUT_LIMIT:
        raise ValueError('Missing or oversized mirrored artwork')
    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() != path.stem:
        raise ValueError('Artwork checksum mismatch')
    with Image.open(io.BytesIO(data)) as picture:
        if picture.format != 'JPEG' or picture.mode != 'RGB' or picture.width > 960 or picture.height > 720:
            raise ValueError('Unsupported mirrored artwork')
        picture.load()
    return data


def mirror_card(directory, old, fetch, stamp):
    card = copy.deepcopy(old)
    origins = dict(card.get('artworkSources', {}))
    images, objects, failed = {}, {}, []
    for kind in BOUNDS:
        url = card.get(kind, '')
        local = local_path(directory, url)
        if local is not None:
            try:
                images[kind] = (url, verify_image(local))
                continue
            except (OSError, ValueError):
                url = origins.get(kind, '')
        if not url:
            continue
        origins[kind] = url
        try:
            normalized = jpeg(fetch(url), kind)
            filename = hashlib.sha256(normalized).hexdigest() + '.jpg'
            objects[filename] = normalized
            images[kind] = (BASE + filename, normalized)
        except (OSError, ValueError, Image.DecompressionBombError):
            failed.append(kind)
    if not images:
        raise ValueError('No usable artwork for ' + old['id'])
    # A missing backdrop uses the poster; a missing poster uses the backdrop.
    # Keep original addresses for a later repair without making the TV contact them.
    for kind in BOUNDS:
        chosen = images.get(kind) or next(iter(images.values()))
        card[kind] = chosen[0]
    if any(card.get(k) != old.get(k) for k in BOUNDS):
        card['artworkSources'] = origins
        card['updatedAt'] = stamp
    return card, objects, failed


def check_images(directory):
    index = json.loads((directory / 'index.json').read_text())
    checked = set()
    for row in index['items']:
        card = json.loads((directory / row['card']).read_text())
        for kind in BOUNDS:
            if row.get(kind) != card.get(kind):
                raise ValueError('Index and card artwork differ')
            path = local_path(directory, card.get(kind))
            if path is None:
                raise ValueError('Unmirrored artwork: ' + row['id'])
            if path not in checked:
                verify_image(path)
                checked.add(path)
    return {'cards': len(index['items']), 'images': len(checked), 'bytes': sum(p.stat().st_size for p in checked)}


def mirror(directory, fetch=download, workers=4):
    build.check(directory)
    index = json.loads((directory / 'index.json').read_text())
    originals = [json.loads((directory / row['card']).read_text()) for row in index['items']]
    stamp = dt.datetime.now(dt.timezone.utc).isoformat(timespec='seconds').replace('+00:00', 'Z')
    with ThreadPoolExecutor(max_workers=workers) as pool:
        results = list(pool.map(lambda c: mirror_card(directory, c, fetch, stamp), originals))
    # All images must decode before the index is changed. Existing immutable files stay available.
    cards = [r[0] for r in results]
    for card in cards:
        catalog.validate_one(card, index)
    (directory / 'images').mkdir(exist_ok=True)
    for _, objects, _ in results:
        for filename, data in objects.items():
            destination = directory / 'images' / filename
            if not destination.exists() or destination.read_bytes() != data:
                temp = destination.with_suffix('.tmp')
                temp.write_bytes(data)
                temp.replace(destination)
    changed = catalog.publish_cards(directory, index, cards, stamp)
    report = check_images(directory)
    report.update(changed=changed, fallbackCards=[c['id'] for c, _, failed in results if failed])
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    directory = args.root / 'catalog/v1'
    report = check_images(directory) if args.check else mirror(directory)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if not args.check:
        import os
        if os.environ.get('GITHUB_STEP_SUMMARY'):
            with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as stream:
                stream.write(f"\n\nОбложки на GitHub: {report['cards']} карточек, {report['images']} JPEG, {report['bytes']/1048576:.1f} МиБ.\n")


if __name__ == '__main__':
    main()
