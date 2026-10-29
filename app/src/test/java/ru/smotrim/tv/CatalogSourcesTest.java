package ru.smotrim.tv;

import org.json.*;
import org.junit.Test;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import static org.junit.Assert.*;

public class CatalogSourcesTest {
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
