package ru.smotrim.tv;

import org.junit.Test;
import org.json.*;
import java.io.*;
import static org.junit.Assert.*;

public class SourcesTest {
    @Test public void m3uQuotedCommaBomDuplicatesAndUnsupportedUrls()throws Exception{
        String m="\uFEFF#EXTM3U\r\n#EXTINF:-1 tvg-id=\"id\" group-title=\"News, live\" tvg-logo=\"https://x/logo.png\",Мир, HD\r\nhttps://tv.test/a.m3u8\r\n#EXTINF:-1,Duplicate\nhttps://tv.test/a.m3u8\n#EXTINF:-1,Bad\nfile:///etc/passwd\n#EXTINF:-1,Other\nhttp://tv.test/b.m3u8";
        JSONArray a=Sources.parseM3u(m);assertEquals(2,a.length());assertEquals("Мир, HD",a.getJSONObject(0).getString("name"));assertEquals("News, live",a.getJSONObject(0).getString("group"));assertEquals("Other",a.getJSONObject(1).getString("name"));assertEquals("",a.getJSONObject(1).getString("logo"));
    }
    @Test public void rutorMetadataIsTextAndMagnetOnly()throws Exception{
        String hash="1234567890123456789012345678901234567890";
        String html="<div id='index'><table><tr><td><a href='magnet:?xt=urn:btih:"+hash+"&amp;dn=film'>M</a><a href='/torrent/12/test'>Фильм &amp; история (2024) 1080p</a></td><td>2.54&nbsp;GB</td><td><span class='green'>123</span></td></tr><tr><td><a href='javascript:alert(1)'>Bad</a></td></tr></table></div>";
        JSONArray a=Sources.parseRutor(html);assertEquals(1,a.length());JSONObject r=a.getJSONObject(0);assertEquals(123,r.getInt("seeds"));assertEquals("1080p",r.getString("quality"));assertTrue(r.getLong("bytes")>2L*1024*1024*1024);assertTrue(r.getString("magnet").contains(hash));assertEquals("Фильм & история (2024) 1080p",r.getString("title"));
    }
    @Test public void apiBayRejectsSentinelAndNonVideoCategories()throws Exception{
        assertEquals(0,Sources.parseApiBay("[{\"id\":\"0\",\"name\":\"No results returned\"}]").length());
        String data="[{\"id\":\"1\",\"category\":\"201\",\"info_hash\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"name\":\"Film 2160p\",\"size\":\"2147483648\",\"seeders\":\"52\"},{\"id\":\"2\",\"category\":\"301\",\"info_hash\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\"}]";
        JSONArray a=Sources.parseApiBay(data);assertEquals(1,a.length());assertEquals("4K",a.getJSONObject(0).getString("quality"));assertEquals(52,a.getJSONObject(0).getInt("seeds"));
    }
    @Test public void pathTraversalAndNonVideoFilesAreRejected()throws Exception{
        File root=new File(System.getProperty("java.io.tmpdir"),"smotrim-test");assertTrue(Sources.safeFile(root,"film/video.mkv").getPath().startsWith(root.getCanonicalPath()));
        try{Sources.safeFile(root,"../../escape");fail();}catch(IOException expected){}
        assertFalse(Sources.videoFile("movie.mp4.exe"));assertTrue(Sources.videoFile("folder/Film.MKV"));assertFalse(Sources.webUrl("javascript:alert(1)"));assertFalse(Sources.webUrl("https://user:pass@example.com/"));
    }
    @Test public void jacRedExtractsRussianReleaseMetadataAndRejectsMissingMagnet()throws Exception{
        JSONArray a=Sources.parseJacRed("[{\"title\":\"Фильм (2014) Дубляж\",\"tracker\":\"rutor\",\"quality\":2160,\"size\":4294967296,\"sid\":123,\"magnet\":\"magnet:?xt=urn:btih:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"},{\"title\":\"Без ссылки\"}]");
        assertEquals(1,a.length());JSONObject r=a.getJSONObject(0);assertEquals("4K",r.getString("quality"));assertEquals(123,r.getInt("seeds"));assertEquals(4294967296L,r.getLong("bytes"));assertEquals("JacRed · rutor",r.getString("source"));
    }
    @Test public void cancelledEngineCannotStartOrRead()throws Exception{
        File root=new File(System.getProperty("java.io.tmpdir"),"smotrim-cancel-"+System.nanoTime());TorrentEngine e=new TorrentEngine(root);e.cancel();
        try{e.prepare("magnet:?xt=urn:btih:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");fail();}catch(InterruptedIOException expected){}
        try{e.read(0,new byte[1],0,1);fail();}catch(InterruptedIOException expected){}
        e.close();assertFalse(root.exists());
    }
}
