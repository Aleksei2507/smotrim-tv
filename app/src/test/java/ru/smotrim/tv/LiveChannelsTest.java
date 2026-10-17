package ru.smotrim.tv;

import org.json.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class LiveChannelsTest {
    private JSONObject stream(String id,String path)throws Exception{return new JSONObject().put("id",id).put("url","https://tv.test/"+path);}
    @Test public void playlistHeadersArePreservedAndDoNotLeakToNextChannel()throws Exception{
        JSONArray rows=Sources.parseM3u("#EXTM3U\n#EXTINF:-1 http-user-agent=\"Initial/1\" http-referrer=\"https://tv.test/watch\",One\n#EXTVLCOPT:http-user-agent=Player/2\nhttps://tv.test/one.m3u8\n#EXTINF:-1,Two\nhttps://tv.test/two.m3u8");
        assertEquals("Player/2",LiveChannels.headers(rows.getJSONObject(0)).get("User-Agent"));
        assertEquals("https://tv.test/watch",LiveChannels.headers(rows.getJSONObject(0)).get("Referer"));
        assertTrue(LiveChannels.headers(rows.getJSONObject(1)).isEmpty());
    }
    @Test public void unsafeAndUnrelatedHeadersAreRejected()throws Exception{
        JSONObject raw=new JSONObject().put("User-Agent","Bad\r\nInjected: yes").put("Referer","file:///private").put("Authorization","secret");
        assertTrue(LiveChannels.headers(new JSONObject().put("headers",raw)).isEmpty());
    }
    @Test public void alternativesKeepTheSameBroadcastAndDeduplicateUrls()throws Exception{
        JSONObject selected=stream("NTV.ru@SD","primary");
        JSONArray list=new JSONArray().put(selected).put(stream("NTV.ru@HD","hd")).put(stream("NTV.ru@Plus2","delayed")).put(stream("NTVMir.ru@SD","other"));
        JSONArray backup=new JSONArray().put(stream("NTV.ru@SD","backup")).put(stream("NTV.ru@SD","primary")).put(stream("NTV.ru@Europa","regional"));
        List<JSONObject> result=LiveChannels.candidates(selected,list,backup,"");
        assertEquals(3,result.size());assertTrue(result.get(0).getString("url").endsWith("primary"));assertTrue(result.get(1).getString("url").endsWith("backup"));assertTrue(result.get(2).getString("url").endsWith("hd"));
        assertEquals(1,LiveChannels.candidates(stream("NTV.ru@Plus2","delayed"),list,backup,"").size());
    }
    @Test public void rememberedSourceMustStillBelongToTheChannelAndAttemptsAreBounded()throws Exception{
        JSONObject selected=stream("TV@SD","first");JSONArray rows=new JSONArray();for(int i=0;i<20;i++)rows.put(stream("TV@HD","alt"+i));
        List<JSONObject> result=LiveChannels.candidates(selected,rows,new JSONArray(),"https://tv.test/alt19");
        assertEquals(6,result.size());assertEquals("https://tv.test/alt19",result.get(0).getString("url"));
        assertEquals("https://tv.test/first",LiveChannels.candidates(selected,rows,new JSONArray(),"https://unrelated.test/evil").get(0).getString("url"));
    }
    @Test public void apiBackupsOnlyIncludeKnownChannelsAndNullHeadersUseDefaults()throws Exception{
        String json="[{\"channel\":\"A\",\"feed\":\"HD\",\"url\":\"https://tv.test/a\",\"user_agent\":null,\"referrer\":null},{\"channel\":\"Other\",\"feed\":\"SD\",\"url\":\"https://tv.test/b\"},{\"channel\":\"A\",\"feed\":null,\"url\":\"https://tv.test/c\"}]";
        JSONArray result=LiveChannels.backupsFromApi(json,new JSONArray().put(stream("A@SD","first")));
        assertEquals(1,result.length());assertEquals("A@HD",result.getJSONObject(0).getString("id"));assertTrue(LiveChannels.headers(result.getJSONObject(0)).isEmpty());
    }
    @Test public void manyDeadStandardDefinitionUrlsCannotExcludeTheHdAlternative()throws Exception{
        JSONObject selected=stream("TV@SD","first");JSONArray rows=new JSONArray();
        for(int i=0;i<20;i++)rows.put(stream("TV@SD","old"+i));rows.put(stream("TV@HD","working"));
        List<JSONObject> choices=LiveChannels.candidates(selected,rows,new JSONArray(),"");
        assertEquals(6,choices.size());assertEquals("https://tv.test/working",choices.get(2).getString("url"));
    }
}
