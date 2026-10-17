/* Shared playback rules; no DOM or Android dependencies. */
(function(root){
'use strict';
const clean=s=>String(s||'').replace(/[._]+/g,' ').replace(/\s+/g,' ').trim();
function episode(file){
 const path=String(file.path||file.name||''),name=String(file.name||path.split('/').pop());
 let m=name.match(/(?:^|[^a-z0-9])s(\d{1,2})[ ._-]*e(\d{1,3})(?:[ ._-]*e?(\d{1,3}))?/i),season=null,number=null,prefix='';
 if(m){season=+m[1];number=+m[2];prefix=name.slice(0,m.index);}
 if(!m){m=name.match(/(?:^|\D)(\d{1,2})x(\d{1,3})(?!\d)/i);if(m){season=+m[1];number=+m[2];prefix=name.slice(0,m.index);}}
 if(number===null){const sm=path.match(/(?:season|сезон|(?:^|[ /_.-])s)[ ._-]*(\d{1,2})(?!\d)/i)||path.match(/(\d{1,2})[ ._-]*сезон/i);if(sm)season=+sm[1];
  const em=name.match(/(?:серия|эпизод|episode|(?:^|[ ._-])e)[ ._-]*(\d{1,3})(?!\d)/i)||name.match(/(?:^|\D)(\d{1,3})[ ._-]*(?:серия|эпизод)/i);
  if(em){number=+em[1];prefix=name.slice(0,em.index);}else if(season!==null){const leading=name.match(/^(\d{1,3})(?:[ ._-]|\.[a-z]{2,4}$)/i);if(leading)number=+leading[1];}}
 if(number!==null&&season===null)season=1;
 return {...file,season,episode:number,seriesName:clean(prefix).replace(/[()\[\]-]+$/,'').trim(),label:number===null?name:`Серия ${number}`};
}
function files(items){return items.map(episode).sort((a,b)=>(a.season??999)-(b.season??999)||(a.episode??9999)-(b.episode??9999)||a.name.localeCompare(b.name,'ru',{numeric:true}));}
function nextFile(items,index){const list=files(items),current=list.find(f=>f.index===index);if(!current||current.episode===null)return null;return list.find(f=>f.episode!==null&&(f.season>current.season||f.season===current.season&&f.episode>current.episode))||null;}
function seriesId(item,file){if(file?.episode==null)return item.movie?.id||'link:'+item.url;const name=file.seriesName||String(item.sourceMovie?.name||item.movie?.name||'').split(/(?:s\d|сезон|season|\d+\s*сезон|серии)/i)[0];return name.trim()?'series:'+clean(name).toLowerCase():'series:'+item.url;}
function rankedSources(items,prefs){const resolution=s=>/2160|4k/i.test(s.quality+' '+s.title)?2160:/1080/i.test(s.quality+' '+s.title)?1080:/720/i.test(s.quality+' '+s.title)?720:0;
 const language=s=>(prefs.language==='ru'?/(?:rus|рус|дубл|dvo|mvo|avo|dub)/i:prefs.language==='en'?/(?:eng|англ|original)/i:prefs.language==='uk'?/(?:ukr|укр)/i:/$a/).test(s.title);
 return items.map((s,i)=>({s,i})).sort((a,b)=>Number(a.s.seeds===0)-Number(b.s.seeds===0)||(prefs.quality!=='auto'?Number(resolution(b.s)===+prefs.quality)-Number(resolution(a.s)===+prefs.quality):0)||(Number(language(b.s))-Number(language(a.s)))||(b.s.seeds||0)-(a.s.seeds||0)||a.i-b.i).map(x=>x.s);
}
function diagnosis(p,live=false){const seconds=Math.max(0,((p.buffered||0)-(p.position||0))/1000);if(live)return p.loading?'Эфир загружается. Если ожидание затянулось, попробуйте переподключиться.':'Эфир воспроизводится.';
 if(p.loading&&!p.rate&&!p.peers)return 'Нет активной загрузки. Ждём раздающих; если ожидание затянулось, выберите другой вариант.';
 if(p.loading)return `Видео подгружается. Запас: ${Math.floor(seconds)} сек. Если паузы повторяются, выберите вариант меньшего размера.`;
 return `Запас видео: ${Math.floor(seconds)} сек. ${p.playing?'Воспроизведение идёт.':'Видео на паузе.'}`;
}
const channelKey=c=>c.id||c.url;
function orderedChannels(channels,order){const ranks=new Map(order.map((id,i)=>[id,i]));return channels.map((c,i)=>({c,i})).sort((a,b)=>(ranks.get(channelKey(a.c))??1e9)-(ranks.get(channelKey(b.c))??1e9)||a.i-b.i).map(x=>x.c);}
function moveItem(items,from,to){const next=items.slice();if(from<0||from>=next.length)return next;to=Math.max(0,Math.min(next.length-1,to));next.splice(to,0,next.splice(from,1)[0]);return next;}
function seekTarget(position,duration,direction,repeats){const step=repeats>=12?120000:repeats>=5?30000:10000;return Math.max(0,Math.min(Math.max(0,duration-1000),position+direction*step));}
const api={episode,files,nextFile,seriesId,rankedSources,diagnosis,channelKey,orderedChannels,moveItem,seekTarget};
if(typeof module!=='undefined')module.exports=api;else root.TVFeatures=api;
})(typeof window!=='undefined'?window:globalThis);
