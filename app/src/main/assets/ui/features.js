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
function seriesId(item,file){if(item.movie?.type==='series'&&/^tt\d+$/.test(item.movie.id))return 'series:'+item.movie.id;if(file?.episode==null)return item.movie?.id||'link:'+item.url;const name=file.seriesName||String(item.sourceMovie?.name||item.movie?.name||'').split(/(?:s\d|сезон|season|\d+\s*сезон|серии)/i)[0];return name.trim()?'series:'+clean(name).toLowerCase():'series:'+item.url;}
function rankedSources(items,prefs){const resolution=s=>/2160|4k/i.test(s.quality+' '+s.title)?2160:/1080/i.test(s.quality+' '+s.title)?1080:/720/i.test(s.quality+' '+s.title)?720:0;
 const language=s=>(prefs.language==='ru'?/(?:rus|рус|дубл|dvo|mvo|avo|dub)/i:prefs.language==='en'?/(?:eng|англ|original)/i:prefs.language==='uk'?/(?:ukr|укр)/i:/$a/).test(s.title);
 return items.map((s,i)=>({s,i})).sort((a,b)=>Number(a.s.seeds===0)-Number(b.s.seeds===0)||(prefs.quality!=='auto'?Number(resolution(b.s)===+prefs.quality)-Number(resolution(a.s)===+prefs.quality):0)||(Number(language(b.s))-Number(language(a.s)))||(b.s.seeds||0)-(a.s.seeds||0)||a.i-b.i).map(x=>x.s);
}
function bufferAhead(p){return Math.max(0,Math.floor(((p.buffered||0)-(p.position||0))/1000));}
function bufferingLabel(p,fallback){if(p.playWhenReady===false)return 'Видео на паузе';return p.preparing===false?'Подгружаем следующий фрагмент…':fallback||'Подключаемся к раздающим…';}
function diagnosis(p,live=false){const seconds=bufferAhead(p);if(p.playWhenReady===false)return 'Видео на паузе.'+(live?'':` Запас: ${seconds} сек. Загрузка фрагментов может продолжаться.`);if(live)return p.loading?'Эфир загружается. Если ожидание затянулось, попробуйте переподключиться.':'Эфир воспроизводится.';
 if(p.loading&&!p.rate&&!p.peers)return 'Нет активной загрузки. Ждём раздающих; если ожидание затянулось, выберите другой вариант.';
 if(p.loading)return `Видео подгружается. Запас: ${seconds} сек. ${p.cacheLimit&&p.cached>=p.cacheLimit*.98?'Заполненный кэш не означает, что следующий фрагмент готов к просмотру. ':''}Если ожидание повторяется, выберите другую раздачу или вариант меньшего размера.`;
 return `Запас видео: ${Math.floor(seconds)} сек. ${p.playing?'Воспроизведение идёт.':'Видео на паузе.'}`;
}
const channelKey=c=>c.id||c.url;
function orderedChannels(channels,order){const ranks=new Map(order.map((id,i)=>[id,i]));return channels.map((c,i)=>({c,i})).sort((a,b)=>(ranks.get(channelKey(a.c))??1e9)-(ranks.get(channelKey(b.c))??1e9)||a.i-b.i).map(x=>x.c);}
function moveItem(items,from,to){const next=items.slice();if(from<0||from>=next.length)return next;to=Math.max(0,Math.min(next.length-1,to));next.splice(to,0,next.splice(from,1)[0]);return next;}
function seekTarget(position,duration,direction,repeats){const step=repeats>=12?120000:repeats>=5?30000:10000;return Math.max(0,Math.min(Math.max(0,duration-1000),position+direction*step));}
function catalogueEpisodes(meta){const seen=new Set();return (Array.isArray(meta?.videos)?meta.videos:[]).filter(v=>{const key=v.season+':'+v.episode;return Number.isInteger(v.season)&&v.season>=0&&Number.isInteger(v.episode)&&v.episode>0&&!seen.has(key)&&seen.add(key);}).map(v=>({...v,title:v.title||v.name||'Серия '+v.episode})).sort((a,b)=>a.season-b.season||a.episode-b.episode);}
function futureEpisode(v,now=Date.now()){const t=Date.parse(v?.released);return Number.isFinite(t)&&t>now;}
function nextCatalogueEpisode(meta,current,now=Date.now()){if(!current)return null;const v=catalogueEpisodes(meta).find(v=>v.season>current.season||v.season===current.season&&v.episode>current.episode);return v&&!futureEpisode(v,now)?v:null;}
function matchingFiles(items,target){return files(items).filter(f=>f.season===target?.season&&f.episode===target?.episode);}
function sourceScope(text,target){
 const s=String(text||'').replace(/[–—]/g,'-');
 const season=s.match(/\bs(\d{1,3})(?:\s*-\s*s?(\d{1,3}))?/i)||s.match(/(\d{1,3})(?:\s*-\s*(\d{1,3}))?\s*сезон/i)||s.match(/(?:сезон[ыа]?|seasons?)\s*[:.]?\s*(\d{1,3})(?:\s*-\s*(\d{1,3}))?/i)||s.match(/\b(\d{1,3})x\d/i);
 const ep=s.match(/\bs\d+\s*e(\d{1,4})(?:\s*-\s*(?:s\d+)?e?(\d{1,4}))?/i)||s.match(/\b\d+x(\d{1,4})(?:\s*-\s*(\d{1,4}))?/i)||s.match(/(?:серии|серия|episodes?)\s*[:.]?\s*(\d{1,4})(?:\s*-\s*(\d{1,4}))?/i)||s.match(/(\d{1,4})(?:\s*-\s*(\d{1,4}))?\s*сер(?:ии|ия|ий)/i);
 const includes=(m,n)=>n>=+m[1]&&n<=+(m[2]||m[1]);
 if(season&&!includes(season,target.season)||ep&&!includes(ep,target.episode))return 3;
 if(season&&ep&&!season[2]&&!ep[2])return 0;
 if(season)return 1;
 return 2;
}
function episodeSources(items,target,prefs){return rankedSources(items,prefs).map(s=>({...s,episodeScope:sourceScope(s.title,target)})).filter(s=>s.episodeScope!==3).sort((a,b)=>Number(a.seeds===0)-Number(b.seeds===0)||a.episodeScope-b.episodeScope);}
function readability(value){return {size:['normal','large','largest'].includes(value?.size)?value.size:'normal',contrast:value?.contrast===true,motion:value?.motion===true};}

const api={bufferAhead,bufferingLabel,catalogueEpisodes,futureEpisode,nextCatalogueEpisode,matchingFiles,sourceScope,episodeSources,readability,episode,files,nextFile,seriesId,rankedSources,diagnosis,channelKey,orderedChannels,moveItem,seekTarget};
if(typeof module!=='undefined')module.exports=api;else root.TVFeatures=api;
})(typeof window!=='undefined'?window:globalThis);
