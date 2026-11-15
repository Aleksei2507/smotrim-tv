// Resume before the decoder knows the duration; no network, torrent or user data.
const movie={id:'tt2250912',type:'movie',name:'Человек-паук: Возвращение домой'};
const url='magnet:?xt=urn:btih:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',key=movie.id;
const record={movie,url,fileIndex:7,position:7381000,duration:7980000,updated:Date.now(),completed:false};
let selected=false,feeds=0,lastFeed;
localStorage.clear();localStorage.setItem('history',JSON.stringify({[key]:record}));
if(!new URLSearchParams(location.search).has('legacy'))localStorage.setItem('fileProgress',JSON.stringify({[url+'#7']:{position:record.position,duration:record.duration}}));
window.Native={
 publishHome(json){lastFeed=JSON.parse(json);feeds++;},homeReady(){},command(){},
 play(){setTimeout(()=>{window.nativeEvent('selectedFile',7);selected=true;},0);},
 request(id,method){
  const values={bootstrap:{movies:[movie],series:[],channels:[],freeBytes:0,bufferMb:96,customPlaylist:true,restorePlayback:{kind:'torrent',url,title:movie.name,movie,historyKey:key,fileIndex:7,position:record.position,files:[{index:7,name:'Spider-Man.Homecoming.mkv'}]}},'catalog-state':{},catalog:{items:[movie],warnings:[]},'github-refresh':{changed:false,providers:{}},'update-check':{status:'idle',installedVersion:'test'},storage:0};
  setTimeout(()=>window.nativeReply(id,values[method]||{},null),0);
 }
};
const read=()=>JSON.parse(localStorage.getItem('history'))[key];
const assert=(ok,label)=>{if(!ok)throw new Error(label);};
async function waitFor(test){for(let i=0;i<100;i++){if(test())return;await new Promise(r=>setTimeout(r,20));}throw new Error('Timed out');}
window.addEventListener('load',async()=>{
 const report=document.getElementById('progress-result');
 try{
  await waitFor(()=>selected);
  assert(read().duration===7980000,'Resume during loading must retain the known file duration');
  const emit=duration=>window.nativeEvent('playback',{position:7381000,duration,loading:!duration,playing:false,playWhenReady:false});
  emit(0);window.nativeEvent('position',7381000);
  assert(read().duration===7980000,'A zero duration status must not erase history');
  const initialFeeds=feeds;await waitFor(()=>feeds>initialFeeds);
  assert(lastFeed.history[0].duration===7980000,'Launcher feed retains the total duration');
  emit(7961000);window.nativeEvent('position',7381000);
  assert(read().duration===7961000,'The actual decoder duration replaces an older cached value');
  assert(document.getElementById('elapsed').textContent==='2:03:01','Elapsed time is displayed in hours');
  report.textContent='PASS: startup, unknown duration, launcher feed and actual decoder duration';
 }catch(error){report.textContent='FAIL: '+error.message;}
});
