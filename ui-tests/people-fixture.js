// Remote-control navigation and delayed replies, isolated from TV accounts and network providers.
const truman={id:'tt0120382',imdbId:'tt0120382',type:'movie',name:'Шоу Трумана',year:'1998',cast:['Jim Carrey','Ed Harris','Laura Linney'],trailers:[{type:'Trailer',source:'N1VlDVRiFrk'},{type:'Trailer',source:'loTIzXAS7v4'}],description:'История человека, который обнаруживает, что вся его жизнь — телевизионное шоу.'};
const jim={id:'Q40504',name:'Джим Керри',originalName:'Jim Carrey',photo:'https://commons.wikimedia.org/wiki/Special:FilePath/Jim%20Carrey%202020%20cropped.jpg?width=320'};
const cast=[jim,{id:'Q104791',name:'Эд Харрис',originalName:'Ed Harris'},{id:'Q229205',name:'Лора Линни',originalName:'Laura Linney'},{id:'Q229171',name:'Наташа Макэлхоун',originalName:'Natascha McElhone'}];
const filmography=[['tt0338013','Вечное сияние чистого разума',2004],['tt0110475','Маска',1994],['tt0315327','Брюс всемогущий',2003],['tt1068680','Всегда говори «Да»',2008],['tt0119528','Лжец, лжец',1997],['tt0109040','Эйс Вентура',1994],['tt0268995','Мажестик',2001]].map(([id,name,year])=>({id,imdbId:id,name,year:String(year),type:'movie',localized:true,poster:'https://images.metahub.space/poster/small/'+id+'/img'}));
const kidding={id:'tt7375404',type:'series',kind:'series',name:'Шучу',year:'2018',videos:[{season:1,episode:1,title:'Первая серия',released:'2018-09-09'}]};
localStorage.clear();let delayed=null,delayedCast=null;
if(new URLSearchParams(location.search).get('size')==='largest')localStorage.setItem('readability',JSON.stringify({size:'largest'}));
window.actorFixture={delay:false,delayCast:true,release(){delayed?.();delayed=null;},releaseCast(){this.delayCast=false;delayedCast?.();delayedCast=null;}};
window.Native={publishHome(){},homeReady(){},command(){},request(id,method,args){
 const a=JSON.parse(args),values={bootstrap:{movies:[truman],series:[],channels:[],freeBytes:0,bufferMb:96,customPlaylist:true},'catalog-state':{},catalog:{items:[truman],warnings:[]},'github-refresh':{changed:false,providers:{}},'update-check':{status:'idle',installedVersion:'test'},detail:{...a.item,description:a.item?.description||'Описание фильма'},'series-detail':kidding,'title-cast':{people:cast,provider:'Wikidata'},'actor-films':{items:filmography.concat(kidding),provider:'Wikidata'},storage:0};
 const reply=()=>window.nativeReply(id,values[method]||{},null);
 if(method==='actor-films'&&actorFixture.delay)delayed=reply;else if(method==='title-cast'&&actorFixture.delayCast)delayedCast=reply;else setTimeout(reply,20);
}};
const assert=(ok,label)=>{if(!ok)throw new Error(label);};
async function waitFor(test){for(let i=0;i<150;i++){if(test())return;await new Promise(r=>setTimeout(r,20));}throw new Error('Timed out');}
const button=action=>document.querySelector('[data-action="'+action+'"]');
const click=action=>{const b=button(action);assert(b,'Missing '+action);b.click();};
window.addEventListener('load',async()=>{
 const report=document.getElementById('people-result');
 try{
  await waitFor(()=>button('movie:tt0120382'));click('movie:tt0120382');click('trailers');assert(document.querySelector('.overlay'),'Trailer selector opens while cast is loading');actorFixture.releaseCast();await new Promise(r=>setTimeout(r,60));window.tvKey('Escape');await waitFor(()=>button('actor:Q40504'));
  window.tvKey('ArrowDown');assert(document.activeElement.dataset.action==='actor:Q40504','Actor is reachable by Down from Watch');window.tvKey('Enter');
  await waitFor(()=>button('movie:tt0338013'));
  assert(document.querySelector('.actor-photo'),'Actor portrait is present');const years=[...document.querySelectorAll('.result-card small')].map(e=>Number(e.textContent.slice(0,4)));assert(years.every((y,i)=>!i||y<=years[i-1]),'Filmography is newest first');click('movie:tt0338013');await waitFor(()=>button('sources'));assert(document.querySelector('h1').textContent==='Вечное сияние чистого разума','A film outside the home catalogue opens');
  window.tvKey('Escape');assert(document.activeElement.dataset.action==='movie:tt0338013','Back restores the film card focus');
  click('page:1');assert(button('page:-1')&&!button('page:-1').disabled,'Second filmography page opens');click('movie:tt0110475');await waitFor(()=>button('sources'));window.tvKey('Escape');assert(document.querySelector('.pager span').textContent==='2 / 2'&&document.activeElement.dataset.action==='movie:tt0110475','Back restores page two and its selected film');
  click('actor-kind:series');assert(document.querySelectorAll('.result-card').length===1,'Series filter keeps only series');click('movie:tt7375404');
  await waitFor(()=>button('catalog-episode:1:1')&&button('actor:Q40504'));click('actor:Q40504');await waitFor(()=>button('movie:tt0338013'));
  window.tvKey('Escape');assert(document.querySelector('.series-detail'),'Back from nested actor restores series details');
  window.tvKey('Escape');assert(document.querySelector('.actor-page')&&button('actor-kind:series').getAttribute('aria-pressed')==='true','Parent actor filter survives nested filmography');assert(document.activeElement.dataset.action==='movie:tt7375404','Series card focus restored');
  window.tvKey('Escape');assert(document.querySelector('.detail-copy')&&document.activeElement.dataset.action==='actor:Q40504','Back returns to the original title and actor');
  click('cast-all');assert(document.querySelectorAll('.cast-list button').length===4,'Full cast is selectable');window.tvKey('Escape');assert(document.activeElement.dataset.action==='cast-all','Cast overlay returns focus');
  actorFixture.delay=true;click('actor:Q40504');assert(document.querySelector('.actor-page'),'Loading screen opens');window.tvKey('Escape');actorFixture.release();await new Promise(r=>setTimeout(r,60));
  assert(document.querySelector('.detail-copy')&&!document.querySelector('.actor-page'),'A late reply cannot replace the current screen');
  actorFixture.delay=false;click('actor:Q40504');await waitFor(()=>button('movie:tt0338013'));
  report.textContent='PASS: actor, outside-catalogue film, series, nested Back/focus, filters, pagination, full cast, delayed reply';
 }catch(e){report.textContent='FAIL: '+e.message;throw e;}
});
