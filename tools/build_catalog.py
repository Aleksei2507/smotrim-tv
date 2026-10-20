#!/usr/bin/env python3
"""Build/validate small public index and content-addressed cards. No API credentials required."""
import argparse
import datetime as dt
import hashlib
import json
import re
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / 'catalog'
ID = re.compile(r'(?:tt\d{5,12}|kp:\d{1,9}|tmdb:(?:movie|series):\d{1,9})\Z')
SUMMARY_FIELDS = ('id','ids','imdbId','name','type','kind','year','origin','poster','background','description','originalName','aliases','genres','imdbRating','runtime')

def encoded(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8')

def summary(card):
    row={k:card[k] for k in SUMMARY_FIELDS if k in card}
    description=row.get('description','')
    if len(description)>300:row['description']=description[:297].rstrip()+'…'
    if 'aliases' in row:row['aliases']=row['aliases'][:6]
    digest=hashlib.sha256(encoded(card)).hexdigest()
    row.update(card='cards/'+digest+'.json',sha256=digest,localized=True,provider='github')
    return row

def normalized(value):
    return re.sub(r'[^\w]', '', value.lower().replace('ё', 'е'), flags=re.UNICODE)

def russian_audio(title, origin):
    # Language names adjacent to subtitle markers describe subtitles, not audio.
    clean = re.sub(r'(?i)(?:sub(?:s|titles)?|субтитры)\s*[:=–-]?\s*[([]?\s*(?:(?:rus|eng|ukr|рус)[\s,+/)]*)+', '', title)
    clean = re.sub(r'(?i)\b(?:rus|рус)\s*(?:sub(?:s|titles)?|субтитры)\b', '', clean)
    marked = re.search(r'(?i)\b(?:rus|russian|dvo|mvo|avo|lostfilm|hdrezka|alexfilm|newstudio|baibako|rudub)\b|русск(?:ая|ий)\s+(?:озвучк|перевод|звук)|кубик\s+в\s+кубе|продубляж|red head sound|первый канал|мосфильм-мастер|невафильм', clean)
    # DUB / D on their own are not language information.
    if marked:
        return 'Русская озвучка · по описанию раздачи'
    if origin == 'ru' and re.search('[А-Яа-яЁё]', title) and not re.search(r'(?i)\b(?:eng|english|ukr)\b', clean):
        return 'Оригинальный русский звук'
    return None

def title_matches(title, names, year, movie):
    text = title.lower().replace('ё', 'е')
    for name in names:
        if not name: continue
        pattern = re.escape(name.lower().replace('ё', 'е')).replace(r'\ ', r'[\s._]+')
        boundary = r'(?=\s*[/([|–—-]|[\s._]+(?:19|20)\d{2}\b|\s+(?:s\d|season|сезон|\d{1,2}\s+сезон)|\s*$)'
        if re.match(r'^\s*' + pattern + boundary, text):
            return not movie or bool(re.search(r'(?<!\d)' + re.escape(year) + r'(?!\d)', title))
    return False

def season_scope(title):
    # Parse number-before-word first: "1 сезон: 1-6 серии" is season 1, not seasons 1-6.
    match = re.search(r'(?i)(?<!\d)(\d{1,2})(?:\s*[-–]\s*(\d{1,2}))?\s+сезон', title)
    if not match:
        match = re.search(r'(?i)(?:сезон(?:ы)?|season(?:s)?)\s*[:№]?\s*(\d{1,2})(?:\s*[-–]\s*(\d{1,2}))?', title)
    if not match:
        match = re.search(r'(?i)(?<![a-z0-9])s(\d{1,2})(?:\s*[-–]\s*s?(\d{1,2}))?', title)
    cross = re.search(r'(?i)(?<!\d)(\d{1,2})[xх](\d{1,3})(?:[-–](\d{1,3}))?', title)
    if not match and not cross: return []
    first = int(match[1] if match else cross[1]); last = int(match[2] or first) if match else first
    if first < 1 or last < first or last-first > 30: return []
    episode = re.search(r'(?i)(?<![a-z0-9])s\d{1,2}[ ._-]*e(\d{1,3})(?!\d)', title)
    span = re.search(r'(?i)(?:сери(?:и|я)|выпуски?)\s*[:№]?\s*(\d{1,3})(?:\s*[-–]\s*(\d{1,3}))?', title)
    if not span:
        span = re.search(r'(?i)(?<!\d)(\d{1,3})(?:\s*[-–]\s*(\d{1,3}))?\s*(?:серии|серия|выпуски?)', title)
    scope = {'episode': int(episode[1]) if episode else 0}
    if cross: scope.update(episodeFrom=int(cross[2]), episodeTo=int(cross[3] or cross[2]))
    elif span: scope.update(episodeFrom=int(span[1]), episodeTo=int(span[2] or span[1]))
    return [dict(scope, season=s) for s in range(first, last+1)]

def source_scope_key(source):
    episode=source.get('episode',0)
    first=episode or source.get('episodeFrom',0)
    last=episode or source.get('episodeTo',first)
    return (source.get('season',0),source['quality'],first,last)

def variants(seed, metadata, rows):
    aliases = [seed['name'], metadata.get('name', ''), *seed.get('aliases', [])]
    year = str(seed.get('year', metadata.get('releaseInfo', '')))[:4]
    buckets = {}; seen = set()
    for source in sorted(rows, key=lambda s: -s.get('seeds', 0)):
        title = source.get('title', '')
        if not title_matches(title, aliases, year, seed['type']=='movie') or source.get('seeds', 0) <= 0:
            continue
        # Homonymous shows can share a Russian title but predate this production.
        if seed.get('sourceYearMin'):
            release_year = re.search(r'(?<!\d)((?:19|20)\d{2})(?!\d)', title)
            if not release_year or int(release_year[1]) < int(seed['sourceYearMin']):
                continue
        voice = russian_audio(title, seed['origin'])
        magnet = source.get('magnet', '')
        match = re.search(r'(?i)xt=urn:btih:([a-f0-9]{40})(?:&|$)', magnet)
        quality = source.get('quality', '')
        if not voice or not match or quality not in ('4K','1080p','720p'):
            continue
        if re.search(r'(?i)\b(?:CAM(?:Rip)?|HDCAM|HDTS|HDTC|TS|TC|TeleSync|TeleCine|3D|SBS|OU)\b', title):
            continue
        # Prefer a compatible SDR/HDR10 release over Dolby-Vision-only variants.
        if re.search(r'(?i)dolby\s*vision|\bDV\b', title) and not re.search(r'(?i)HDR10|Hybrid|гибрид', title):
            continue
        scopes = season_scope(title) if seed['type']=='series' else [{}]
        if not scopes and seed.get('defaultSeason') and re.search(r'(?i)серии|\[\d+-\d+',title):scopes=[{'season':seed['defaultSeason'],'episode':0}]
        for scope in scopes:
            key=source_scope_key(dict(scope,quality=quality))
            identity=(scope.get('season', 0), match[1].lower())
            if identity in seen or len(buckets.get(key, [])) >= 2:
                continue
            seen.add(identity)
            row={k:source[k] for k in ('title','magnet','quality','source','bytes','size') if k in source}
            row.update(scope, audioLanguage='ru', label=voice, languageEvidence=title)
            buckets.setdefault(key, []).append(row)
    return [r for key in sorted(buckets, key=lambda k:(k[0], {'1080p':0,'4K':1,'720p':2}[k[1]],k[2],k[3])) for r in buckets[key]]

def make_card(seed, meta, rows, override, stamp):
    seed={**seed,**{k:v for k,v in override.items() if k in ('aliases','description','name')}}
    card={k:meta[k] for k in ('id','type','poster','background','genres','runtime','imdbRating','releaseInfo','trailers') if k in meta}
    card.update(seed, imdbId=seed['id'], ids=[seed['id']], originalName=meta['name'], aliases=list(dict.fromkeys([seed['name'],meta['name'],*seed.get('aliases',[])])), year=str(seed.get('year',meta.get('releaseInfo','')))[:4], localized=True, ratingSource='IMDb', updatedAt=stamp)
    card['metadataSources']=list(dict.fromkeys(['https://v3-cinemeta.strem.io/meta/'+seed['type']+'/'+seed['id']+'.json',*seed.get('metadataSources',[]),*override.get('episodeSources',[])]))
    genres={'Drama':'Драма','Crime':'Криминал','Thriller':'Триллер','Action':'Боевик','Adventure':'Приключения','Comedy':'Комедия','Romance':'Мелодрама','Sci-Fi':'Фантастика','Fantasy':'Фэнтези','Mystery':'Детектив','History':'История','Biography':'Биография','Sport':'Спорт','Animation':'Мультфильм','Family':'Семейный','Musical':'Мюзикл','Horror':'Ужасы','War':'Военный','Reality-TV':'Реалити-шоу','Game-Show':'Телеигра','Documentary':'Документальный'}
    card['genres']=[genres.get(g,g) for g in meta.get('genres',[]) if g in genres or re.search('[А-Яа-яЁё]',g)]
    if 'runtime' in card:card['runtime']=str(card['runtime']).replace(' min',' мин')
    episodes=[]; seen=set(); translations=override.get('episodes',{})
    for video in meta.get('videos',[]):
        season=video.get('season');episode=video.get('episode',video.get('number'))
        if not isinstance(season,int) or not isinstance(episode,int) or season<1 or episode<1 or (season,episode) in seen:
            continue
        seen.add((season,episode)); text=translations.get(f'{season}:{episode}',{})
        title=text.get('title',video.get('name',video.get('title','')))
        # Do not silently reintroduce English text into a Russian card.
        if not re.search('[А-Яа-яЁё]',title):title='Серия '+str(episode)
        description=text.get('description','')
        episodes.append({'id':f"{seed['id']}:{season}:{episode}",'season':season,'episode':episode,'title':title,'description':description,'released':video.get('released',video.get('firstAired',''))})
    episodes=override.get('videos',episodes)
    if seed['type']=='series':
        card.update(videos=sorted(episodes,key=lambda e:(e['season'],e['episode'])),episodeProvider='github',seasons=[{'number':s,'name':'Сезон '+str(s)} for s in sorted({e['season'] for e in episodes})])
    card['sources']=variants(seed,meta,rows);card['sourcesUpdatedAt']=stamp
    if seed['type']=='series':card['sources']=[s for s in card['sources'] if any(v['season']==s['season'] for v in episodes)]
    return card

def build(raw_directory, revision):
    selection=json.loads((CATALOG/'curated/titles.json').read_text())
    overrides_path=CATALOG/'curated/details.json'
    overrides=json.loads(overrides_path.read_text()) if overrides_path.exists() else {}
    stamp=dt.datetime.now(dt.timezone.utc).isoformat(timespec='seconds').replace('+00:00','Z')
    output=CATALOG/'v1'; (output/'cards').mkdir(parents=True,exist_ok=True)
    previous=json.loads((output/'index.json').read_text()) if (output/'index.json').exists() else {'revision':0,'items':[]}
    revision=max(revision,previous['revision']+1)
    index={'schemaVersion':1,'revision':revision,'updatedAt':stamp,'selectionNote':selection['selectionNote'],'references':selection['references'],'items':[]}
    for seed in selection['items']:
        raw=json.loads((raw_directory/(seed['id']+'.json')).read_text());meta=raw['metadata']
        if meta['id']!=seed['id'] or meta['type']!=seed['type']:
            raise ValueError('Wrong metadata identity: '+seed['id'])
        card=make_card(seed,meta,raw['search']['items'],overrides.get(seed['id'],{}),stamp)
        episodes=card.get('videos',[])
        data=encoded(card);digest=hashlib.sha256(data).hexdigest();path='cards/'+digest+'.json'
        (output/path).write_bytes(data)
        index['items'].append(summary(card))
        print(seed['name']+': '+str(len(card['sources']))+' variants, '+str(len(episodes))+' episodes')
    editorial_ids={r['id'] for r in index['items']}
    index['items'].extend(r for r in previous['items'] if r['id'] not in editorial_ids)
    (output/'index.json').write_bytes(encoded(index))
    check(output)

def check_media(row):
    assert ID.fullmatch(row['id']) and row['type'] in ('movie','series')
    assert 0<len(row['name'])<=240 and (re.fullmatch('.*[А-Яа-яЁё].*',row['name']) or re.fullmatch(r'[0-9 +:.-]+',row['name']))
    assert len(row.get('description',''))<=6000
    for field in ('poster','background'):
        if row.get(field):
            url=urlsplit(row[field]);assert url.scheme=='https' and url.hostname and url.username is None and url.password is None

def check(output):
    data=(output/'index.json').read_bytes(); root=json.loads(data);seen=set()
    assert len(data)<=2*1024*1024 and root['schemaVersion']==1 and 0<root['revision']<2**53
    assert re.fullmatch(r'\d{4}-\d{2}-\d{2}T.*Z',root['updatedAt'])
    assert 0<len(root['items'])<=3000
    total_sources=0
    for row in root['items']:
        check_media(row);assert row['id'] not in seen;seen.add(row['id'])
        assert row['type'] in ('movie','series') and re.fullmatch(r'cards/[a-f0-9]{64}\.json',row['card'])
        payload=(output/row['card']).read_bytes();digest=hashlib.sha256(payload).hexdigest()
        assert len(payload)<=512*1024 and digest==row['sha256'] and row['card']=='cards/'+digest+'.json'
        card=json.loads(payload);check_media(card);assert card['id']==row['id'] and card['type']==row['type']
        assert len(card.get('sources',[]))<=500 and len(card.get('videos',[]))<=3000
        numbers=set()
        for episode in card.get('videos',[]):
            key=(episode['season'],episode['episode']);assert key not in numbers and 1<=key[0]<=999 and 1<=key[1]<=9999;numbers.add(key)
            assert re.search('[А-Яа-яЁё]',episode['title'])
        for source in card.get('sources',[]):
            assert re.fullmatch(r'(?i)magnet:\?[^\r\n]*xt=urn:btih:[a-f0-9]{40}(?:&[^\r\n]*)?',source['magnet']) and len(source['magnet'])<=8192
            assert source['audioLanguage']=='ru' and source['quality'] in ('4K','1080p','720p') and source['languageEvidence']
            assert 0<len(source['title'])<=1200
            if 'episodeFrom' in source:assert 1<=source['episodeFrom']<=source['episodeTo']<=9999
            if card['type']=='series':assert 1<=source['season']<=999 and 0<=source.get('episode',0)<=9999
            total_sources+=1
    print(f"Validated {len(seen)} cards, {total_sources} Russian-audio variants, index {len(data)} bytes")

def bundle():
    source=CATALOG/'v1'; check(source);root=json.loads((source/'index.json').read_text());target=ROOT/'app/src/main/assets/catalog/v1'
    (target/'cards').mkdir(parents=True,exist_ok=True)
    for file in (target/'cards').glob('*.json'):file.unlink()
    (target/'index.json').write_bytes((source/'index.json').read_bytes())
    for row in root['items']:(target/row['card']).write_bytes((source/row['card']).read_bytes())

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--raw',type=Path);parser.add_argument('--revision',type=int);parser.add_argument('--check',action='store_true');parser.add_argument('--bundle',action='store_true');args=parser.parse_args()
    if args.raw:build(args.raw,args.revision or int(dt.datetime.now(dt.timezone.utc).timestamp()))
    if args.check:check(CATALOG/'v1')
    if args.bundle:bundle()
