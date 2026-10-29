(function(root){
 'use strict';
 function snapshot(state,title,image,ordered,keyOf){
  const history=Object.entries(state.history||{}).filter(([,r])=>r&&r.movie&&!r.completed&&r.position>0)
   .sort((a,b)=>(b[1].updated||0)-(a[1].updated||0)).slice(0,12).map(([key,r])=>({
    key,title:title(r.movie),subtitle:r.episode||'',image:image(r.movie.background||r.movie.poster||''),position:r.position,duration:r.duration||0
   }));
  const channels=ordered((state.channels||[]).filter(c=>state.favoriteChannels[c.url]),state.favoriteOrder||[])
   .slice(0,24).map(c=>({key:keyOf(c),title:c.name,subtitle:'Прямой эфир',image:image(c.logo||''),position:0,duration:0}));
  return {version:1,history,channels};
 }
 const api={snapshot};if(typeof module!=='undefined'&&module.exports)module.exports=api;else root.HomeFeed=api;
})(typeof window!=='undefined'?window:globalThis);
