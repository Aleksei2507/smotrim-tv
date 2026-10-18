package ru.smotrim.tv;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import androidx.webkit.WebViewAssetLoader;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.*;
import androidx.media3.datasource.*;
import androidx.media3.ui.PlayerView;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class MainActivity extends Activity {
    private static final String LOCAL="https://appassets.androidplatform.net/assets/ui/index.html";
    private WebView web;private PlayerView video;private ExoPlayer player;private FrameLayout root;
    private volatile int webGeneration;private AlertDialog recoveryDialog;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService network=Executors.newFixedThreadPool(4),torrentWork=Executors.newSingleThreadExecutor(),cleanup=Executors.newSingleThreadExecutor();
    private volatile TorrentEngine torrent;
    private volatile JSONArray liveChannels=new JSONArray(),liveBackups=new JSONArray();
    private volatile boolean customPlaylist;private LivePlayback livePlayback;
    private int playbackGeneration;private boolean destroyed;private long resumePosition;private JSONObject now;
    private final SleepTimer sleepTimer=new SleepTimer();
    private boolean selectingFile;private JSONArray pendingFiles=new JSONArray();
    private ApkUpdates updates;private boolean resumed,awaitingUpdatePermission,installPreparing;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);fullscreen();
        updates=new ApkUpdates(this,new ApkUpdates.Listener(){
            public void changed(JSONObject state){event("update",state);}
            public void ready(){ui.post(()->{if(!destroyed&&resumed&&now==null)installUpdate();});}
        });
        root=new FrameLayout(this);root.setBackgroundColor(Color.rgb(17,21,27));
        video=new PlayerView(this);video.setUseController(false);video.setKeepContentOnPlayerReset(false);root.addView(video,new FrameLayout.LayoutParams(-1,-1));
        setContentView(root);attachWebView();
        File old=new File(getFilesDir(),"torrents");torrentWork.execute(()->TorrentEngine.deleteTree(old));
        ui.postDelayed(ticker,1000);
    }
    private void attachWebView(){
        if(destroyed)return;webGeneration++;
        web=new WebView(this);web.setBackgroundColor(Color.TRANSPARENT);root.addView(web,new FrameLayout.LayoutParams(-1,-1));
        WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setMediaPlaybackRequiresUserGesture(true);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);s.setSupportZoom(false);
        WebViewAssetLoader loader=new WebViewAssetLoader.Builder().addPathHandler("/assets/",new WebViewAssetLoader.AssetsPathHandler(this)).build();
        web.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){return loader.shouldInterceptRequest(request.getUrl());}
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return !request.getUrl().toString().equals(LOCAL);}
            @Override public boolean onRenderProcessGone(WebView view,RenderProcessGoneDetail detail){return recoverRenderer(view,detail.didCrash());}
        });
        web.addJavascriptInterface(new Bridge(),"Native");
        if((getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0)WebView.setWebContentsDebuggingEnabled(true);
        web.loadUrl(LOCAL);web.requestFocus();
    }
    private boolean recoverRenderer(WebView failed,boolean crashed){
        boolean current=failed==web;
        if(current){web=null;webGeneration++;stopPlayback(false);PlaybackHealth.rendererGone(this,crashed);}
        root.removeView(failed);failed.destroy();
        if(current&&!destroyed){recoveryDialog=new AlertDialog.Builder(this).setTitle("Восстановим интерфейс")
            .setMessage(crashed?"Произошёл сбой интерфейса. Просмотр остановлен. Вернитесь в приложение и продолжите видео из истории.":"Android остановил интерфейс. Возможно, не хватило оперативной памяти. Просмотр остановлен, последний сохранённый прогресс доступен в истории.")
            .setCancelable(false).setPositiveButton("Вернуться",(dialog,which)->{recoveryDialog=null;attachWebView();}).create();recoveryDialog.show();}
        return true;
    }
    private void fullscreen(){getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);}
    private void js(String script){WebView target=web;if(!destroyed&&target!=null)ui.post(()->{if(!destroyed&&web==target)target.evaluateJavascript(script,null);});}
    private static String jsonValue(Object value){if(value==null)return "null";if(value instanceof String)return JSONObject.quote((String)value);return value.toString();}
    private void event(String type,Object value){js("window.nativeEvent&&window.nativeEvent("+JSONObject.quote(type)+","+jsonValue(value)+")");}
    private void reply(int id,Object value,String error){js("window.nativeReply&&window.nativeReply("+id+","+(value==null?"null":jsonValue(value))+","+(error==null?"null":JSONObject.quote(error))+")");}
    private String asset(String name)throws IOException{try(InputStream in=getAssets().open(name)){return readStream(in);}}
    private String cached(String name,String fallback)throws IOException{File f=new File(getFilesDir(),name);return f.isFile()?new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8):asset(fallback);}
    private void cache(String name,String text)throws IOException{File f=new File(getFilesDir(),name+".tmp");Files.write(f.toPath(),text.getBytes(StandardCharsets.UTF_8));Files.move(f.toPath(),new File(getFilesDir(),name).toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
    private void loadChannels()throws Exception{
        String bundled=asset("channels.m3u"),data=cached("channels.m3u","channels.m3u");
        SharedPreferences prefs=getSharedPreferences("iptv",MODE_PRIVATE);
        if(prefs.contains("custom"))customPlaylist=prefs.getBoolean("custom",false);
        else {
            byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8));StringBuilder hex=new StringBuilder();for(byte b:hash)hex.append(String.format(Locale.ROOT,"%02x",b&255));
            customPlaylist=!data.equals(bundled)&&!hex.toString().equals("8dc6357c23ea7d181b593ea5759df272fa92e388de0dfb13bfa09f941dcecaca");
            prefs.edit().putBoolean("custom",customPlaylist).apply();
        }
        liveChannels=Sources.parseM3u(data);
        try{liveBackups=new JSONArray(cached("channel-backups.json","channel-backups.json"));}catch(Exception ignored){liveBackups=new JSONArray(asset("channel-backups.json"));}
    }
    private void refreshChannelBackups(){
        SharedPreferences prefs=getSharedPreferences("iptv",MODE_PRIVATE);
        if(customPlaylist||System.currentTimeMillis()-prefs.getLong("backupsUpdated",0)<86400000L)return;
        network.execute(()->{try{
            String json=Sources.text("https://iptv-org.github.io/api/streams.json");JSONArray updated=LiveChannels.backupsFromApi(json,liveChannels);
            if(updated.length()>0){cache("channel-backups.json",updated.toString());liveBackups=updated;prefs.edit().putLong("backupsUpdated",System.currentTimeMillis()).apply();}
        }catch(Exception ignored){}});
    }
    public final class Bridge {
        private final int generation=webGeneration;
        private void post(Runnable action){ui.post(()->{if(generation==webGeneration&&!destroyed)action.run();});}
        @JavascriptInterface public void request(int id,String method,String args){network.execute(()->{
            if(generation!=webGeneration||destroyed)return;
            try {
                JSONObject a=new JSONObject(args);Object answer;
                switch(method){
                    case "bootstrap": loadChannels();answer=new JSONObject().put("movies",new JSONObject(cached("movies.json","movies.json")).getJSONArray("metas")).put("series",new JSONObject(cached("series.json","series.json")).getJSONArray("metas")).put("channels",liveChannels).put("customPlaylist",customPlaylist).put("freeBytes",getFilesDir().getUsableSpace()).put("bufferMb",selectedBufferMb()).put("bufferStorage",selectedBufferStorage()).put("update",updates.state()).put("preferences",playbackPreferences());break;
                    case "preferences": answer=playbackPreferences();break;
                    case "memory-info": answer=PlaybackHealth.snapshot(MainActivity.this);break;
                    case "preferences-set": {
                        SharedPreferences.Editor editor=getSharedPreferences("playback",MODE_PRIVATE).edit();
                        String quality=a.optString("quality","auto"),language=a.optString("language","ru");
                        if(!Arrays.asList("auto","720","1080","2160").contains(quality)||!language.matches("(?:auto|[a-zA-Z]{2,3}(?:-[a-zA-Z0-9]{2,8})*)"))throw new IOException("Неизвестное предпочтение");
                        editor.putString("quality",quality).putString("audioLanguage",language).putBoolean("subtitles",a.optBoolean("subtitles")).apply();answer=playbackPreferences();break;
                    }
                    case "epg": answer=ProgrammeGuide.load(new File(getCacheDir(),"epg.json"),getSharedPreferences("iptv",MODE_PRIVATE).getString("epg",ProgrammeGuide.DEFAULT_URL),liveChannels,a.optBoolean("refresh"));break;
                    case "update-state": answer=updates.state();break;
                    case "update-check": updates.check(a.optBoolean("automatic"));answer=updates.state();break;
                    case "update-download": updates.download();answer=updates.state();break;
                    case "update-auto": updates.setAutoCheck(a.getBoolean("enabled"));answer=updates.state();break;
                    case "buffer-set": {
                        int mb=a.getInt("mb");String storage=a.optString("storage","ram");
                        if(!Arrays.asList("ram","disk").contains(storage)||!(storage.equals("disk")?TorrentBufferPolicy.diskSupported(mb):TorrentBufferPolicy.supported(mb)))throw new IOException("Недопустимый размер буфера");
                        getSharedPreferences("playback",MODE_PRIVATE).edit().putString("torrentBufferStorage",storage).putInt(storage.equals("disk")?"torrentDiskBufferMb":"torrentBufferMb",mb).apply();
                        answer=new JSONObject().put("mb",mb).put("storage",storage);break;
                    }
                    case "catalog-state": answer=mediaCatalog().state();break;
                    case "catalog": {
                        JSONObject result=mediaCatalog().catalog(a.optString("query"),a.optString("type"),a.optInt("page",1));answer=result;
                        if(a.optString("query").isEmpty()&&a.optInt("page",1)==1&&result.getJSONArray("items").length()>0)cache("series".equals(a.optString("type"))?"series.json":"movies.json",new JSONObject().put("metas",result.getJSONArray("items")).toString());break;
                    }
                    case "detail": case "series-detail": {
                        JSONObject selected=a.optJSONObject("item");if(selected==null)selected=new JSONObject().put("id",a.getString("id")).put("type",method.equals("series-detail")?"series":"movie");
                        answer=mediaCatalog().detail(selected,a.optBoolean("refresh"));break;
                    }
                    case "series-season": answer=mediaCatalog().season(a.getJSONObject("item"),a.getInt("season"));break;
                    case "series-torrents": case "torrents": answer=CatalogSources.search(a,method.equals("series-torrents"));break;
                    case "channels": {
                        String data=Sources.text(Sources.IPTV);JSONArray channels=Sources.parseM3u(data);if(channels.length()==0)throw new IOException("Список каналов пуст");cache("channels.m3u",data);liveChannels=channels;customPlaylist=false;getSharedPreferences("iptv",MODE_PRIVATE).edit().putBoolean("custom",false).apply();refreshChannelBackups();answer=channels;break;
                    }
                    case "storage": answer=getFilesDir().getUsableSpace();break;
                    default:throw new IOException("Неизвестный запрос");
                }
                post(()->reply(id,answer,null));
            }catch(Exception e){post(()->reply(id,null,message(e)));}
        });}
        @JavascriptInterface public void play(String json){post(()->{try{startPlayback(new JSONObject(json));}catch(Exception e){event("error",message(e));}});}
        @JavascriptInterface public void trailer(String videoId){post(()->{if(!TrailerLauncher.open(MainActivity.this,videoId))event("toast","Не удалось открыть трейлер. Установите или включите YouTube на телевизоре.");});}
        @JavascriptInterface public void command(String cmd){post(()->commandPlayer(cmd));}
        @JavascriptInterface public void input(String kind){post(()->showInput(kind,kind.equals("epg")?getSharedPreferences("iptv",MODE_PRIVATE).getString("epg",ProgrammeGuide.DEFAULT_URL):""));}
        @JavascriptInterface public void inputSearch(String kind,String value){if(kind.equals("search")||kind.equals("search-channel"))post(()->showInput(kind,value));}
        @JavascriptInterface public void voiceSearch(String kind){if("search".equals(kind)||"search-channel".equals(kind))post(()->startVoiceSearch("search-channel".equals(kind)));}
        @JavascriptInterface public void installUpdate(){post(MainActivity.this::installUpdate);}
        @JavascriptInterface public void exit(){post(()->new AlertDialog.Builder(MainActivity.this).setTitle("Выйти из Смотрим?").setNegativeButton("Остаться",null).setPositiveButton("Выйти",(d,w)->finish()).show());}
    }
    private static String readStream(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");}
    private static JSONObject inputResult(String kind,String value){JSONObject o=new JSONObject();try{o.put("kind",kind).put("value",value);}catch(JSONException ignored){}return o;}
    private static String message(Throwable e){String m=e.getMessage();return m==null?e.getClass().getSimpleName():m;}
    private MediaCatalog mediaCatalog(){SharedPreferences p=getSharedPreferences("catalog-credentials",MODE_PRIVATE);return new MediaCatalog(new File(getCacheDir(),"media-catalog"),p.getString("kp",""),p.getString("tmdb",""));}
    private void catalogCredential(String provider){
        if(!provider.equals("kp")&&!provider.equals("tmdb"))return;
        EditText input=new EditText(this);input.setSingleLine(true);input.setTextSize(18);input.setPadding(28,16,28,16);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint(provider.equals("kp")?"Ключ Kinopoisk API Unofficial":"API key или Read Access Token TMDB");
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(provider.equals("kp")?"Подключить русский каталог":"Подключить TMDB")
            .setMessage((provider.equals("kp")?"Ключ из личного кабинета kinopoiskapiunofficial.tech. Это независимый сервис.":"Ключ из themoviedb.org → Настройки → API.")+" Ключ хранится только в приложении на этом телевизоре.")
            .setView(input).setNegativeButton("Отмена",null).setNeutralButton("Отключить",(d,w)->{getSharedPreferences("catalog-credentials",MODE_PRIVATE).edit().remove(provider).apply();catalogChanged();})
            .setPositiveButton("Проверить и сохранить",null).create();dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String value=input.getText().toString().trim();if(!value.matches("[A-Za-z0-9._-]{16,2048}")){input.setError("Проверьте ключ");return;}dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            network.execute(()->{boolean ok=false;try{MediaCatalog.request(provider,provider.equals("kp")?"/api/v2.2/films/301":"/configuration",value);ok=true;}catch(Exception ignored){}final boolean success=ok;ui.post(()->{if(destroyed)return;if(success){getSharedPreferences("catalog-credentials",MODE_PRIVATE).edit().putString(provider,value).apply();dialog.dismiss();catalogChanged();}else{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);input.setError("Не удалось подключиться. Проверьте ключ и интернет.");}});});});
        input.requestFocus();dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }
    private void catalogChanged(){TorrentEngine.deleteTree(new File(getCacheDir(),"media-catalog"));try{event("catalog-state",mediaCatalog().state());}catch(Exception ignored){}}
    private void startVoiceSearch(boolean channels){
        try{startActivityForResult(VoiceSearch.intent(channels),channels?VoiceSearch.CHANNELS:VoiceSearch.MOVIES);}
        catch(ActivityNotFoundException|SecurityException unavailable){event("toast","Голосовой ввод недоступен. Проверьте приложение Google и микрофон пульта или введите название клавиатурой.");}
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=VoiceSearch.MOVIES&&requestCode!=VoiceSearch.CHANNELS)return;
        if(resultCode!=RESULT_OK)return;
        String value=VoiceSearch.result(data);
        if(value.isEmpty())event("toast","Не удалось разобрать речь. Попробуйте ещё раз.");
        else event("voice-input",inputResult(requestCode==VoiceSearch.CHANNELS?"search-channel":"search",value));
    }
    private JSONObject playbackPreferences()throws JSONException{
        SharedPreferences p=getSharedPreferences("playback",MODE_PRIVATE);
        return new JSONObject().put("quality",p.getString("quality","auto")).put("language",p.getString("audioLanguage","ru")).put("subtitles",p.getBoolean("subtitles",false));
    }
    private String selectedBufferStorage(){return "disk".equals(getSharedPreferences("playback",MODE_PRIVATE).getString("torrentBufferStorage","ram"))?"disk":"ram";}
    private int selectedRamBufferMb(){return TorrentBufferPolicy.requested(getSharedPreferences("playback",MODE_PRIVATE).getInt("torrentBufferMb",TorrentBufferPolicy.DEFAULT_MB));}
    private int selectedBufferMb(){return "disk".equals(selectedBufferStorage())?TorrentBufferPolicy.diskRequested(getSharedPreferences("playback",MODE_PRIVATE).getInt("torrentDiskBufferMb",TorrentBufferPolicy.DISK_DEFAULT_MB)):selectedRamBufferMb();}
    private void installUpdate(){
        if(destroyed||!resumed||installPreparing)return;
        if(now!=null){event("toast","Завершите просмотр, затем откройте Настройки → Обновления");return;}
        if(!getPackageManager().canRequestPackageInstalls()){
            awaitingUpdatePermission=true;
            try{startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())));}
            catch(ActivityNotFoundException e){awaitingUpdatePermission=false;event("toast","Разрешите установку для Смотрим в настройках Google TV, затем нажмите «Установить»");}
            return;
        }
        installPreparing=true;
        network.execute(()->{
            try{
                File apk=updates.verifiedApk();
                Uri uri=androidx.core.content.FileProvider.getUriForFile(this,getPackageName()+".updates",apk);
                ui.post(()->{installPreparing=false;if(destroyed||!resumed||now!=null)return;
                    try{Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(intent);}
                    catch(ActivityNotFoundException|SecurityException e){event("toast","Не удалось открыть установщик Android. Попробуйте ещё раз");}
                });
            }catch(Exception e){ui.post(()->{installPreparing=false;event("update",updates.state());event("toast",message(e));});}
        });
    }
    private int availableBufferMb(int requested){ActivityManager manager=(ActivityManager)getSystemService(ACTIVITY_SERVICE);if(manager==null)return TorrentBufferPolicy.MIN_MB;ActivityManager.MemoryInfo memory=new ActivityManager.MemoryInfo();manager.getMemoryInfo(memory);return TorrentBufferPolicy.effective(requested,memory.totalMem,memory.availMem,memory.threshold,memory.lowMemory);}
    private void startPlayback(JSONObject item)throws Exception {
        stopPlayback(false,false);now=item;resumePosition=Math.max(0,item.optLong("position"));final int generation=playbackGeneration;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if("torrent".equals(item.optString("kind"))){
            int requested=selectedBufferMb();boolean disk="disk".equals(selectedBufferStorage());
            int bufferMb=disk?TorrentBufferPolicy.diskEffective(requested,getFilesDir().getUsableSpace()):availableBufferMb(requested);
            if(disk&&bufferMb==0){disk=false;bufferMb=availableBufferMb(selectedRamBufferMb());event("toast","Для дискового буфера мало места. Используем "+bufferMb+" МБ оперативной памяти.");}
            else if(bufferMb<requested)event("toast",disk?"Буфер уменьшен до "+bufferMb+" МБ: оставляем свободное место для Android.":"Используем буфер "+bufferMb+" МБ вместо "+requested+" МБ, чтобы оставить оперативную память плееру и Android.");
            TorrentEngine engine=new TorrentEngine(new File(getApplicationInfo().nativeLibraryDir,"libtorrserver.so"),new File(getFilesDir(),"torrents/"+UUID.randomUUID()),bufferMb*TorrentBufferPolicy.MIB,disk);torrent=engine;
            event("loading","Подключаемся к раздающим…");
            torrentWork.execute(()->{
                try {
                    JSONArray choices=engine.prepare(item.getString("url"));
                    ui.post(()->{if(generation!=playbackGeneration||engine.isClosed())return;
                        pendingFiles=choices;event("files",choices);
                        int savedIndex=item.optInt("fileIndex",-1);boolean foundSaved=false;
                        for(int i=0;i<choices.length();i++)if(choices.optJSONObject(i).optInt("index")==savedIndex)foundSaved=true;
                        if(item.optJSONObject("seriesEpisode")!=null){selectingFile=true;event("choose-files",choices);}
                        else if(foundSaved)selectFile(engine,savedIndex,generation);
                        else if(choices.length()==1)selectFile(engine,choices.optJSONObject(0).optInt("index"),generation);
                        else {
                            selectingFile=true;event("choose-files",choices);
                        }
                    });
                }catch(Throwable e){playbackError(generation,e);}
            });
        }else{
            String url=item.getString("url");if(!Sources.webUrl(url))throw new IOException("Некорректная ссылка канала");
            JSONObject selected=item.optJSONObject("channel");if(selected==null)selected=new JSONObject().put("url",url).put("id","");
            final String channelId=selected.optString("id",url);SharedPreferences prefs=getSharedPreferences("iptv",MODE_PRIVATE);
            List<JSONObject> candidates=LiveChannels.candidates(selected,liveChannels,customPlaylist?new JSONArray():liveBackups,prefs.getString("working:"+channelId,""));
            createPlayer(null);
            livePlayback=new LivePlayback(player,candidates,new LivePlayback.Listener(){
                public void connecting(String message){if(generation==playbackGeneration)event("loading",message);}
                public void playing(String workingUrl){if(generation==playbackGeneration)prefs.edit().putString("working:"+channelId,workingUrl).apply();}
                public void failed(String message){playbackError(generation,new IOException(message));}
            });livePlayback.start();
        }
    }
    private void selectFile(TorrentEngine engine,int index,int generation){
        torrentWork.execute(()->{try{
            engine.select(index);
            ui.post(()->{if(generation!=playbackGeneration||engine.isClosed())return;
                MediaSource source=new ProgressiveMediaSource.Factory(()->new TorrentDataSource(engine)).createMediaSource(MediaItem.fromUri(Uri.fromFile(engine.file())));
                selectingFile=false;event("selectedFile",index);createPlayer(source);
            });
        }catch(Throwable e){playbackError(generation,e);}});
    }
    private void playbackError(int generation,Throwable error){ui.post(()->{if(generation!=playbackGeneration)return;String msg=message(error);stopPlayback(false);event("error",msg);});}
    private void createPlayer(MediaSource source){
        player=new ExoPlayer.Builder(this).setLoadControl(PlaybackLoadControl.create()).setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true);
        player.setTrackSelectionParameters(PlaybackTracks.apply(this,player.getTrackSelectionParameters()));
        video.setPlayer(player);final ExoPlayer created=player;final int generation=playbackGeneration;final boolean live=source==null;
        player.addListener(new Player.Listener(){
            @Override public void onPlayerError(PlaybackException error){if(player==created&&!live)playbackError(generation,new IOException(torrent!=null&&torrent.failure()!=null?torrent.failure():"Не удалось воспроизвести: "+error.getErrorCodeName()+". Попробуйте другую раздачу."));}
            @Override public void onPlaybackStateChanged(int state){if(player==created&&!live&&state==Player.STATE_ENDED){
                try{event("finished",new JSONObject().put("sleep",sleepTimer.afterEpisode()));}catch(JSONException ignored){}
                if(sleepTimer.afterEpisode()){stopPlayback(true);event("toast","Просмотр остановлен: таймер сна");}
            }}
        });
        if(source!=null){player.setMediaSource(source);if(resumePosition>0)player.seekTo(resumePosition);player.prepare();player.play();}
    }
    private void commandPlayer(String cmd){
        if(cmd.equals("stop")){stopPlayback(true);return;}
        if(cmd.startsWith("sleep:")){try{int minutes=Integer.parseInt(cmd.substring(6));if(now==null)return;sleepTimer.set(minutes,SystemClock.elapsedRealtime());publishSleep();}catch(IllegalArgumentException ignored){}return;}
        if(cmd.startsWith("file:")&&selectingFile&&torrent!=null){try{String[] parts=cmd.split(":");if(parts.length<2||parts.length>3)return;int index=Integer.parseInt(parts[1]);long position=parts.length>2?Math.max(0,Long.parseLong(parts[2])):resumePosition;boolean valid=false;for(int i=0;i<pendingFiles.length();i++)if(pendingFiles.optJSONObject(i).optInt("index")==index)valid=true;if(valid){resumePosition=position;selectingFile=false;selectFile(torrent,index,playbackGeneration);}}catch(NumberFormatException ignored){}return;}
        if(player==null)return;
        switch(cmd){
            case "pause":if(player.getPlayWhenReady())player.pause();else player.play();break;
            case "back":player.seekBack();break;
            case "forward":player.seekForward();break;
            case "audio":chooseTrack(C.TRACK_TYPE_AUDIO);break;
            case "subtitles":chooseTrack(C.TRACK_TYPE_TEXT);break;
            default:if(cmd.startsWith("seek:"))try{player.seekTo(Math.max(0,Math.min(Math.max(0,player.getDuration()-1000),Long.parseLong(cmd.substring(5)))));}catch(NumberFormatException ignored){}
        }
    }
    private void chooseTrack(int type){
        List<String> labels=new ArrayList<>();List<TrackSelectionOverride> overrides=new ArrayList<>();List<String> languages=new ArrayList<>();languages.add("auto");final ExoPlayer choosing=player;int selected=0;
        labels.add(type==C.TRACK_TYPE_TEXT?"Выключены":"Автоматически · язык по умолчанию");overrides.add(null);
        for(Tracks.Group group:player.getCurrentTracks().getGroups())if(group.getType()==type){
            for(int i=0;i<group.length;i++)if(group.isTrackSupported(i)){
                Format f=group.getTrackFormat(i);String language=f.language==null?"Без языка":Locale.forLanguageTag(f.language).getDisplayLanguage(new Locale("ru"));
                labels.add(language+(f.label==null?"":" · "+f.label)+(f.channelCount>0?" · "+f.channelCount+" кан.":""));overrides.add(new TrackSelectionOverride(group.getMediaTrackGroup(),i));languages.add(f.language==null?"":f.language);if(group.isTrackSelected(i))selected=labels.size()-1;
            }
        }
        if(labels.size()==1){event("toast",type==C.TRACK_TYPE_AUDIO?"Звуковые дорожки ещё не доступны":"В этом видео нет доступных субтитров");return;}
        new AlertDialog.Builder(this).setTitle(type==C.TRACK_TYPE_AUDIO?"Звуковая дорожка":"Субтитры").setSingleChoiceItems(labels.toArray(new String[0]),selected,(d,which)->{
            d.dismiss();if(player==null||player!=choosing)return;TrackSelectionParameters.Builder b=player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type,type==C.TRACK_TYPE_TEXT&&which==0);
            String language=languages.get(which);PlaybackTracks.remember(this,type,language,which>0);
            if(type==C.TRACK_TYPE_AUDIO&&!language.isEmpty())b.setPreferredAudioLanguage(language.equals("auto")?null:language);
            if(type==C.TRACK_TYPE_TEXT&&which>0&&!language.isEmpty())b.setPreferredTextLanguage(language);
            if(which>0)b.setOverrideForType(overrides.get(which));player.setTrackSelectionParameters(b.build());event("toast",labels.get(which)+" · выбор языка сохранён");try{event("preferences",playbackPreferences());}catch(JSONException ignored){}
        }).show();
    }
    private void showInput(String kind,String currentValue){
        if(kind.startsWith("catalog-")){catalogCredential(kind.substring(8));return;}
        EditText input=new EditText(this);input.setSingleLine(true);input.setTextSize(18);input.setPadding(28,16,28,16);
        input.setText(currentValue);input.setSelection(input.length());
        String title=kind.equals("magnet")?"Вставьте magnet-ссылку":kind.equals("epg")?"Ссылка на программу передач XMLTV":kind.equals("playlist")?"Ссылка на M3U-плейлист":kind.equals("search-channel")?"Название телеканала":"Фильм, сериал или шоу";
        input.setHint(kind.equals("magnet")?"magnet:?xt=urn:btih:…":(kind.equals("playlist")||kind.equals("epg"))?"https://…":"Поиск");
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setView(input).setNegativeButton("Отмена",null).setPositiveButton(kind.startsWith("search")?"Найти":"Открыть",(d,w)->{
            String value=input.getText().toString().trim();
            if(kind.equals("epg")){
                if(!Sources.webUrl(value)){event("toast","Нужна HTTP-ссылка на XMLTV или XMLTV.GZ");return;}
                getSharedPreferences("iptv",MODE_PRIVATE).edit().putString("epg",value).apply();event("epg-source",value);
            }else if(kind.equals("playlist")){
                event("toast","Загружаем плейлист…");network.execute(()->{try{String data=Sources.text(value);JSONArray channels=Sources.parseM3u(data);if(channels.length()==0)throw new IOException("В плейлисте нет HTTP-каналов");cache("channels.m3u",data);liveChannels=channels;customPlaylist=true;getSharedPreferences("iptv",MODE_PRIVATE).edit().putBoolean("custom",true).apply();event("channels",channels);}catch(Exception e){event("toast",message(e));}});
            }else event("input",inputResult(kind,value));
        }).create();dialog.show();input.requestFocus();dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }
    private void publishSleep(){try{event("sleep",new JSONObject().put("episode",sleepTimer.afterEpisode()).put("remaining",sleepTimer.remaining(SystemClock.elapsedRealtime())));}catch(JSONException ignored){}}
    private final Runnable ticker=new Runnable(){@Override public void run(){
        if(destroyed)return;
        if(now!=null&&sleepTimer.expired(SystemClock.elapsedRealtime())){stopPlayback(true);event("toast","Просмотр остановлен: таймер сна");}
        if(now!=null){try{
            if(torrent!=null&&torrent.failure()!=null){playbackError(playbackGeneration,new IOException(torrent.failure()));ui.postDelayed(this,1000);return;}
            JSONObject status=torrent==null?new JSONObject():torrent.status();
            status.put("playing",player!=null&&player.isPlaying()).put("loading",player==null||player.getPlaybackState()==Player.STATE_BUFFERING);
            status.put("preparing",player==null).put("playWhenReady",player==null||player.getPlayWhenReady());
            if(player!=null)status.put("position",player.getCurrentPosition()).put("duration",Math.max(0,player.getDuration())).put("buffered",player.getBufferedPosition());
            status.put("sleepRemaining",sleepTimer.remaining(SystemClock.elapsedRealtime())).put("sleepEpisode",sleepTimer.afterEpisode());
            event("playback",status);
        }catch(Exception ignored){}}
        ui.postDelayed(this,1000);
    }};
    private void stopPlayback(boolean notify){stopPlayback(notify,true);}
    private void stopPlayback(boolean notify,boolean reportPosition){
        playbackGeneration++;selectingFile=false;pendingFiles=new JSONArray();if(notify)sleepTimer.clear();
        if(livePlayback!=null){livePlayback.close();livePlayback=null;}
        TorrentEngine old=torrent;torrent=null;if(old!=null)old.cancel();
        if(player!=null){if(reportPosition)event("position",player.getCurrentPosition());video.setPlayer(null);player.release();player=null;}
        if(old!=null)cleanup.execute(old::close);
        now=null;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);if(notify)event("stopped",true);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent e){
        String key=switch(e.getKeyCode()){
            case KeyEvent.KEYCODE_DPAD_UP->"ArrowUp";case KeyEvent.KEYCODE_DPAD_DOWN->"ArrowDown";
            case KeyEvent.KEYCODE_DPAD_LEFT->"ArrowLeft";case KeyEvent.KEYCODE_DPAD_RIGHT->"ArrowRight";
            case KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_NUMPAD_ENTER->"Enter";
            case KeyEvent.KEYCODE_CHANNEL_UP->"ChannelUp";case KeyEvent.KEYCODE_CHANNEL_DOWN->"ChannelDown";case KeyEvent.KEYCODE_LAST_CHANNEL->"LastChannel";
            case KeyEvent.KEYCODE_BACK->"Escape";default->null;
        };
        if(key!=null){if(key.equals("Enter")&&e.getRepeatCount()>0)return true;if(e.getAction()==KeyEvent.ACTION_DOWN)js("window.tvKey&&window.tvKey("+JSONObject.quote(key)+","+e.getRepeatCount()+")");return true;}
        if(e.getAction()==KeyEvent.ACTION_DOWN){
            if(e.getKeyCode()==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE){commandPlayer("pause");return true;}
            if(e.getKeyCode()==KeyEvent.KEYCODE_MEDIA_FAST_FORWARD){commandPlayer("forward");return true;}
            if(e.getKeyCode()==KeyEvent.KEYCODE_MEDIA_REWIND){commandPlayer("back");return true;}
        }
        return super.dispatchKeyEvent(e);
    }
    @Override protected void onPause(){resumed=false;super.onPause();}
    @Override protected void onStop(){super.onStop();if(now!=null)stopPlayback(true);}
    @Override protected void onResume(){super.onResume();resumed=true;fullscreen();if(awaitingUpdatePermission){awaitingUpdatePermission=false;if(getPackageManager().canRequestPackageInstalls())ui.post(this::installUpdate);else event("toast","Разрешение не выдано. APK сохранён: установку можно повторить из раздела «Обновления»");}}
    @Override protected void onDestroy(){stopPlayback(false);destroyed=true;webGeneration++;if(recoveryDialog!=null){recoveryDialog.dismiss();recoveryDialog=null;}if(updates!=null)updates.close();ui.removeCallbacks(ticker);if(web!=null){web.removeJavascriptInterface("Native");web.destroy();web=null;}network.shutdownNow();torrentWork.shutdownNow();cleanup.shutdown();super.onDestroy();}
}
