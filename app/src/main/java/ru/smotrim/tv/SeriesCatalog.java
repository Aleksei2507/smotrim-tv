package ru.smotrim.tv;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** Series metadata is independent of torrent availability and file names. */
public final class SeriesCatalog {
    private SeriesCatalog() {}
    static String validId(String id) throws IOException {
        if(!MediaCatalog.validId(id))throw new IOException("Некорректный сериал");
        return id;
    }
    public static JSONObject normalize(JSONObject raw,String id) throws IOException,JSONException {
        validId(id);
        if(!id.equals(raw.optString("id"))||!"series".equals(raw.optString("type"))||raw.optString("name").trim().isEmpty())throw new IOException("Каталог не вернул карточку сериала");
        JSONObject result=new JSONObject();
        for(String key:Arrays.asList("id","ids","imdbId","kpId","tmdbId","provider","kind","localized","originalName","aliases","countries","origin","seasons","loadedSeasons","episodeProvider","type","name","poster","background","description","year","releaseInfo","imdbRating","ratingSource","runtime","genres","genre","cast","director","trailers","trailerStreams"))if(raw.has(key))result.put(key,raw.get(key));
        JSONArray videos=raw.optJSONArray("videos");List<JSONObject> list=new ArrayList<>();Set<String> seen=new HashSet<>();
        if(videos!=null)for(int i=0;i<videos.length()&&list.size()<3000;i++){
            JSONObject v=videos.optJSONObject(i);if(v==null)continue;
            int season=integer(v.opt("season")),episode=integer(v.has("episode")?v.opt("episode"):v.opt("number"));
            if(season<0||season>999||episode<1||episode>9999||!seen.add(season+":"+episode))continue;
            list.add(new JSONObject().put("id",id+":"+season+":"+episode).put("season",season).put("episode",episode)
                .put("title",shortText(v.optString("title",v.optString("name")),240))
                .put("released",shortText(v.optString("released",v.optString("firstAired")),50))
                .put("description",shortText(v.optString("overview",v.optString("description")),600)));
        }
        list.sort(Comparator.<JSONObject>comparingInt(v->v.optInt("season")).thenComparingInt(v->v.optInt("episode")));
        return result.put("videos",new JSONArray(list));
    }
    private static int integer(Object value){try{double n=Double.parseDouble(String.valueOf(value));return Double.isFinite(n)&&n==Math.floor(n)&&n<=Integer.MAX_VALUE&&n>=0?(int)n:-1;}catch(Exception e){return -1;}}
    private static String shortText(String s,int max){return s.substring(0,Math.min(s.length(),max));}
    public static JSONObject detail(File directory,String id,boolean refresh) throws IOException,JSONException {
        validId(id);directory.mkdirs();File file=new File(directory,id+".json");
        JSONObject saved=null;
        if(file.isFile()&&file.length()<=4*1024*1024)try{saved=normalize(new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8)),id);}catch(Exception ignored){}
        long age=System.currentTimeMillis()-file.lastModified();
        if(!refresh&&saved!=null&&age>=0&&age<86400000)return saved;
        JSONObject fresh;
        try{fresh=normalize(new JSONObject(Sources.text(Sources.CINEMA+"/meta/series/"+id+".json")).getJSONObject("meta"),id);}
        catch(IOException|JSONException e){if(saved!=null)return saved.put("stale",true);throw e;}
        try{
            File tmp=File.createTempFile("series-",".tmp",directory);
            try{Files.write(tmp.toPath(),fresh.toString().getBytes(StandardCharsets.UTF_8));if(!tmp.renameTo(file))throw new IOException("cache rename");}finally{tmp.delete();}
            File[] files=directory.listFiles(f->f.getName().matches("tt[0-9]+\\.json"));
            if(files!=null){Arrays.sort(files,Comparator.comparingLong(File::lastModified).reversed());for(int i=30;i<files.length;i++)files[i].delete();}
        }catch(IOException ignored){}
        return fresh;
    }
    public static JSONObject torrents(String name,String id,int season,int episode) throws JSONException,IOException {
        validId(id);if(season<0||season>999||episode<1||episode>9999)throw new IOException("Некорректный номер серии");
        String query=name.trim();if(query.isEmpty()||query.length()>200)throw new IOException("Укажите название сериала");
        String exact=query+String.format(Locale.ROOT," S%02dE%02d",season,episode);
        ExecutorService pool=Executors.newFixedThreadPool(2);List<JSONObject> responses=new ArrayList<>();
        try{for(Future<JSONObject> f:pool.invokeAll(Arrays.<Callable<JSONObject>>asList(()->Sources.torrents(query,id,""),()->Sources.torrents(exact,"","")),32,TimeUnit.SECONDS))try{responses.add(f.get());}catch(Exception ignored){}}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Поиск отменён",e);}finally{pool.shutdownNow();}
        JSONArray items=new JSONArray(),warnings=new JSONArray();Set<String> hashes=new HashSet<>(),messages=new LinkedHashSet<>();
        for(JSONObject r:responses){JSONArray rows=r.optJSONArray("items");if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);String magnet=row.optString("magnet"),hash=magnet.replaceFirst("(?i).*btih:([a-f0-9]{40}).*","$1").toLowerCase(Locale.ROOT);if(hashes.add(hash))items.put(row);}
            JSONArray w=r.optJSONArray("warnings");if(w!=null)for(int i=0;i<w.length();i++)messages.add(w.optString(i));}
        if(responses.isEmpty())messages.add("Источники поиска не ответили");for(String message:messages)warnings.put(message);
        return new JSONObject().put("items",items).put("warnings",warnings);
    }
}
