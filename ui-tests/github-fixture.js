// Public JSON fixture exercises production UI; no torrent is connected.
const catalogPromise=fetch('catalog/v1/index.json?v=067',{cache:'no-store'}).then(r=>r.json());
catalogPromise.then(index=>{document.getElementById('test-log').textContent='Каталог '+index.revision+' · '+index.items[0]?.name;});
let manualSearches=0;
const providers=index=>({kinopoisk:false,tmdb:false,github:{count:index.items.length,updatedAt:index.updatedAt,error:''}});
window.Native={
 async request(id,method,json){try{const args=JSON.parse(json),index=await catalogPromise;let result=[];
  const item=args.item||{},row=index.items.find(r=>r.id===(item.id||args.id));
  if(method==='bootstrap')result={movies:index.items.filter(m=>m.type==='movie'),series:index.items.filter(m=>m.type==='series'),channels:[],customPlaylist:true,bufferMb:96,preferences:{quality:'auto',language:'ru',subtitles:false}};
  if(method==='catalog-state')result=providers(index);
  if(method==='github-refresh')result={changed:false,providers:providers(index)};
  if(method==='catalog')result={items:index.items.filter(m=>args.query?m.aliases.join(' ').toLowerCase().includes(args.query.toLowerCase()):m.type===(args.type||'movie')),warnings:[],hasMore:false};
  if(method==='detail'||method==='series-detail')result={...await fetch('catalog/v1/'+row.card).then(r=>r.json()),provider:'github'};
  if(method==='catalog-sources'){const card=await fetch('catalog/v1/'+row.card).then(r=>r.json());result={items:card.sources.filter(s=>args.season==null?card.type==='movie':s.season===args.season&&(!s.episode||s.episode===args.episode)&&(!s.episodeFrom||args.episode>=s.episodeFrom&&args.episode<=s.episodeTo)).map(s=>({...s,catalogSource:true,seeds:-1})),updatedAt:index.updatedAt,warnings:[]};}
  if(method==='torrents'||method==='series-torrents'){manualSearches++;document.getElementById('test-log').textContent='Ручной поиск: '+manualSearches;result={items:[{title:'Тестовый ручной вариант S01 RUS 1080p',quality:'1080p',seeds:20,size:'2 ГБ',source:'Тест',magnet:'magnet:?xt=urn:btih:'+'a'.repeat(40)}],warnings:[]};}
  if(method==='preferences-set')result=args;
  if(method==='update-check'||method==='update-state')result={status:'current',installedVersion:'0.6.0',autoCheck:true};
  window.nativeReply(id,result,null);
 }catch(e){window.nativeReply(id,null,e.message);}},
 inputSearch(kind){window.nativeEvent('input',{kind,value:'Дюна'});},voiceSearch(kind){window.nativeEvent('voice-input',{kind,value:'Дюна'});},input(){},
 play(json){const item=JSON.parse(json);document.getElementById('test-log').textContent='Выбран: '+item.title;window.nativeEvent('toast','Тест: вариант передан в плеер');},command(){},exit(){}
};
