const {test}=require('node:test');
const assert=require('node:assert/strict');
const {snapshot}=require('../app/src/main/assets/ui/home-feed.js');
const f=require('../app/src/main/assets/ui/features.js');
const make=state=>snapshot({history:{},channels:[],favoriteChannels:{},favoriteOrder:[],...state},m=>m.name,s=>s,f.orderedChannels,f.channelKey);
test('home exports unfinished items in recent order without playback URLs or credentials',()=>{
 const state={history:{older:{movie:{name:'Фильм',background:'https://example.org/p.jpg',apiKey:'private'},position:20000,duration:90000,updated:1,url:'magnet:private',files:['secret']},episode:{movie:{name:'Сериал'},position:1234,duration:5000,updated:3,episode:'Сезон 2 · Серия 4'},done:{movie:{name:'Конец'},position:5000,updated:4,completed:true},zero:{movie:{name:'Не начат'},position:0}}};
 const feed=make(state);assert.deepEqual(feed.history.map(r=>r.key),['episode','older']);
 assert.equal(feed.history[0].subtitle,'Сезон 2 · Серия 4');assert.equal(feed.history[0].position,1234);
 assert.equal(JSON.stringify(feed).includes('private'),false);assert.equal(JSON.stringify(feed).includes('secret'),false);
});
test('favorite channels use their saved order and stable IDs, not stream URLs',()=>{
 const feed=make({channels:[{id:'one',name:'Первый',url:'https://a/private'},{id:'two',name:'Второй',url:'https://b/private'},{id:'three',name:'Третий',url:'https://c'}],favoriteChannels:{'https://a/private':true,'https://b/private':true},favoriteOrder:['two','one']});
 assert.deepEqual(feed.channels.map(c=>c.key),['two','one']);assert.equal(JSON.stringify(feed).includes('/private'),false);
});
test('removal and clear history produce empty rows; feed is bounded',()=>{
 assert.deepEqual(make({}).history,[]);assert.deepEqual(make({}).channels,[]);
 const history=Object.fromEntries(Array.from({length:40},(_,i)=>[String(i),{movie:{name:'Видео '+i},position:1,updated:i}]));assert.equal(make({history}).history.length,12);
});
