// Deterministic UI fixture; never included in the APK.
let fixtureUpdate={status:'idle',installedVersion:'0.2.2',autoCheck:true,repository:'Aleksei2507/smotrim-tv'};
const fixtureNotes='Обновление приложения через GitHub Releases.\nПроверка подписи APK и сохранение истории.\n'+Array.from({length:14},(_,i)=>'Проверка прокрутки пультом, строка '+(i+1)).join('\n')+'\n<script>Этот текст должен остаться текстом</script>';
window.Native={
 request(id,method,json){(async()=>{
  const args=JSON.parse(json);let answer;
  if(method==='bootstrap'){
   const [movies,channels]=await Promise.all([fetch('movies.json').then(r=>r.json()),fetch('preview-channels.json').then(r=>r.json())]);
   answer={movies:movies.metas,channels,bufferMb:96,customPlaylist:true,update:fixtureUpdate};
  }else if(method==='update-check'){
   fixtureUpdate={...fixtureUpdate,status:'available',version:'0.2.3',bytes:70000000,notes:fixtureNotes,checkedAt:Date.now()};answer=fixtureUpdate;
  }else if(method==='update-state')answer=fixtureUpdate;
  else if(method==='update-auto'){fixtureUpdate={...fixtureUpdate,autoCheck:args.enabled};answer=fixtureUpdate;}
  else if(method==='update-download'){
   fixtureUpdate={...fixtureUpdate,status:'downloading',received:0};answer=fixtureUpdate;
   let received=0;const timer=setInterval(()=>{
    received=Math.min(received+14000000,70000000);fixtureUpdate={...fixtureUpdate,received,status:received===70000000?'ready':'downloading'};
    window.nativeEvent('update',fixtureUpdate);if(received===70000000)clearInterval(timer);
   },1000);
  }else if(method==='catalog')answer=[];
  else throw new Error('Тест: запрос не предусмотрен');
  window.nativeReply(id,answer,null);
 })().catch(e=>window.nativeReply(id,null,e.message));},
 installUpdate(){window.nativeEvent('toast','Тест: здесь откроется установщик Android');},
 exit(){}, command(){}
};
