package ru.smotrim.tv;

import org.json.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Fetches public metadata and search results using the same bounded adapters as the app. */
public final class CatalogCollect {
    public static void main(String[] args)throws Exception {
        JSONArray seeds=new JSONObject(Files.readString(Path.of(args[0]))).getJSONArray("items");Path output=Path.of(args[1]);Files.createDirectories(output);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        for(int i=0;i<seeds.length();i++){JSONObject seed=seeds.getJSONObject(i);pool.submit(()->{
            String id=seed.optString("id");Path file=output.resolve(id+".json");boolean retry=args.length>2&&java.util.Arrays.asList(args[2].split(",")).contains(id);if(Files.exists(file)&&!retry){System.out.println(id+" cached");return;}
            try{
                JSONObject metadata=MediaCatalog.request("cinemeta","/meta/"+seed.getString("type")+"/"+id+".json","").getJSONObject("meta");
                if(!id.equals(metadata.optString("id")))throw new IllegalStateException("Metadata identity mismatch");
                JSONObject sources=Sources.torrents(seed.optString("searchName",seed.getString("name")),id,seed.getString("type").equals("movie")?seed.optString("year",metadata.optString("releaseInfo").replaceFirst("^([0-9]{4}).*","$1")):"");
                if(retry&&Files.exists(file)){JSONArray previous=new JSONObject(Files.readString(file)).getJSONObject("search").getJSONArray("items");for(int n=0;n<previous.length();n++)sources.getJSONArray("items").put(previous.get(n));}
                JSONObject result=new JSONObject().put("metadata",metadata).put("search",sources);
                Files.writeString(file,result.toString(2),StandardCharsets.UTF_8);
                System.out.println(id+" "+seed.getString("name")+": "+sources.getJSONArray("items").length()+" variants, "+(metadata.optJSONArray("videos")==null?0:metadata.optJSONArray("videos").length())+" episodes");
            }catch(Exception e){System.out.println(id+" failed: "+e.getMessage());}
        });}
        pool.shutdown();if(!pool.awaitTermination(20,TimeUnit.MINUTES)){pool.shutdownNow();throw new IllegalStateException("Collection timed out");}
    }
}
