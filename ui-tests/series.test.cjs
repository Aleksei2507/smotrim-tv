const test=require('node:test'),assert=require('node:assert/strict'),F=require('../app/src/main/assets/ui/features.js');
test('metadata episodes sorted, deduplicated, zero season preserved',()=>{const list=F.catalogueEpisodes({videos:[{season:2,episode:1},{season:1,episode:2},{season:1,episode:1},{season:0,episode:1},{season:1,episode:1},{season:1,episode:0}]});assert.deepEqual(list.map(e=>[e.season,e.episode]),[[0,1],[1,1],[1,2],[2,1]]);});
test('future episodes are not offered as the next episode',()=>{const m={videos:[{season:1,episode:1},{season:1,episode:2,released:'2030-01-01T00:00:00Z'}]};assert.equal(F.nextCatalogueEpisode(m,{season:1,episode:1},Date.parse('2026-10-17')),null);assert.equal(F.nextCatalogueEpisode(m,{season:1,episode:1},Date.parse('2031-01-01')).episode,2);});
test('source filtering accepts target episodes and season packs, excludes other seasons',()=>{const t={season:2,episode:3};for(const text of ['Show S02E03','Show 2x03'])assert.equal(F.sourceScope(text,t),0,text);for(const text of ['Show S02','Show S01-S03','Шоу Сезон: 2 / Серии: 1-10','Шоу 1-3 сезоны','Шоу S02E01-E06'])assert.equal(F.sourceScope(text,t),1,text);for(const text of ['Show S01E03','Show S02E04','Шоу 3 сезон','Выживалити. Миссия Альфа (3 сезон: 1-11 выпуски из 11)','Шоу Сезон: 2 / Серии: 4-8'])assert.equal(F.sourceScope(text,t),3,text);assert.equal(F.sourceScope('Unknown 1080p',t),2);});
test('ambiguous files remain ambiguous and wrong episodes never auto match',()=>{const rows=[{name:'Show.S02E03.mkv',index:1},{name:'Show.S02E04.mkv',index:2},{name:'Show.S02E03.alternative.mkv',index:3},{name:'trailer.mkv',index:4}];assert.deepEqual(F.matchingFiles(rows,{season:2,episode:3}).map(f=>f.index),[3,1]);assert.equal(F.matchingFiles(rows,{season:3,episode:3}).length,0);});
test('series identity survives title and torrent changes',()=>{assert.equal(F.seriesId({movie:{id:'tt1234567',type:'series'},url:'a'},{episode:1,seriesName:'English'}),F.seriesId({movie:{id:'tt1234567',type:'series'},url:'b'},{episode:2,seriesName:'Русский'}));});
test('known episode and seeded season outrank unknown and dead exact source',()=>{const out=F.episodeSources([{title:'Unknown',seeds:10},{title:'Show S02',seeds:5},{title:'Show S02E03',seeds:0},{title:'Show S01E03',seeds:99}],{season:2,episode:3},{quality:'auto',language:'auto'});assert.deepEqual(out.map(s=>s.title),['Show S02','Unknown','Show S02E03']);});
test('readability values are bounded and preserve independent options',()=>{assert.deepEqual(F.readability({size:'huge',contrast:'true',motion:1}),{size:'normal',contrast:false,motion:false});assert.deepEqual(F.readability({size:'largest',contrast:true,motion:true}),{size:'largest',contrast:true,motion:true});});
test('comma-separated season packs stay visible and sparse episodes retain gaps',()=>{
 const pack='Вспыш и чудо-машинки / Сезон: 1, 2, 3, 4, 5, 6, 7 / Серии: 1-152 из 152';
 assert.equal(F.sourceScope(pack,{season:2,episode:3}),1);
 assert.equal(F.sourceScope(pack,{season:8,episode:3}),3);
 assert.equal(F.sourceScope('Шоу / Сезон: 1, 3 / Серии: 1-20',{season:2,episode:3}),3);
 const sparse='Вспыш / Сезон: 6 / Серии: 1-12, 16-17 из 20';
 assert.equal(F.sourceScope(sparse,{season:6,episode:16}),1);
 assert.equal(F.sourceScope(sparse,{season:6,episode:14}),3);
});
test('empty search distinguishes outages and partial results without a provider-count magic number',()=>{
 for(const count of [1,2,3,4,5])assert.match(F.sourceSearchError({items:[],warnings:Array(count).fill('offline'),successfulProviders:0},0),/недоступны/);
 assert.match(F.sourceSearchError({items:[],warnings:['offline'],successfulProviders:1},0),/Часть источников/);
 assert.equal(F.sourceSearchError({items:[],warnings:[],successfulProviders:4},0),'');
 assert.equal(F.sourceSearchError({items:[{title:'found'}],warnings:['offline'],successfulProviders:1},1),'');
 assert.equal(F.sourceSearchError({items:[{title:'wrong episode'}],warnings:['offline'],successfulProviders:1},0),'');
});
