package ru.smotrim.tv;

import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class GithubCatalogTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    final Map<String,byte[]> bundle=new HashMap<>();
    JSONObject card(String id,String type)throws Exception{return new JSONObject().put("id",id).put("type",type).put("name","Русское название").put("originalName","Original title").put("description","Описание на русском").put("localized",true).put("sources",new JSONArray());}
    byte[] bytes(JSONObject o){return o.toString().getBytes(StandardCharsets.UTF_8);}
    JSONObject index(JSONObject card,long revision)throws Exception {byte[] data=bytes(card);String hash=GithubCatalog.sha256(data);String path="cards/"+hash+".json";bundle.put(path,data);return new JSONObject().put("schemaVersion",1).put("revision",revision).put("updatedAt","2026-10-19T00:00:00Z").put("items",new JSONArray().put(new JSONObject().put("id",card.getString("id")).put("type",card.getString("type")).put("name",card.getString("name")).put("originalName",card.optString("originalName")).put("aliases",new JSONArray().put("Дюна Пророчество")).put("card",path).put("sha256",hash)));}
    GithubCatalog open(JSONObject index,GithubCatalog.Fetcher fetch)throws Exception {bundle.put("index.json",bytes(index));return new GithubCatalog(temp.newFolder(),path->{byte[] b=bundle.get(path);if(b==null)throw new FileNotFoundException(path);return b;},fetch);}
    GithubCatalog.Fetcher offline(){return (p,e,l)->{throw new IOException("offline");};}
    JSONObject selected()throws JSONException{return new JSONObject().put("id","tt10466872").put("type","series").put("searchHint","ДЮНА").put("historyId","tt10466872");}

    @Test public void bundledCardsWorkOfflineAndSearchAliases()throws Exception{
        GithubCatalog c=open(index(card("tt10466872","series"),1),offline());
        assertEquals(1,c.items("дюна: пророчество","movie").length());assertEquals(0,c.items("","movie").length());
        JSONObject detail=c.detail(selected());assertEquals("Русское название",detail.getString("name"));assertEquals("ДЮНА",detail.getString("searchHint"));
        assertFalse(c.refresh(true));assertEquals(1,c.state().getInt("revision"));assertFalse(c.state().getString("error").isEmpty());
    }
    @Test public void conditionalRefreshStoresEtagAndSkipsFrequentChecks()throws Exception {
        JSONObject first=index(card("tt10466872","series"),1),next=index(card("tt10466872","series").put("description","Новое описание"),2);List<String> etags=new ArrayList<>();
        GithubCatalog c=open(first,(p,e,l)->{etags.add(e);return new GithubCatalog.Response(etags.size()==1?200:304,bytes(next),"etag-2");});
        assertTrue(c.refresh(true));assertFalse(c.refresh(false));assertEquals(1,etags.size());assertFalse(c.refresh(true));assertEquals("etag-2",etags.get(1));assertEquals(2,c.state().getInt("revision"));
    }
    @Test public void rollbackAndInvalidManifestKeepLastGoodIndex()throws Exception{
        JSONObject first=index(card("tt10466872","series"),2),old=index(card("tt10466872","series"),1);
        GithubCatalog c=open(first,(p,e,l)->new GithubCatalog.Response(200,bytes(old),""));assertFalse(c.refresh(true));assertEquals(2,c.state().getLong("revision"));
        old.getJSONArray("items").getJSONObject(0).put("card","../private");assertThrows(IOException.class,()->GithubCatalog.validateIndex(bytes(old)));
    }
    @Test public void corruptedCardFallsBackToBundledPreviousRevision()throws Exception {
        JSONObject first=index(card("tt10466872","series"),1),next=index(card("tt10466872","series").put("name","Новое название"),2);
        bundle.remove(next.getJSONArray("items").getJSONObject(0).getString("card"));
        GithubCatalog c=open(first,(p,e,l)->new GithubCatalog.Response(200,p.equals("index.json")?bytes(next):"{}".getBytes(StandardCharsets.UTF_8),""));
        assertTrue(c.refresh(true));JSONObject detail=c.detail(selected());assertTrue(detail.getBoolean("stale"));assertEquals("Русское название",detail.getString("name"));
    }
    @Test public void downloadedCardAndIndexSurviveRestartWithoutNetwork()throws Exception{
        JSONObject first=index(card("tt10466872","series"),1),next=index(card("tt10466872","series").put("name","Новое название"),2);String path=next.getJSONArray("items").getJSONObject(0).getString("card");byte[] remote=bundle.remove(path);File dir=temp.newFolder();bundle.put("index.json",bytes(first));GithubCatalog.Bundled b=p->{if(!bundle.containsKey(p))throw new FileNotFoundException(p);return bundle.get(p);};
        GithubCatalog c=new GithubCatalog(dir,b,(p,e,l)->new GithubCatalog.Response(200,p.equals("index.json")?bytes(next):remote,"new"));assertTrue(c.refresh(true));assertEquals("Новое название",c.detail(selected()).getString("name"));
        GithubCatalog restored=new GithubCatalog(dir,b,offline());assertEquals(2,restored.state().getInt("revision"));assertEquals("Новое название",restored.detail(selected()).getString("name"));
    }
    JSONObject source(int season,int episode)throws Exception{return new JSONObject().put("title","Сериал S01 RUS").put("magnet","magnet:?xt=urn:btih:"+"a".repeat(40)).put("audioLanguage","ru").put("season",season).put("episode",episode);}
    @Test public void sourcesFilterSeasonExactEpisodeAndPartialPack()throws Exception{
        JSONObject card=card("tt10466872","series");card.getJSONArray("sources").put(source(1,0).put("episodeFrom",1).put("episodeTo",2)).put(source(1,3)).put(source(2,3));
        GithubCatalog c=open(index(card,1),offline());JSONArray list=c.sources(selected(),1,3).getJSONArray("items");assertEquals(1,list.length());assertEquals(3,list.getJSONObject(0).getInt("episode"));assertEquals(-1,list.getJSONObject(0).getInt("seeds"));assertTrue(list.getJSONObject(0).getBoolean("catalogSource"));assertEquals(0,c.sources(selected(),-1,0).getJSONArray("items").length());
    }
    @Test public void contentHashAndIdentityAreValidated()throws Exception{
        JSONObject c=card("tt10466872","series"),idx=index(c,1),row=idx.getJSONArray("items").getJSONObject(0);assertThrows(IOException.class,()->GithubCatalog.validateCard(bytes(c.put("name","Подмена")),row));
        row.put("id","tt0110357").put("sha256",GithubCatalog.sha256(bytes(c)));assertThrows(IOException.class,()->GithubCatalog.validateCard(bytes(c),row));
    }
    @Test public void invalidEpisodeRangeIsRejected()throws Exception{JSONObject c=card("tt10466872","series");c.getJSONArray("sources").put(source(1,0).put("episodeFrom",5).put("episodeTo",2));JSONObject row=index(c,1).getJSONArray("items").getJSONObject(0);assertThrows(IOException.class,()->GithubCatalog.validateCard(bytes(c),row));}
    @Test public void shippedCatalogAndBundleHaveIdenticalValidCards()throws Exception {
        Path publicRoot=Path.of("../catalog/v1"),assets=Path.of("src/main/assets/catalog/v1");byte[] data=Files.readAllBytes(publicRoot.resolve("index.json"));assertArrayEquals(data,Files.readAllBytes(assets.resolve("index.json")));JSONObject idx=GithubCatalog.validateIndex(data);assertEquals(34,idx.getJSONArray("items").length());
        for(int i=0;i<idx.getJSONArray("items").length();i++){JSONObject row=idx.getJSONArray("items").getJSONObject(i);byte[] card=Files.readAllBytes(publicRoot.resolve(row.getString("card")));GithubCatalog.validateCard(card,row);assertArrayEquals(card,Files.readAllBytes(assets.resolve(row.getString("card"))));}
    }
}
