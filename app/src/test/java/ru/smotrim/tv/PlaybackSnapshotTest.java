package ru.smotrim.tv;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackSnapshotTest {
    private JSONObject torrent()throws Exception{return new JSONObject().put("kind","torrent").put("url","magnet:?xt=urn:btih:0123456789012345678901234567890123456789");}

    @Test public void restoresSelectedEpisodePositionAndPauseWithoutLargeCatalogData()throws Exception{
        JSONObject episode=new JSONObject().put("season",2).put("episode",4);
        JSONObject movie=new JSONObject().put("id","tt123").put("historyId","kp123").put("ids",new JSONArray().put("kp123").put("tt123"))
            .put("name","Название").put("type","series").put("searchHint","Название сериала").put("selectedEpisode",episode)
            .put("videos",new JSONArray().put("x".repeat(100000))).put("apiKey","secret");
        JSONObject item=torrent().put("movie",movie).put("sourceMovie",movie).put("seriesEpisode",episode)
            .put("fileIndex",8).put("historyKey","show:2:4").put("files",new JSONArray().put("x".repeat(100000)));
        JSONObject saved=PlaybackSnapshot.capture(item,123456,false);
        assertNotNull(saved);assertEquals(123456,saved.getLong("position"));assertTrue(saved.getBoolean("paused"));
        assertEquals(8,saved.getInt("fileIndex"));assertEquals("show:2:4",saved.getString("historyKey"));
        assertEquals(4,saved.getJSONObject("seriesEpisode").getInt("episode"));
        assertEquals("kp123",saved.getJSONObject("sourceMovie").getString("historyId"));
        assertEquals(2,saved.getJSONObject("movie").getJSONArray("ids").length());
        assertEquals("Название сериала",saved.getJSONObject("movie").getString("searchHint"));
        assertFalse(saved.has("files"));assertFalse(saved.getJSONObject("movie").has("videos"));
        assertFalse(saved.toString().contains("secret"));assertTrue(saved.toString().length()<4096);
        assertNotNull(PlaybackSnapshot.restore(saved.toString()));
    }
    @Test public void preservesLiveChannelAndPlayIntent()throws Exception{
        JSONObject channel=new JSONObject().put("id","channel").put("name","Канал").put("url","https://example.org/live.m3u8");
        JSONObject saved=PlaybackSnapshot.capture(new JSONObject().put("kind","live").put("url",channel.getString("url")).put("channel",channel),-100,true);
        assertNotNull(saved);assertFalse(saved.getBoolean("paused"));assertEquals(0,saved.getLong("position"));
        assertEquals("Канал",saved.getJSONObject("channel").getString("name"));
    }
    @Test public void rejectsIncompleteAndUnboundedSnapshots()throws Exception{
        assertNull(PlaybackSnapshot.capture(null,0,true));assertNull(PlaybackSnapshot.restore(null));
        assertNull(PlaybackSnapshot.restore("{"));assertNull(PlaybackSnapshot.restore("{}"));
        assertNull(PlaybackSnapshot.restore(torrent().put("kind","other").toString()));
        assertNull(PlaybackSnapshot.restore(torrent().put("url","file:///private/video").toString()));
        assertNull(PlaybackSnapshot.restore(torrent().put("title","x".repeat(70000)).toString()));
    }
}
