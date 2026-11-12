#!/usr/bin/env python3
"""Refresh every existing series, independently of movie discovery and API keys."""
import argparse
import concurrent.futures
import copy
import datetime as dt
import json
import os
from pathlib import Path
import re
import threading
import time
from zoneinfo import ZoneInfo

import auto_catalog as catalog
import build_catalog as build

ROOT = Path(__file__).resolve().parents[1]


def release_date(value):
    try:
        return dt.date.fromisoformat(str(value or '')[:10])
    except ValueError:
        return None


class EpisodeFeed:
    def __init__(self, fetch=catalog.get_json, pause=time.sleep):
        self.fetch = fetch
        self.pause = pause
        self.lock = threading.Lock()
        self.next_request = 0.0

    def tvmaze(self, path):
        with self.lock:
            self.pause(max(0, self.next_request - time.monotonic()))
            self.next_request = time.monotonic() + 0.6
        return self.fetch('https://api.tvmaze.com' + path)

    def episodes(self, card, override):
        feeds = override.get('episodeFeeds')
        if feeds is None:
            try:
                show = self.tvmaze('/lookup/shows?imdb=' + card['id'])
            except catalog.Unavailable:
                response = self.fetch('https://v3-cinemeta.strem.io/meta/series/' + card['id'] + '.json')
                meta = response.get('meta', {}) if isinstance(response, dict) else {}
                if not isinstance(meta, dict) or meta.get('id') != card['id'] or meta.get('type') != 'series':
                    raise catalog.Rejected('Episode identity mismatch')
                return [dict(e, released=e.get('released', e.get('firstAired', '')),
                             title=e.get('name', e.get('title', '')),
                             source='https://v3-cinemeta.strem.io/meta/series/' + card['id'] + '.json')
                        for e in meta.get('videos', []) if isinstance(e, dict)]
            if not isinstance(show, dict) or not isinstance(show.get('externals'), dict) or show['externals'].get('imdb') != card['id']:
                raise catalog.Rejected('Episode identity mismatch')
            feeds = [{'tvmazeId': show['id']}]
        if not isinstance(feeds, list) or not 1 <= len(feeds) <= 10:
            raise catalog.Rejected('Invalid episode feeds')
        output = []
        for feed in feeds:
            show_id = feed['tvmazeId']
            if type(show_id) is not int or not 1 <= show_id <= 10**9:
                raise catalog.Rejected('Invalid episode feed')
            episodes = self.tvmaze(f'/shows/{show_id}/episodes')
            if not isinstance(episodes, list) or len(episodes) > 3000:
                raise catalog.Rejected('Invalid episode list')
            for episode in episodes:
                if not isinstance(episode, dict):
                    continue
                season = episode.get('season')
                if 'seasonMap' in feed:
                    season = feed['seasonMap'].get(str(season))
                if type(season) is not int or season < feed.get('minSeason', 1):
                    continue
                output.append({'season': season, 'episode': episode.get('number'),
                               'title': episode.get('name', ''), 'released': episode.get('airdate', ''),
                               'airstamp': episode.get('airstamp'),
                               'source': f'https://api.tvmaze.com/shows/{show_id}/episodes'})
        return output


def merge_episodes(old, incoming, now):
    today = now.astimezone(ZoneInfo('Europe/Moscow')).date()
    videos = {(e['season'], e['episode']): copy.deepcopy(e) for e in old['videos']}
    if len(incoming) > 3000:
        raise catalog.Rejected('Too many episodes')
    for episode in incoming:
        season, number = episode.get('season'), episode.get('episode')
        date = release_date(episode.get('released'))
        if type(season) is not int or type(number) is not int or not 1 <= season <= 999 or not 1 <= number <= 9999:
            continue
        if not date or date > today:
            continue
        if episode.get('airstamp'):
            try:
                airtime = dt.datetime.fromisoformat(episode['airstamp'].replace('Z', '+00:00'))
                if airtime.tzinfo is None or airtime > now:
                    continue
            except (TypeError, ValueError):
                continue
        key = (season, number)
        if key not in videos:
            title = str(episode.get('title') or '')[:240]
            videos[key] = {'id': f'{old["id"]}:{season}:{number}', 'season': season, 'episode': number,
                           'title': title if re.search('[А-Яа-яЁё]', title) else f'Серия {number}',
                           'description': '', 'released': date.isoformat(), 'source': episode['source']}
        elif not release_date(videos[key].get('released')):
            videos[key]['released'] = date.isoformat()
    return sorted(videos.values(), key=lambda e: (e['season'], e['episode']))


def refresh(card, incoming, rows, now):
    fresh = copy.deepcopy(card)
    fresh['videos'] = merge_episodes(card, incoming, now)
    fresh['seasons'] = [{'number': s, 'name': f'Сезон {s}'} for s in sorted({v['season'] for v in fresh['videos']})]
    today = now.astimezone(ZoneInfo('Europe/Moscow')).date()
    aired = {}
    for episode in fresh['videos']:
        date = release_date(episode.get('released'))
        if date and date <= today:
            aired[episode['season']] = max(aired.get(episode['season'], 0), episode['episode'])
    meta = {'name': card.get('originalName', card['name']), 'releaseInfo': card.get('year', '')}
    sources = []
    for source in build.variants(card, meta, rows):
        season = source['season']
        last = source.get('episode') or source.get('episodeTo', 0)
        if season not in aired or (last and last > aired[season]):
            continue
        year = re.search(r'(?<!\d)((?:19|20)\d{2})(?!\d)', source['title'])
        if year and str(card.get('year', '')).isdigit() and int(year[1]) < int(card['year']):
            continue
        sources.append(source)
    old_by_hash = {(s['season'], re.search(r'(?i)btih:([a-f0-9]{40})', s['magnet'])[1].lower()): s for s in card['sources']}
    buckets = {build.source_scope_key(s) for s in sources}
    combined = sources + [s for s in card['sources'] if build.source_scope_key(s) not in buckets]
    fresh['sources'] = []
    seen = set()
    for source in combined:
        key = (source['season'], re.search(r'(?i)btih:([a-f0-9]{40})', source['magnet'])[1].lower())
        if key not in seen:
            fresh['sources'].append(copy.deepcopy(old_by_hash.get(key, source)))
            seen.add(key)
    if catalog.same_payload(card, fresh):
        return card
    stamp = now.isoformat(timespec='seconds').replace('+00:00', 'Z')
    fresh['updatedAt'] = stamp
    if fresh['sources'] != card['sources']:
        fresh['sourcesUpdatedAt'] = stamp
    return fresh


def run(root, provider=None, feed=None, now=None):
    provider = provider or catalog.PublicSources()
    feed = feed or EpisodeFeed()
    now = now or dt.datetime.now(dt.timezone.utc)
    directory = root / 'catalog/v1'
    build.check(directory)
    index = json.loads((directory / 'index.json').read_text())
    cards = [json.loads((directory / row['card']).read_text()) for row in index['items']]
    path = root / 'catalog/curated/details.json'
    overrides = json.loads(path.read_text()) if path.exists() else {}
    before = copy.deepcopy(overrides)
    report = {'checked': 0, 'refreshed': [], 'warnings': []}
    selected = [(position, card) for position, card in enumerate(cards) if card['type'] == 'series']

    def collect(pair):
        _, card = pair
        warnings = []
        override = copy.deepcopy(before.get(card['id'], {}))
        try:
            incoming = feed.episodes(card, override)
        except (catalog.Unavailable, catalog.Rejected, KeyError, TypeError, ValueError):
            incoming = []
            warnings.append('Episode feed unavailable: ' + card['id'])
        try:
            rows = provider.torrents(card, card)
        except catalog.Unavailable:
            rows = []
            warnings.append('Torrent feed unavailable: ' + card['id'])
        return incoming, rows, warnings

    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        for (position, card), (incoming, rows, warnings) in zip(selected, pool.map(collect, selected)):
            report['checked'] += 1
            report['warnings'].extend(warnings)
            try:
                fresh = refresh(card, incoming, rows, now)
                catalog.validate_one(fresh, index)
                if fresh is not card:
                    cards[position] = fresh
                    if 'videos' in overrides.get(card['id'], {}):
                        overrides[card['id']]['videos'] = fresh['videos']
                    report['refreshed'].append({'id': card['id'], 'name': card['name'],
                                                'newEpisodes': len(fresh['videos']) - len(card['videos'])})
                print('Checked: ' + card['name'], flush=True)
            except (catalog.Rejected, KeyError, TypeError, ValueError, AssertionError):
                report['warnings'].append('Invalid refresh skipped: ' + card['id'])
    stamp = now.isoformat(timespec='seconds').replace('+00:00', 'Z')
    report['changed'] = catalog.publish_cards(directory, index, cards, stamp)
    if overrides != before:
        path.write_bytes(build.encoded(overrides))
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    args = parser.parse_args()
    report = run(args.root)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as stream:
            stream.write(f"# Обновление серий\n\nПроверено сериалов и шоу: {report['checked']}. Обновлено: {len(report['refreshed'])}.\n")
            for item in report['refreshed']:
                stream.write(f"\n- {item['name']}: новых серий {item['newEpisodes']}")
            if report['warnings']:
                stream.write('\n\nЧасть источников недоступна; сохранённые серии и ссылки оставлены.\n')
    if report['checked'] and len(report['warnings']) == report['checked'] * 2:
        raise SystemExit('All series providers unavailable; previous catalog retained')


if __name__ == '__main__':
    main()
