/* Shared playback rules; no DOM or Android dependencies. */
(function(root){
'use strict';
const clean=s=>String(s||'').replace(/[._]+/g,' ').replace(/\s+/g,' ').trim();
function mediaGenres(item,translations={}){
 const labels=new Map();Object.entries(translations).forEach(([key,value])=>{labels.set(key.toLocaleLowerCase('ru'),value);labels.set(value.toLocaleLowerCase('ru'),value);});
 const raw=item?.genres||item?.genre||[],values=Array.isArray(raw)?raw:typeof raw==='string'?[raw]:[];
 return [...new Set(values.filter(g=>typeof g==='string').map(g=>g.trim().replace(/\s+/g,' ')).filter(Boolean).map(g=>labels.get(g.toLocaleLowerCase('ru'))||g[0].toLocaleUpperCase('ru')+g.slice(1).toLocaleLowerCase('ru')))];
}
function genreOptions(items,translations={}){return ['Все',...[...new Set(items.flatMap(item=>mediaGenres(item,translations)))].filter(g=>g!=='Все').sort((a,b)=>a.localeCompare(b,'ru'))];}
function sourceSize(source,unknown='Размер неизвестен'){
 const raw=String(source?.size??'').trim();let bytes=Number(source?.bytes);
 if(!Number.isFinite(bytes)||bytes<=0){const m=raw.match(/^(\d+(?:[.,]\d+)?)\s*(?:bytes?|байт(?:а|ов)?)?$/i);bytes=m?Number(m[1].replace(',','.')):0;}
 if(Number.isFinite(bytes)&&bytes>0){
  const units=['Б','КБ','МБ','ГБ','ТБ'];let unit=0;
  while(bytes>=1024&&unit<units.length-1){bytes/=1024;unit++;}
  return bytes.toFixed(unit===0?0:2).replace('.',',')+' '+units[unit];
 }
 const formatted=raw.match(/^(\d+(?:[.,]\d+)?)\s*(KB|MB|GB|TB|KiB|MiB|GiB|TiB|КБ|МБ|ГБ|ТБ)$/i);
 if(formatted)return formatted[1].replace('.',',')+' '+({K:'К',M:'М',G:'Г',T:'Т'}[formatted[2][0].toUpperCase()]||formatted[2][0].toUpperCase())+'Б';
 return unknown;
}
function torrentSizeKey(url){
 if(!url)return '';
 const hash=/^magnet:\?/i.test(url)&&url.match(/[?&]xt=urn:btih:([a-f0-9]{40}|[a-z2-7]{32})(?:&|$)/i);
 return hash?hash[1].toLowerCase():url;
}
function viewingFile(record,target,isSeries){
 const selected=record.files.find(file=>file.index===record.fileIndex);
 if(!target)return isSeries?null:selected;
 if(selected&&record.seriesEpisode?.season===target.season&&record.seriesEpisode?.episode===target.episode)return selected;
 const season=record.torrentSeason===undefined?record.seriesEpisode?.season??null:record.torrentSeason;
 const matches=matchingFiles(record.files,target,season);
 return matches.length===1?matches[0]:null;
}
function sourceSizeLabels(media,records){
 const byTorrent=new Map();
 for(const record of records){
  if(!record?.url||!record.files?.length)continue;
  const sameQuery=media.searchQuery&&record.sourceMovie?.searchQuery&&media.name===record.sourceMovie.name;
  if(!sameQuery&&!sameMedia(media,record.movie)&&!sameMedia(media,record.sourceMovie))continue;
  const key=torrentSizeKey(record.url);
  if(!byTorrent.has(key))byTorrent.set(key,[]);
  byTorrent.get(key).push(record);
 }
 return source=>{
  const total=sourceSize(source,''),target=media.selectedEpisode;
  for(const record of byTorrent.get(torrentSizeKey(source.magnet))||[]){
   const file=viewingFile(record,target,media.type==='series');
   if(!(Number(file?.bytes)>0&&Number.isFinite(Number(file.bytes))))continue;
   const size=sourceSize(file),label=(target?'Серия ':'Файл ')+size;
   return total&&total!==size?label+' · Вся раздача '+total:label;
  }
  return total?'Вся раздача '+total:'Размер раздачи неизвестен';
 };
}
function episode(file,seasonHint){
 const path=String(file.path||file.name||''),parts=path.split(/[\\/]/),name=String(file.name||parts[parts.length-1]);
 const fallback=Number.isInteger(seasonHint)&&seasonHint>=0?seasonHint:null;
 let m=name.match(/(?:^|[^a-z0-9])s(\d{1,2})[ ._-]*e(\d{1,3})(?:[ ._-]*e?(\d{1,3}))?/i),season=null,number=null,prefix='';
 if(m){season=+m[1];number=+m[2];prefix=name.slice(0,m.index);}
 if(!m){m=name.match(/(?:^|\D)(\d{1,2})x(\d{1,3})(?!\d)/i);if(m){season=+m[1];number=+m[2];prefix=name.slice(0,m.index);}}
 if(number===null){
  for(const part of [name,...parts.reverse()]){
   const sm=part.match(/(?:season|сезон|(?:^|[ ._-])s)[ ._:-]*(\d{1,2})(?!\d)/i)||part.match(/(\d{1,2})[ ._-]*сезон/i);
   if(sm){season=+sm[1];break;}
  }
  const em=name.match(/(?:серия|эпизод|episode|(?:^|[ ._-])e)[ ._-]*(\d{1,3})(?!\d)/i)||name.match(/(?:^|\D)(\d{1,3})[ ._-]*(?:серия|эпизод)/i);
  if(em){number=+em[1];prefix=name.slice(0,em.index);}else if(season!==null||fallback!==null){const leading=name.match(/^(\d{1,3})(?:[ ._-]|\.[a-z]{2,4}$)/i);if(leading)number=+leading[1];}}
 if(number!==null&&season===null)season=fallback??1;
 return {...file,season,episode:number,seriesName:clean(prefix).replace(/[()\[\]-]+$/,'').trim(),label:number===null?name:`Серия ${number}`};
}
function files(items,seasonHint){return items.map(file=>episode(file,seasonHint)).sort((a,b)=>(a.season??999)-(b.season??999)||(a.episode??9999)-(b.episode??9999)||a.name.localeCompare(b.name,'ru',{numeric:true}));}
function nextFile(items,index){const list=files(items),current=list.find(f=>f.index===index);if(!current||current.episode===null)return null;return list.find(f=>f.episode!==null&&(f.season>current.season||f.season===current.season&&f.episode>current.episode))||null;}
const contentId=m=>m?.historyId||m?.imdbId||m?.id;
const mediaIds=m=>[m?.id,m?.imdbId,m?.historyId,...(Array.isArray(m?.ids)?m.ids:[])].filter(Boolean);
const sameMedia=(a,b)=>(a?.type||'movie')===(b?.type||'movie')&&mediaIds(a).some(id=>mediaIds(b).includes(id));
function uniqueMedia(items){const out=[];for(const m of items){if(!m?.id)continue;const i=out.findIndex(v=>sameMedia(v,m));if(i<0)out.push(m);else out[i]={...out[i],...m,id:out[i].id,ids:[...new Set([...mediaIds(out[i]),...mediaIds(m)])]};}return out;}
function filterMedia(items,kind='all',origin='all'){return items.filter(m=>(kind==='all'||(kind==='series'?m.type==='series'&&m.kind!=='show':kind===(m.kind||m.type)))&&(origin==='all'||m.origin===origin));}
function searchSelection(item,query){const hint=String(query||'').trim();return {...item,...(hint&&hint.length<=100?{searchHint:hint}:{})};}
function audioLanguages(source){if(source?.catalogSource&&source.audioLanguage==='ru')return ['ru'];const s=String(source?.title||'').replace(/[_.]+/g,' ').replace(/(?:\bsub(?:s|titles)?\b|субтитры)[\s:([+-]*(?:(?:rus|russian|рус|eng|english|ukr|ukrainian)\b[\s,)/+\]-]*)+/gi,' ').replace(/\b(?:rus|russian|eng|english|ukr|ukrainian)\s+sub(?:s|titles)?\b/gi,' '),out=[];
 if(/\b(?:rus|russian|dvo|mvo|avo)\b|русск(?:ая|ий|ое)\s+(?:озвучк|перевод|звук)|дубляж/i.test(s))out.push('ru');
 if(/\b(?:eng|english)\b|английск(?:ая|ий|ое)\s+(?:озвучк|звук)/i.test(s))out.push('en');
 if(/\b(?:ukr|ukrainian)\b|украинск(?:ая|ий|ое)\s+(?:озвучк|звук)/i.test(s))out.push('uk');
 return out;
}
function audioLabel(source){if(source?.catalogSource&&source.audioLanguage==='ru')return 'Русский звук · указан в подборке';const langs=audioLanguages(source);return langs.length?langs.map(v=>({ru:'RUS',en:'ENG',uk:'UKR'})[v]).join(' / ')+' · по названию раздачи':'Язык звука не указан';}
function languageRank(source,prefs){if(!prefs.language||prefs.language==='auto')return 0;const langs=audioLanguages(source);return langs.includes(prefs.language)?2:langs.length?0:1;}
function seriesId(item,file){if(item.movie?.type==='series'&&contentId(item.movie))return 'series:'+contentId(item.movie);if(file?.episode==null)return contentId(item.movie)||'link:'+item.url;const name=file.seriesName||String(item.sourceMovie?.name||item.movie?.name||'').split(/(?:s\d|сезон|season|\d+\s*сезон|серии)/i)[0];return name.trim()?'series:'+clean(name).toLowerCase():'series:'+item.url;}
function rankedSources(items,prefs){const resolution=s=>/2160|4k/i.test(s.quality+' '+s.title)?2160:/1080/i.test(s.quality+' '+s.title)?1080:/720/i.test(s.quality+' '+s.title)?720:0;
 return items.map((s,i)=>({s,i})).sort((a,b)=>Number(a.s.seeds===0)-Number(b.s.seeds===0)||languageRank(b.s,prefs)-languageRank(a.s,prefs)||(prefs.quality!=='auto'?Number(resolution(b.s)===+prefs.quality)-Number(resolution(a.s)===+prefs.quality):0)||(b.s.seeds||0)-(a.s.seeds||0)||a.i-b.i).map(x=>x.s);
}
function sourceSearchError(result,visibleCount){
 if(visibleCount||(result.items||[]).length||!(result.warnings||[]).length)return '';
 return result.successfulProviders===0?'Поисковые источники сейчас недоступны. Это не означает, что видео нет. Проверьте интернет или повторите позже.':'Часть источников не ответила, а в остальных ничего не найдено. Повторите поиск позже.';
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
function matchingFiles(items,target,seasonHint=target?.season){return files(items,seasonHint).filter(f=>f.season===target?.season&&f.episode===target?.episode);}
function sourceScope(text,target){
 const s=String(text||'').replace(/[–—]/g,'-');
 const season=s.match(/\bs(\d{1,3})(?:\s*-\s*s?(\d{1,3}))?/i)||s.match(/(\d{1,3})(?:\s*-\s*(\d{1,3}))?\s*сезон/i)||s.match(/(?:сезон[ыа]?|seasons?)\s*[:.]?\s*(\d{1,3})(?:\s*-\s*(\d{1,3}))?/i)||s.match(/\b(\d{1,3})x\d/i);
 const ep=s.match(/\bs\d+\s*e(\d{1,4})(?:\s*-\s*(?:s\d+)?e?(\d{1,4}))?/i)||s.match(/\b\d+x(\d{1,4})(?:\s*-\s*(\d{1,4}))?/i)||s.match(/(?:серии|серия|episodes?)\s*[:.]?\s*(\d{1,4})(?:\s*-\s*(\d{1,4}))?/i)||s.match(/(\d{1,4})(?:\s*-\s*(\d{1,4}))?\s*сер(?:ии|ия|ий)/i);
 const includes=(m,n)=>n>=+m[1]&&n<=+(m[2]||m[1]);
 const seasonList=s.match(/(?:сезон[ыа]?|seasons?)\s*[:.]?\s*(\d{1,3}(?:\s*-\s*\d{1,3})?(?:\s*,\s*\d{1,3}(?:\s*-\s*\d{1,3})?)+)/i);
 const episodeList=s.match(/(?:серии|серия|episodes?)\s*[:.]?\s*(\d{1,4}(?:\s*-\s*\d{1,4})?(?:\s*,\s*\d{1,4}(?:\s*-\s*\d{1,4})?)+)/i);
 const inList=(m,n)=>m[1].split(',').some(part=>{const pair=part.trim().split(/\s*-\s*/).map(Number);return n>=pair[0]&&n<=(pair[1]??pair[0]);});
 if(seasonList?!inList(seasonList,target.season):season&&!includes(season,target.season))return 3;
 if(episodeList?!inList(episodeList,target.episode):ep&&!includes(ep,target.episode))return 3;
 if(seasonList||episodeList)return 1;
 if(season&&ep&&!season[2]&&!ep[2])return 0;
 if(season)return 1;
 return 2;
}
function episodeSources(items,target,prefs){return rankedSources(items,prefs).map(s=>({...s,episodeScope:s.catalogSource?(s.season!==target.season||s.episode>0&&s.episode!==target.episode||s.episodeFrom&&(target.episode<s.episodeFrom||target.episode>s.episodeTo)?3:s.episode>0?0:1):sourceScope(s.title,target)})).filter(s=>s.episodeScope!==3).sort((a,b)=>Number(a.seeds===0)-Number(b.seeds===0)||languageRank(b,prefs)-languageRank(a,prefs)||a.episodeScope-b.episodeScope);}
function readability(value){return {size:['normal','large','largest'].includes(value?.size)?value.size:'normal',contrast:value?.contrast===true,motion:value?.motion===true};}

function playbackDuration(item,state,savedFile,savedHistory){
 const previous=savedHistory?.url===item.url&&savedHistory.fileIndex===item.fileIndex?savedHistory.duration:0;
 return [state?.duration,savedFile?.duration,previous].find(n=>Number.isFinite(n)&&n>0)||0;
}
function segmentQuery(item){
 const imdbId=mediaIds(item?.movie).find(id=>typeof id==='string'&&/^tt\d{5,12}$/.test(id)),season=item?.seriesEpisode?.season,episode=item?.seriesEpisode?.episode;
 if(item?.kind!=='torrent'||!imdbId)return null;
 if(item.movie?.type==='movie')return {imdbId,isMovie:true};
 if(item.movie?.type!=='series'||!Number.isInteger(season)||season<0||season>100||!Number.isInteger(episode)||episode<1||episode>10000)return null;
 return {imdbId,season,episode};
}
function segmentsMatch(data,query){
 return !!query&&data?.imdb_id===query.imdbId&&(query.isMovie?data.media_type==='movie'&&data.is_movie===true:data.media_type==='tv'&&data.season===query.season&&data.episode===query.episode);
}
function verifiedSegment(segment,maxLength,minVotes=1){
 const start=segment?.start_ms,end=segment?.end_ms;
 if(!Number.isFinite(start)||!Number.isFinite(end)||start<0||end-start<5000||end-start>maxLength||!Number.isFinite(segment.confidence)||segment.confidence<.9||segment.confidence>1||!Number.isInteger(segment.submission_count)||segment.submission_count<minVotes)return null;
 return {start:Math.round(start),end:Math.round(end)};
}
function introSegment(data,query){return segmentsMatch(data,query)&&!data.no_intro?verifiedSegment(data.intro,300000,1):null;}
function creditsSegment(data,query){
 if(!segmentsMatch(data,query)||data.no_outro)return null;
 const credits=verifiedSegment(data.outro,900000);if(!credits)return null;
 const postCredits=verifiedSegment(data.post_credits,600000);
 return {...credits,matchEnd:Math.max(credits.end,postCredits?.end||0)};
}
function creditsReached(segment,state){
 const duration=state?.duration,position=state?.position;
 if(state?.loading||!Number.isFinite(duration)||duration<=0||!Number.isFinite(position)||position<0)return false;
 if(segment&&segment.start>=duration/2&&Math.abs(segment.matchEnd-duration)<=15000&&position>=segment.start)return true;
 // Without matching timestamps, finish only after 98% and at most two minutes remain.
 return position>=duration*.98&&duration-position<=120000;
}
function introTarget(segment,state){
 if(!segment||state?.loading||!Number.isFinite(state?.duration)||segment.end>=state.duration||!Number.isFinite(state?.position)||state.position<segment.start||state.position>=segment.end)return null;
 return segment.end;
}
function switchSource(item,source,state){
 return {...item,url:source.magnet,torrentSeason:item.seriesEpisode?.season,fileIndex:undefined,files:[],position:Math.max(0,state.position??item.position??0),paused:!(state.playWhenReady??state.playing??!item.paused),completed:false};
}
function historyCard(record){
 const m=record?.movie,id=mediaIds(m).find(id=>/^(?:tt\d{5,12}|kp:\d{1,9}|tmdb:(?:movie|series):\d{1,9})$/.test(id));if(!id)return null;
 const season=record.seriesEpisode?.season;
 return {...m,id,selectedEpisode:null,...(Number.isInteger(season)&&season>=0&&season<=999?{browseSeason:season}:{})};
}
function castOrder(people,names=[]){
 const rank=p=>{const i=names.findIndex(n=>[p.name,p.originalName].some(v=>String(v||'').toLowerCase()===String(n).toLowerCase()));return i<0?Infinity:i;};
 const billing=p=>Number.isInteger(p.billingOrder)&&p.billingOrder>0?p.billingOrder:Infinity;
 return [...people].sort((a,b)=>{const ar=rank(a),br=rank(b);if(ar!==br)return ar<br?-1:1;const ap=billing(a),bp=billing(b);return ap===bp?0:ap<bp?-1:1;});
}
function filmographyNewest(items){
 const stamp=m=>{const year=String(m.year||m.releaseInfo||m.released||'').match(/^(\d{4})/);if(!year)return '';const date=String(m.released||'').match(/^(\d{4}-\d{2}-\d{2})/);return date&&date[1].startsWith(year[1])?date[1]:year[1]+'-00-00';};
 return [...items].sort((a,b)=>stamp(b).localeCompare(stamp(a)));
}
const api={historyCard,castOrder,filmographyNewest,playbackDuration,segmentQuery,introSegment,introTarget,creditsSegment,creditsReached,switchSource,mediaGenres,genreOptions,sourceSize,sourceSizeLabels,searchSelection,audioLanguages,audioLabel,contentId,mediaIds,sameMedia,uniqueMedia,filterMedia,bufferAhead,bufferingLabel,catalogueEpisodes,futureEpisode,nextCatalogueEpisode,matchingFiles,sourceScope,episodeSources,readability,episode,files,nextFile,seriesId,rankedSources,sourceSearchError,diagnosis,channelKey,orderedChannels,moveItem,seekTarget};
if(typeof module!=='undefined')module.exports=api;else root.TVFeatures=api;
})(typeof window!=='undefined'?window:globalThis);
