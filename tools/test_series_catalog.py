import contextlib
import datetime as dt
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import auto_catalog as auto
import refresh_series_catalog as series
from test_auto_catalog import card, torrent

NOW = dt.datetime(2026, 11, 12, 20, tzinfo=dt.timezone.utc)


def episode(number=2, **values):
    return dict({'season': 1, 'episode': number, 'title': 'Новая серия',
                 'released': '2026-11-11', 'source': 'https://api.tvmaze.com/shows/1/episodes'}, **values)


class SeriesCatalogTest(unittest.TestCase):
    def test_wrong_identity_cannot_add_episodes(self):
        feed = series.EpisodeFeed(fetch=lambda _: {'id': 1, 'externals': {'imdb': 'tt9999999'}}, pause=lambda _: None)
        with self.assertRaises(auto.Rejected):
            feed.episodes(card(kind='series'), {})

    def test_explicit_season_mapping_is_bounded_and_does_not_use_cinemeta(self):
        calls = []
        def fetch(url):
            calls.append(url)
            return [{'season': 1, 'number': 8, 'name': 'Выпуск 8', 'airdate': '2026-11-19'},
                    {'season': 2, 'number': 1, 'name': 'Неизвестный формат', 'airdate': '2026-11-01'}]
        feed = series.EpisodeFeed(fetch=fetch, pause=lambda _: None)
        rows = feed.episodes(card(kind='series'), {'episodeFeeds': [{'tvmazeId': 94659, 'seasonMap': {'1': 4}}]})
        self.assertEqual([(r['season'], r['episode']) for r in rows], [(4, 8)])
        self.assertEqual(calls, ['https://api.tvmaze.com/shows/94659/episodes'])

    def test_future_undated_and_not_yet_aired_episodes_are_skipped(self):
        old = card(kind='series')
        incoming = [episode(2), episode(3, released='2026-11-13'), episode(4, released=''),
                    episode(5, released='2026-11-12', airstamp='2026-11-12T21:00:00Z')]
        rows = series.merge_episodes(old, incoming, NOW)
        self.assertEqual([r['episode'] for r in rows], [1, 2])

    def test_manual_translation_and_known_date_survive(self):
        old = card(kind='series')
        old['videos'][0].update(title='Авторское название', description='Русское описание', released='2025-01-01')
        rows = series.merge_episodes(old, [episode(1, title='English title', released='2025-01-02')], NOW)
        self.assertEqual(rows, old['videos'])

    def test_no_empty_or_wrong_season_response_erases_existing_sources(self):
        old = card(kind='series')
        fresh = series.refresh(old, [], [], NOW)
        self.assertEqual(fresh['sources'], old['sources'])
        future = torrent('series', 'b')
        future['title'] = future['title'].replace('S01E01', 'S02E01')
        fresh = series.refresh(old, [], [future], NOW)
        self.assertEqual(fresh['sources'], old['sources'])

    def test_partial_release_preserves_other_episode_source(self):
        old = card(kind='series')
        incoming = [episode(1), episode(2)]
        row = torrent('series', 'b')
        row['title'] = row['title'].replace('S01E01', 'S01E02')
        fresh = series.refresh(old, incoming, [row], NOW)
        self.assertEqual({r['episode'] for r in fresh['sources']}, {1, 2})

    def test_all_series_are_checked_and_manual_list_is_extended(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); directory = root / 'catalog/v1'
            (directory / 'cards').mkdir(parents=True)
            index = {'schemaVersion': 1, 'revision': 1, 'updatedAt': '2026-11-01T00:00:00Z', 'items': []}
            cards = [card('tt' + str(1111111 + n), 'series') for n in range(7)] + [card('tt9999999', 'movie')]
            with contextlib.redirect_stdout(io.StringIO()):
                auto.publish_cards(directory, index, cards, index['updatedAt'])
            manual = root / 'catalog/curated/details.json'; manual.parent.mkdir()
            manual.write_text(json.dumps({'tt1111111': {'videos': cards[0]['videos']}}))
            class Feed:
                def episodes(self, *_): return [episode(1), episode(2)]
            class Provider:
                def torrents(self, *_): return []
            with contextlib.redirect_stdout(io.StringIO()):
                result = series.run(root, Provider(), Feed(), NOW)
            self.assertEqual(result['checked'], 7)
            self.assertEqual(len(result['refreshed']), 7)
            self.assertEqual(len(json.loads(manual.read_text())['tt1111111']['videos']), 2)
            self.assertEqual(len(json.loads((directory / 'index.json').read_text())['items']), 8)
            with contextlib.redirect_stdout(io.StringIO()):
                repeat = series.run(root, Provider(), Feed(), NOW)
            self.assertFalse(repeat['changed'])

    def test_backup_is_used_after_403_and_successful_host_is_reused(self):
        calls = []
        def fetch(url):
            calls.append(url)
            if 'https://jac-red.ru/' in url: raise auto.Unavailable('HTTP 403')
            return [{'title': 'Тестовый фильм S01E02 RUS 1080p', 'magnet': 'magnet:?xt=urn:btih:' + 'b' * 40,
                     'quality': 1080, 'size': 100, 'sid': 5, 'tracker': 'rutor'}]
        with patch.object(auto, 'get_json', side_effect=fetch):
            provider = auto.PublicSources()
            rows = provider.torrents(card(kind='series'), {})
            provider.torrents(card(kind='series'), {})
        self.assertEqual(len(rows), 1)
        self.assertEqual(len(calls), 3)
        self.assertIn('https://jac.red/', calls[-1])


if __name__ == '__main__':
    unittest.main()
