package ru.smotrim.tv;

import org.json.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** Search real titles plus a bounded, punctuation-tolerant season-pack fallback. */
public final class CatalogSources {
    private CatalogSources(){}
    static List<String> queries(JSONObject args,boolean episode)throws Exception{
        String main=args.optString("name").trim();if(main.isEmpty()||main.length()>200)throw new IOException("Укажите название фильма или сериала");
        LinkedHashMap<String,String> names=new LinkedHashMap<>();addTitle(names,main);
        JSONArray aliases=args.optJSONArray("aliases");if(aliases!=null)for(int i=0;i<aliases.length()&&i<20;i++)addTitle(names,aliases.optString(i));
        String russian=names.values().stream().filter(CatalogSources::russian).findFirst().orElse("");
        String hint=args.optString("searchHint").trim();if(russian.isEmpty()&&hint.length()<=100&&russian(hint))russian=hint;
        LinkedHashMap<String,String> chosen=new LinkedHashMap<>();addTitle(chosen,russian);addTitle(chosen,main);
        for(String name:names.values())if(chosen.size()<2)addTitle(chosen,name);
        List<String> titles=new ArrayList<>(chosen.values());if(titles.size()>2)titles=titles.subList(0,2);
        Set<String> result=new LinkedHashSet<>();if(episode){int season=args.getInt("season"),number=args.getInt("episode");if(season<0||season>999||number<1||number>9999)throw new IOException("Некорректный номер серии");
            for(String title:titles)result.add(title+String.format(Locale.ROOT," S%02dE%02d",season,number));
        }else result.addAll(titles);
        // Packs may say «Сезоны: 1, 2» or [s01], without the requested S01E03 token.
        // Episode/file matching remains responsible for rejecting a different episode.
        for(String title:titles){String broad=broadTitle(title);if(!broad.isEmpty())result.add(broad);}
        return new ArrayList<>(result);
    }
    static String broadTitle(String title){
        String value=java.text.Normalizer.normalize(title,java.text.Normalizer.Form.NFKC);
        value=value.replaceFirst("(?iu)\\s+[\\[(]?(?:s\\d{1,3}(?:e\\d{1,4})?|\\d{1,3}x\\d{1,4}|(?:сезон|season|серия|episode)\\s*[:№]?\\s*\\d{1,4}|\\d{1,3}\\s*сезон(?:а|ы|ов)?)(?=$|[\\s\\p{P}]).*$", "");
        return value.replace('ё','е').replace('Ё','Е').replaceAll("[\\p{P}\\p{Z}]+"," ").replaceAll("\\s+"," ").trim();
    }
    private static boolean russian(String text){return text.matches(".*[А-Яа-яЁё].*");}
    private static void addTitle(Map<String,String> titles,String value){String s=value.trim();if(!s.isEmpty()&&s.length()<=200)titles.putIfAbsent(s.toLowerCase(Locale.ROOT).replace('ё','е').replaceAll("[^\\p{L}\\p{N}]",""),s);}
    interface Searcher { JSONObject search(String name,String imdb,String year)throws Exception; }
    public static JSONObject search(JSONObject args,boolean episode)throws Exception{return search(args,episode,Sources::torrents);}
    static JSONObject search(JSONObject args,boolean episode,Searcher searcher)throws Exception{
        List<String> queries=queries(args,episode);String imdb=args.optString("imdbId",args.optString("id"));if(!imdb.matches("tt[0-9]{5,12}"))imdb="";final String id=imdb,year=episode?"":args.optString("year");
        List<Callable<JSONObject>> jobs=new ArrayList<>();for(int i=0;i<queries.size();i++){String q=queries.get(i),ref=i==0?id:"";jobs.add(()->searcher.search(q,ref,year));}
        ExecutorService pool=Executors.newFixedThreadPool(queries.size());JSONArray items=new JSONArray(),warnings=new JSONArray();Set<String> hashes=new HashSet<>(),messages=new LinkedHashSet<>();
        int successful=0,failed=0;
        try{for(Future<JSONObject> future:pool.invokeAll(jobs,32,TimeUnit.SECONDS))try{JSONObject response=future.get();successful+=response.optInt("successfulProviders");failed+=response.optInt("failedProviders");JSONArray rows=response.getJSONArray("items");for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);String hash=r.optString("magnet").replaceFirst("(?i).*btih:([a-f0-9]{40}).*","$1").toLowerCase(Locale.ROOT);if(hashes.add(hash))items.put(r);}JSONArray w=response.optJSONArray("warnings");if(w!=null)for(int i=0;i<w.length();i++)messages.add(w.optString(i));}catch(Exception e){failed++;messages.add("Часть источников не ответила. Можно повторить поиск.");}}finally{pool.shutdownNow();}
        for(String warning:messages)warnings.put(warning);return new JSONObject().put("items",items).put("warnings",warnings).put("successfulProviders",successful).put("failedProviders",failed);
    }
}
