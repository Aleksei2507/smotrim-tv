package ru.smotrim.tv;

import org.json.*;
import org.junit.Test;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import static org.junit.Assert.*;

public class CatalogSourcesTest {
    @Test public void catalogueSearchRejectsOtherTitansBeforeRanking()throws Exception {
        JSONObject args=new JSONObject().put("id","tt32370407").put("name","Титаны").put("sourceYearMin",2024).put("season",5).put("episode",7)
                .put("aliases",new JSONArray().put("Титаны. Битва сезонов").put("Titany"));
        JSONArray rows=new JSONArray();
        String[] names={"Юные Титаны, вперед! / Teen Titans Go! / Сезон: 1-9 (2013-2025)","Юные Титаны / Teen Titans / Сезон: 5 (2005)",
                "Титаны / Titans / Сезон: 1-4 (2018-2023)","Титаны [05x01-07] (2026) WEB-DL 720p от Files-x","Титаны. Битва сезонов [S04] (2026) WEBRip 1080p"};
        for(int i=0;i<names.length;i++)rows.put(new JSONObject().put("title",names[i]).put("magnet","magnet:?xt=urn:btih:"+String.format("%040x",i+1)));
        JSONObject result=CatalogSources.search(args,true,(name,id,year)->new JSONObject().put("items",rows).put("successfulProviders",1));
        assertEquals(2,result.getJSONArray("items").length());
        assertEquals(names[3],result.getJSONArray("items").getJSONObject(0).getString("title"));
    }
    @Test public void translatedTitlesAndPunctuationStillMatch()throws Exception {
        JSONObject args=new JSONObject().put("id","tt10466872").put("name","Дюна: Пророчество")
                .put("aliases",new JSONArray().put("Dune: Prophecy"));
        for(String name:new String[]{"Дюна.Пророчество S01E03 1080p", "Dune.Prophecy.S01E03.1080p", "Дюна: Пророчество / Dune: Prophecy (2024)"})
            assertTrue(name,CatalogSources.matchesTitle(new JSONObject().put("title",name),args,true));
        assertFalse(CatalogSources.matchesTitle(new JSONObject().put("title","Дюна (2021)"),args,true));
        assertFalse(CatalogSources.matchesTitle(new JSONObject().put("title","Dune Prophecy Behind the Scenes (2024)"),args,true));
    }
    @Test public void movieIdentityDoesNotAcceptSequelsOrWrongYear()throws Exception {
        JSONObject args=new JSONObject().put("id","tt0110357").put("name","Король Лев").put("year","1994");
        assertTrue(CatalogSources.matchesTitle(new JSONObject().put("title","Король Лев / The Lion King (1994) BDRip 1080p"),args,false));
        assertFalse(CatalogSources.matchesTitle(new JSONObject().put("title","Король Лев 2 (1998)"),args,false));
        assertFalse(CatalogSources.matchesTitle(new JSONObject().put("title","Король Лев (2019)"),args,false));
    }
    @Test public void manualTrackerSearchStaysFreeForm()throws Exception {
        JSONObject args=new JSONObject().put("name","Титаны");JSONObject row=new JSONObject().put("title","Юные Титаны / Teen Titans (2005)");
        assertTrue(CatalogSources.matchesTitle(row,args,true));
        args.put("id","tt32370407").put("searchQuery",true);
        assertTrue(CatalogSources.matchesTitle(row,args,true));
    }
    @Test public void manualSeasonAndEpisodeSuffixesDoNotHideWholeSeasonPacks()throws Exception {
        for(String suffix:new String[]{" S02E03"," 2 сезон 3 серия"," сезон 2 серия 3"," серия 3"," 2x03"}) {
            List<String> queries=CatalogSources.queries(new JSONObject().put("name","Вспыш и чудо-машинки"+suffix),false);
            assertTrue(suffix,queries.contains("Вспыш и чудо машинки"));
            assertTrue(queries.size()<=4);
        }
        assertEquals("Сезон охоты 2",CatalogSources.broadTitle("Сезон охоты 2"));
        assertEquals("1917",CatalogSources.broadTitle("1917"));
    }
    @Test public void exactEpisodeMissFallsBackToTitleAndMergesProviderStatus()throws Exception {
        Set<String> calls=Collections.synchronizedSet(new HashSet<>());
        JSONObject args=new JSONObject().put("name","Вспыш и чудо-машинки").put("season",2).put("episode",3).put("year","2014");
        JSONObject result=CatalogSources.search(args,true,(name,id,year)->{
            calls.add(name);assertEquals("",year);
            return new JSONObject().put("items",name.equals("Вспыш и чудо машинки")?new JSONArray().put(release()):new JSONArray())
                    .put("warnings",new JSONArray().put("JacRed недоступен")).put("successfulProviders",1).put("failedProviders",3);
        });
        assertTrue(calls.contains("Вспыш и чудо-машинки S02E03"));assertTrue(calls.contains("Вспыш и чудо машинки"));
        assertEquals(1,result.getJSONArray("items").length());assertEquals(1,result.getJSONArray("warnings").length());
        assertEquals(2,result.getInt("successfulProviders"));assertEquals(6,result.getInt("failedProviders"));
    }
    @Test public void backupRecoversWhenPrimaryIsForbiddenAndOtherProvidersAreEmpty()throws Exception {
        JSONObject result=Sources.torrents("Вспыш и чудо машинки","","",url->{
            String host=URI.create(url).getHost();
            if(host.equals("jac.red"))throw new IOException("HTTP 403");
            if(host.equals("jac-red.ru"))return new JSONArray().put(release()).toString();
            return host.equals("rutor.info")?"<div id='index'><table></table></div>":"[]";
        });
        assertEquals(1,result.getJSONArray("items").length());assertEquals(3,result.getInt("successfulProviders"));
        assertEquals(1,result.getInt("failedProviders"));assertEquals("JacRed недоступен",result.getJSONArray("warnings").getString(0));
    }
    @Test public void duplicateHashesFromBothAggregatorsAppearOnce()throws Exception {
        JSONObject result=Sources.torrents("Test","","",url->{String host=URI.create(url).getHost();return host.startsWith("jac")?new JSONArray().put(release()).toString():host.equals("rutor.info")?"":"[]";});
        assertEquals(1,result.getJSONArray("items").length());assertEquals(4,result.getInt("successfulProviders"));
    }
    @Test public void completeOutageIsDifferentFromSuccessfulEmptySearch()throws Exception {
        JSONObject unavailable=Sources.torrents("Test","","",url->{throw new IOException("offline");});
        assertEquals(0,unavailable.getInt("successfulProviders"));assertEquals(4,unavailable.getInt("failedProviders"));
        JSONObject empty=Sources.torrents("Test","","",url->URI.create(url).getHost().equals("rutor.info")?"":"[]");
        assertEquals(4,empty.getInt("successfulProviders"));assertEquals(0,empty.getInt("failedProviders"));
    }
    private static JSONObject release()throws Exception {
        return new JSONObject().put("title","Вспыш и чудо-машинки / Сезон: 1, 2 / Серии: 1-40 / Dub")
                .put("magnet","magnet:?xt=urn:btih:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").put("sid",12).put("tracker","fixture");
    }
}
