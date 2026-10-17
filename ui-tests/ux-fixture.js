// Deterministic, isolated browser fixture. Never included in Android assets.
const fixtureData=fetch('movies.json').then(r=>r.json());
const fixtureChannels=[
 {id:'Friday.ru',name:'Пятница! (1080p)',group:'Entertainment',url:'https://example.test/friday.m3u8',logo:''},
 {id:'NTV.ru',name:'НТВ (1080p)',group:'General',url:'https://example.test/ntv.m3u8',logo:''}
];
let fixtureMode='results',fixtureUpdate={status:'current',installedVersion:'0.2.4',autoCheck:true,repository:'Aleksei2507/smotrim-tv'};
document.querySelectorAll('[data-fixture]').forEach(b=>b.addEventListener('click',()=>{fixtureMode=b.dataset.fixture;}));
window.Native={
 async request(id,method,json){
  const args=JSON.parse(json),data=await fixtureData;let result;
  if(method==='bootstrap')result={movies:data.metas,channels:fixtureChannels,customPlaylist:true,freeBytes:0,bufferMb:96,update:fixtureUpdate};
  else if(method==='catalog')result=data.metas;
  else if(method==='detail')result=data.metas.find(m=>m.id===args.id);
  else if(method==='storage')result=0;
  else if(method==='buffer-set')result=args.mb;
  else if(method==='update-state'||method==='update-check')result=fixtureUpdate;
  else if(method==='update-auto')result=fixtureUpdate={...fixtureUpdate,autoCheck:args.enabled};
  else if(method==='torrents'){
   if(fixtureMode==='error'){window.nativeReply(id,null,'Источник не ответил. Проверьте подключение к интернету.');return;}
   result={items:fixtureMode==='empty'?[]:Array.from({length:12},(_,i)=>({
    title:args.name+' · Сезон 2, серии 1–12 · '+(i?'1080p':'2160p HDR10 Dolby Vision')+' · Русская озвучка, оригинальный звук и субтитры · Проверка длинного названия',
    quality:i?'1080p':'4K',seeds:i===11?0:120-i*10,size:i?'4 ГБ':'20 ГБ',source:'Тестовый источник',
    magnet:'magnet:?xt=urn:btih:'+String(i+1).padStart(40,'0')
   })),warnings:[]};
  }else result=[];
  setTimeout(()=>window.nativeReply(id,result,null),method==='torrents'?300:0);
 },
 play(){setTimeout(()=>window.nativeEvent('playback',{playing:false,loading:false,position:42000,duration:120000,buffered:50000,peers:1,rate:1048576}),0);},
 command(cmd){if(cmd==='stop')setTimeout(()=>{window.nativeEvent('position',42000);window.nativeEvent('stopped',true);},0);},
 inputSearch(kind){setTimeout(()=>window.nativeEvent('input',{kind,value:kind==='search-channel'?'Пятница':'Выживалити 2 сезон'}),0);},
 voiceSearch(kind){setTimeout(()=>window.nativeEvent('voice-input',{kind,value:kind==='search-channel'?'Пятница':'Выживалити'}),0);},
 trailer(){window.nativeEvent('toast','Тест: внешний YouTube не запускается');},
 exit(){},input(){},installUpdate(){}
};
document.getElementById('simulate-error').addEventListener('click',()=>window.nativeEvent('error','Не удалось воспроизвести: ERROR_CODE_IO_BAD_HTTP_STATUS. Попробуйте другую раздачу.'));
