// Synthetic history and native bridge; never starts a torrent or reads TV data.
const film={id:'tt0120382',name:'Шоу Трумана',type:'movie',year:'1998'};
const series={id:'tt14688458',name:'Укрытие',type:'series',videos:[{season:1,episode:1},{season:2,episode:1},{season:2,episode:3}]};
const featured={id:'tt0111161',name:'Побег из Шоушенка',type:'movie'};
const movieKey=film.id,seriesKey='series:'+series.id,legacyKey='torrent:legacy';
const record=(movie,url,updated)=>({movie,url,fileIndex:0,files:[{index:0,name:'video.mkv'}],position:123456,duration:7200000,updated,completed:false});
localStorage.clear();localStorage.setItem('history',JSON.stringify({[movieKey]:record(film,'magnet:?xt=urn:btih:'+'a'.repeat(40),3),[seriesKey]:{...record(series,'magnet:?xt=urn:btih:'+'b'.repeat(40),2),seriesEpisode:{season:2,episode:3},episode:'Сезон 2 · Серия 3'},[legacyKey]:record({id:legacyKey,name:'Видео по ссылке'},'magnet:?xt=urn:btih:'+'c'.repeat(40),1)}));
if(new URLSearchParams(location.search).get('size')==='largest')localStorage.setItem('readability',JSON.stringify({size:'largest'}));
const plays=[];
window.Native={publishHome(){},homeReady(){},command(){},play(value){plays.push(JSON.parse(value));setTimeout(()=>window.nativeEvent('selectedFile',0),0);},request(id,method,args){
 const a=JSON.parse(args),values={bootstrap:{movies:[featured],series:[],channels:[],freeBytes:0,bufferMb:96,customPlaylist:true},'catalog-state':{},catalog:{items:[featured],warnings:[]},'github-refresh':{changed:false,providers:{}},'update-check':{status:'idle',installedVersion:'test'},detail:{...a.item,description:'Описание фильма'},'series-detail':series,'title-cast':{people:[]},storage:0};
 setTimeout(()=>window.nativeReply(id,values[method]||{},null),10);
}};
const assert=(ok,label)=>{if(!ok)throw new Error(label);};
async function waitFor(test){for(let i=0;i<150;i++){if(test())return;await new Promise(r=>setTimeout(r,20));}throw new Error('Timed out');}
const button=a=>document.querySelector('[data-action="'+a+'"]');
function click(a){const b=button(a);assert(b,'Missing '+a);b.click();}
function selectFirst(){document.querySelector('[data-key="home-watch"]').focus();window.tvKey('ArrowDown');assert(document.activeElement.dataset.action?.startsWith('resume:'),'Down reaches a continue-watching card');}
async function hold(){window.tvOk(true);window.tvOk(true);await waitFor(()=>document.querySelector('.overlay'));window.tvOk(false);}
const read=()=>JSON.parse(localStorage.getItem('history'));
window.addEventListener('load',async()=>{
 const report=document.getElementById('history-result');
 try{
  await waitFor(()=>button('resume:'+movieKey));selectFirst();assert(document.activeElement.dataset.action==='resume:'+movieKey,'Latest history card selected');
  window.tvOk(true);await new Promise(r=>setTimeout(r,80));assert(plays.length===0,'Key down must not start the movie before a hold is known');window.tvOk(false);await waitFor(()=>document.querySelector('#player'));assert(plays.length===1&&plays[0].position===123456,'Short OK resumes once at the saved position');
  window.nativeEvent('stopped');await waitFor(()=>button('resume:'+movieKey));selectFirst();await hold();assert(plays.length===1,'Holding and releasing OK never starts playback');
  for(const a of ['history-play:','history-restart:','history-detail:','history-watched:','history-remove:'])assert(button(a+movieKey),'History action '+a);
  click('history-detail:'+movieKey);await waitFor(()=>document.querySelector('.detail-copy'));assert(document.querySelector('h1').textContent==='Шоу Трумана','Out-of-catalogue movie details open');assert(read()[movieKey].position===123456&&plays.length===1,'Opening details preserves progress and playback');window.tvKey('Escape');assert(document.activeElement.dataset.action==='resume:'+movieKey,'Back restores the selected continue card');
  window.tvKey('ArrowRight');assert(document.activeElement.dataset.action==='resume:'+seriesKey,'Next card is the series');await hold();click('history-detail:'+seriesKey);await waitFor(()=>document.querySelector('.series-detail')&&button('catalog-season:2'));assert(button('catalog-season:2').getAttribute('aria-pressed')==='true','Series details open the saved season');assert(plays.length===1,'Opening a series page does not autoplay');window.tvKey('Escape');assert(document.activeElement.dataset.action==='resume:'+seriesKey,'Back restores series card focus');
  window.tvKey('ArrowLeft');await hold();click('history-watched:'+movieKey);assert(read()[movieKey].completed&&!button('resume:'+movieKey),'Mark watched removes Continue watching but keeps history');
  selectFirst();assert(document.activeElement.dataset.action==='resume:'+seriesKey,'Series is now first');await hold();click('history-remove:'+seriesKey);assert(!read()[seriesKey]&&read()[movieKey].completed,'Remove only affects the chosen history record');
  selectFirst();window.tvOk(true);window.tvKey('ArrowUp');window.tvOk(false);await new Promise(r=>setTimeout(r,700));assert(!document.querySelector('.overlay')&&plays.length===1,'Moving during a hold cancels it');
  selectFirst();window.tvOk(true);window.cancelHeldOk();window.tvOk(false);await new Promise(r=>setTimeout(r,700));assert(!document.querySelector('.overlay')&&plays.length===1,'Backgrounding cancels a pending hold');
  selectFirst();document.activeElement.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true,cancelable:true}));document.activeElement.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',repeat:true,bubbles:true,cancelable:true}));await waitFor(()=>document.querySelector('.overlay'));document.activeElement.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',bubbles:true,cancelable:true}));assert(!button('history-detail:'+legacyKey),'A raw torrent has no invented title page');assert(plays.length===1,'Browser repeat and keyup do not activate the opened menu');click('close-overlay');
  click('history');click('history-item:'+movieKey);assert(button('history-detail:'+movieKey),'Full history has the same Open page action');window.tvKey('Escape');window.tvKey('Escape');
  report.textContent='PASS: short/held OK, browser repeats, cancel/background, shared menu, title/season page, progress, watched/remove, return focus';
 }catch(e){report.textContent='FAIL: '+e.message;throw e;}
});
