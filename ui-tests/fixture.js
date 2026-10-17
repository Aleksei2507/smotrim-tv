// Local, deterministic bridge substitute. This file is never bundled into the APK.
const hash='0123456789abcdef0123456789abcdef01234567';
const magnet='magnet:?xt=urn:btih:'+hash;
const fixtureData=Promise.all([fetch('movies.json').then(r=>r.json()),fetch('preview-channels.json').then(r=>r.json())]);
window.Native={
 async request(id,method,json){
  const args=JSON.parse(json),[data,channels]=await fixtureData;let result;
  if(method==='bootstrap')result={movies:data.metas,channels,customPlaylist:true,freeBytes:0,bufferMb:Number(localStorage.getItem("fixtureBuffer")||96)};
  else if(method==='buffer-set'){localStorage.setItem('fixtureBuffer',args.mb);result=args.mb;}
  else if(method==='catalog')result=data.metas;
  else if(method==='detail')result=data.metas.find(m=>m.id===args.id);
  else if(method==='storage')result=0;
  else if(method==='torrents')result={items:[{title:args.name+' · тестовая раздача',quality:'1080p',seeds:10,size:'2 ГБ',source:'Локальная проверка',magnet}],warnings:[]};
  else result=[];
  window.nativeReply(id,result,null);
 },
 play(){setTimeout(()=>window.nativeEvent('playback',{playing:false,loading:false,position:42000,duration:120000,buffered:50000,peers:1,rate:0}),0);},
 command(cmd){if(cmd==='stop')setTimeout(()=>{window.nativeEvent('position',42000);window.nativeEvent('stopped',true);},0);},
 inputSearch(kind){setTimeout(()=>window.nativeEvent('input',{kind,value:kind==='search-channel'?'Пятница':'Король Лев (1994), мультфильм, WEB-DL 2160p, HDR10, Dolby Vision [Hybrid] Dub + DVO + 7x AVO + Original Eng + Sub (Rus, Ukr, Eng)'}),0);},
 voiceSearch(kind){setTimeout(()=>window.nativeEvent('voice-input',{kind,value:kind==='search-channel'?'Пятница':'Выживалити'}),0);},
 trailer(){window.nativeEvent('toast','Тест: внешний YouTube не запускается');},
 exit(){},input(){}
};
document.getElementById('simulate-error').addEventListener('click',()=>window.nativeEvent('error','Тестовая ошибка источника'));
