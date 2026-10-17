package ru.smotrim.tv;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** Owns a loopback-only TorrServer process. Video pieces live in a bounded RAM cache. */
public final class TorrentEngine implements AutoCloseable {
    public static final long CACHE_BYTES=96L*1024*1024;
    private final Object lifecycle=new Object();
    private final File root,executable;
    private final long cacheBytes;
    private final Set<HttpURLConnection> requests=ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private volatile Process process;
    private String base,authorization,hash;
    private JSONArray files;
    private File selectedFile;
    private long fileSize,streamPosition=-1;
    private int selectedId;
    private volatile HttpURLConnection streamConnection;
    private volatile InputStream stream;
    private volatile JSONObject cachedStatus=new JSONObject();
    private ScheduledExecutorService poller;

    public TorrentEngine(File root){this(new File(System.getProperty("smotrim.torrserver","missing-torrserver")),root,CACHE_BYTES);}
    public TorrentEngine(File executable,File root,long cacheBytes){this.executable=executable;this.root=root;this.cacheBytes=cacheBytes;}
    public boolean isClosed(){return closed;}
    private void check()throws IOException{if(closed||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Просмотр остановлен");}
    private void sleep()throws IOException{check();try{Thread.sleep(150);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("Просмотр остановлен");}}

    private void start()throws Exception{
        synchronized(lifecycle){
            check();if(process!=null)return;
            if(!executable.isFile()||!executable.canExecute())throw new IOException("Не удалось запустить встроенный торрент-движок. Переустановите APK.");
            if(!root.mkdirs()&&!root.isDirectory())throw new IOException("Не удалось создать папку настроек плеера");
            JSONObject initial=new JSONObject().put("CacheSize",cacheBytes).put("UseDisk",false).put("ReaderReadAHead",50).put("PreloadCache",5)
                .put("EnableDLNA",false).put("EnableBonjour",false).put("EnableLPD",false).put("DisableUPNP",true)
                .put("StoreSettingsInJson",true).put("ConnectionsLimit",40).put("TorrentDisconnectTimeout",120).put("ResponsiveMode",false);
            Files.write(new File(root,"settings.json").toPath(),new JSONObject().put("BitTorr",initial).toString().getBytes(StandardCharsets.UTF_8));
            String password=UUID.randomUUID().toString();
            Files.write(new File(root,"accs.db").toPath(),new JSONObject().put("smotrim",password).toString().getBytes(StandardCharsets.UTF_8));
            authorization="Basic "+Base64.getEncoder().encodeToString(("smotrim:"+password).getBytes(StandardCharsets.UTF_8));
            int port;try(ServerSocket socket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){port=socket.getLocalPort();}
            base="http://127.0.0.1:"+port;
            ProcessBuilder builder=new ProcessBuilder(executable.getAbsolutePath(),"--ip","127.0.0.1","--port",String.valueOf(port),"--path",root.getAbsolutePath(),"--httpauth");
            builder.directory(root);builder.redirectErrorStream(true);builder.redirectOutput(new File(System.getProperty("smotrim.torrserver.log","/dev/null")));
            builder.environment().put("GOMEMLIMIT",TorrentBufferPolicy.runtimeLimitMiB(cacheBytes)+"MiB");builder.environment().put("TMPDIR",root.getAbsolutePath());
            process=builder.start();
        }
        long until=System.currentTimeMillis()+15000;
        JSONObject settings=null;
        while(System.currentTimeMillis()<until){
            check();if(!process.isAlive())throw new IOException("Встроенный торрент-движок завершился при запуске");
            try{settings=api("/settings",new JSONObject().put("action","get"),1000);break;}catch(IOException ignored){sleep();}
        }
        if(settings==null)throw new IOException("Торрент-движок не ответил при запуске");
        settings.put("CacheSize",cacheBytes).put("UseDisk",false).put("TorrentsSavePath","").put("RemoveCacheOnDrop",true)
            .put("ReaderReadAHead",50).put("PreloadCache",5).put("ConnectionsLimit",40).put("UploadRateLimit",256)
            .put("EnableDLNA",false).put("EnableBonjour",false).put("EnableLPD",false).put("DisableUPNP",true)
            .put("EnableDebug",false).put("ResponsiveMode",false).put("TorrentDisconnectTimeout",120);
        api("/settings",new JSONObject().put("action","set").put("sets",settings),5000);
        JSONObject confirmed=api("/settings",new JSONObject().put("action","get"),5000);
        if(confirmed.optBoolean("UseDisk",true)||confirmed.optLong("CacheSize")!=cacheBytes)throw new IOException("Не удалось включить ограниченный буфер в памяти");
    }
    public JSONArray prepare(String link)throws Exception{
        check();if(link==null||(!link.startsWith("magnet:?")&&!Sources.webUrl(link))||link.length()>16384)throw new IOException("Нужна magnet-ссылка или HTTP-ссылка на .torrent");
        start();JSONObject info=api("/torrents",new JSONObject().put("action","add").put("link",link).put("save_to_db",false),20000);
        hash=info.optString("hash");if(!hash.matches("(?i)[0-9a-f]{40,64}"))throw new IOException("Не удалось открыть раздачу");
        long until=System.currentTimeMillis()+90000;
        while(System.currentTimeMillis()<until){
            check();files=info.optJSONArray("file_stats");if(files!=null&&files.length()>0)break;
            sleep();info=api("/torrents",new JSONObject().put("action","get").put("hash",hash),5000);
        }
        if(files==null||files.length()==0)throw new IOException("Раздающие не ответили. Попробуйте раздачу с большим числом сидов.");
        JSONArray choices=new JSONArray();
        for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i);String path=f.getString("path");Sources.safeFile(root,path);
            if(Sources.videoFile(path)&&f.optLong("length")>0)choices.put(new JSONObject().put("index",f.getInt("id")).put("name",new File(path).getName()).put("path",path).put("bytes",f.getLong("length")));
        }
        if(choices.length()==0)throw new IOException("В этой раздаче нет поддерживаемого видео");
        updateStatus(info);
        synchronized(lifecycle){check();poller=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"torrent-status");t.setDaemon(true);return t;});
            poller.scheduleWithFixedDelay(()->{try{updateStatus(api("/torrents",new JSONObject().put("action","get").put("hash",hash),1500));}catch(Exception ignored){}},1,1,TimeUnit.SECONDS);}
        return choices;
    }
    public void select(int id)throws Exception{
        check();if(files!=null)for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i);if(f.getInt("id")==id&&Sources.videoFile(f.getString("path"))){
            closeReader();selectedId=id;selectedFile=Sources.safeFile(root,f.getString("path"));fileSize=f.getLong("length");return;
        }}throw new IOException("Файл не найден");
    }
    public long size(){return fileSize;}
    /** A name for Media3's URI/format detection; no movie file is created on disk. */
    public File file(){return selectedFile;}
    public void prioritize(long position)throws IOException{check();if(selectedFile==null)throw new IOException("Видео не подготовлено");}
    public int read(long position,byte[] buffer,int offset,int length)throws IOException{
        check();if(length==0)return 0;if(position<0)throw new IOException("Некорректная позиция");if(position>=fileSize)return -1;prioritize(position);
        if(stream==null||streamPosition!=position){
            closeReader();HttpURLConnection connection=(HttpURLConnection)new URL(base+"/stream?link="+hash+"&index="+selectedId+"&play").openConnection();
            streamConnection=connection;connection.setRequestProperty("Authorization",authorization);connection.setRequestProperty("Range","bytes="+position+"-");
            connection.setConnectTimeout(5000);connection.setReadTimeout(60000);connection.setInstanceFollowRedirects(false);
            try{check();int code=connection.getResponseCode();if(code!=206&&!(position==0&&code==200))throw new IOException("Не удалось загрузить фрагмент видео ("+code+")");
                String range=connection.getHeaderField("Content-Range");if(code==206&&(range==null||!range.startsWith("bytes "+position+"-")))throw new IOException("Источник вернул неправильную позицию видео");
                stream=connection.getInputStream();streamPosition=position;check();
            }catch(IOException e){closeReader();throw e;}
        }
        InputStream input=stream;if(input==null)throw new InterruptedIOException("Просмотр остановлен");
        int count=input.read(buffer,offset,(int)Math.min(length,fileSize-position));check();if(count>0)streamPosition+=count;return count;
    }
    public void closeReader(){HttpURLConnection c=streamConnection;streamConnection=null;stream=null;streamPosition=-1;if(c!=null)c.disconnect();}
    private JSONObject api(String path,JSONObject payload,int timeout)throws Exception{
        check();HttpURLConnection c=(HttpURLConnection)new URL(base+path).openConnection();requests.add(c);
        try{check();c.setConnectTimeout(Math.min(1500,timeout));c.setReadTimeout(timeout);c.setInstanceFollowRedirects(false);c.setRequestMethod("POST");c.setDoOutput(true);
            c.setRequestProperty("Authorization",authorization);c.setRequestProperty("Content-Type","application/json");
            try(OutputStream out=c.getOutputStream()){out.write(payload.toString().getBytes(StandardCharsets.UTF_8));}
            int code=c.getResponseCode();if(code!=200)throw new IOException("Торрент-движок вернул ошибку "+code);
            ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){check();out.write(b,0,n);if(out.size()>4*1024*1024)throw new IOException("Слишком большой ответ движка");}}
            String body=out.toString("UTF-8").trim();return body.isEmpty()?new JSONObject():new JSONObject(body);
        }finally{requests.remove(c);c.disconnect();}
    }
    private void updateStatus(JSONObject value)throws Exception{cachedStatus=new JSONObject().put("peers",value.optInt("active_peers")).put("rate",value.optDouble("download_speed",0))
        .put("downloaded",value.optLong("bytes_read_data")).put("cached",value.optLong("loaded_size")).put("cacheLimit",cacheBytes).put("total",fileSize);}
    public JSONObject status(){try{return new JSONObject(cachedStatus.toString());}catch(Exception ignored){return new JSONObject();}}
    public void cancel(){synchronized(lifecycle){closed=true;if(poller!=null)poller.shutdownNow();Process p=process;if(p!=null)p.destroy();}for(HttpURLConnection c:requests)c.disconnect();closeReader();}
    @Override public void close(){cancel();Process p=process;if(p!=null)try{if(!p.waitFor(2,TimeUnit.SECONDS)){p.destroyForcibly();p.waitFor(2,TimeUnit.SECONDS);}}catch(InterruptedException e){p.destroyForcibly();Thread.currentThread().interrupt();}deleteTree(root);}
    public static void deleteTree(File f){File[] children=Files.isSymbolicLink(f.toPath())?null:f.listFiles();if(children!=null)for(File child:children)deleteTree(child);if(f.exists())f.delete();}
}
