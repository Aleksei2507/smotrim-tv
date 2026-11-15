// Run with a Playwright page against the local UI preview, without a TV or torrent network.
module.exports=async function playerBrowsingChecks(page){
 const assert=require('node:assert/strict');
 await page.addInitScript(()=>{
  const film={id:'film',name:'Тестовый фильм',type:'movie',year:2024,genres:['Drama']};
  const show={id:'show',name:'Тестовый сериал',type:'series',year:2024,videos:[{season:5,episode:7,title:'Серия 7'}]};
  const sources=[{magnet:'magnet:?xt=urn:btih:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',quality:'1080p',title:'Первый вариант',bytes:1073741824,seeds:12,audioLanguages:['ru']},{magnet:'magnet:?xt=urn:btih:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',quality:'720p',title:'Другой вариант',bytes:536870912,seeds:30,audioLanguages:['ru']}];
  window.__plays=[];window.__commands=[];window.__queries=[];window.__sourceMode='ready';window.__file=7;
  localStorage.clear();localStorage.setItem('history',JSON.stringify({episode:{movie:show,sourceMovie:show,seriesEpisode:{season:5,episode:7},url:sources[0].magnet,fileIndex:7,files:[{index:7,name:'Test.S05E07.mkv'}],position:12000,duration:1000000,updated:Date.now()}}));
  window.Native={
   publishHome(){},homeReady(){},voiceSearch(kind){window.__voice=kind;},
   request(id,method,json){
    const args=JSON.parse(json);window.__queries.push({method,args});
    if(['catalog-sources','series-torrents','torrents'].includes(method)){
     if(window.__sourceMode==='pending'){window.__pending=id;return;}
     if(window.__sourceMode==='error'){setTimeout(()=>window.nativeReply(id,null,'Источник недоступен'),0);return;}
    }
    const values={bootstrap:{movies:[film],series:[show],channels:[],freeBytes:0,bufferMb:96,customPlaylist:true},'catalog-state':{},catalog:{items:[film],warnings:[]},'series-detail':show,'catalog-sources':{items:sources,warnings:[]},'series-torrents':{items:sources,warnings:[]},torrents:{items:sources,warnings:[]},'github-refresh':{changed:false,providers:{}},'update-check':{status:'idle',installedVersion:'test'},storage:0};
    setTimeout(()=>window.nativeReply(id,values[method]||{},null),0);
   },
   play(json){const item=JSON.parse(json);window.__plays.push(item);if(item.kind==='torrent')setTimeout(()=>window.nativeEvent('choose-files',[{index:window.__file,name:'Test.S05E07.mkv',bytes:10000}]),0);},
   command(cmd){window.__commands.push(cmd);if(cmd.startsWith('file:'))setTimeout(()=>window.nativeEvent('selectedFile',+cmd.split(':')[1]),0);if(cmd==='stop')setTimeout(()=>window.nativeEvent('stopped',true),0);}
  };
 });
 await page.goto('http://127.0.0.1:8767/index.html?test='+Date.now());
 await page.locator('[data-action="resume:episode"]').waitFor();
 await page.locator('[data-action="resume:episode"]').click();
 await page.waitForFunction(()=>window.__commands.includes('file:7:12000'));
 const transport=await page.locator('.player-transport button').evaluateAll(buttons=>buttons.map(b=>({key:b.dataset.key,label:b.getAttribute('aria-label'),text:b.textContent.trim()})));
 assert.deepEqual(transport.map(b=>b.key),['rewind','pause','forward']);
 assert.equal(transport.every(b=>b.label&&(!b.text||b.text==='10')),true,'transport uses icons with accessible labels');
 const pauseBox=await page.locator('[data-key="pause"]').boundingBox(),stage=await page.locator('#tv').boundingBox();
 assert.equal(Math.abs(pauseBox.x+pauseBox.width/2-(stage.x+stage.width/2))<1,true,'pause is horizontally centered');
 assert.equal(Math.abs(pauseBox.y+pauseBox.height/2-(stage.y+stage.height/2))<1,true,'pause is vertically centered');
 await page.evaluate(()=>window.tvKey('ArrowLeft'));assert.equal(await page.evaluate(()=>document.activeElement.dataset.key),'rewind');
 await page.evaluate(()=>window.tvKey('Enter'));assert.equal(await page.evaluate(()=>window.__commands.at(-1)),'back');
 await page.evaluate(()=>window.tvKey('ArrowRight'));assert.equal(await page.evaluate(()=>document.activeElement.dataset.key),'pause');
 await page.evaluate(()=>window.tvKey('ArrowRight'));assert.equal(await page.evaluate(()=>document.activeElement.dataset.key),'forward');
 await page.evaluate(()=>window.tvKey('Enter'));assert.equal(await page.evaluate(()=>window.__commands.at(-1)),'forward');
 await page.evaluate(()=>window.tvKey('ArrowDown'));assert.equal(await page.evaluate(()=>document.activeElement.dataset.key),'timeline');
 await page.evaluate(()=>window.tvKey('ArrowDown'));assert.equal(await page.evaluate(()=>document.activeElement.dataset.key),'audio');
 await page.evaluate(()=>window.tvKey('ArrowRight'));assert.equal(await page.evaluate(()=>document.activeElement.dataset.key),'subtitles');
 await page.evaluate(()=>window.tvKey('ArrowRight'));assert.equal(await page.evaluate(()=>document.activeElement.dataset.key),'video-options');
 await page.evaluate(()=>window.tvKey('ArrowRight'));assert.equal(await page.evaluate(()=>document.activeElement.dataset.key),'player-tools');
 await page.evaluate(()=>window.tvKey('ArrowLeft'));assert.equal(await page.evaluate(()=>document.activeElement.dataset.key),'mini-player');
 await page.evaluate(()=>window.tvKey('ArrowDown'));assert.equal(await page.evaluate(()=>document.activeElement.dataset.key),'pause');

 await page.evaluate(()=>window.nativeEvent('playback',{position:456789,duration:1000000,playing:false,playWhenReady:false,loading:false}));
 await page.locator('[data-action="video-options"]').click();
 await page.locator('[data-action^="switch-source:"]:not([aria-current])').waitFor();
 assert.equal(await page.locator('.video-sheet [aria-current="true"]').count(),1);
 assert.equal(await page.evaluate(()=>window.__plays.length),1,'opening alternatives keeps the current session');
 await page.evaluate(()=>{window.__file=21;window.nativeEvent('playback',{position:467000,duration:1000000,playing:false,playWhenReady:false,loading:false});});
 await page.locator('[data-action^="switch-source:"]:not([aria-current])').click();
 await page.waitForFunction(()=>window.__commands.includes('file:21:467000'));
 const replacement=await page.evaluate(()=>window.__plays.at(-1));
 assert.equal(replacement.position,467000);assert.equal(replacement.paused,true);assert.equal(replacement.fileIndex,undefined);assert.equal(replacement.seriesEpisode.season,5);assert.equal(replacement.seriesEpisode.episode,7);assert.equal(replacement.movie.id,'show');
 assert.equal(await page.evaluate(()=>window.__commands.includes('stop')),false);
 await page.locator('[data-action="mini-player"]').click();
 await page.locator('.mini-player').waitFor();
 assert.equal(await page.evaluate(()=>window.__commands.at(-1)),'video:mini');
 await page.locator('[data-action="nav:catalog"]').click();
 await page.locator('.catalog').waitFor();
 await page.waitForTimeout(5100);
 await page.evaluate(()=>window.nativeEvent('playback',{position:501000,duration:1000000,playing:true,playWhenReady:true,loading:false}));
 const record=await page.evaluate(()=>Object.values(JSON.parse(localStorage.getItem('history'))).find(r=>r.seriesEpisode?.episode===7));assert.equal(record.position,501000,'progress keeps updating while browsing');
 await page.locator('[data-action="mini-expand"]').click();
 assert.equal(await page.evaluate(()=>window.__commands.at(-1)),'video:full');
 assert.equal(await page.evaluate(()=>window.__plays.length),2,'expanding does not reconnect the torrent');
 await page.evaluate(()=>window.__sourceMode='error');
 await page.locator('[data-action="video-options"]').click();
 await page.locator('[data-action="video-search"]').click();
 await page.getByText('Источник недоступен',{exact:true}).waitFor();
 assert.equal(await page.evaluate(()=>window.__plays.length),2,'failed search leaves current playback alone');
 await page.locator('[data-action="close-overlay"]').click();
 await page.evaluate(()=>{window.__sourceMode='pending';});
 await page.locator('[data-action="video-options"]').click();
 await page.locator('[data-action="video-search"]').click();
 await page.locator('[data-action="close-overlay"]').click();
 await page.evaluate(()=>window.nativeReply(window.__pending,{items:[],warnings:[]},null));
 assert.equal(await page.locator('.overlay').count(),0,'late search response cannot reopen a closed panel');
 await page.locator('[data-action="mini-player"]').click();
 await page.locator('[data-action="mini-stop"]').click();
 await page.waitForFunction(()=>!document.querySelector('.mini-player'));
 assert.equal(await page.locator('.catalog').count(),1,'closing mini stays on the catalog');
 await page.locator('[data-action="nav:search"]').click();
 assert.equal(await page.locator('.search-page button').count(),6,'search starts with six controls');
 assert.equal(await page.locator('.keyboard button').count(),0,'typing keys are initially hidden');
 await page.evaluate(()=>window.tvKey('ArrowDown'));assert.equal(await page.evaluate(()=>document.activeElement.dataset.key),'search-submit','down from query has a visible target');
 await page.locator('[data-action="search-keyboard"]').click();
 assert.equal(await page.locator('.keyboard button').count()>30,true);
 await page.locator('[data-action="key:Д"]').click();
 await page.locator('[data-action="key:Ю"]').click();
 await page.locator('[data-action="key:Н"]').click();
 await page.locator('[data-action="key:А"]').click();
 assert.equal(await page.locator('.query-text').innerText(),'ДЮНА');
 await page.evaluate(()=>window.tvKey('Escape'));
 assert.equal(await page.locator('.keyboard button').count(),0);
 assert.equal(await page.locator('.query-text').innerText(),'ДЮНА','closing the keyboard preserves the query');
 await page.locator('[data-action="search-filter"]').click();
 await page.locator('[data-action="search-mode:series"]').click();
 assert.equal(await page.locator('.query-text').innerText(),'ДЮНА','changing scope preserves input');
 assert.equal((await page.locator('[data-action="search-filter"]').innerText()).replace(/\s+/g,' '),'Искать: Сериалы ⌄');
 await page.locator('[data-action="search-filter"]').click();
 await page.locator('[data-action="search-mode:all"]').click();
 await page.evaluate(()=>window.nativeEvent('voice-input',{kind:'search',value:'Ошибка распознавания'}));
 assert.equal(await page.locator('.search-input-row [data-action="clear"] + [data-action="voice-search:movies"]').count(),1);
 await page.locator('[data-key="search-query"]').focus();
 await page.evaluate(()=>window.tvKey('ArrowRight'));
 assert.equal(await page.evaluate(()=>document.activeElement.dataset.key),'clear','right from the end of a query reaches Clear');
 await page.evaluate(()=>window.tvKey('ArrowRight'));
 assert.equal(await page.evaluate(()=>document.activeElement.dataset.key),'search-voice');
 await page.evaluate(()=>window.tvKey('ArrowLeft'));
 assert.equal(await page.evaluate(()=>document.activeElement.dataset.key),'clear','left from the microphone reaches Clear');
 await page.evaluate(()=>window.tvKey('ArrowLeft'));
 assert.equal(await page.evaluate(()=>document.activeElement.dataset.key),'search-query');

 await page.locator('[data-action="clear"]').click();
 assert.equal(await page.locator('.query-placeholder').count(),1);
 await page.locator('[data-key="search-query"]').focus();
 await page.evaluate(()=>window.tvKey('ArrowRight'));
 assert.equal(await page.evaluate(()=>document.activeElement.dataset.key),'clear','empty query also leads to Clear');

 await page.locator('[data-action="voice-search:movies"]').click();assert.equal(await page.evaluate(()=>window.__voice),'search');
 await page.evaluate(()=>window.nativeEvent('channels',[{id:'live',name:'Тестовый канал',url:'https://example.com/live.m3u8',group:'News'}]));
 await page.locator('[data-action="channel:0"]').first().click();
 assert.equal(await page.locator('.player-transport button').count(),1,'live TV only has a central pause button');
 assert.equal(await page.locator('[data-action="timeline"]').count(),0);
 await page.evaluate(()=>window.tvKey('ArrowRight'));assert.equal(await page.evaluate(()=>document.activeElement.dataset.key),'quick-channels');
 await page.evaluate(()=>window.tvKey('ArrowLeft'));assert.equal(await page.evaluate(()=>document.activeElement.dataset.key),'subtitles');
 await page.evaluate(()=>window.tvKey('ArrowUp'));assert.equal(await page.evaluate(()=>document.activeElement.dataset.key),'pause');
 await page.evaluate(()=>window.nativeEvent('playback',{position:0,playing:true,playWhenReady:true,loading:false}));
 await page.waitForTimeout(5700);
 assert.equal(await page.locator('.player-transport').isVisible(),false,'center controls hide with the rest of the player');
 await page.evaluate(()=>window.tvKey('Enter'));
 assert.equal(await page.locator('.player-transport').isVisible(),true);
 assert.equal(await page.evaluate(()=>document.activeElement.dataset.key),'pause');
 return {checks:'icon layout, remote navigation, auto-hide, live TV, source switching, mini player, search navigation',plays:await page.evaluate(()=>window.__plays.length)};
};
