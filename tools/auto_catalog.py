#!/usr/bin/env python3
"""Incrementally discover Russian cards and refresh sources. Data only; no APK or keys."""
import argparse
import copy
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

import build_catalog as build

ROOT = Path(__file__).resolve().parents[1]
USER_AGENT = 'SmotrimCatalog/1.0 (+https://github.com/Aleksei2507/smotrim-tv)'
IMDB = re.compile(r'tt\d{5,12}\Z')
QID = re.compile(r'Q\d+\Z')
SUMMARY_FIELDS = ('id','ids','imdbId','name','type','kind','year','origin','poster','background','description','originalName','aliases','genres','imdbRating','runtime')

class Unavailable(Exception): pass
class Rejected(Exception): pass

def get_json(url):
    request=urllib.request.Request(url,headers={'User-Agent':USER_AGENT,'Accept':'application/json'})
    for attempt in range(2):
        try:
            with urllib.request.urlopen(request,timeout=18) as response:
                payload=response.read(4*1024*1024+1)
                if len(payload)>4*1024*1024:raise Unavailable('response too large')
                return json.loads(payload)
        except (OSError,ValueError) as error:
            if attempt:raise Unavailable(type(error).__name__) from error
            time.sleep(1)

def wiki(**args):
    data=get_json('https://www.wikidata.org/w/api.php?'+urllib.parse.urlencode(dict(args,format='json',maxlag=5)))
    if 'error' in data:raise Unavailable('Wikidata API error')
    return data

def claims(entity,prop):
    return [s['mainsnak']['datavalue']['value'] for s in entity.get('claims',{}).get(prop,[]) if s.get('rank')!='deprecated' and 'datavalue' in s.get('mainsnak',{})]

def entity_seed(entity,imdb,kind):
    # Exact external ID, never fuzzy matching by a translated name.
    if imdb not in claims(entity,'P345'):raise Rejected('IMDb identity mismatch')
    name=entity.get('labels',{}).get('ru',{}).get('value','').strip()
    description=entity.get('descriptions',{}).get('ru',{}).get('value','').strip()
    if not re.search('[А-Яа-яЁё]',name) or not re.search('[А-Яа-яЁё]',description):raise Rejected('no Russian metadata')
    if len(name)>240 or len(description)>6000:raise Rejected('metadata too long')
    countries=[v.get('id') for v in claims(entity,'P495') if isinstance(v,dict)]
    aliases=[name,entity.get('labels',{}).get('en',{}).get('value','')]
    aliases += [v['value'] for lang in ('ru','en') for v in entity.get('aliases',{}).get(lang,[])][:20]
    return {'id':imdb,'type':kind,'name':name,'description':description,
            'origin':'ru' if any(c in ('Q159','Q15180','Q34266') for c in countries) else 'other' if countries else 'unknown',
            'aliases':list(dict.fromkeys(a for a in aliases if a and len(a)<=240)),
            'metadataSources':['https://www.wikidata.org/wiki/'+entity['id']],
            'metadataLicense':'CC0 (Wikidata labels and descriptions)'}

class PublicSources:
    def __init__(self):self.entities={}
    def entity(self,qid):
        if not QID.fullmatch(qid):raise Rejected('invalid entity ID')
        if qid not in self.entities:
            self.entities[qid]=wiki(action='wbgetentities',ids=qid,props='labels|descriptions|aliases|claims',languages='ru|en')['entities'][qid]
        return self.entities[qid]
    def localized(self,imdb,kind,qid=None):
        if not IMDB.fullmatch(imdb):raise Rejected('invalid IMDb ID')
        if qid:return entity_seed(self.entity(qid),imdb,kind)
        found=wiki(action='query',list='search',srsearch='haswbstatement:P345='+imdb,srnamespace=0,srlimit=3)['query']['search']
        matches=[]
        for hit in found:
            e=self.entity(hit['title'])
            if imdb in claims(e,'P345'):matches.append(e)
        if len(matches)!=1:raise Rejected('missing or ambiguous identity')
        return entity_seed(matches[0],imdb,kind)
    def candidates(self):
        feeds=[];warnings=[]
        for kind in ('movie','series'):
            try:
                rows=get_json('https://v3-cinemeta.strem.io/catalog/'+kind+'/top.json')['metas']
                feeds.append([{'id':r['id'],'type':kind} for r in rows if IMDB.fullmatch(r.get('id',''))][:100])
            except (Unavailable,KeyError,TypeError) as e:warnings.append('Cinemeta '+kind+': unavailable')
        try:
            hits=wiki(action='query',list='search',srsearch='haswbstatement:P495=Q159 haswbstatement:P345',srnamespace=0,srlimit=40,srsort='last_edit_desc')['query']['search']
            russian=[]
            # Batch identity lookup; metadata remains validated again before publication.
            for start in range(0,len(hits),5):
                qids=[h['title'] for h in hits[start:start+5] if QID.fullmatch(h['title'])]
                entities=wiki(action='wbgetentities',ids='|'.join(qids),props='labels|descriptions|aliases|claims',languages='ru|en')['entities']
                self.entities.update(entities)
                for qid,e in entities.items():
                    for imdb in claims(e,'P345'):
                        if isinstance(imdb,str) and IMDB.fullmatch(imdb):russian.append({'id':imdb,'type':None,'qid':qid})
            feeds.insert(0,russian)
        except (Unavailable,KeyError,TypeError):warnings.append('Russian discovery: unavailable')
        if not feeds:raise Unavailable('All discovery providers are unavailable')
        output=[];seen=set()
        for offset in range(max(map(len,feeds),default=0)):
            for feed in feeds:
                if offset<len(feed) and feed[offset]['id'] not in seen:
                    seen.add(feed[offset]['id']);output.append(feed[offset])
        return output,warnings
    def metadata(self,imdb,kind):
        for candidate in ([kind] if kind else ['movie','series']):
            try:
                meta=get_json('https://v3-cinemeta.strem.io/meta/'+candidate+'/'+imdb+'.json')['meta']
                if not isinstance(meta,dict) or meta.get('id')!=imdb or meta.get('type')!=candidate:continue
                if meta.get('adult') or 'Adult' in meta.get('genres',[]):raise Rejected('adult category')
                if not str(meta.get('poster','')).startswith('https://'):raise Rejected('no poster')
                return meta
            except (Unavailable,KeyError,TypeError):
                if kind:raise
        raise Unavailable('Metadata unavailable')
    def torrents(self,seed,meta):
        query=seed.get('searchName',seed['name']);year=str(seed.get('year',meta.get('releaseInfo','')))[:4]
        params={'search':query,'sort':'sid'}
        if seed['type']=='movie' and re.fullmatch(r'\d{4}',year):params['relased']=year
        data=get_json('https://jac.red/api/v1.0/torrents?'+urllib.parse.urlencode(params))
        if not isinstance(data,list):raise Unavailable('Invalid torrent response')
        rows=[]
        for row in data[:1500]:
            if not isinstance(row,dict):continue
            try:
                title=str(row.get('title',''));magnet=str(row.get('magnet',''));quality=int(row.get('quality',0));size=int(row.get('size',0));seeds=int(row.get('sid',0))
                if not re.fullmatch(r'(?i)magnet:\?[^\r\n]*xt=urn:btih:[a-f0-9]{40}(?:&[^\r\n]*)?',magnet):continue
                if not title or len(title)>1200 or len(magnet)>8192:continue
                rows.append({'title':title,'magnet':magnet,'quality':'4K' if quality>=2160 else '1080p' if quality>=1080 else '720p' if quality>=720 else '',
                    'source':'JacRed · '+str(row.get('tracker','трекер'))[:60],'bytes':max(0,size),'size':f'{size/1073741824:.2f} GB','seeds':max(0,seeds)})
            except (TypeError,ValueError):continue
        return rows

def same_payload(a,b):
    omit={'updatedAt','sourcesUpdatedAt'}
    return {k:v for k,v in a.items() if k not in omit}=={k:v for k,v in b.items() if k not in omit}

def refresh_card(old,meta,raw_rows,override,stamp):
    # Keep editorial fields. Extend episode metadata without erasing translated episodes.
    fresh=copy.deepcopy(old)
    if old['type']=='series':
        translated={f"{e['season']}:{e['episode']}":e for e in old.get('videos',[])}
        effective={**override,'episodes':{**translated,**override.get('episodes',{})}}
        rebuilt=build.make_card(old,meta,[],effective,stamp)
        if 'videos' not in override:
            episodes={(e['season'],e['episode']):e for e in old.get('videos',[])}
            episodes.update({(e['season'],e['episode']):e for e in rebuilt.get('videos',[])})
            rebuilt['videos']=sorted(episodes.values(),key=lambda e:(e['season'],e['episode']))
        fresh['videos']=rebuilt['videos']
        fresh['seasons']=[{'number':s,'name':'Сезон '+str(s)} for s in sorted({e['season'] for e in fresh['videos']})]
    sources=build.variants(old,meta,raw_rows)
    if old['type']=='series':sources=[s for s in sources if any(v['season']==s['season'] for v in fresh.get('videos',[]))]
    # Replace only refreshed season/quality buckets; an empty/partial answer is not deletion.
    buckets={(s.get('season',0),s['quality']) for s in sources}
    fresh['sources']=sources+[s for s in old.get('sources',[]) if (s.get('season',0),s['quality']) not in buckets]
    if same_payload(old,fresh):return old
    fresh['sourcesUpdatedAt']=stamp;fresh['updatedAt']=stamp
    return fresh

def publish_cards(directory,index,cards,stamp):
    updated=copy.deepcopy(index);rows=[]
    for card in cards:
        payload=build.encoded(card);digest=hashlib.sha256(payload).hexdigest()
        row={k:card[k] for k in SUMMARY_FIELDS if k in card}
        row.update(card='cards/'+digest+'.json',sha256=digest,localized=True,provider='github');rows.append(row)
    if rows==index['items']:return False
    updated.update(items=rows,revision=max(index['revision']+1,int(time.time())),updatedAt=stamp)
    # Validate a complete staged tree first. Old files remain addressable by old indices.
    with tempfile.TemporaryDirectory(prefix='smotrim-catalog-') as tmp:
        stage=Path(tmp);(stage/'cards').mkdir()
        for row,card in zip(rows,cards):(stage/row['card']).write_bytes(build.encoded(card))
        (stage/'index.json').write_bytes(build.encoded(updated));build.check(stage)
        for row in rows:
            destination=directory/row['card']
            if not destination.exists():shutil.copyfile(stage/row['card'],destination)
        temporary=directory/'index.next.json';temporary.write_bytes(build.encoded(updated));os.replace(temporary,directory/'index.json')
    return True

def validate_one(card,index):
    with tempfile.TemporaryDirectory() as tmp:
        stage=Path(tmp);(stage/'cards').mkdir();h=hashlib.sha256(build.encoded(card)).hexdigest();(stage/'cards'/f'{h}.json').write_bytes(build.encoded(card))
        one={**index,'items':[{**{k:card[k] for k in SUMMARY_FIELDS if k in card},'card':f'cards/{h}.json','sha256':h}]}
        (stage/'index.json').write_bytes(build.encoded(one));build.check(stage)

def run(directory,state_file,provider,max_candidates=24,max_new=8,refresh_limit=6,now=None):
    now=now or dt.datetime.now(dt.timezone.utc);stamp=now.isoformat(timespec='seconds').replace('+00:00','Z');epoch=int(now.timestamp())
    build.check(directory);index=json.loads((directory/'index.json').read_text());cards=[json.loads((directory/r['card']).read_text()) for r in index['items']]
    state=json.loads(state_file.read_text()) if state_file.exists() else {'attempts':{},'refresh':{}}
    overrides_path=directory.parent/'curated/details.json'
    overrides=json.loads(overrides_path.read_text()) if overrides_path.exists() else {}
    report={'checkedAt':stamp,'added':[],'refreshed':[],'skipped':[],'warnings':[]}
    candidates,warnings=provider.candidates();report['warnings']+=warnings;existing={c['id'] for c in cards}
    eligible=[r for r in candidates if r['id'] not in existing and epoch-state['attempts'].get(r['id'],0)>=3*86400]
    eligible.sort(key=lambda r:state['attempts'].get(r['id'],0))
    for candidate in eligible[:max_candidates]:
        if len(report['added'])>=max_new or len(cards)>=1200:break
        imdb=candidate['id'];state['attempts'][imdb]=epoch
        try:
            meta=provider.metadata(imdb,candidate.get('type'));seed=provider.localized(imdb,meta['type'],candidate.get('qid'))
            rows=provider.torrents(seed,meta);card=build.make_card(seed,meta,rows,{},stamp)
            if not card.get('sources'):raise Rejected('no confirmed Russian audio')
            if card['type']=='series' and not card.get('videos'):raise Rejected('no episode list')
            # Check this card before accepting it; one bad provider item cannot poison the batch.
            validate_one(card,index)
            cards.append(card);existing.add(imdb);report['added'].append({'id':imdb,'name':card['name']});print('Added: '+card['name'],flush=True)
        except (Unavailable,Rejected,KeyError,ValueError,TypeError,AssertionError) as error:
            report['skipped'].append({'id':imdb,'reason':str(error)[:120]});print('Skipped '+imdb+': '+str(error)[:120],flush=True)
    # Round-robin refresh, so one temporarily unavailable source cannot starve other cards.
    order=sorted(range(len(index['items'])),key=lambda i:state['refresh'].get(cards[i]['id'],0))[:refresh_limit]
    for position in order:
        old=cards[position];state['refresh'][old['id']]=epoch
        try:
            try:meta=provider.metadata(old['id'],old['type']) if old['type']=='series' else old
            except (Unavailable,Rejected):meta=old
            fresh=refresh_card(old,meta,provider.torrents(old,meta),overrides.get(old['id'],{}),stamp)
            validate_one(fresh,index)
            if fresh is not old:cards[position]=fresh;report['refreshed'].append({'id':old['id'],'name':old['name']})
        except (Unavailable,Rejected,KeyError,ValueError,TypeError,AssertionError):report['warnings'].append('Source refresh unavailable: '+old['id'])
    changed=publish_cards(directory,index,cards,stamp)
    # Keep the retry ledger bounded and free of any user information.
    state['attempts']={k:v for k,v in sorted(state['attempts'].items(),key=lambda kv:-kv[1])[:2000] if epoch-v<90*86400}
    state['lastRun']=stamp;state_file.parent.mkdir(parents=True,exist_ok=True);state_file.write_bytes(build.encoded(state))
    report.update(changed=changed,total=len(cards));return report

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--root',type=Path,default=ROOT)
    parser.add_argument('--max-candidates',type=int,default=24);parser.add_argument('--max-new',type=int,default=8);parser.add_argument('--refresh',type=int,default=6)
    args=parser.parse_args()
    if not 1<=args.max_candidates<=40 or not 1<=args.max_new<=12 or not 0<=args.refresh<=12:parser.error('Invalid batch size')
    report=run(args.root/'catalog/v1',args.root/'catalog/automation/state.json',PublicSources(),args.max_candidates,args.max_new,args.refresh)
    summary='# Обновление каталога\n\n'+f"Карточек: {report['total']}. Добавлено: {len(report['added'])}. Обновлены варианты: {len(report['refreshed'])}.\n"
    for item in report['added']:summary+='\n- '+item['name']+' ('+item['id']+')'
    if report['warnings']:summary+='\n\nЧасть источников недоступна. Сохранённые карточки оставлены без потерь.\n'
    print(json.dumps(report,ensure_ascii=False,indent=2))
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'],'a') as stream:stream.write(summary)

if __name__=='__main__':main()
