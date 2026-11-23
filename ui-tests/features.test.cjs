const {test}=require('node:test');const assert=require('node:assert/strict');const f=require('../app/src/main/assets/ui/features.js');
test('loading a resumed file does not erase its known duration',()=>{
 const item={url:'magnet-a',fileIndex:7},history={...item,duration:7980000};
 assert.equal(f.playbackDuration(item,{duration:0},{duration:7980000},history),7980000);
 assert.equal(f.playbackDuration(item,{loading:true},undefined,history),7980000);
 assert.equal(f.playbackDuration(item,{duration:7961000},{duration:7980000},history),7961000);
 for(const duration of [0,-1,NaN,Infinity,'7980000']){
  assert.equal(f.playbackDuration(item,{duration},{duration:7980000},history),7980000);
 }
});
test('duration is not copied from another torrent or another file in a season pack',()=>{
 const item={url:'magnet-a',fileIndex:7};
 assert.equal(f.playbackDuration(item,{duration:0},undefined,{url:'magnet-b',fileIndex:7,duration:7980000}),0);
 assert.equal(f.playbackDuration(item,{duration:0},undefined,{url:'magnet-a',fileIndex:8,duration:7980000}),0);
 assert.equal(f.playbackDuration(item,{duration:0},undefined,undefined),0);
});
const introQuery={imdbId:'tt31510819',season:2,episode:7};
const introResponse={imdb_id:'tt31510819',media_type:'tv',season:2,episode:7,intro:{start_ms:240039,end_ms:300507,confidence:1,submission_count:2}};
test('segment lookup requires exact identity and episode numbers, never a title guess or a live channel',()=>{
 const item={kind:'torrent',movie:{id:'kp:5512084',imdbId:'tt31510819',type:'series'},seriesEpisode:{season:2,episode:7}};
 assert.deepEqual(f.segmentQuery(item),introQuery);
 assert.equal(f.segmentQuery({...item,kind:'live'}),null);
 assert.equal(f.segmentQuery({...item,movie:{id:'kp:5512084',type:'series'}}),null);
 assert.equal(f.segmentQuery({...item,seriesEpisode:{season:2}}),null);
 assert.equal(f.segmentQuery({...item,seriesEpisode:{season:2,episode:'7'}}),null);
 assert.deepEqual(f.segmentQuery({...item,movie:{id:'tt31510819',type:'movie'}}),{imdbId:'tt31510819',isMovie:true});
});
test('intro data must belong to the current episode and have a high-confidence submitted timestamp',()=>{
 assert.deepEqual(f.introSegment(introResponse,introQuery),{start:240039,end:300507});
 for(const change of [{imdb_id:'tt0903747'},{season:1},{episode:8},{media_type:'movie'},{no_intro:true},{intro:null}]){
  assert.equal(f.introSegment({...introResponse,...change},introQuery),null);
 }
 for(const change of [{start_ms:-1},{end_ms:240038},{end_ms:Infinity},{end_ms:600040},{start_ms:'240039'},{confidence:.8},{confidence:'1'},{confidence:2},{submission_count:0},{submission_count:-1},{submission_count:1.5},{submission_count:'1'}]){
  assert.equal(f.introSegment({...introResponse,intro:{...introResponse.intro,...change}},introQuery),null);
 }
});
test('one high-confidence live submission works for the observed I Will Find You and MobLand timestamps',()=>{
 for(const [imdbId,start,end] of [['tt34771210',81379,171379],['tt31510819',340000,400000]]){
  const query={imdbId,season:1,episode:1},data={imdb_id:imdbId,media_type:'tv',season:1,episode:1,intro:{start_ms:start,end_ms:end,confidence:1,submission_count:1}};
  assert.deepEqual(f.introSegment(data,query),{start,end});
  assert.equal(f.introTarget(f.introSegment(data,query),{position:start,duration:3000000,loading:false}),end);
  assert.equal(f.introSegment({...data,intro:{...data.intro,confidence:.89}},query),null);
 }
});
test('confirmed movie intros use exact movie identity and never accept a series response',()=>{
 const query={imdbId:'tt4154756',isMovie:true},data={imdb_id:query.imdbId,media_type:'movie',is_movie:true,intro:introResponse.intro};
 assert.deepEqual(f.introSegment(data,query),{start:240039,end:300507});
 assert.equal(f.introSegment({...data,media_type:'tv'},query),null);
 assert.equal(f.introSegment({...data,imdb_id:'tt2250912'},query),null);
 assert.equal(f.introSegment({...data,intro:null},query),null);
});
test('intro skip appears only inside the intro of a loaded file with a valid duration',()=>{
 const segment=f.introSegment(introResponse,introQuery),state={position:240039,duration:3000000,loading:false};
 assert.equal(f.introTarget(segment,state),300507);
 assert.equal(f.introTarget(segment,{...state,playing:false,playWhenReady:false}),300507);
 for(const change of [{position:240038},{position:300507},{position:NaN},{duration:0},{duration:300507},{loading:true}]){
  assert.equal(f.introTarget(segment,{...state,...change}),null);
 }
 assert.equal(f.introTarget(null,state),null);
});
test('credits mark an episode complete at credits start only when its file duration matches',()=>{
 const response={...introResponse,outro:{start_ms:3203100,end_ms:3283100,confidence:1,submission_count:1}};
 const credits=f.creditsSegment(response,introQuery),state={duration:3283100,position:3203100,loading:false};
 assert.equal(f.creditsReached(credits,state),true);
 assert.equal(f.creditsReached(credits,{...state,position:3203099}),false);
 assert.equal(f.creditsReached(credits,{...state,duration:3600000}),false);
 assert.equal(f.creditsReached(credits,{...state,loading:true}),false);
 assert.equal(f.creditsSegment({...response,episode:8},introQuery),null);
 assert.equal(f.creditsSegment({...response,no_outro:true},introQuery),null);
});
test('movie credits allow a final post-credits scene without waiting for it to finish',()=>{
 const query={imdbId:'tt0110357',isMovie:true};
 const data={imdb_id:query.imdbId,media_type:'movie',is_movie:true,outro:{start_ms:5000000,end_ms:5200000,confidence:1,submission_count:2},post_credits:{start_ms:5200000,end_ms:5300000,confidence:1,submission_count:2}};
 assert.equal(f.introSegment(data,query),null);
 assert.equal(f.creditsReached(f.creditsSegment(data,query),{duration:5300000,position:5000000}),true);
 assert.equal(f.creditsSegment({...data,imdb_id:'tt0903747'},query),null);
});
test('without credits data completion requires 98 percent watched and at most two minutes left',()=>{
 assert.equal(f.creditsReached(null,{duration:6000000,position:5880000}),true);
 assert.equal(f.creditsReached(null,{duration:6000000,position:5879999}),false);
 assert.equal(f.creditsReached(null,{duration:12000000,position:11760000}),false);
 assert.equal(f.creditsReached(null,{duration:12000000,position:11880000}),true);
 assert.equal(f.creditsReached(null,{duration:0,position:0}),false);
 assert.equal(f.creditsReached(null,{duration:Infinity,position:1234}),false);
});
test('changing source preserves episode, identity and current position but discards old file indexes',()=>{
 const item={url:'old',fileIndex:17,files:[{index:17}],movie:{id:'show',type:'series'},sourceMovie:{id:'show'},seriesEpisode:{season:5,episode:7},historyKey:'show:5:7',position:1000,completed:true};
 const next=f.switchSource(item,{magnet:'new'},{position:842000,playing:false,playWhenReady:true});
 assert.equal(next.url,'new');assert.equal(next.position,842000);assert.equal(next.paused,false);assert.equal(next.fileIndex,undefined);assert.deepEqual(next.files,[]);
 assert.deepEqual(next.seriesEpisode,item.seriesEpisode);assert.equal(next.movie,item.movie);assert.equal(next.sourceMovie,item.sourceMovie);assert.equal(next.historyKey,item.historyKey);assert.equal(next.completed,false);
 assert.equal(item.fileIndex,17);assert.equal(item.url,'old');
});
test('changing source preserves a manual pause and allows resume from zero',()=>{
 assert.equal(f.switchSource({position:9000},{magnet:'new'},{position:0,playWhenReady:false}).paused,true);
 assert.equal(f.switchSource({position:9000},{magnet:'new'},{position:0,playWhenReady:false}).position,0);
 assert.equal(f.switchSource({position:9000,paused:true},{magnet:'new'},{}).position,9000);
 assert.equal(f.switchSource({paused:true},{magnet:'new'},{}).paused,true);
});
test('source sizes from Dune use byte values rather than raw display strings',()=>{
 for(const [bytes,label] of [[21582210662,'20,10 ГБ'],[28808493137,'26,83 ГБ'],[89947352596,'83,77 ГБ']])
  assert.equal(f.sourceSize({bytes,size:bytes+' bytes'}),label);
 assert.equal(f.sourceSize({bytes:734003200,size:'wrong'}),'700,00 МБ');
});
test('source size accepts older byte strings and existing formatted sizes',()=>{
 assert.equal(f.sourceSize({size:'21582210662 bytes'}),'20,10 ГБ');
 assert.equal(f.sourceSize({size:1073741824}),'1,00 ГБ');
 assert.equal(f.sourceSize({size:'700 MB'}),'700 МБ');
 assert.equal(f.sourceSize({size:'2.50 GiB'}),'2,50 ГБ');
 assert.equal(f.sourceSize({size:'2,50 ГБ'}),'2,50 ГБ');
});
test('invalid source sizes never display NaN or negative file sizes',()=>{
 for(const s of [{},{bytes:0},{bytes:-1},{bytes:Infinity},{size:'NaN bytes'},{size:'-4 GB'}])
  assert.equal(f.sourceSize(s),'Размер неизвестен');
});
test('season pack shows its total until the requested episode file is identified',()=>{
 const gib=1024**3,show={id:'show',type:'series'},target={season:2,episode:3};
 const hash='ab'.repeat(20),url='magnet:?xt=urn:btih:'+hash;
 const source={magnet:url+'&tr=new',bytes:88*gib};
 const record={movie:show,url:'magnet:?tr=old&xt=urn:btih:'+hash.toUpperCase(),torrentSeason:2,
  seriesEpisode:{season:2,episode:2},fileIndex:0,files:[
   {index:0,name:'02.mkv',bytes:7*gib},{index:4,name:'03.mkv',bytes:8*gib}]};
 const label=(records,episode=target)=>f.sourceSizeLabels({...show,selectedEpisode:episode},records)(source);
 assert.equal(label([]),'Вся раздача 88,00 ГБ');
 assert.equal(label([record]),'Серия 8,00 ГБ · Вся раздача 88,00 ГБ');
 assert.equal(label([record],{season:1,episode:3}),'Вся раздача 88,00 ГБ');
 const ambiguous={...record,files:[...record.files,{index:5,name:'03-alt.mkv',bytes:5*gib}]};
 assert.equal(label([ambiguous]),'Вся раздача 88,00 ГБ');
 assert.equal(label([{...ambiguous,seriesEpisode:target,fileIndex:5}]),'Серия 5,00 ГБ · Вся раздача 88,00 ГБ');
 assert.equal(label([{...record,movie:{id:'different',type:'series'}}]),'Вся раздача 88,00 ГБ');
 assert.equal(label([{...record,url:'another torrent'}]),'Вся раздача 88,00 ГБ');
});
test('multi-film torrents show the selected movie file, including legacy size-only rows',()=>{
 const gib=1024**3,movie={id:'film-2'},source={magnet:'bundle',size:'88 GB'};
 const files=[{index:6,name:'Part I.mkv',bytes:20*gib},{index:0,name:'Part II.mkv',bytes:8*gib}];
 const other={movie:{id:'film-1'},url:'bundle',fileIndex:6,files},record={movie,url:'bundle',fileIndex:0,files};
 const label=(records,s=source)=>f.sourceSizeLabels(movie,records)(s);
 assert.equal(label([other]),'Вся раздача 88 ГБ');
 assert.equal(label([other,record]),'Файл 8,00 ГБ · Вся раздача 88 ГБ');
 assert.equal(label([{...record,fileIndex:undefined}]),'Вся раздача 88 ГБ');
 assert.equal(label([{...record,files:[{index:0,bytes:0}]}]),'Вся раздача 88 ГБ');
 assert.equal(label([record],{magnet:'bundle',bytes:8*gib}),'Файл 8,00 ГБ');
 assert.equal(label([record],{magnet:'bundle'}),'Файл 8,00 ГБ');
 assert.equal(label([],{}),'Размер раздачи неизвестен');
});
test('episode conventions, folders and numeric order',()=>{
 assert.equal(f.episode({name:'Show.S02E10.mkv'}).season,2);assert.equal(f.episode({name:'Show.2x03.mkv'}).episode,3);
 assert.equal(f.episode({name:'01.mkv',path:'Show/Сезон 3/01.mkv'}).season,3);
 assert.equal(f.episode({name:'Фильм.2025.1080p.mkv'}).episode,null);
 assert.deepEqual(f.files([{name:'Show.S01E10.mkv'},{name:'Show.S01E02.mkv'}]).map(x=>x.episode),[2,10]);
});
test('next episode skips extras and duplicate versions',()=>{
 const files=[{index:1,name:'Show.S01E01.mkv'},{index:2,name:'Show.S01E01.4k.mkv'},{index:3,name:'Show.S02E01.mkv'},{index:4,name:'extra.mkv'}];
 assert.equal(f.nextFile(files,1).index,3);assert.equal(f.nextFile(files,3),null);assert.equal(f.nextFile(files,4),null);
 assert.equal(f.seriesId({url:'a',movie:{}},f.episode(files[0])),f.seriesId({url:'b',movie:{}},f.episode(files[2])));
});
test('zero seeds last, preferred quality then language',()=>{
 const list=[{title:'1080p rus',seeds:20},{title:'2160p rus',seeds:0},{title:'2160p rus',seeds:3}];
 assert.deepEqual(f.rankedSources(list,{quality:'2160',language:'ru'}),[list[2],list[0],list[1]]);
});
test('channel order survives URL updates and puts new channels last',()=>{
 const channels=[{id:'b',url:'new-b'},{id:'a',url:'new-a'},{id:'c',url:'c'}];
 assert.deepEqual(f.orderedChannels(channels,['a','b']).map(c=>c.id),['a','b','c']);
 const moved=f.moveItem(['a','b','c'],0,2);assert.deepEqual(moved,['b','c','a']);assert.deepEqual(f.moveItem(moved,2,0),['a','b','c']);
});
test('seeking clamps boundaries and accelerates held key',()=>{
 assert.equal(f.seekTarget(5000,100000,-1,0),0);assert.equal(f.seekTarget(0,100000,1,0),10000);assert.equal(f.seekTarget(0,100000,1,6),30000);assert.equal(f.seekTarget(0,100000,1,15),99000);
});
test('diagnostics distinguishes cache and playable reserve',()=>{
 assert.match(f.diagnosis({buffered:60000,position:20000,cached:9999999,playing:true}),/40 сек/);
 assert.match(f.diagnosis({loading:true,rate:0,peers:0}),/Нет активной загрузки/);
 assert.match(f.diagnosis({loading:true,rate:10,peers:2}),/Видео подгружается/);
});
test('full torrent cache does not inflate playable reserve',()=>{
 const p={loading:true,preparing:false,playWhenReady:true,position:42000,buffered:42000,cached:134217728,cacheLimit:134217728,peers:1,rate:314573};
 assert.equal(f.bufferAhead(p),0);assert.match(f.diagnosis(p),/Запас: 0 сек/);assert.match(f.diagnosis(p),/Заполненный кэш не означает/);
 assert.equal(f.bufferingLabel(p,'Подключаемся к раздающим…'),'Подгружаем следующий фрагмент…');
});
test('user pause differs from automatic buffering',()=>{
 const p={playWhenReady:false,playing:false,loading:true,preparing:false,position:5000,buffered:25000};
 assert.equal(f.bufferingLabel(p,''),'Видео на паузе');assert.match(f.diagnosis(p),/^Видео на паузе\. Запас: 20 сек/);
});
test('initial connection and seek with stale buffered position are handled',()=>{
 assert.equal(f.bufferingLabel({preparing:true},'Открываем видео…'),'Открываем видео…');
 assert.equal(f.bufferAhead({position:90000,buffered:50000}),0);
});
