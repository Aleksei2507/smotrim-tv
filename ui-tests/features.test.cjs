const {test}=require('node:test');const assert=require('node:assert/strict');const f=require('../app/src/main/assets/ui/features.js');
test('episode conventions, folders and numeric order',()=>{
 assert.equal(f.episode({name:'Show.S02E10.mkv'}).season,2);assert.equal(f.episode({name:'Show.2x03.mkv'}).episode,3);
 assert.equal(f.episode({name:'01.mkv',path:'Show/Сезон 3/01.mkv'}).season,3);
 assert.equal(f.episode({name:'Фильм.2025.1080p.mkv'}).episode,null);
 assert.deepEqual(f.files([{name:'Show.S01E10.mkv'},{name:'Show.S01E02.mkv'}]).map(x=>x.episode),[2,10]);
});
test('next episode skips extras and duplicate versions',()=>{
 const files=[{index:1,name:'Show.S01E01.mkv'},{index:2,name:'Show.S01E01.4k.mkv'},{index:3,name:'Show.S02E01.mkv'},{index:4,name:'extra.mkv'}];
 assert.equal(f.nextFile(files,1).index,3);assert.equal(f.nextFile(files,3),null);assert.equal(f.nextFile(files,4),null);
 assert.equal(f.seriesId({url:'a',movie:{}},f.episode(files[0])),f.seriesId({url:'b',movie:{}},f.episode(files[2])));
});
test('zero seeds last, preferred quality then language',()=>{
 const list=[{title:'1080p rus',seeds:20},{title:'2160p rus',seeds:0},{title:'2160p rus',seeds:3}];
 assert.deepEqual(f.rankedSources(list,{quality:'2160',language:'ru'}),[list[2],list[0],list[1]]);
});
test('channel order survives URL updates and puts new channels last',()=>{
 const channels=[{id:'b',url:'new-b'},{id:'a',url:'new-a'},{id:'c',url:'c'}];
 assert.deepEqual(f.orderedChannels(channels,['a','b']).map(c=>c.id),['a','b','c']);
 const moved=f.moveItem(['a','b','c'],0,2);assert.deepEqual(moved,['b','c','a']);assert.deepEqual(f.moveItem(moved,2,0),['a','b','c']);
});
test('seeking clamps boundaries and accelerates held key',()=>{
 assert.equal(f.seekTarget(5000,100000,-1,0),0);assert.equal(f.seekTarget(0,100000,1,0),10000);assert.equal(f.seekTarget(0,100000,1,6),30000);assert.equal(f.seekTarget(0,100000,1,15),99000);
});
test('diagnostics distinguishes cache and playable reserve',()=>{
 assert.match(f.diagnosis({buffered:60000,position:20000,cached:9999999,playing:true}),/40 сек/);
 assert.match(f.diagnosis({loading:true,rate:0,peers:0}),/Нет активной загрузки/);
 assert.match(f.diagnosis({loading:true,rate:10,peers:2}),/Видео подгружается/);
});
