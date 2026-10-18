package ru.smotrim.tv;
import org.json.*;
import org.junit.Test;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class MediaCatalogTest {
    @Test public void cinemetaDetailWorksWithoutKeysAndLocalizesOldImdbCard()throws Exception{
        List<String> calls=new ArrayList<>();
        MediaCatalog c=new MediaCatalog(Files.createTempDirectory("catalog-no-keys").toFile(),false,false,(provider,path)->{
            calls.add(provider+path);
            return new JSONObject().put("meta",new JSONObject().put("id","tt31531192").put("type","series").put("name","Выживалити: Миссия Альфа").put("description","Show description").put("videos",new JSONArray().put(new JSONObject().put("season",2).put("episode",1).put("overview",""))));
        });
        JSONObject selected=new JSONObject().put("id","tt31531192").put("type","series").put("name","Vyzhivaliti. Missiya Alfa"),detail=c.detail(selected,false);
        assertEquals(Collections.singletonList("cinemeta/meta/series/tt31531192.json"),calls);
        assertEquals("Выживалити: Миссия Альфа",detail.getString("name"));
        assertEquals("Show description",detail.getString("description"));
        assertEquals(2,detail.getJSONArray("videos").getJSONObject(0).getInt("season"));
        assertEquals("",detail.getJSONArray("videos").getJSONObject(0).getString("description"));
        assertEquals("Выживалити: Миссия Альфа",c.detail(selected,false).getString("name"));assertEquals("Repeated opening uses the metadata cache",1,calls.size());assertTrue(detail.getJSONArray("aliases").toString().contains("Vyzhivaliti"));
    }
    @Test public void cinemetaCannotReplaceRussianProviderTitle()throws Exception{
        MediaCatalog c=new MediaCatalog(Files.createTempDirectory("catalog-localized").toFile(),false,false,(provider,path)->new JSONObject().put("meta",new JSONObject().put("id","tt31531192").put("type","series").put("name","Другое имя")));
        JSONObject selected=MediaCatalog.kinopoisk(kp(123,"Выживалити. Миссия Альфа",2024).put("imdbId","tt31531192"));
        assertEquals("Выживалити. Миссия Альфа",c.detail(selected,false).getString("name"));
    }
    private JSONObject kp(int id,String name,int year)throws Exception{return new JSONObject().put("kinopoiskId",id).put("nameRu",name).put("nameOriginal","Original title").put("type","TV_SHOW").put("year",year).put("countries",new JSONArray().put(new JSONObject().put("country","Россия")));}
    @Test public void russianShowHasStableIdentityWithoutImdbAndNoLiteralNull()throws Exception{JSONObject m=MediaCatalog.kinopoisk(kp(123,"Выживалити",2024).put("description",JSONObject.NULL).put("imdbId",JSONObject.NULL));assertEquals("kp:123",m.getString("id"));assertEquals("series",m.getString("type"));assertEquals("show",m.getString("kind"));assertEquals("ru",m.getString("origin"));assertEquals("",m.getString("description"));assertTrue(MediaCatalog.validId(m.getString("id")));assertFalse(MediaCatalog.validId("kp:../../secret"));}
    @Test public void mergeKeepsOneCardAndRussianTextWithoutMergingRemakes()throws Exception{JSONObject a=MediaCatalog.cinemeta(new JSONObject().put("id","tt1234567").put("type","series").put("name","Original title").put("year","2024")),b=MediaCatalog.kinopoisk(kp(123,"Русское название",2024).put("imdbId","tt1234567"));JSONArray mixed=MediaCatalog.mix(Arrays.asList(new JSONArray().put(a),new JSONArray().put(b)));assertEquals(1,mixed.length());assertEquals("Русское название",mixed.getJSONObject(0).getString("name"));assertEquals("tt1234567",mixed.getJSONObject(0).getString("id"));assertTrue(mixed.getJSONObject(0).getJSONArray("ids").toString().contains("kp:123"));assertFalse(MediaCatalog.same(a,MediaCatalog.kinopoisk(kp(456,"Original title",1990))));assertFalse(MediaCatalog.same(a,MediaCatalog.kinopoisk(kp(456,"Original title",2024).put("imdbId","tt7654321"))));}
    @Test public void searchQueriesProvidersNotBundledPopularAndSurvivesFailure()throws Exception{List<String> calls=Collections.synchronizedList(new ArrayList<>());MediaCatalog c=new MediaCatalog(Files.createTempDirectory("catalog-test").toFile(),true,false,(provider,path)->{calls.add(provider+path);if(provider.equals("kp"))return new JSONObject().put("items",new JSONArray().put(kp(123,"Выживалити",2024))).put("totalPages",3);throw new IOException("offline");});JSONObject result=c.catalog("Выживалити","movie",2);assertEquals("Выживалити",result.getJSONArray("items").getJSONObject(0).getString("name"));assertTrue(result.getBoolean("hasMore"));assertTrue(result.getJSONArray("warnings").length()>0);assertTrue(calls.stream().anyMatch(s->s.contains("keyword=")&&s.contains("page=2")));assertTrue(calls.stream().anyMatch(s->s.contains("/catalog/series/")));}
    @Test public void episodesNormalizeBothProvidersAndPreserveDescription()throws Exception{JSONObject raw=new JSONObject().put("items",new JSONArray().put(new JSONObject().put("number",2).put("episodes",new JSONArray().put(new JSONObject().put("episodeNumber",3).put("nameRu","Испытание").put("synopsis","Описание серии").put("releaseDate","2024-01-01")))));JSONArray videos=MediaCatalog.kpEpisodes(raw);JSONObject m=SeriesCatalog.normalize(MediaCatalog.kinopoisk(kp(123,"Выживалити",2024)).put("videos",videos),"kp:123");JSONObject e=m.getJSONArray("videos").getJSONObject(0);assertEquals(2,e.getInt("season"));assertEquals(3,e.getInt("episode"));assertEquals("Описание серии",e.getString("description"));JSONArray tm=MediaCatalog.tmdbEpisodes(new JSONObject().put("episodes",new JSONArray().put(new JSONObject().put("episode_number",4).put("name","Эпизод").put("overview","Сюжет"))),2);assertEquals(4,tm.getJSONObject(0).getInt("episode"));assertEquals("Сюжет",tm.getJSONObject(0).getString("description"));}
    @Test public void alternateTitleQueriesAreBoundedAndEpisodeSpecific()throws Exception{JSONObject a=new JSONObject().put("name","Выживалити").put("aliases",new JSONArray().put("Survival").put("Third alias")).put("season",2).put("episode",3);List<String> q=CatalogSources.queries(a,true);assertEquals(3,q.size());assertEquals("Выживалити S02E03",q.get(0));assertEquals("Survival S02E03",q.get(1));assertEquals("Выживалити 2 сезон",q.get(2));assertEquals(2,CatalogSources.queries(a,false).size());}
    @Test public void tmdbPeopleAndAdultResultsAreRejected()throws Exception{JSONObject raw=new JSONObject().put("id",10).put("name","Test").put("origin_country",new JSONArray().put("RU")).put("genre_ids",new JSONArray().put(10764));assertNull(MediaCatalog.tmdb(raw,"person"));JSONObject m=MediaCatalog.tmdb(raw,"series");assertEquals("show",m.getString("kind"));assertEquals("tmdb:series:10",m.getString("id"));assertNull(MediaCatalog.tmdb(raw.put("adult",true),"movie"));}
    @Test public void imdbCardCanGetRussianTmdbDescriptionAndLocalizedEpisodes()throws Exception{
        MediaCatalog c=new MediaCatalog(Files.createTempDirectory("catalog-detail-test").toFile(),false,true,(provider,path)->{
            if(path.startsWith("/find/"))return new JSONObject().put("tv_results",new JSONArray().put(new JSONObject().put("id",42)));
            if(path.contains("/season/"))return new JSONObject().put("episodes",new JSONArray().put(new JSONObject().put("episode_number",1).put("name","Первая серия").put("overview","Русское содержание")));
            if(provider.equals("tmdb"))return new JSONObject().put("id",42).put("name","Русское название").put("overview","Русское описание").put("external_ids",new JSONObject().put("imdb_id","tt1234567")).put("seasons",new JSONArray().put(new JSONObject().put("season_number",1)));
            return new JSONObject().put("meta",new JSONObject().put("id","tt1234567").put("name","English title").put("type","series").put("videos",new JSONArray().put(new JSONObject().put("season",1).put("episode",1).put("title","English episode"))));
        });
        JSONObject selected=new JSONObject().put("id","tt1234567").put("type","series").put("name","English title"),m=c.detail(selected,false);assertEquals("tt1234567",m.getString("id"));assertEquals("Русское название",m.getString("name"));assertEquals("Русское содержание",m.getJSONArray("videos").getJSONObject(0).getString("description"));assertTrue(m.getJSONArray("ids").toString().contains("tmdb:series:42"));
    }
}
