const {test}=require('node:test');const assert=require('node:assert/strict');const f=require('../app/src/main/assets/ui/features.js');
test('a history card opens the title page without changing playback progress or selecting an episode',()=>{
 const record={movie:{id:'tt14688458',type:'series',selectedEpisode:{season:1,episode:2}},seriesEpisode:{season:2,episode:3},position:123456,url:'magnet-a',completed:false};
 const before=JSON.stringify(record),card=f.historyCard(record);
 assert.equal(card.id,'tt14688458');assert.equal(card.browseSeason,2);assert.equal(card.selectedEpisode,null);assert.equal(JSON.stringify(record),before);
 assert.equal(f.historyCard({movie:{imdbId:'tt0120382',name:'Шоу Трумана'}}).id,'tt0120382');
 assert.equal(f.historyCard({movie:{id:'kp:326'}}).id,'kp:326');
 assert.equal(f.historyCard({movie:{id:'tmdb:series:42'}}).id,'tmdb:series:42');
});
test('raw torrent history has no invented catalogue page',()=>{
 for(const r of [undefined,{}, {movie:{id:'torrent:abc'}},{movie:{id:'tt123;bad'}}])assert.equal(f.historyCard(r),null);
});
test('the main cast precedes cameos and alphabetic order, then billing order is used',()=>{
 const cast=[{id:'extra1',name:'Александр'},{id:'support',name:'Актёр второго плана',billingOrder:4},{id:'lead',name:'Ян',originalName:'Yan Lead'},{id:'support2',name:'Сергей',billingOrder:2},{id:'extra2',name:'Борис'}];
 const before=cast.map(p=>p.id);assert.deepEqual(f.castOrder(cast,['Yan Lead']).map(p=>p.id),['lead','support2','support','extra1','extra2']);assert.deepEqual(cast.map(p=>p.id),before);
});
test('filmography sorts movies and series newest first after local metadata has been merged',()=>{
 const items=[{id:'old',year:'1998'},{id:'new-series',type:'series',year:'2026',released:'2026-10-01'},{id:'same-year',year:'2026',released:'2026-02-01'},{id:'unknown'},{id:'range',releaseInfo:'2018–2020'},{id:'local-year',year:'2025',released:'1994-01-01'}];
 assert.deepEqual(f.filmographyNewest(items).map(v=>v.id),['new-series','same-year','local-year','range','old','unknown']);assert.equal(items[0].id,'old');
 assert.deepEqual(f.filmographyNewest([{id:'z',year:2020},{id:'a',year:2020}]).map(v=>v.id),['z','a']);
});
