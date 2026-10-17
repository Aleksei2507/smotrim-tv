package ru.smotrim.tv;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;

public class SeriesCatalogTest {
    private JSONObject meta() throws Exception {return new JSONObject("{id:'tt1234567',type:'series',name:'Example',videos:[{season:2,episode:1,name:'Second',released:'2026-11-01T00:00:00Z'},{season:1,episode:2,title:'Two'},{season:1,episode:1,title:'One'},{season:0,episode:1,title:'Special'}]}");}
    @Test public void metadataDoesNotDependOnTorrentFiles()throws Exception{JSONObject r=SeriesCatalog.normalize(meta(),"tt1234567");JSONArray v=r.getJSONArray("videos");assertEquals(4,v.length());assertEquals(0,v.getJSONObject(0).getInt("season"));assertEquals("tt1234567:1:1",v.getJSONObject(1).getString("id"));assertEquals("Second",v.getJSONObject(3).getString("title"));assertEquals("2026-11-01T00:00:00Z",v.getJSONObject(3).getString("released"));}
    @Test public void duplicateAndInvalidEpisodeNumbersAreDiscarded()throws Exception{JSONObject m=meta();m.getJSONArray("videos").put(new JSONObject("{season:1,episode:1,title:'Duplicate'}")).put(new JSONObject("{season:1,episode:0}")).put(new JSONObject("{season:1.5,episode:1}")).put(new JSONObject("{season:-1,episode:4}")).put(new JSONObject("{season:1,number:3,name:'Three'}"));JSONArray rows=SeriesCatalog.normalize(m,"tt1234567").getJSONArray("videos");assertEquals(5,rows.length());assertEquals(3,rows.getJSONObject(3).getInt("episode"));}
    @Test public void mismatchedMovieAndTraversalIdsAreRejected()throws Exception{for(String id:new String[]{"../tt1234567","tt1234568","tt1"})try{SeriesCatalog.normalize(meta(),id);fail(id);}catch(IOException expected){}JSONObject m=meta().put("type","movie");try{SeriesCatalog.normalize(m,"tt1234567");fail();}catch(IOException expected){}}
    @Test public void missingEpisodesRemainEmpty()throws Exception{JSONObject m=meta();m.remove("videos");assertEquals(0,SeriesCatalog.normalize(m,"tt1234567").getJSONArray("videos").length());}
    @Test public void currentCacheCanBeReadWithoutNetwork()throws Exception{File dir=Files.createTempDirectory("series-cache").toFile(),f=new File(dir,"tt1234567.json");try{Files.write(f.toPath(),SeriesCatalog.normalize(meta(),"tt1234567").toString().getBytes(StandardCharsets.UTF_8));assertEquals(4,SeriesCatalog.detail(dir,"tt1234567",false).getJSONArray("videos").length());}finally{f.delete();dir.delete();}}
    @Test public void episodeDescriptionsAreBounded()throws Exception{JSONObject m=meta();m.getJSONArray("videos").getJSONObject(0).put("overview",new String(new char[10000]).replace('\0','x'));assertEquals(600,SeriesCatalog.normalize(m,"tt1234567").getJSONArray("videos").getJSONObject(3).getString("description").length());}
}
