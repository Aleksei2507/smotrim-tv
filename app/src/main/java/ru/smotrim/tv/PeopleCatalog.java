package ru.smotrim.tv;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.net.URL;
import java.util.*;

/** Exact title/person identities; a name search would also return films about the actor. */
public final class PeopleCatalog {
    interface Transport { JSONObject query(String query) throws Exception; }
    private static final long TTL=86400000L;
    private final File directory;
    private final Transport transport;
    public PeopleCatalog(File directory){this(directory,q->new JSONObject(new String(Sources.fetch("https://query.wikidata.org/sparql?format=json&query="+Sources.encode(q),2*1024*1024),StandardCharsets.UTF_8)));}
    PeopleCatalog(File directory,Transport transport){this.directory=directory;this.transport=transport;}
    static String titleIdentity(JSONObject item)throws IOException{
        String imdb=item.optString("imdbId");if(!imdb.matches("tt[0-9]{5,12}"))imdb=item.optString("id");
        if(imdb.matches("tt[0-9]{5,12}"))return "wdt:P345 \""+imdb+"\"";
        long kp=item.optLong("kpId");String id=item.optString("id");
        if(kp==0&&id.matches("kp:[0-9]{1,9}"))kp=Long.parseLong(id.substring(3));
        if(kp>0&&kp<=999999999)return "wdt:P2603 \""+kp+"\"";
        throw new IOException("У этой карточки пока нет идентификатора для поиска актёров");
    }
    static String personId(String value){return value!=null&&value.matches("https?://www\\.wikidata\\.org/entity/Q[1-9][0-9]{0,11}")?value.substring(value.lastIndexOf('/')+1):"";}
    static String value(JSONObject row,String key){JSONObject cell=row.optJSONObject(key);return cell==null?"":cell.optString("value").trim();}
    static String label(JSONObject row,String key){String label=value(row,key);return label.matches("Q[0-9]+")?"":label;}
    static String portrait(String value){
        try{URL url=new URL(value);if(!Arrays.asList("http","https").contains(url.getProtocol())||!url.getHost().equals("commons.wikimedia.org")||url.getUserInfo()!=null||url.getPort()!=-1||url.getQuery()!=null||url.getRef()!=null||!url.getPath().startsWith("/wiki/Special:FilePath/")||url.getPath().length()<="/wiki/Special:FilePath/".length())return "";return "https://commons.wikimedia.org"+url.getPath()+"?width=320";}catch(Exception ignored){return "";}
    }
    static JSONArray rows(JSONObject raw)throws IOException{
        JSONObject results=raw.optJSONObject("results");JSONArray rows=results==null?null:results.optJSONArray("bindings");
        if(rows==null)throw new IOException("Источник актёров вернул неподдерживаемый ответ");return rows;
    }
    static JSONObject castResponse(JSONObject raw)throws Exception{
        Map<String,JSONObject> people=new LinkedHashMap<>();JSONArray rows=rows(raw);
        for(int i=0;i<rows.length()&&people.size()<100;i++){
            JSONObject row=rows.optJSONObject(i);if(row==null)continue;String id=personId(value(row,"person")),name=label(row,"personLabel");
            if(id.isEmpty()||name.isEmpty())continue;
            String imdb=value(row,"imdb"),order=value(row,"order"),photo=portrait(value(row,"image"));int billing=order.matches("[1-9][0-9]{0,3}")?Integer.parseInt(order):0;
            JSONObject old=people.get(id);if(old!=null){if(old.optString("photo").isEmpty())old.put("photo",photo);if(billing>0&&(old.optInt("billingOrder")==0||billing<old.optInt("billingOrder")))old.put("billingOrder",billing);continue;}
            people.put(id,new JSONObject().put("id",id).put("name",name).put("originalName",label(row,"originalName")).put("imdbId",imdb.matches("nm[0-9]{5,12}")?imdb:"").put("photo",photo).put("billingOrder",billing));
        }
        return new JSONObject().put("people",new JSONArray(people.values())).put("provider","Wikidata").put("schema",2);
    }
    static JSONObject filmographyResponse(JSONObject raw)throws Exception{
        Map<String,JSONObject> titles=new LinkedHashMap<>();JSONArray rows=rows(raw);
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);if(row==null)continue;String imdb=value(row,"imdb");if(!imdb.matches("tt[0-9]{5,12}"))continue;
            String name=label(row,"filmLabel"),stamp=value(row,"date"),year=stamp.matches("[0-9]{4}-.*")?stamp.substring(0,4):"",type="true".equals(value(row,"series"))?"series":"movie",released=stamp.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}.*")?stamp.substring(0,10):"";
            JSONObject old=titles.get(imdb);
            if(old!=null){if(!released.isEmpty()&&(old.optString("released").isEmpty()||released.compareTo(old.optString("released"))<0))old.put("year",year).put("released",released);if(type.equals("series"))old.put("type",type).put("kind",type);continue;}
            titles.put(imdb,new JSONObject().put("id",imdb).put("imdbId",imdb).put("type",type).put("kind",type).put("name",name).put("localized",name.matches(".*[А-Яа-яЁё].*")).put("year",year).put("released",released).put("poster","https://images.metahub.space/poster/small/"+imdb+"/img").put("provider","wikidata"));
        }
        List<JSONObject> sorted=new ArrayList<>(titles.values());sorted.sort(Comparator.comparing((JSONObject m)->m.optString("year")+m.optString("released")).reversed());
        boolean truncated=rows.length()>=3001||sorted.size()>500;
        return new JSONObject().put("items",new JSONArray(sorted.subList(0,Math.min(500,sorted.size())))).put("truncated",truncated).put("provider","Wikidata");
    }
    public JSONObject cast(JSONObject item,boolean refresh)throws Exception{
        String identity=titleIdentity(item),key="cast-"+identity.replaceAll("[^A-Za-z0-9]","");
        String query="SELECT DISTINCT ?person ?personLabel ?originalName ?imdb ?image ?order WHERE { ?work "+identity+"; (wdt:P161|wdt:P725) ?person. OPTIONAL { ?person wdt:P345 ?imdb } OPTIONAL { ?person wdt:P18 ?image } OPTIONAL { ?work (p:P161|p:P725) ?credit. ?credit (ps:P161|ps:P725) ?person; pq:P1545 ?order } OPTIONAL { ?person rdfs:label ?originalName FILTER(LANG(?originalName)=\"en\") } SERVICE wikibase:label { bd:serviceParam wikibase:language \"ru,en\" } } LIMIT 301";
        return load(key,query,false,refresh);
    }
    public JSONObject filmography(String id,boolean refresh)throws Exception{
        if(id==null||!id.matches("Q[1-9][0-9]{0,11}"))throw new IOException("Неизвестный актёр");
        String query="SELECT DISTINCT ?film ?filmLabel ?imdb ?date ?series WHERE { ?film (wdt:P161|wdt:P725) wd:"+id+"; wdt:P345 ?imdb. FILTER NOT EXISTS { ?film wdt:P31/wdt:P279* wd:Q21191270 } OPTIONAL { ?film wdt:P577 ?date } BIND(EXISTS { ?film wdt:P31/wdt:P279* wd:Q5398426 } AS ?series) SERVICE wikibase:label { bd:serviceParam wikibase:language \"ru,en\" } } LIMIT 3001";
        return load("actor-"+id,query,true,refresh);
    }
    private JSONObject load(String key,String query,boolean filmography,boolean refresh)throws Exception{
        File file=new File(directory,key+".json");JSONObject saved=null;
        try{if(file.length()>0&&file.length()<=2*1024*1024)saved=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));}catch(Exception ignored){}
        if(!refresh&&saved!=null&&(filmography||saved.optInt("schema")>=2)&&System.currentTimeMillis()-file.lastModified()<TTL)return saved;
        try{
            JSONObject result=filmography?filmographyResponse(transport.query(query)):castResponse(transport.query(query));
            save(file,result);return result;
        }catch(Exception e){if(saved!=null)return saved.put("stale",true);throw new IOException("Не удалось загрузить "+(filmography?"фильмографию":"актёров")+". Проверьте интернет и повторите.",e);}
    }
    private void save(File target,JSONObject value){
        File temp=null;try{
            directory.mkdirs();temp=File.createTempFile("people-",".tmp",directory);Files.write(temp.toPath(),value.toString().getBytes(StandardCharsets.UTF_8));temp.renameTo(target);
            File[] files=directory.listFiles(f->f.getName().endsWith(".json"));if(files!=null){Arrays.sort(files,Comparator.comparingLong(File::lastModified).reversed());for(int i=100;i<files.length;i++)files[i].delete();}
        }catch(Exception ignored){}finally{if(temp!=null)temp.delete();}
    }
}
