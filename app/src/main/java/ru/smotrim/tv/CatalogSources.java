package ru.smotrim.tv;

import org.json.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** Search a bounded set of real titles, with exact episode and season-pack queries. */
public final class CatalogSources {
    private CatalogSources(){}
    static List<String> queries(JSONObject args,boolean episode)throws Exception{
        LinkedHashSet<String> titles=new LinkedHashSet<>();String main=args.optString("name").trim();if(!main.isEmpty())titles.add(main);
        JSONArray aliases=args.optJSONArray("aliases");if(aliases!=null)for(int i=0;i<aliases.length()&&titles.size()<2;i++){String s=aliases.optString(i).trim();if(!s.isEmpty())titles.add(s);}
        if(titles.isEmpty()||titles.stream().anyMatch(s->s.length()>200))throw new IOException("Укажите название фильма или сериала");
        List<String> result=new ArrayList<>();if(episode){int season=args.getInt("season"),number=args.getInt("episode");if(season<0||season>999||number<1||number>9999)throw new IOException("Некорректный номер серии");
            for(String title:titles)result.add(title+String.format(Locale.ROOT," S%02dE%02d",season,number));
            result.add(main+" "+season+" сезон"); // Includes packs; file matching still verifies the episode.
        }else result.addAll(titles);return result;
    }
    public static JSONObject search(JSONObject args,boolean episode)throws Exception{
        List<String> queries=queries(args,episode);String imdb=args.optString("imdbId",args.optString("id"));if(!imdb.matches("tt[0-9]{5,12}"))imdb="";final String id=imdb,year=episode?"":args.optString("year");
        List<Callable<JSONObject>> jobs=new ArrayList<>();for(int i=0;i<queries.size();i++){String q=queries.get(i),ref=i==0?id:"";jobs.add(()->Sources.torrents(q,ref,year));}
        ExecutorService pool=Executors.newFixedThreadPool(queries.size());JSONArray items=new JSONArray(),warnings=new JSONArray();Set<String> hashes=new HashSet<>(),messages=new LinkedHashSet<>();
        try{for(Future<JSONObject> future:pool.invokeAll(jobs,32,TimeUnit.SECONDS))try{JSONObject response=future.get();JSONArray rows=response.getJSONArray("items");for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);String hash=r.optString("magnet").replaceFirst("(?i).*btih:([a-f0-9]{40}).*","$1").toLowerCase(Locale.ROOT);if(hashes.add(hash))items.put(r);}JSONArray w=response.optJSONArray("warnings");if(w!=null)for(int i=0;i<w.length();i++)messages.add(w.optString(i));}catch(Exception e){messages.add("Часть источников не ответила. Можно повторить поиск.");}}finally{pool.shutdownNow();}
        for(String warning:messages)warnings.put(warning);return new JSONObject().put("items",items).put("warnings",warnings);
    }
}
