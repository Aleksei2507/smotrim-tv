import contextlib
import copy
import datetime as dt
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import auto_catalog as a
import build_catalog as b

STAMP = '2026-10-19T00:00:00Z'
NOW = dt.datetime(2026,10,19,tzinfo=dt.timezone.utc)

def seed(imdb='tt1111111',kind='movie'):
    return {'id':imdb,'type':kind,'name':'Тестовый фильм','description':'Русское описание.','origin':'other','aliases':['Test Film']}

def meta(imdb='tt1111111',kind='movie'):
    return {'id':imdb,'type':kind,'name':'Test Film','releaseInfo':'2025','poster':'https://example.com/poster.jpg','videos':[{'season':1,'episode':1,'name':'Pilot'}]}

def torrent(kind='movie',suffix='a'):
    return {'title':'Тестовый фильм (2025) '+('S01E01 ' if kind=='series' else '')+'RUS 1080p',
            'quality':'1080p','seeds':10,'magnet':'magnet:?xt=urn:btih:'+suffix*40}

def card(imdb='tt1111111',kind='movie'):
    return b.make_card(seed(imdb,kind),meta(imdb,kind),[torrent(kind)],{},STAMP)

class Fake:
    def __init__(self,kind='movie'):
        self.kind=kind;self.items=[{'id':'tt2222222','type':kind}];self.rows=[torrent(kind)]
    def candidates(self):return self.items,[]
    def metadata(self,imdb,kind):return meta(imdb,kind or self.kind)
    def localized(self,imdb,kind,qid=None):return seed(imdb,kind)
    def torrents(self,seed,meta):return self.rows

class AutomaticCatalogTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name);self.directory=self.root/'catalog/v1'
        (self.directory/'cards').mkdir(parents=True);self.state=self.root/'catalog/automation/state.json'
        self.index={'schemaVersion':1,'revision':1,'updatedAt':STAMP,'items':[]}
        with contextlib.redirect_stdout(io.StringIO()):a.publish_cards(self.directory,self.index,[card()],STAMP)
        self.index=json.loads((self.directory/'index.json').read_text())
    def tearDown(self):self.tmp.cleanup()
    def run_catalog(self,provider,**kwargs):
        with contextlib.redirect_stdout(io.StringIO()):return a.run(self.directory,self.state,provider,now=NOW,**kwargs)
    def current(self):return json.loads((self.directory/'index.json').read_text())
    def test_new_titles_lead_and_refresh_does_not_move_old_titles(self):
        first=self.current()['items'][0]['id']
        provider=Fake();provider.items[0]['id']='tt2222222'
        self.run_catalog(provider,refresh_limit=0)
        provider.items[0]['id']='tt3333333'
        self.run_catalog(provider,refresh_limit=0)
        expected=['tt3333333','tt2222222',first]
        self.assertEqual([r['id'] for r in self.current()['items']],expected)
        index=self.current();cards=[json.loads((self.directory/r['card']).read_text()) for r in index['items']]
        cards[-1]['description']='Обновлённое описание старого фильма'
        with contextlib.redirect_stdout(io.StringIO()):a.publish_cards(self.directory,index,list(reversed(cards)),STAMP)
        self.assertEqual([r['id'] for r in self.current()['items']],expected)
    def test_first_batch_preserves_order_and_manual_rebuild_keeps_newest_first(self):
        rows=[{'id':'new-a'},{'id':'old-a'},{'id':'new-b'},{'id':'old-b'}]
        self.assertEqual(b.newest_first(rows,[]),rows)
        self.assertEqual([r['id'] for r in b.newest_first(rows,[{'id':'old-b'},{'id':'old-a'}])],['new-a','new-b','old-b','old-a'])
    def test_add_movies_and_series_without_duplicates_or_apk_changes(self):
        for kind in ('movie','series'):
            provider=Fake(kind);provider.items[0]['id']='tt2222222' if kind=='movie' else 'tt3333333'
            result=self.run_catalog(provider,refresh_limit=0)
            self.assertEqual(len(result['added']),1)
            repeat=self.run_catalog(provider,refresh_limit=0);self.assertFalse(repeat['changed'])
        self.assertEqual(len(self.current()['items']),3)
    def test_subtitles_or_absent_sources_do_not_add_card_and_cooldown_applies(self):
        provider=Fake();provider.rows[0]['title']='Тестовый фильм (2025) ENG + Sub (Rus)'
        result=self.run_catalog(provider,refresh_limit=0);self.assertFalse(result['changed']);self.assertEqual(len(result['skipped']),1)
        provider.rows=[torrent()];retry=self.run_catalog(provider,refresh_limit=0);self.assertEqual(retry['added'],[])
    def test_invalid_new_card_is_skipped_without_losing_existing(self):
        provider=Fake()
        with patch.object(provider,'metadata',return_value={**meta('tt2222222'),'poster':'http://unsafe.example/poster.jpg'}):
            result=self.run_catalog(provider,refresh_limit=0)
        self.assertEqual(result['added'],[]);self.assertEqual(self.current(),self.index)
    def test_provider_outage_keeps_last_good_catalog(self):
        provider=Fake()
        with patch.object(provider,'candidates',side_effect=a.Unavailable('offline')):
            self.assertRaises(a.Unavailable,self.run_catalog,provider)
        self.assertEqual(self.current(),self.index);self.assertFalse(self.state.exists())
    def test_empty_refresh_preserves_sources_and_revision(self):
        provider=Fake();provider.items=[];provider.rows=[]
        result=self.run_catalog(provider,refresh_limit=1)
        self.assertFalse(result['changed']);self.assertEqual(self.current(),self.index)
    def test_invalid_staged_catalog_cannot_replace_index(self):
        old=(self.directory/'index.json').read_bytes();invalid=card();invalid['sources'][0].update(episodeFrom=4,episodeTo=2)
        with self.assertRaises(AssertionError):a.publish_cards(self.directory,self.index,[invalid],STAMP)
        self.assertEqual((self.directory/'index.json').read_bytes(),old)
    def test_updated_card_keeps_old_immutable_object(self):
        provider=Fake();provider.items=[];provider.rows=[torrent(suffix='b')]
        previous=self.directory/self.index['items'][0]['card'];old=previous.read_bytes()
        self.assertTrue(self.run_catalog(provider,refresh_limit=1)['changed'])
        self.assertEqual(previous.read_bytes(),old);self.assertGreater(self.current()['revision'],self.index['revision'])
    def test_exact_wikidata_identity_and_russian_text_required(self):
        entity={'id':'Q1','labels':{'ru':{'value':'Фильм'}},'descriptions':{'ru':{'value':'Российский фильм'}},'claims':{'P345':[{'mainsnak':{'datavalue':{'value':'tt1111111'}}}]}}
        self.assertEqual(a.entity_seed(entity,'tt1111111','movie')['name'],'Фильм')
        self.assertRaises(a.Rejected,a.entity_seed,entity,'tt9999999','movie')
        entity['descriptions']['ru']['value']='English description'
        self.assertRaises(a.Rejected,a.entity_seed,entity,'tt1111111','movie')
    def test_episode_refresh_preserves_translation_and_adds_season(self):
        old=card(kind='series');old['videos'][0].update(title='Начало',description='Описание первой серии')
        metadata=meta(kind='series');metadata['videos'].append({'season':2,'episode':1,'name':'Return'})
        row=torrent('series','b');row['title']=row['title'].replace('S01E01','S02E01')
        fresh=a.refresh_card(old,metadata,[row],{},STAMP)
        self.assertEqual(fresh['videos'][0]['title'],'Начало');self.assertEqual(fresh['videos'][0]['description'],'Описание первой серии')
        self.assertEqual(fresh['videos'][1]['title'],'Серия 1');self.assertEqual({s['season'] for s in fresh['sources']},{1,2})
    def test_partial_episode_refresh_preserves_other_releases_and_season_pack(self):
        old=card(kind='series');metadata=meta(kind='series')
        rows=[]
        for episode in range(1,4):
            row=torrent('series',str(episode));row['title']=row['title'].replace('S01E01',f'S01E{episode:02}')
            rows.append(row)
        pack=torrent('series','d');pack['title']=pack['title'].replace('S01E01','[01x01-03]')
        old['sources']=b.variants(old,metadata,rows+[pack])
        replacement=torrent('series','e');replacement['title']=replacement['title'].replace('S01E01','[01x02]')
        fresh=a.refresh_card(old,metadata,[replacement],{},STAMP)
        hashes={s['magnet'].split(':')[-1] for s in fresh['sources']}
        self.assertEqual(hashes,{'1'*40,'3'*40,'d'*40,'e'*40})

    def test_partial_translation_keeps_existing_title(self):
        old=card(kind='series');old['videos'][0]['title']='Начало'
        fresh=a.refresh_card(old,meta(kind='series'),[],{'episodes':{'1:1':{'description':'Новое описание'}}},STAMP)
        self.assertEqual(fresh['videos'][0]['title'],'Начало');self.assertEqual(fresh['videos'][0]['description'],'Новое описание')

    def test_editorial_episode_override_survives_provider_wrong_season(self):
        old=card(kind='series');metadata=meta(kind='series');metadata['videos']=[{'season':8,'episode':99,'name':'Wrong'}]
        fresh=a.refresh_card(old,metadata,[],{'videos':old['videos']},STAMP)
        self.assertEqual(fresh['videos'],old['videos'])
    def test_metadata_updates_even_if_torrent_service_is_offline(self):
        provider=Fake();provider.items=[];provider.enrichment=lambda *_:{'description':'Новое русское описание'}
        with patch.object(provider,'torrents',side_effect=a.Unavailable('offline')):
            result=self.run_catalog(provider,refresh_limit=1)
        updated=json.loads((self.directory/self.current()['items'][0]['card']).read_text())
        self.assertTrue(result['changed']);self.assertEqual(updated['description'],'Новое русское описание')
        self.assertEqual(updated['sources'],card()['sources'])

    def test_index_shortens_description_but_detail_and_hash_remain_complete(self):
        full=card();full['description']='Описание фильма. '*200
        with contextlib.redirect_stdout(io.StringIO()):a.publish_cards(self.directory,self.index,[full],STAMP)
        row=self.current()['items'][0];self.assertLessEqual(len(row['description']),300)
        detail=json.loads((self.directory/row['card']).read_text());self.assertEqual(detail['description'],full['description'])
        with contextlib.redirect_stdout(io.StringIO()):b.check(self.directory)

    def test_editorial_rebuild_preserves_automatic_cards(self):
        raw=self.root/'raw';raw.mkdir()
        (raw/'tt2222222.json').write_text(json.dumps({'metadata':meta('tt2222222'),'search':{'items':[torrent()]}}))
        curated=self.directory.parent/'curated';curated.mkdir()
        (curated/'titles.json').write_text(json.dumps({'selectionNote':'test','references':[],'items':[seed('tt2222222')]}))
        with patch.object(b,'CATALOG',self.directory.parent),contextlib.redirect_stdout(io.StringIO()):b.build(raw,1)
        self.assertEqual({r['id'] for r in self.current()['items']},{'tt1111111','tt2222222'})
        self.assertGreater(self.current()['revision'],self.index['revision'])

    def test_invalid_refresh_is_skipped_independently(self):
        provider=Fake();provider.items=[];provider.rows=[torrent(suffix='b')];provider.rows[0]['title']+='x'*1300
        result=self.run_catalog(provider,refresh_limit=1)
        self.assertFalse(result['changed']);self.assertEqual(self.current(),self.index);self.assertTrue(result['warnings'])

class KinopoiskTest(unittest.TestCase):
    def raw(self):
        return {'kinopoiskId':123,'imdbId':'tt2222222','type':'TV_SERIES','nameRu':'Русский сериал','nameOriginal':'Original Show',
                'description':'Описание сериала на русском.','year':2025,'posterUrl':'https://example.com/p.jpg','genres':[{'genre':'драма'}],'countries':[{'country':'США'}]}
    def client(self):
        kp=a.Kinopoisk('private-test-value')
        def get(path):
            if 'imdbId=' in path:return {'items':[self.raw()]}
            if path.endswith('/seasons'):return {'items':[{'number':1,'episodes':[{'episodeNumber':1,'nameRu':'Начало','synopsis':'Описание первой серии','releaseDate':'2025-01-01'}]}]}
            return self.raw()
        kp.get=get;return kp
    def test_localized_card_and_episodes_without_cinemeta(self):
        kp=self.client();metadata=kp.metadata('tt2222222','series',None);localized=kp.localized('tt2222222','series')
        c=b.make_card(localized,metadata,[],kp.episode_details('tt2222222','series'),STAMP)
        self.assertEqual(c['name'],'Русский сериал');self.assertEqual(c['videos'][0]['title'],'Начало')
        self.assertEqual(c['videos'][0]['description'],'Описание первой серии');self.assertEqual(c['genres'],['Драма'])
        self.assertNotIn(b'private-test-value',b.encoded(c))
    def test_wrong_identity_or_type_not_used(self):
        kp=self.client();kp.get=lambda _: {'items':[dict(self.raw(),imdbId='tt9999999')]}
        self.assertIsNone(kp.film('tt2222222','series'))
        self.assertIsNone(self.client().film('tt2222222','movie'))
    def test_no_key_means_no_requests(self):
        with patch.object(a,'get_json') as request:
            kp=a.Kinopoisk('');self.assertIsNone(kp.film('tt2222222'));self.assertEqual(kp.collection('POPULAR_SERIES'),[])
            request.assert_not_called()
    def test_rate_limit_disables_remaining_requests_without_exposing_key(self):
        with patch.object(a,'get_json',side_effect=a.Unavailable('HTTP 429')) as request:
            kp=a.Kinopoisk('private-test-value');self.assertIsNone(kp.get('/test'));self.assertIsNone(kp.get('/test2'))
            self.assertEqual(request.call_count,1);self.assertNotIn('private-test-value',str(kp.warnings))
    def test_credential_request_does_not_follow_redirects(self):
        handler=a.NoRedirect()
        self.assertIsNone(handler.redirect_request(None,None,302,'Found',{},'https://other.example/'))

if __name__=='__main__':unittest.main()
