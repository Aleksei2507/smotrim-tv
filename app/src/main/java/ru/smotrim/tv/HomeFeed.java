package ru.smotrim.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** A small local projection. Playback URLs and API credentials never cross this boundary. */
final class HomeFeed {
    static final Uri URI=Uri.parse("content://ru.smotrim.tv.home/items");
    static final String PERMISSION="ru.smotrim.tv.permission.READ_HOME";
    private static final ThreadPoolExecutor ART=new ThreadPoolExecutor(0,1,15,TimeUnit.SECONDS,new LinkedBlockingQueue<>());
    private static volatile String latest="";
    private static boolean running;
    private static final Map<String,Long> failedArtwork=new HashMap<>();

    static JSONObject sanitize(JSONObject input) throws Exception {
        JSONObject result=new JSONObject().put("version",1);
        for(String kind:new String[]{"history","channels"}) {
            JSONArray clean=new JSONArray(),rows=input.optJSONArray(kind);Set<String> seen=new HashSet<>();
            if(rows!=null)for(int i=0;i<rows.length()&&clean.length()<(kind.equals("history")?12:24);i++) {
                JSONObject row=rows.optJSONObject(i);if(row==null)continue;
                String key=row.optString("key"),title=cut(row.optString("title"),180);
                if(key.isEmpty()||key.length()>8192||title.isEmpty()||!seen.add(key))continue;
                long position=Math.max(0,row.optLong("position")),duration=Math.max(0,row.optLong("duration"));
                clean.put(new JSONObject().put("key",key).put("title",title).put("subtitle",cut(row.optString("subtitle"),180))
                    .put("image",safeImage(row.optString("image"))).put("position",duration>0?Math.min(position,duration):position).put("duration",duration));
            }
            result.put(kind,clean);
        }
        return result;
    }
    private static String cut(String s,int n){return s.length()>n?s.substring(0,n):s;}
    static String safeImage(String s) {
        if(s.matches("artwork/[A-Za-z0-9_-]+\\.jpg"))return s;
        try{URL u=new URL(s);if(s.length()<2048&&u.getProtocol().equals("https")&&u.getUserInfo()==null)return s;}catch(Exception ignored){}
        return "";
    }
    static String hash(String image) {
        try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(image.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}catch(Exception e){throw new IllegalStateException(e);}
    }
    static File directory(Context c){return new File(c.getCacheDir(),"home-art");}
    static void publish(Context c,String json) {
        if(json==null||json.length()>128*1024)return;
        try {
            String clean=sanitize(new JSONObject(json)).toString();
            String old=c.getSharedPreferences("home-feed",0).getString("snapshot","");
            c.getSharedPreferences("home-feed",0).edit().putString("snapshot",clean).apply();
            if(!old.equals(clean))c.getContentResolver().notifyChange(URI,null);
            synchronized(HomeFeed.class){latest=clean;if(running)return;running=true;}
            Context app=c.getApplicationContext();ART.execute(()->{
                String handled="";
                while(true){String value;synchronized(HomeFeed.class){value=latest;if(value.equals(handled)){running=false;return;}}handled=value;
                    try{cacheImages(app,new JSONObject(value));}catch(Exception ignored){}
                }
            });
        }catch(Exception ignored){}
    }
    static JSONObject read(Context c) {
        try{return new JSONObject(c.getSharedPreferences("home-feed",0).getString("snapshot","{}"));}catch(Exception e){return new JSONObject();}
    }
    static String opaqueKey(String kind,String key){return hash(kind+"\n"+key);}
    static JSONObject resolveAction(Context c,JSONObject action)throws Exception {
        String kind=action.optString("kind"),key=action.optString("key");
        if(kind.equals("tv"))return action;
        JSONArray rows=read(c).optJSONArray(kind);if(rows!=null)for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);if(row!=null&&opaqueKey(kind,row.optString("key")).equals(key))return new JSONObject().put("kind",kind).put("key",row.optString("key"));
        }
        return null;
    }
    private static void cacheImages(Context c,JSONObject data) {
        File dir=directory(c);dir.mkdirs();Set<String> keep=new HashSet<>();
        for(String kind:new String[]{"history","channels"}) {
            JSONArray rows=data.optJSONArray(kind);if(rows==null)continue;
            for(int i=0;i<rows.length();i++) {
                String url=rows.optJSONObject(i).optString("image");if(url.isEmpty())continue;
                String name=hash(url)+".jpg";keep.add(name);File target=new File(dir,name);
                if(target.isFile())continue;
                if(System.currentTimeMillis()-failedArtwork.getOrDefault(name,0L)<10*60*1000L)continue;
                try{byte[] bytes=download(c,url);BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
                    if(bounds.outWidth<=0||bounds.outHeight<=0||bounds.outWidth>16000||bounds.outHeight>16000)throw new IOException("Invalid image size");
                    BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;
                    while(bounds.outWidth/options.inSampleSize>800||bounds.outHeight/options.inSampleSize>500)options.inSampleSize*=2;
                    Bitmap image=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(image==null)throw new IOException("Invalid image");
                    File temp=new File(dir,name+".tmp");try(FileOutputStream out=new FileOutputStream(temp)){image.compress(Bitmap.CompressFormat.JPEG,83,out);}finally{image.recycle();}
                    if(temp.renameTo(target))c.getContentResolver().notifyChange(URI,null);else temp.delete();
                }catch(Exception ignored){failedArtwork.put(name,System.currentTimeMillis());}
            }
        }
        File[] files=dir.listFiles();if(files!=null)for(File f:files)if(!keep.contains(f.getName()))f.delete();
        failedArtwork.keySet().retainAll(keep);
    }
    private static byte[] download(Context c,String image)throws IOException {
        if(image.startsWith("artwork/"))try(InputStream in=c.getAssets().open("ui/"+image)){return bounded(in);}
        HttpURLConnection connection=null;String url=image;
        try{for(int redirects=0;redirects<4;redirects++) {
            if(safeImage(url).isEmpty())throw new IOException("Invalid image URL");
            if(url.startsWith("https://appassets.androidplatform.net/assets/")) {
                String path=Uri.parse(url).getPath().substring("/assets/".length());
                if(path.contains(".."))throw new IOException("Invalid asset");
                try(InputStream in=c.getAssets().open(path)){return bounded(in);}
            }
            connection=(HttpURLConnection)new URL(url).openConnection();connection.setConnectTimeout(4000);connection.setReadTimeout(6000);connection.setInstanceFollowRedirects(false);
            int status=connection.getResponseCode();
            if(status>=300&&status<400){url=new URL(new URL(url),connection.getHeaderField("Location")).toString();connection.disconnect();continue;}
            if(status!=200||connection.getContentLengthLong()>4*1024*1024)throw new IOException("Image unavailable");
            try(InputStream in=connection.getInputStream()){return bounded(in);}
        }throw new IOException("Too many redirects");}finally{if(connection!=null)connection.disconnect();}
    }
    private static byte[] bounded(InputStream in)throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>4*1024*1024)throw new IOException("Large image");out.write(buf,0,n);}return out.toByteArray();}
}
