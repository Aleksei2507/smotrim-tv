// Isolated UI regression fixture. No external requests or video playback.
const languageSeries={id:'tt10466872',imdbId:'tt10466872',type:'series',name:'Dune: Prophecy',year:'2024',description:'An English description from the catalogue. This is test data.',videos:[1,2,3].map(episode=>({season:1,episode,title:'English episode '+episode,description:'An English episode description for the UI test.',released:'2024-11-18'}))};
window.Native={
 request(id,method,json){const args=JSON.parse(json);let result=[];
  if(method==='bootstrap')result={movies:[],series:[languageSeries],channels:[],customPlaylist:true,bufferMb:96,preferences:{quality:'auto',language:'ru',subtitles:false}};
  if(method==='catalog-state')result={kinopoisk:false,tmdb:false};
  if(method==='catalog')result={items:args.query||args.type==='series'?[languageSeries]:[],warnings:[],hasMore:false};
  if(method==='series-detail')result=languageSeries;
  if(method==='preferences-set')result=args;
  if(method==='torrents'||method==='series-torrents'){
   document.getElementById('test-log').textContent=JSON.stringify({name:args.name,searchHint:args.searchHint,season:args.season,episode:args.episode});
   result={items:[{title:'Dune Prophecy S01E03 ENG',seeds:1000,quality:'4K',size:'3 GB',source:'Тест ENG'},{title:'Дюна Пророчество S01 серии 1-6 MVO',seeds:10,quality:'1080p',size:'8 GB',source:'Тест RUS'},{title:'Dune Prophecy S01E03 SuccessfulCrab',seeds:300,quality:'1080p',size:'3 GB',source:'Тест неизвестного языка'}].map((v,i)=>({...v,magnet:'magnet:?xt=urn:btih:'+String(i+1).padStart(40,'0')})),warnings:[]};
  }
  if(method==='update-check'||method==='update-state')result={status:'current',installedVersion:'0.5.2',autoCheck:true};
  setTimeout(()=>window.nativeReply(id,result,null),0);
 },
 inputSearch(kind){window.nativeEvent('input',{kind,value:'ДЮНА ПРОРОЧЕСТВО'});},voiceSearch(kind){window.nativeEvent('voice-input',{kind,value:'ДЮНА ПРОРОЧЕСТВО'});},input(){window.nativeEvent('toast','Тест: ключи не вводятся');},play(){window.nativeEvent('toast','Тест: видео не запускается');},command(){},exit(){}
};
