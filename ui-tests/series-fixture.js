// Deterministic, isolated browser fixture. Never included in Android assets.
const fixtureSeries={id:'tt1234567',type:'series',kind:'show',origin:'ru',localized:true,poster:'https://images.metahub.space/poster/medium/tt22084616/img',name:'Тестовый сериал с длинным названием',releaseInfo:'2024–2026',genres:['Drama'],description:'Тестовый каталог: сезоны не зависят от выбранной раздачи. Названия серий бывают длинными — проверяем читаемость и управление с пульта.',videos:[{season:0,episode:1,title:'Специальный выпуск',released:'2024-01-01'},...Array.from({length:12},(_,season)=>Array.from({length:season<2?12:1},(_,episode)=>({season:season+1,episode:episode+1,description:'Краткое содержание серии: участники знакомятся с новым испытанием и выбирают, как действовать дальше.',title:episode===0?'Начало большого путешествия и встреча, которая меняет всё':'Продолжение истории',released:season>=2?'2030-11-12T00:00:00Z':'2024-06-01T00:00:00Z'}))).flat()]};
const fixtureData=fetch('movies.json').then(r=>r.json());
const fixtureChannels=[
 {id:'Friday.ru',name:'Пятница! (1080p)',group:'Entertainment',url:'https://example.test/friday.m3u8',logo:''},
 {id:'NTV.ru',name:'НТВ (1080p)',group:'General',url:'https://example.test/ntv.m3u8',logo:''}
];
let fixtureMode='results',fixtureUpdate={status:'current',installedVersion:'0.5.0',autoCheck:true,repository:'Aleksei2507/smotrim-tv'};
document.querySelectorAll('[data-fixture]').forEach(b=>b.addEventListener('click',()=>{fixtureMode=b.dataset.fixture;}));
window.Native={
 async request(id,method,json){
  const args=JSON.parse(json),data=await fixtureData;let result;
  if(method==='bootstrap')result={movies:data.metas,series:[fixtureSeries],channels:fixtureChannels,customPlaylist:true,freeBytes:0,bufferMb:96,update:fixtureUpdate};
  else if(method==='catalog-state')result={kinopoisk:true,tmdb:true};
  else if(method==='catalog'){if(fixtureMode==='error'){window.nativeReply(id,null,'Catalogue unavailable');return;}result={items:fixtureMode==='empty'?[]:args.query?[fixtureSeries,...data.metas.slice(0,9)]:args.type==='series'?[fixtureSeries]:data.metas,hasMore:args.page!==2,warnings:[]};}
  else if(method==='series-detail'){if(fixtureMode==='error'){window.nativeReply(id,null,'Каталог недоступен');return;}result=fixtureSeries;}
  else if(method==='detail')result=data.metas.find(m=>m.id===args.id);
  else if(method==='preferences-set')result=args;
  else if(method==='epg')result={channels:Object.fromEntries(fixtureChannels.map(c=>[c.url,{current:{title:'Сейчас: передача',start:Date.now()-600000,end:Date.now()+1200000},next:{title:'Следующая передача',start:Date.now()+1200000,end:Date.now()+3600000},programs:[]}]))};
  else if(method==='memory-info')result={totalMb:2048,availableMb:420,playerTargetMb:24,lowMemory:false,exitTime:Date.now(),exitReason:'Android завершил приложение из-за нехватки оперативной памяти'};
  else if(method==='storage')result=0;
  else if(method==='buffer-set')result=args.mb;
  else if(method==='update-state'||method==='update-check')result=fixtureUpdate;
  else if(method==='update-auto')result=fixtureUpdate={...fixtureUpdate,autoCheck:args.enabled};
  else if((method==='torrents'||method==='series-torrents')){
   if(fixtureMode==='error'){window.nativeReply(id,null,'Источник не ответил. Проверьте подключение к интернету.');return;}
   result={items:fixtureMode==='empty'?[]:Array.from({length:12},(_,i)=>({
    title:args.name+' · Сезон '+(args.season||2)+', серии 1–12 · '+(i?'1080p':'2160p HDR10 Dolby Vision')+' · Русская озвучка, оригинальный звук и субтитры · Проверка длинного названия',
    quality:i?'1080p':'4K',seeds:i===11?0:120-i*10,size:i?'4 ГБ':'20 ГБ',source:'Тестовый источник',
    magnet:'magnet:?xt=urn:btih:'+String(i+1).padStart(40,'0')
   })),warnings:[]};
  }else result=[];
  setTimeout(()=>window.nativeReply(id,result,null),(method==='torrents'||method==='series-torrents')?300:0);
 },
 play(json){const item=JSON.parse(json);document.getElementById('test-log').textContent='play '+(item.seriesEpisode?JSON.stringify(item.seriesEpisode):'');fixtureItem=item;fixturePosition=item.position||42000;setTimeout(()=>{if(item.kind==='torrent'){window.nativeEvent('files',fixtureFiles);if(item.fileIndex===undefined||item.seriesEpisode)window.nativeEvent('choose-files',fixtureFiles);else{window.nativeEvent('selectedFile',item.fileIndex);fixturePlayback();}}else fixturePlayback();},0);},
 command(cmd){document.getElementById('test-log').textContent=cmd;if(cmd==='stop')setTimeout(()=>{window.nativeEvent('position',fixturePosition);window.nativeEvent('stopped',true);},0);else if(cmd.startsWith('file:')){fixtureItem.fileIndex=+cmd.split(':')[1];if(cmd.split(':').length>2)fixturePosition=+cmd.split(':')[2];setTimeout(()=>{window.nativeEvent('selectedFile',fixtureItem.fileIndex);fixturePlayback();},0);}else if(cmd==='forward'||cmd==='rewind'){fixturePosition=Math.max(0,fixturePosition+(cmd==='forward'?10000:-10000));fixturePlayback();}else if(cmd.startsWith('seek:')){fixturePosition=+cmd.slice(5);fixturePlayback();}else if(cmd.startsWith('sleep:'))window.nativeEvent('sleep',{episode:cmd==='sleep:-1',remaining:+cmd.slice(6)*60000});},
 inputSearch(kind){setTimeout(()=>window.nativeEvent('input',{kind,value:kind==='search-channel'?'Пятница':'Выживалити 2 сезон'}),0);},
 voiceSearch(kind){setTimeout(()=>window.nativeEvent('voice-input',{kind,value:kind==='search-channel'?'Пятница':'Выживалити'}),0);},
 trailer(){window.nativeEvent('toast','Тест: внешний YouTube не запускается');},
 exit(){},input(){},installUpdate(){}
};
document.getElementById('simulate-error').addEventListener('click',()=>window.nativeEvent('error','Не удалось воспроизвести: ERROR_CODE_IO_BAD_HTTP_STATUS. Попробуйте другую раздачу.'));

let fixtureItem,fixturePosition=42000;
const fixtureFiles=[{index:1,name:'Шоу.S01E01.mkv',bytes:1073741824},{index:2,name:'Шоу.S01E02.mkv',bytes:1073741824},{index:3,name:'Шоу.S02E01.mkv',bytes:1073741824},{index:4,name:'Дополнительный материал.mkv',bytes:100000}];
function fixturePlayback(){window.nativeEvent('playback',{playing:false,loading:false,position:fixturePosition,duration:1800000,buffered:fixturePosition+120000,peers:8,rate:2097152,cached:32000000,cacheLimit:100663296});}
document.getElementById('finish').onclick=()=>window.nativeEvent('finished',{sleep:false});
document.getElementById('channel-up').onclick=()=>window.tvKey('ChannelUp');
document.getElementById('channel-back').onclick=()=>window.tvKey('LastChannel');
document.getElementById('simulate-stall').onclick=()=>window.nativeEvent('playback',{playing:false,playWhenReady:true,preparing:false,loading:true,position:fixturePosition,duration:1800000,buffered:fixturePosition,peers:1,rate:314573,cached:134217728,cacheLimit:134217728});
