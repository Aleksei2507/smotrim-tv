// Isolated native bridge: no video, external requests or user data.
const show={id:'tt31510819',name:'Гангстерленд',type:'series',videos:[{season:2,episode:7},{season:2,episode:8}]};
const files=[{index:7,name:'MobLand.S02E07.mkv'},{index:8,name:'MobLand.S02E08.mkv'}];
const pendingMode=new URLSearchParams(location.search).has('pending');
const replies=[],commands=[],introRequests=[];
const response=q=>({imdb_id:q.imdbId,media_type:'tv',season:q.season,episode:q.episode,intro:{start_ms:240039,end_ms:q.episode===8?310507:300507,confidence:1,submission_count:1},outro:{start_ms:2800000,end_ms:3000000,confidence:1,submission_count:1}});
window.Native={
 publishHome(){},homeReady(){},
 request(id,method,json){
  const args=JSON.parse(json);
  if(method==='playback-segments'){introRequests.push(args);if(pendingMode){replies.push({id,args});return;}setTimeout(()=>window.nativeReply(id,response(args),null),0);return;}
  const data={bootstrap:{movies:[],series:[show],channels:[],freeBytes:0,bufferMb:96,customPlaylist:true,restorePlayback:{kind:'torrent',url:'magnet:?xt=urn:btih:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',title:show.name,movie:show,seriesEpisode:{season:2,episode:7},fileIndex:7,files,position:0}},'catalog-state':{},catalog:{items:[],warnings:[]},'github-refresh':{changed:false,providers:{}},'update-check':{status:'idle',installedVersion:'test'},storage:0};
  setTimeout(()=>window.nativeReply(id,data[method]||{},null),0);
 },
 play(json){const item=JSON.parse(json);setTimeout(()=>window.nativeEvent('selectedFile',item.fileIndex),0);},
 command(command){commands.push(command);}
};
localStorage.clear();
const assert=(ok,label)=>{if(!ok)throw new Error(label);};
const pause=ms=>new Promise(r=>setTimeout(r,ms));
async function waitFor(predicate){for(let i=0;i<100;i++){if(predicate())return;await pause(20);}throw new Error('Timed out');}
const button=()=>document.getElementById('skip-intro');
const click=action=>{const node=document.querySelector('[data-action="'+action+'"]');assert(node,'Action exists: '+action);node.click();};
const emit=(position,extra={})=>window.nativeEvent('playback',{position,duration:3000000,loading:false,playing:false,playWhenReady:false,...extra});
window.addEventListener('load',async()=>{
 const report=document.getElementById('intro-result');
 try{
  await waitFor(()=>introRequests.length===1);
  if(pendingMode){
   emit(250000);assert(!button(),'Pending metadata must not block playback or display a button');
   click('player-tools');click('episodes');click('episode:8');
   await waitFor(()=>replies.length===2);
   emit(250000);
   window.nativeReply(replies[0].id,response(replies[0].args),null);await pause(30);
   assert(!button(),'A late reply from episode 7 must not apply to episode 8');
   window.nativeReply(replies[1].id,response(replies[1].args),null);
   await waitFor(()=>button());
   click('skip-intro');assert(commands.includes('seek:310507'),'Episode 8 uses its own timestamps');
   click('player-tools');click('episodes');click('episode:7');
   await waitFor(()=>replies.length===3);
   window.nativeReply(replies[2].id,null,'No network');await pause(30);emit(250000);
   assert(!button()&&document.getElementById('player'),'Unavailable metadata leaves playback running');
   report.textContent='PASS: pending request, episode change, stale reply isolation and unavailable API';
  }else{
   await pause(50);assert(!button(),'No intro button before the intro');
   emit(240038);assert(!button(),'No button one millisecond before the intro');
   emit(240039);assert(button(),'Button appears at intro start');
   assert(document.activeElement.dataset.key==='pause','Metadata must not steal focus from visible controls');
   assert(!commands.some(c=>c.startsWith('seek:')),'Never skip automatically');
   window.tvKey('ArrowRight');window.tvKey('ArrowRight');window.tvKey('ArrowUp');
   assert(document.activeElement===button(),'Remote reaches skip from video controls');
   window.tvKey('Enter');assert(commands.includes('seek:300507'),'OK skips to intro end');
   assert(!button(),'Skip button disappears immediately after selection');
   emit(300507);assert(!button(),'No button at intro end');
   emit(250000,{loading:true});assert(!button(),'No button while buffering');
   emit(250000,{duration:200000});assert(!button(),'Reject a segment outside file duration');
   emit(250000);assert(button(),'Seeking back into the intro restores the button');
   click('player-tools');assert(!button(),'Hide skip underneath menus');
   click('close-overlay');assert(button(),'Restore skip after closing menu');
   window.tvKey('Escape');emit(250000);
   assert(button().classList.contains('quiet')&&document.activeElement===button(),'Skip stays focused with hidden controls');
   window.tvKey('Escape');assert(!button()&&!commands.includes('stop'),'Back dismisses skip without leaving the film');
   emit(260000);assert(!button(),'Dismissed skip does not return every playback tick');
   emit(400000);emit(250000);window.tvKey('Enter');
   assert(commands.filter(c=>c==='seek:300507').length===2,'OK works with the transport controls hidden');
   emit(400000);window.tvKey('Enter');emit(250000);
   click('mini-player');assert(!button(),'Hide skip while browsing with PiP');
   click('mini-expand');assert(button(),'Restore skip when expanding PiP');
   assert(introRequests.length===1,'PiP does not repeat the metadata request');
   document.documentElement.style.setProperty('--text-scale','1.35');
   const r=button().getBoundingClientRect(),stage=document.getElementById('tv').getBoundingClientRect();
   assert(r.left>=stage.left&&r.right<=stage.right&&r.top>=stage.top&&r.bottom<=stage.bottom,'Large text fits the TV screen');
   emit(2800000);
   const entry=Object.values(JSON.parse(localStorage.getItem('history'))).find(r=>r.movie.id===show.id);
   assert(entry?.completed===true,'Credits mark the episode watched before video ends');
   assert(!commands.includes('stop'),'Credits do not stop playback or skip a post-credits scene');
   click('mini-player');
   assert(!document.querySelector('[data-action^="resume:"]'),'Completed episode is removed from Continue watching');
   click('mini-expand');emit(250000);
   report.textContent='PASS: intro remote, boundaries, PiP, menus; credits mark watched and remove Continue watching';
  }
 }catch(error){report.textContent='FAIL: '+error.message;}
 document.documentElement.dataset.introTest=report.textContent.startsWith('PASS')?'passed':'failed';
});
