import contextlib
import copy
import hashlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from PIL import Image
import auto_catalog as catalog
import build_catalog as build
import cache_catalog_images as artwork


def picture(size=(1800, 1200), mode='RGB'):
    out = io.BytesIO()
    Image.new(mode, size, 'blue').save(out, 'PNG')
    return out.getvalue()


class ArtworkTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.directory = self.root / 'catalog/v1'
        (self.directory / 'cards').mkdir(parents=True)
        self.card = {'id': 'tt1111111', 'type': 'movie', 'name': 'Фильм', 'description': 'Описание',
                     'poster': 'https://images.metahub.space/poster/small/tt1111111/img',
                     'background': 'https://images.metahub.space/background/medium/tt1111111/img',
                     'sources': [], 'updatedAt': '2026-10-19T00:00:00Z'}
        index = {'schemaVersion': 1, 'revision': 1, 'updatedAt': self.card['updatedAt'], 'items': []}
        with contextlib.redirect_stdout(io.StringIO()):
            catalog.publish_cards(self.directory, index, [self.card], self.card['updatedAt'])
        self.initial = (self.directory / 'index.json').read_bytes()

    def tearDown(self):
        self.temp.cleanup()

    def mirror(self, fetch):
        with contextlib.redirect_stdout(io.StringIO()):
            return artwork.mirror(self.directory, fetch=fetch)

    def current(self):
        index = json.loads((self.directory / 'index.json').read_text())
        return index, json.loads((self.directory / index['items'][0]['card']).read_text())

    def test_normalizes_large_images_and_keeps_original_card(self):
        result = self.mirror(lambda _: picture())
        index, card = self.current()
        self.assertTrue(result['changed'])
        self.assertEqual(card['artworkSources']['poster'], self.card['poster'])
        for kind, bounds in artwork.BOUNDS.items():
            path = artwork.local_path(self.directory, card[kind])
            data = artwork.verify_image(path)
            self.assertLessEqual(len(data), artwork.OUTPUT_LIMIT)
            with Image.open(io.BytesIO(data)) as image:
                self.assertLessEqual(image.width, bounds[0])
                self.assertLessEqual(image.height, bounds[1])
                self.assertNotIn('exif', image.info)
        old_row = json.loads(self.initial)['items'][0]
        self.assertTrue((self.directory / old_row['card']).exists())
        self.assertGreater(index['revision'], json.loads(self.initial)['revision'])

    def test_second_run_reuses_images_without_network_or_new_revision(self):
        self.mirror(lambda _: picture())
        index = (self.directory / 'index.json').read_bytes()
        def offline(_):
            self.fail('Cached artwork must not be downloaded again')
        self.assertFalse(self.mirror(offline)['changed'])
        self.assertEqual((self.directory / 'index.json').read_bytes(), index)

    def test_failed_background_uses_working_poster(self):
        def fetch(url):
            if '/background/' in url:
                raise OSError('offline')
            return picture((400, 600))
        self.mirror(fetch)
        _, card = self.current()
        self.assertEqual(card['poster'], card['background'])
        artwork.check_images(self.directory)

    def test_invalid_or_unavailable_images_do_not_publish_empty_cards(self):
        with self.assertRaises(ValueError):
            self.mirror(lambda _: b'<html>service unavailable</html>')
        self.assertEqual((self.directory / 'index.json').read_bytes(), self.initial)
        with self.assertRaises(ValueError):
            artwork.jpeg(b'a' * (artwork.INPUT_LIMIT + 1), 'poster')
        with patch.object(artwork, 'PIXEL_LIMIT', 100):
            with self.assertRaises(ValueError):
                artwork.jpeg(picture((100, 100)), 'poster')

    def test_disallows_private_hosts_credentials_and_redirects(self):
        for url in ['http://images.metahub.space/img', 'https://127.0.0.1/img',
                    'https://user:password@images.metahub.space/img',
                    'https://images.metahub.space.evil.example/img', 'https://images.metahub.space:8443/img']:
            self.assertFalse(artwork.source_allowed(url))
        self.assertTrue(artwork.source_allowed(self.card['poster']))
        with self.assertRaises(ValueError):
            artwork.ImageRedirect().redirect_request(None, None, 302, '', {}, 'https://127.0.0.1/')
        with self.assertRaises(ValueError):
            artwork.local_path(self.directory, artwork.BASE + '../private')

    def test_detects_missing_and_corrupted_cached_image(self):
        self.mirror(lambda _: picture())
        _, card = self.current()
        path = artwork.local_path(self.directory, card['poster'])
        path.write_bytes(b'broken')
        with self.assertRaises(ValueError):
            artwork.check_images(self.directory)
        self.mirror(lambda _: picture())
        artwork.check_images(self.directory)


if __name__ == '__main__':
    unittest.main()
