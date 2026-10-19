package ru.smotrim.tv;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Public, versioned data only. Index + immutable cards; no account, token or executable content. */
public final class GithubCatalog {
    static final String BASE="https://raw.githubusercontent.com/Aleksei2507/smotrim-tv/main/catalog/v1/";
    static final int INDEX_LIMIT=2*1024*1024,CARD_LIMIT=512*1024;
    static final long CHECK_INTERVAL=4*60*60*1000L,CACHE_LIMIT=8*1024*1024L;
    public interface Bundled { byte[] read(String path)throws IOException; }
    interface Fetcher { Response get(String path,String etag,int limit)throws IOException; }
    static final class Response {
        final int status;final byte[] body;final String etag;
        Response(int status,byte[] body,String etag){this.status=status;this.body=body;this.etag=etag==null?"":etag;}
    }
    private final File directory;
    private final Bundled bundled;
    private final Fetcher fetcher;
    private volatile JSONObject index;
    private volatile String error="",etag="";
    private volatile long checkedAt;

    public GithubCatalog(File directory,Bundled bundled)throws IOException {this(directory,bundled,GithubCatalog::download);}
    GithubCatalog(File directory,Bundled bundled,Fetcher fetcher)throws IOException {
        this.directory=directory;this.bundled=bundled;this.fetcher=fetcher;
        JSONObject seed=validateIndex(bundled.read("index.json"));index=seed;
        try{JSONObject saved=validateIndex(read(new File(directory,"index.json"),INDEX_LIMIT));if(saved.getLong("revision")>=seed.getLong("revision"))index=saved;}catch(Exception ignored){}
        try{JSONObject state=new JSONObject(new String(read(new File(directory,"state.json"),4096),StandardCharsets.UTF_8));if(state.optLong("revision")==index.optLong("revision")){etag=state.optString("etag");checkedAt=state.optLong("checkedAt");}}catch(Exception ignored){}
    }
    public synchronized boolean refresh(boolean force) {
        if(!force&&checkedAt>0&&System.currentTimeMillis()-checkedAt<CHECK_INTERVAL)return false;
        try{
            Response response=fetcher.get("index.json",etag,INDEX_LIMIT);boolean changed=false;
            if(response.status==200){JSONObject next=validateIndex(response.body);if(next.getLong("revision")<index.getLong("revision"))throw new IOException("GitHub вернул более старую подборку");
                if(next.getLong("revision")==index.getLong("revision")&&!next.toString().equals(index.toString()))throw new IOException("Изменён каталог без новой версии");
                write(new File(directory,"index.json"),response.body);changed=next.getLong("revision")!=index.getLong("revision");index=next;etag=response.etag;
            }else if(response.status!=304)throw new IOException("GitHub временно недоступен (HTTP "+response.status+")");
            checkedAt=System.currentTimeMillis();error="";
            write(new File(directory,"state.json"),new JSONObject().put("revision",index.getLong("revision")).put("etag",etag).put("checkedAt",checkedAt).toString().getBytes(StandardCharsets.UTF_8));return changed;
        }catch(Exception e){error="Не удалось обновить подборку. Показана сохранённая версия.";return false;}
    }
    public JSONObject state()throws JSONException{return new JSONObject().put("count",index.getJSONArray("items").length()).put("revision",index.getLong("revision")).put("updatedAt",index.getString("updatedAt")).put("checkedAt",checkedAt).put("error",error);}
    public JSONArray items(String query,String type)throws JSONException {
        String term=normalize(query);JSONArray out=new JSONArray(),rows=index.getJSONArray("items");
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(query.isEmpty()&&!type.equals(row.getString("type")))continue;
            String names=row.optString("name")+" "+row.optString("originalName")+" "+row.optJSONArray("aliases");if(!term.isEmpty()&&!normalize(names).contains(term))continue;
            JSONObject copy=new JSONObject(row.toString());copy.remove("card");copy.remove("sha256");out.put(copy.put("provider","github").put("localized",true));
        }return out;
    }
    public boolean contains(JSONObject selected){return entry(selected)!=null;}
    private JSONObject entry(JSONObject selected){
        if(selected==null)return null;Set<String> ids=new HashSet<>();ids.add(selected.optString("id"));ids.add(selected.optString("imdbId"));JSONArray aliases=selected.optJSONArray("ids");if(aliases!=null)for(int i=0;i<aliases.length();i++)ids.add(aliases.optString(i));ids.remove("");
        JSONArray rows=index.optJSONArray("items");for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(ids.contains(row.optString("id")))return row;JSONArray other=row.optJSONArray("ids");if(other!=null)for(int n=0;n<other.length();n++)if(ids.contains(other.optString(n)))return row;}return null;
    }
    public synchronized JSONObject detail(JSONObject selected)throws Exception {
        JSONObject row=entry(selected);if(row==null)return null;
        String path=row.getString("card"),hash=row.getString("sha256");File file=new File(directory,hash+".json");byte[] data=null;
        try{byte[] cached=read(file,CARD_LIMIT);validateCard(cached,row);data=cached;file.setLastModified(System.currentTimeMillis());}catch(Exception ignored){}
        if(data==null)try{byte[] seed=bundled.read(path);validateCard(seed,row);data=seed;}catch(Exception ignored){}
        JSONObject card;
        if(data==null)try{Response response=fetcher.get(path,"",CARD_LIMIT);if(response.status!=200)throw new IOException("Карточка временно недоступна. Попробуйте обновить подборку.");validateCard(response.body,row);data=response.body;write(file,data);prune();}
        catch(Exception unavailable){JSONObject previous=previousCard(row);if(previous==null)throw unavailable;return MediaCatalog.merge(previous.put("provider","github").put("localized",true).put("stale",true).put("metadataWarnings",new JSONArray().put("Показана сохранённая карточка. Обновление временно недоступно.")),selected);}
        card=validateCard(data,row);card.put("provider","github").put("localized",true).put("catalogUpdatedAt",card.optString("updatedAt",index.getString("updatedAt")));
        // Curated Russian text wins; keep local identity, query hint and episode selection.
        return MediaCatalog.merge(card,selected);
    }
    private JSONObject previousCard(JSONObject row){
        File[] files=directory.listFiles(f->f.getName().matches("[a-f0-9]{64}\\.json"));
        if(files!=null){Arrays.sort(files,Comparator.comparingLong(File::lastModified).reversed());for(File file:files)try{JSONObject expected=new JSONObject(row.toString()).put("sha256",file.getName().replace(".json",""));return validateCard(read(file,CARD_LIMIT),expected);}catch(Exception ignored){}}
        try{JSONArray rows=validateIndex(bundled.read("index.json")).getJSONArray("items");for(int i=0;i<rows.length();i++){JSONObject seed=rows.getJSONObject(i);if(seed.getString("id").equals(row.getString("id")))return validateCard(bundled.read(seed.getString("card")),seed);}}catch(Exception ignored){}
        return null;
    }
    public JSONObject sources(JSONObject selected,int season,int episode)throws Exception {
        JSONObject card=detail(selected);JSONArray result=new JSONArray();String updated="";
        if(card!=null){JSONArray sources=card.optJSONArray("sources");updated=card.optString("sourcesUpdatedAt");if(sources!=null)for(int i=0;i<sources.length();i++){
            JSONObject row=sources.getJSONObject(i);
            if(season>=0&&(row.optInt("season",-1)!=season||row.optInt("episode",0)>0&&row.optInt("episode")!=episode))continue;
            if(episode>0&&row.has("episodeFrom")&&(episode<row.optInt("episodeFrom")||episode>row.optInt("episodeTo")))continue;
            if(season<0&&card.optString("type").equals("series"))continue;
            JSONObject copy=new JSONObject(row.toString()).put("catalogSource",true).put("seeds",-1); // Stored availability is not a live peer count.
            copy.put("source","Подборка · "+row.optString("source","торрент"));result.put(copy);
        }}
        return new JSONObject().put("items",result).put("updatedAt",updated).put("warnings",new JSONArray());
    }
    static String normalize(String s){return s.toLowerCase(Locale.ROOT).replace('ё','е').replaceAll("[^\\p{L}\\p{N}]","");}
    static JSONObject validateIndex(byte[] data)throws IOException {
        try{
            if(data.length>INDEX_LIMIT)throw new IOException("Список слишком большой");JSONObject root=new JSONObject(new String(data,StandardCharsets.UTF_8));
            if(root.getInt("schemaVersion")!=1||root.getLong("revision")<1||!root.getString("updatedAt").matches("\\d{4}-\\d{2}-\\d{2}T.*Z"))throw new IOException("Неподдерживаемый каталог");
            JSONArray rows=root.getJSONArray("items");if(rows.length()<1||rows.length()>3000)throw new IOException("Некорректный размер списка");Set<String> ids=new HashSet<>();
            for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);String id=row.getString("id"),hash=row.getString("sha256");validateMedia(row);
                if(!ids.add(id)||!hash.matches("[a-f0-9]{64}")||!row.getString("card").equals("cards/"+hash+".json"))throw new IOException("Некорректная ссылка карточки");
            }return root;
        }catch(JSONException e){throw new IOException("Некорректный список карточек",e);}
    }
    static JSONObject validateCard(byte[] data,JSONObject row)throws IOException {
        try{
            if(data.length>CARD_LIMIT||!sha256(data).equals(row.getString("sha256")))throw new IOException("Карточка не совпала с контрольной суммой");JSONObject card=new JSONObject(new String(data,StandardCharsets.UTF_8));validateMedia(card);
            if(!card.getString("id").equals(row.getString("id"))||!card.getString("type").equals(row.getString("type")))throw new IOException("Другая карточка");
            JSONArray sources=card.optJSONArray("sources");if(sources!=null){if(sources.length()>500)throw new IOException("Слишком много вариантов");for(int i=0;i<sources.length();i++){
                JSONObject source=sources.getJSONObject(i);String magnet=source.getString("magnet");
                if(magnet.length()>8192||!magnet.matches("(?i)magnet:\\?[^\\r\\n]*xt=urn:btih:[a-f0-9]{40}(?:&[^\\r\\n]*)?")||source.optString("title").isEmpty()||source.optString("title").length()>1200||!source.getString("audioLanguage").equals("ru"))throw new IOException("Некорректный вариант просмотра");
                if(source.has("episodeFrom")&&(source.optInt("episodeFrom")<1||source.optInt("episodeTo")<source.optInt("episodeFrom")||source.optInt("episodeTo")>9999))throw new IOException("Некорректный диапазон серий");
                if(card.getString("type").equals("series")&&(source.optInt("season",-1)<0||source.optInt("season")>999||source.optInt("episode",0)<0||source.optInt("episode")>9999))throw new IOException("Некорректная серия раздачи");
            }}
            JSONArray videos=card.optJSONArray("videos");if(videos!=null){if(videos.length()>3000)throw new IOException("Слишком много серий");Set<String> seen=new HashSet<>();for(int i=0;i<videos.length();i++){JSONObject v=videos.getJSONObject(i);int season=v.getInt("season"),episode=v.getInt("episode");if(season<0||season>999||episode<1||episode>9999||!seen.add(season+":"+episode))throw new IOException("Некорректный список серий");}}
            return card;
        }catch(JSONException e){throw new IOException("Некорректная карточка",e);}
    }
    private static void validateMedia(JSONObject row)throws IOException,JSONException {
        if(!MediaCatalog.validId(row.getString("id"))||!Arrays.asList("movie","series").contains(row.getString("type"))||row.getString("name").trim().isEmpty()||row.getString("name").length()>240||(!row.getString("name").matches(".*[А-Яа-яЁё].*")&&!row.getString("name").matches("[0-9 +:.-]+"))||row.optString("description").length()>6000)throw new IOException("Некорректные данные фильма");
        for(String field:new String[]{"poster","background"}){String url=row.optString(field);if(!url.isEmpty())try{URL u=new URL(url);if(!u.getProtocol().equals("https")||u.getUserInfo()!=null)throw new IOException("Некорректный адрес изображения");}catch(MalformedURLException e){throw new IOException("Некорректный адрес изображения");}}
    }
    static String sha256(byte[] bytes)throws IOException {try{StringBuilder hex=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))hex.append(String.format(Locale.ROOT,"%02x",b&255));return hex.toString();}catch(Exception e){throw new IOException(e);}}
    static byte[] read(File file,int limit)throws IOException {if(file.length()>limit)throw new IOException("Слишком большой файл");try(InputStream in=new FileInputStream(file)){return bounded(in,limit);}}
    private static byte[] bounded(InputStream in,int limit)throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];long deadline=System.nanoTime()+20_000_000_000L;int n;while((n=in.read(b))!=-1){if(System.nanoTime()>deadline)throw new IOException("Истекло время загрузки");if(out.size()+n>limit)throw new IOException("Слишком большой ответ");out.write(b,0,n);}return out.toByteArray();}
    private static void write(File file,byte[] data)throws IOException {File parent=file.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Не удалось сохранить каталог");File temp=File.createTempFile("catalog-",".tmp",parent);try{Files.write(temp.toPath(),data);Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);}finally{temp.delete();}}
    private void prune(){File[] files=directory.listFiles(f->f.getName().matches("[a-f0-9]{64}\\.json"));if(files==null)return;Arrays.sort(files,Comparator.comparingLong(File::lastModified).reversed());long size=0;for(int i=0;i<files.length;i++){size+=files[i].length();if(i>=40||size>CACHE_LIMIT)files[i].delete();}}
    private static Response download(String path,String etag,int limit)throws IOException {
        if(!path.equals("index.json")&&!path.matches("cards/[a-f0-9]{64}\\.json"))throw new IOException("Некорректный путь каталога");
        HttpURLConnection connection=(HttpURLConnection)new URL(BASE+path).openConnection();connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(5000);connection.setReadTimeout(8000);connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("User-Agent","SmotrimTV-Catalog/1");if(!etag.isEmpty())connection.setRequestProperty("If-None-Match",etag);
        try{int code=connection.getResponseCode();if(code!=200)return new Response(code,new byte[0],connection.getHeaderField("ETag"));if(connection.getContentLengthLong()>limit)throw new IOException("Слишком большой ответ");try(InputStream in=connection.getInputStream()){return new Response(code,bounded(in,limit),connection.getHeaderField("ETag"));}}finally{connection.disconnect();}
    }
}
