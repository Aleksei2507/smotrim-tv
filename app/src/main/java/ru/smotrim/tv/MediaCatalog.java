package ru.smotrim.tv;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** Metadata providers never return playback URLs. Credentials stay in the native process. */
public final class MediaCatalog {
    public interface Transport { JSONObject get(String provider,String path) throws Exception; }
    private final Transport transport;
    private final boolean kp,tmdb;
    private final File directory;
    private long detailDeadline=Long.MAX_VALUE;
    private JSONObject detailGet(String provider,String path)throws Exception{if(System.nanoTime()>detailDeadline)throw new IOException("Каталог отвечает слишком долго");return transport.get(provider,path);}
    public MediaCatalog(File directory,String kpKey,String tmdbToken){this(directory,!kpKey.isEmpty(),!tmdbToken.isEmpty(),(provider,path)->request(provider,path,provider.equals("kp")?kpKey:tmdbToken));}
    MediaCatalog(File directory,boolean kp,boolean tmdb,Transport transport){this.directory=directory;this.kp=kp;this.tmdb=tmdb;this.transport=transport;}
    static JSONObject request(String provider,String path,String credential)throws Exception{
        String base=provider.equals("kp")?"https://kinopoiskapiunofficial.tech":provider.equals("tmdb")?"https://api.themoviedb.org/3":Sources.CINEMA;
        boolean apiKey=provider.equals("tmdb")&&credential.matches("[a-fA-F0-9]{32}");
        URL url=new URL(base+path+(apiKey?(path.contains("?")?"&":"?")+"api_key="+credential:""));
        HttpURLConnection c=(HttpURLConnection)url.openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(8000);c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent","SmotrimTV/0.5");c.setRequestProperty("Accept","application/json");
        if(provider.equals("kp"))c.setRequestProperty("X-API-KEY",credential);
        if(provider.equals("tmdb")&&!apiKey)c.setRequestProperty("Authorization","Bearer "+credential);
        try{int status=c.getResponseCode();if(status!=200)throw new IOException(status==401||status==403?"Проверьте ключ доступа":status==402||status==429?"Исчерпан лимит каталога":"Каталог недоступен (HTTP "+status+")");
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1){if(out.size()+n>4*1024*1024)throw new IOException("Слишком большой ответ каталога");out.write(bytes,0,n);}return new JSONObject(out.toString("UTF-8"));}
        }finally{c.disconnect();}
    }
    public JSONObject state()throws JSONException{return new JSONObject().put("kinopoisk",kp).put("tmdb",tmdb);}
    static String text(JSONObject o,String... keys){for(String key:keys)if(o!=null&&!o.isNull(key)){String s=o.optString(key).trim();if(!s.isEmpty())return s;}return "";}
    static JSONArray strings(Object... values){LinkedHashSet<String> result=new LinkedHashSet<>();for(Object value:values){if(value instanceof JSONArray){JSONArray a=(JSONArray)value;for(int i=0;i<a.length();i++){String s=a.optString(i).trim();if(!s.isEmpty())result.add(s);}}else if(value instanceof String&&!((String)value).isEmpty())result.add((String)value);}return new JSONArray(result);}
    static JSONArray fields(JSONArray list,String key){JSONArray result=new JSONArray();if(list!=null)for(int i=0;i<list.length();i++){JSONObject o=list.optJSONObject(i);String s=text(o,key);if(!s.isEmpty())result.put(s);}return result;}
    static String image(String s){return s.startsWith("https://")?s:s.startsWith("http://kinopoiskapiunofficial.tech/")?s.replaceFirst("http:","https:"):"";}
    static boolean validId(String id){return id!=null&&id.matches("(?:tt[0-9]{5,12}|kp:[0-9]{1,9}|tmdb:(?:movie|series):[0-9]{1,9})");}
    static JSONObject kinopoisk(JSONObject raw)throws JSONException{
        long id=raw.optLong("kinopoiskId",0);if(id<=0)return null;
        String type=text(raw,"type"),name=text(raw,"nameRu","nameOriginal","nameEn");if(name.isEmpty()||!(type.equals("FILM")||type.equals("TV_SERIES")||type.equals("MINI_SERIES")||type.equals("TV_SHOW")))return null;
        String imdb=text(raw,"imdbId");if(!imdb.matches("tt[0-9]{5,12}"))imdb="";
        JSONArray countries=fields(raw.optJSONArray("countries"),"country");
        JSONObject out=new JSONObject().put("id",imdb.isEmpty()?"kp:"+id:imdb).put("imdbId",imdb).put("kpId",id).put("provider","kp")
            .put("ids",strings("kp:"+id,imdb)).put("type",type.equals("FILM")?"movie":"series").put("kind",type.equals("TV_SHOW")?"show":type.equals("FILM")?"movie":"series")
            .put("name",name).put("localized",!text(raw,"nameRu").isEmpty()).put("originalName",text(raw,"nameOriginal","nameEn"))
            .put("aliases",strings(text(raw,"nameRu"),text(raw,"nameOriginal"),text(raw,"nameEn"))).put("year",text(raw,"year","startYear"))
            .put("poster",image(text(raw,"posterUrlPreview","posterUrl"))).put("background",image(text(raw,"coverUrl")))
            .put("description",text(raw,"description","shortDescription")).put("genres",fields(raw.optJSONArray("genres"),"genre")).put("countries",countries)
            
            .put("imdbRating",text(raw,"ratingKinopoisk","ratingImdb")).put("ratingSource","Кинопоиск");
        out.put("origin",countries.length()==0?"unknown":countries.toString().contains("Россия")||countries.toString().contains("СССР")?"ru":"other");
        if(raw.optInt("filmLength")>0)out.put("runtime",raw.optInt("filmLength")+" мин");return out;
    }
    static JSONObject tmdb(JSONObject raw,String type)throws JSONException{
        if(!type.equals("movie")&&!type.equals("series"))return null;long id=raw.optLong("id",0);String name=text(raw,"title","name");if(id<=0||name.isEmpty()||raw.optBoolean("adult"))return null;
        String imdb=text(raw,"imdb_id");if(imdb.isEmpty())imdb=text(raw.optJSONObject("external_ids"),"imdb_id");if(!imdb.matches("tt[0-9]{5,12}"))imdb="";
        JSONArray country=raw.optJSONArray("origin_country");if(country==null)country=fields(raw.optJSONArray("production_countries"),"iso_3166_1");
        String stamp=text(raw,"release_date","first_air_date");String genreText=String.valueOf(raw.opt("genre_ids"))+String.valueOf(raw.opt("genres"));
        JSONObject out=new JSONObject().put("id",imdb.isEmpty()?"tmdb:"+type+":"+id:imdb).put("imdbId",imdb).put("tmdbId",id).put("provider","tmdb")
            .put("ids",strings("tmdb:"+type+":"+id,imdb)).put("type",type).put("kind",type.equals("series")&&(genreText.contains("10764")||genreText.contains("10767"))?"show":type)
            .put("name",name).put("localized",true).put("originalName",text(raw,"original_title","original_name")).put("aliases",strings(name,text(raw,"original_title","original_name")))
            .put("year",stamp.length()>=4?stamp.substring(0,4):"").put("description",text(raw,"overview")).put("genres",fields(raw.optJSONArray("genres"),"name"))
            .put("countries",country).put("origin",country.length()==0?"unknown":country.toString().contains("RU")||country.toString().contains("SU")?"ru":"other")
            .put("poster",tmdbImage(text(raw,"poster_path"),"w342")).put("background",tmdbImage(text(raw,"backdrop_path"),"w780"))
            .put("imdbRating",raw.optDouble("vote_average",0)>0?String.format(Locale.ROOT,"%.1f",raw.optDouble("vote_average")):"").put("ratingSource","TMDB");
        if(raw.optInt("runtime")>0)out.put("runtime",raw.optInt("runtime")+" мин");
        JSONArray seasons=new JSONArray(),s=raw.optJSONArray("seasons");if(s!=null)for(int i=0;i<s.length()&&seasons.length()<150;i++){JSONObject v=s.optJSONObject(i);if(v!=null&&v.optInt("season_number",-1)>=0)seasons.put(new JSONObject().put("number",v.optInt("season_number")).put("name",text(v,"name")));}out.put("seasons",seasons);
        JSONArray trailers=new JSONArray();JSONObject videos=raw.optJSONObject("videos");if(videos!=null){JSONArray a=videos.optJSONArray("results");if(a!=null)for(int i=0;i<a.length();i++){JSONObject v=a.optJSONObject(i);if(v!=null&&"YouTube".equals(v.optString("site"))&&"Trailer".equals(v.optString("type")))trailers.put(new JSONObject().put("type","Trailer").put("source",v.optString("key")));}}out.put("trailers",trailers);return out;
    }
    private static String tmdbImage(String path,String width){return path.matches("/[A-Za-z0-9_.-]+")?"https://image.tmdb.org/t/p/"+width+path:"";}
    static JSONObject cinemeta(JSONObject raw)throws JSONException{String id=text(raw,"id"),type=text(raw,"type");if(!id.matches("tt[0-9]{5,12}")||!Arrays.asList("movie","series").contains(type))return null;return new JSONObject(raw.toString()).put("imdbId",id).put("year",text(raw,"year","releaseInfo").replaceFirst("^([0-9]{4}).*","$1")).put("ids",strings(id)).put("provider","cinemeta").put("kind",type).put("origin","unknown").put("aliases",strings(text(raw,"name")));}
    private static Set<String> identities(JSONObject value){Set<String> ids=new HashSet<>();JSONArray a=strings(value.optJSONArray("ids"),text(value,"id","imdbId"));for(int i=0;i<a.length();i++)ids.add(a.optString(i));return ids;}
    private static String normalized(String s){return s.toLowerCase(Locale.ROOT).replace('ё','е').replaceAll("[^\\p{L}\\p{N}]","");}
    static boolean same(JSONObject a,JSONObject b){if(!a.optString("type").equals(b.optString("type")))return false;Set<String> ids=identities(a);ids.retainAll(identities(b));if(!ids.isEmpty())return true;
        if(!text(a,"imdbId").isEmpty()&&!text(b,"imdbId").isEmpty())return false;
        if(text(a,"year").isEmpty()||!text(a,"year").equals(text(b,"year")))return false;
        Set<String> names=new HashSet<>();JSONArray aliases=strings(a.optJSONArray("aliases"),text(a,"name","originalName"));for(int i=0;i<aliases.length();i++)names.add(normalized(aliases.optString(i)));
        JSONArray other=strings(b.optJSONArray("aliases"),text(b,"name","originalName"));for(int i=0;i<other.length();i++)if(names.contains(normalized(other.optString(i))))return true;return false;
    }
    static JSONObject merge(JSONObject a,JSONObject b)throws JSONException{
        JSONObject out=new JSONObject(a.toString());boolean translated=b.optBoolean("localized")&&(!a.optBoolean("localized")||"kp".equals(b.optString("provider"))&&!"kp".equals(a.optString("provider")));
        for(Iterator<String> it=b.keys();it.hasNext();){String key=it.next();if(!key.equals("id")&&!key.equals("ids")&&!key.equals("aliases")&&(!out.has(key)||out.isNull(key)||out.optString(key).isEmpty()||out.optString(key).equals("[]")||out.optString(key).equals("unknown")||translated&&Arrays.asList("name","description","genres","localized","originalName").contains(key)))out.put(key,b.get(key));}
        out.put("ids",strings(a.optJSONArray("ids"),b.optJSONArray("ids"),text(a,"id"),text(b,"id"))).put("aliases",strings(a.optJSONArray("aliases"),b.optJSONArray("aliases")));return out;
    }
    static JSONArray mix(List<JSONArray> lists)throws JSONException{List<JSONObject> out=new ArrayList<>();for(int row=0;row<100;row++)for(JSONArray list:lists){JSONObject item=list.optJSONObject(row);if(item==null)continue;int match=-1;for(int i=0;i<out.size();i++)if(same(out.get(i),item)){match=i;break;}if(match<0)out.add(item);else out.set(match,merge(out.get(match),item));}return new JSONArray(out);}
    private JSONObject list(String provider,String type,String query,int page)throws Exception{
        JSONObject raw;JSONArray source,result=new JSONArray();boolean more=false;
        if(provider.equals("kp")){raw=transport.get(provider,query.isEmpty()?"/api/v2.2/films/collections?type="+(type.equals("series")?"POPULAR_SERIES":"TOP_POPULAR_MOVIES")+"&page="+page:"/api/v2.2/films?keyword="+Sources.encode(query)+"&page="+page);source=raw.optJSONArray("items");more=raw.optInt("totalPages")>page;}
        else if(provider.equals("tmdb")){raw=transport.get(provider,query.isEmpty()?"/"+(type.equals("series")?"tv":"movie")+"/popular?language=ru-RU&page="+page:"/search/multi?language=ru-RU&include_adult=false&query="+Sources.encode(query)+"&page="+page);source=raw.optJSONArray("results");more=raw.optInt("total_pages")>page;}
        else{String extra=query.isEmpty()?"skip="+((page-1)*100):"search="+Sources.encode(query)+(page>1?"&skip="+((page-1)*100):"");raw=transport.get(provider,"/catalog/"+type+"/top/"+extra+".json");source=raw.optJSONArray("metas");more=source!=null&&source.length()>=100;}
        if(source!=null)for(int i=0;i<source.length()&&i<100;i++){JSONObject v=source.optJSONObject(i);if(v==null)continue;JSONObject m=provider.equals("kp")?kinopoisk(v):provider.equals("tmdb")?tmdb(v,query.isEmpty()?type:"tv".equals(v.optString("media_type"))?"series":v.optString("media_type")):cinemeta(v);if(m!=null&&(query.isEmpty()?type.equals(m.optString("type")):true))result.put(m);}
        return new JSONObject().put("items",result).put("hasMore",more);
    }
    public JSONObject catalog(String query,String type,int page)throws Exception{
        query=query.trim();if(query.length()>100||page<1||page>20)throw new IOException("Некорректный запрос каталога");if(!type.equals("series"))type="movie";
        final String q=query,t=type;final int p=page;
        List<Callable<JSONObject>> jobs=new ArrayList<>();List<String> labels=new ArrayList<>();
        if(kp){labels.add("Русский каталог");jobs.add(()->list("kp",t,q,p));}if(tmdb){labels.add("TMDB");jobs.add(()->list("tmdb",t,q,p));}
        labels.add("Cinemeta");jobs.add(()->list("cinemeta",t,q,p));
        if(!q.isEmpty()){labels.add("Сериалы Cinemeta");jobs.add(()->list("cinemeta",t.equals("movie")?"series":"movie",q,p));}
        ExecutorService pool=Executors.newFixedThreadPool(jobs.size());List<JSONArray> lists=new ArrayList<>();JSONArray warnings=new JSONArray();boolean more=false;int successful=0;
        try{List<Future<JSONObject>> results=pool.invokeAll(jobs,22,TimeUnit.SECONDS);for(int i=0;i<results.size();i++)try{JSONObject r=results.get(i).get();lists.add(r.getJSONArray("items"));more|=r.optBoolean("hasMore");successful++;}catch(Exception e){warnings.put(labels.get(i)+": временно недоступен. Проверьте подключение и ключ в настройках.");}}finally{pool.shutdownNow();}
        if(!kp)warnings.put("Русский каталог не подключён. Добавьте ключ в настройках, чтобы находить больше российских фильмов и шоу.");
        if(successful==0)throw new IOException("Каталоги не ответили. Проверьте интернет и повторите поиск.");
        return new JSONObject().put("items",mix(lists)).put("warnings",warnings).put("hasMore",more&&page<20).put("page",page).put("providers",state());
    }
    private JSONObject saved(String key){File f=new File(directory,key+".json");try{if(f.length()>4*1024*1024)return null;return new JSONObject(new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));}catch(Exception e){return null;}}
    private void save(String key,JSONObject data){directory.mkdirs();File tmp=null;try{tmp=File.createTempFile("catalog-",".tmp",directory);Files.write(tmp.toPath(),data.toString().getBytes(StandardCharsets.UTF_8));tmp.renameTo(new File(directory,key+".json"));File[] files=directory.listFiles(f->f.getName().endsWith(".json"));if(files!=null){Arrays.sort(files,Comparator.comparingLong(File::lastModified).reversed());for(int i=80;i<files.length;i++)files[i].delete();}}catch(Exception ignored){}finally{if(tmp!=null)tmp.delete();}}
    public JSONObject detail(JSONObject selected,boolean refresh)throws Exception{
        detailDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        String id=selected.optString("id"),type=selected.optString("type","movie");if(!validId(id))throw new IOException("Неизвестная карточка");
        String key=id.replace(':','-'),imdb=selected.optString("imdbId",id.startsWith("tt")?id:"");if(!imdb.matches("tt[0-9]{5,12}"))imdb="";JSONObject cached=saved(key);File f=new File(directory,key+".json");
        if(!refresh&&cached!=null&&System.currentTimeMillis()-f.lastModified()<86400000)return merge(selected,cached);
        JSONObject result=new JSONObject(selected.toString());boolean loaded=false;JSONArray warnings=new JSONArray();
        long kpId=selected.optLong("kpId",id.startsWith("kp:")?Long.parseLong(id.substring(3)):0),tmdbId=selected.optLong("tmdbId",id.startsWith("tmdb:")?Long.parseLong(id.substring(id.lastIndexOf(':')+1)):0);
        if(kp)try{if(kpId==0&&!imdb.isEmpty()){JSONArray found=detailGet("kp","/api/v2.2/films?imdbId="+imdb).optJSONArray("items");if(found!=null&&found.length()==1)kpId=found.getJSONObject(0).optLong("kinopoiskId");}if(kpId>0){JSONObject raw=detailGet("kp","/api/v2.2/films/"+kpId),m=kinopoisk(raw);if(m!=null){result=merge(result,m);loaded=true;if(type.equals("series"))try{JSONArray episodes=kpEpisodes(detailGet("kp","/api/v2.2/films/"+kpId+"/seasons"));result.put("videos",episodes);if(episodes.length()>0)result.put("episodeProvider","kp");}catch(Exception e){warnings.put("Не удалось загрузить серии русского каталога");}}}}catch(Exception e){warnings.put("Описание русского каталога недоступно");}
        if(tmdb&&tmdbId==0&&!imdb.isEmpty())try{JSONArray found=detailGet("tmdb","/find/"+imdb+"?external_source=imdb_id&language=ru-RU").optJSONArray(type.equals("series")?"tv_results":"movie_results");if(found!=null&&found.length()==1)tmdbId=found.getJSONObject(0).optLong("id");}catch(Exception ignored){}
        if(tmdb&&tmdbId>0)try{JSONObject raw=detailGet("tmdb","/"+(type.equals("series")?"tv":"movie")+"/"+tmdbId+"?language=ru-RU&append_to_response=external_ids,videos"),m=tmdb(raw,type);if(m!=null){result=merge(result,m);loaded=true;}}catch(Exception e){warnings.put("Описание TMDB недоступно");}
        imdb=text(result,"imdbId");if(!imdb.isEmpty())try{JSONObject m=cinemeta(detailGet("cinemeta","/meta/"+type+"/"+imdb+".json").getJSONObject("meta"));if(m!=null){result=merge(result,m);loaded=true;}}catch(Exception e){warnings.put("Описание Cinemeta недоступно");}
        if(!loaded){if(cached!=null)return cached.put("stale",true);throw new IOException("Не удалось загрузить описание. Можно повторить или выбрать вариант просмотра.");}
        if(type.equals("series")){result=SeriesCatalog.normalize(result,id);if(!"kp".equals(result.optString("episodeProvider"))&&tmdb&&result.optLong("tmdbId")>0){JSONArray seasons=result.optJSONArray("seasons");if(seasons!=null&&seasons.length()>0){int s=seasons.getJSONObject(0).optInt("number");for(int i=0;i<seasons.length();i++)if(seasons.getJSONObject(i).optInt("number")>0){s=seasons.getJSONObject(i).optInt("number");break;}try{JSONArray localized=season(result,s);if(localized.length()>0){JSONArray combined=new JSONArray();JSONArray existing=result.getJSONArray("videos");for(int i=0;i<existing.length();i++)if(existing.getJSONObject(i).optInt("season")!=s)combined.put(existing.getJSONObject(i));for(int i=0;i<localized.length();i++)combined.put(localized.getJSONObject(i));result.put("videos",combined);}result.put("loadedSeasons",new JSONArray().put(s));result.put("episodeProvider","tmdb");}catch(Exception e){warnings.put("Серии не загрузились. Выберите сезон, чтобы повторить.");}}}}
        result.put("metadataWarnings",warnings);save(key,result);return result;
    }
    public JSONArray season(JSONObject selected,int season)throws Exception{if(!validId(selected.optString("id"))||season<0||season>999||!tmdb||selected.optLong("tmdbId")<=0)throw new IOException("Сезон недоступен");String key="tmdb-season-"+selected.optLong("tmdbId")+"-"+season;JSONObject cached=saved(key);File f=new File(directory,key+".json");if(cached!=null&&System.currentTimeMillis()-f.lastModified()<86400000)return cached.getJSONArray("videos");
        try{JSONArray videos=tmdbEpisodes(detailGet("tmdb","/tv/"+selected.getLong("tmdbId")+"/season/"+season+"?language=ru-RU"),season);save(key,new JSONObject().put("videos",videos));return videos;}catch(Exception e){if(cached!=null)return cached.getJSONArray("videos");throw e;}}
    static JSONArray kpEpisodes(JSONObject raw)throws JSONException{JSONArray out=new JSONArray(),seasons=raw.optJSONArray("items");if(seasons!=null)for(int i=0;i<seasons.length()&&i<150;i++){JSONObject s=seasons.optJSONObject(i);if(s==null)continue;JSONArray episodes=s.optJSONArray("episodes");if(episodes!=null)for(int j=0;j<episodes.length()&&out.length()<3000;j++){JSONObject e=episodes.optJSONObject(j);if(e!=null)out.put(new JSONObject().put("season",e.optInt("seasonNumber",s.optInt("number",-1))).put("episode",e.optInt("episodeNumber")).put("title",text(e,"nameRu","nameEn")).put("description",text(e,"synopsis")).put("released",text(e,"releaseDate")));}}return out;}
    static JSONArray tmdbEpisodes(JSONObject raw,int season)throws JSONException{JSONArray out=new JSONArray(),episodes=raw.optJSONArray("episodes");if(episodes!=null)for(int i=0;i<episodes.length()&&i<1000;i++){JSONObject e=episodes.optJSONObject(i);if(e!=null)out.put(new JSONObject().put("season",season).put("episode",e.optInt("episode_number")).put("title",text(e,"name")).put("description",text(e,"overview")).put("released",text(e,"air_date")));}return out;}
}
