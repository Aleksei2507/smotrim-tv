package ru.smotrim.tv;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class PeopleCatalogTest {
    private JSONObject cell(String value)throws Exception{return new JSONObject().put("value",value);}
    private JSONObject response(JSONObject... rows)throws Exception{return new JSONObject().put("results",new JSONObject().put("bindings",new JSONArray(rows)));}
    private JSONObject person(String id,String name)throws Exception{return new JSONObject().put("person",cell("http://www.wikidata.org/entity/"+id)).put("personLabel",cell(name)).put("originalName",cell("Jim Carrey")).put("imdb",cell("nm0000120"));}
    private JSONObject film(String id,String name,String year,boolean series)throws Exception{return new JSONObject().put("imdb",cell(id)).put("filmLabel",cell(name)).put("date",cell(year+"-01-01T00:00:00Z")).put("series",cell(String.valueOf(series)));}
    @Test public void castUsesStablePersonIdsAndRejectsForeignUris()throws Exception{
        JSONObject bad=person("Q40504","Подмена").put("person",cell("https://evil.example/entity/Q40504"));
        JSONArray people=PeopleCatalog.castResponse(response(person("Q40504","Джим Керри"),person("Q40504","Дубль"),bad,person("Q123","Q123"))).getJSONArray("people");
        assertEquals(1,people.length());assertEquals("Q40504",people.getJSONObject(0).getString("id"));assertEquals("Джим Керри",people.getJSONObject(0).getString("name"));
    }
    @Test public void filmographyDeduplicatesReleaseDatesAndKeepsSeriesAndRussianNames()throws Exception{
        JSONArray titles=PeopleCatalog.filmographyResponse(response(film("tt0120382","Шоу Трумана","1999",false),film("tt0120382","Шоу Трумана","1998",false),film("tt7375404","Шучу","2018",true),film("nm0000120","Человек","2020",false))).getJSONArray("items");
        assertEquals(2,titles.length());assertEquals("series",titles.getJSONObject(0).getString("type"));assertEquals("1998",titles.getJSONObject(1).getString("year"));assertEquals("Шоу Трумана",titles.getJSONObject(1).getString("name"));assertTrue(titles.getJSONObject(1).getBoolean("localized"));
    }
    @Test public void titleLookupIsExactAndInputsCannotInjectSparql()throws Exception{
        assertEquals("wdt:P345 \"tt0120382\"",PeopleCatalog.titleIdentity(new JSONObject().put("id","tt0120382").put("imdbId","")));
        assertEquals("wdt:P2603 \"326\"",PeopleCatalog.titleIdentity(new JSONObject().put("id","kp:326")));
        AtomicInteger calls=new AtomicInteger();PeopleCatalog catalog=new PeopleCatalog(Files.createTempDirectory("people-input").toFile(),q->{calls.incrementAndGet();return response();});
        for(String id:new String[]{"Q0","Q1 } UNION { ?x ?p ?o","../Q40504","nm0000120"}){try{catalog.filmography(id,false);fail("Invalid person ID");}catch(IOException expected){}}
        try{catalog.cast(new JSONObject().put("id","tt0120382\"; ?p ?o"),false);fail("Invalid title ID");}catch(IOException expected){}
        assertEquals(0,calls.get());
    }
    @Test public void cachedDataSurvivesAnOutageAndRefreshRecovers()throws Exception{
        File dir=Files.createTempDirectory("people-cache").toFile();AtomicInteger calls=new AtomicInteger();
        PeopleCatalog catalog=new PeopleCatalog(dir,q->{if(calls.incrementAndGet()==2)throw new IOException("offline");return response(film("tt0120382","Шоу Трумана","1998",false));});
        assertEquals(1,catalog.filmography("Q40504",false).getJSONArray("items").length());catalog.filmography("Q40504",false);assertEquals(1,calls.get());
        assertTrue(catalog.filmography("Q40504",true).getBoolean("stale"));assertFalse(catalog.filmography("Q40504",true).optBoolean("stale"));assertEquals(3,calls.get());
    }
    @Test public void malformedSuccessIsNotCachedAsAnEmptyFilmography()throws Exception{
        AtomicInteger calls=new AtomicInteger();PeopleCatalog catalog=new PeopleCatalog(Files.createTempDirectory("people-invalid").toFile(),q->{calls.incrementAndGet();return new JSONObject();});
        for(int i=0;i<2;i++)try{catalog.filmography("Q40504",false);fail("Invalid upstream body");}catch(IOException expected){}
        assertEquals(2,calls.get());
    }
    @Test public void queryOnlyIncludesActingAndVoiceRolesNotNameMatchesOrProductionCredits()throws Exception{
        PeopleCatalog catalog=new PeopleCatalog(Files.createTempDirectory("people-query").toFile(),q->{assertTrue(q.contains("(wdt:P161|wdt:P725)"));assertFalse(q.contains("wdt:P57 "));assertFalse(q.contains("Jim Carrey"));return response();});
        catalog.cast(new JSONObject().put("id","tt0120382"),false);catalog.filmography("Q40504",false);
    }
    @Test public void portraitsAreBoundedCommonsThumbnailsAndBillingSurvivesDuplicateImages()throws Exception{
        String image="http://commons.wikimedia.org/wiki/Special:FilePath/Jim%20Carrey.jpg";
        assertEquals("https://commons.wikimedia.org/wiki/Special:FilePath/Jim%20Carrey.jpg?width=320",PeopleCatalog.portrait(image));
        for(String bad:new String[]{"https://evil.example/Jim.jpg","file:///sdcard/photo.jpg","https://commons.wikimedia.org@evil.example/wiki/Special:FilePath/a.jpg","https://commons.wikimedia.org:8443/wiki/Special:FilePath/a.jpg"})assertEquals("",PeopleCatalog.portrait(bad));
        JSONObject first=person("Q40504","Джим Керри"),second=person("Q40504","Джим Керри").put("image",cell(image)).put("order",cell("1"));
        JSONObject parsed=PeopleCatalog.castResponse(response(first,second)).getJSONArray("people").getJSONObject(0);
        assertEquals(1,parsed.getInt("billingOrder"));assertTrue(parsed.getString("photo").endsWith("?width=320"));
    }
    @Test public void oldCastCacheIsEnrichedWithoutLosingDataDuringAnOutage()throws Exception{
        File dir=Files.createTempDirectory("people-upgrade").toFile();AtomicInteger calls=new AtomicInteger();JSONObject title=new JSONObject().put("id","tt0120382");
        PeopleCatalog catalog=new PeopleCatalog(dir,q->{if(calls.incrementAndGet()==2)throw new IOException("offline");return response(person("Q40504","Джим Керри").put("image",cell("http://commons.wikimedia.org/wiki/Special:FilePath/Jim.jpg")));});
        catalog.cast(title,false);File cache=dir.listFiles(f->f.getName().endsWith(".json"))[0];
        JSONObject old=new JSONObject(new String(Files.readAllBytes(cache.toPath()),java.nio.charset.StandardCharsets.UTF_8));old.remove("schema");Files.write(cache.toPath(),old.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(catalog.cast(title,false).getBoolean("stale"));assertEquals(1,catalog.cast(title,false).getJSONArray("people").length());assertEquals(3,calls.get());
        catalog.cast(title,false);assertEquals(3,calls.get());
    }
}
