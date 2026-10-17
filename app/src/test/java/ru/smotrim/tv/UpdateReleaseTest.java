package ru.smotrim.tv;

import org.json.*;
import org.junit.Test;
import java.io.IOException;
import java.net.URI;
import static org.junit.Assert.*;

public class UpdateReleaseTest {
    private JSONObject release() throws Exception {
        return new JSONObject().put("tag_name","v0.2.3").put("body","Changes")
            .put("assets",new JSONArray().put(new JSONObject().put("name","Smotrim-TV-0.2.3.apk")
                .put("state","uploaded").put("size",70000000)
                .put("browser_download_url","https://github.com/"+UpdateRelease.REPOSITORY+"/releases/download/v0.2.3/Smotrim-TV-0.2.3.apk")
                .put("digest","sha256:"+"a".repeat(64))));
    }
    private JSONObject asset(JSONObject release)throws Exception{return release.getJSONArray("assets").getJSONObject(0);}
    @Test public void comparesNumericallyAndAcceptsTagPrefix() throws Exception {
        assertTrue(UpdateRelease.compareVersions("v0.2.10","0.2.9")>0);
        assertTrue(UpdateRelease.compareVersions("1.0.0","0.99.99")>0);
        assertEquals(0,UpdateRelease.compareVersions("v1.0","1.0.0"));
        assertTrue(UpdateRelease.compareVersions("0.2.2","0.2.3")<0);
    }
    @Test public void rejectsUnstableOrInvalidVersionStrings() {
        for(String value:new String[]{"latest","0.2.3-beta","1", "../1.0", "1.9999999", "0.2.3<script>"})
            assertThrows(IOException.class,()->UpdateRelease.compareVersions(value,"0.2.2"));
    }
    @Test public void onlyOffersNewStableReleases() throws Exception {
        JSONObject json=release();assertNotNull(UpdateRelease.parse(json,"0.2.2"));
        assertNull(UpdateRelease.parse(json,"0.2.3"));assertNull(UpdateRelease.parse(json,"0.3.0"));
        json.put("prerelease",true);assertNull(UpdateRelease.parse(json,"0.2.2"));
        json.put("prerelease",false).put("draft",true);assertNull(UpdateRelease.parse(json,"0.2.2"));
    }
    @Test public void requiresOneUploadedApk() throws Exception {
        JSONObject json=release();JSONArray assets=json.getJSONArray("assets");
        assets.put(new JSONObject().put("name","source.zip").put("state","uploaded"));
        assertNotNull(UpdateRelease.parse(json,"0.2.2"));
        assets.put(new JSONObject(asset(json).toString()).put("name","other.apk"));
        assertThrows(IOException.class,()->UpdateRelease.parse(json,"0.2.2"));
        JSONObject empty=release();asset(empty).put("state","new");
        assertThrows(IOException.class,()->UpdateRelease.parse(empty,"0.2.2"));
    }
    @Test public void requiresKnownRepoAndHttps() throws Exception {
        for(String url:new String[]{"http://github.com/"+UpdateRelease.REPOSITORY+"/releases/download/v0.2.3/x.apk",
            "https://github.com.evil.test/"+UpdateRelease.REPOSITORY+"/releases/download/v0.2.3/x.apk",
            "https://github.com/other/repo/releases/download/v0.2.3/x.apk",
            "https://user@github.com/"+UpdateRelease.REPOSITORY+"/releases/download/v0.2.3/x.apk",
            "https://github.com/"+UpdateRelease.REPOSITORY+"/releases/download/../x.apk"}) {
            JSONObject json=release();asset(json).put("browser_download_url",url);
            assertThrows(IOException.class,()->UpdateRelease.parse(json,"0.2.2"));
        }
    }
    @Test public void boundsSizeAndValidatesDigest() throws Exception {
        for(long length:new long[]{0,-1,UpdateRelease.MAX_APK_BYTES+1}) {
            JSONObject json=release();asset(json).put("size",length);
            assertThrows(IOException.class,()->UpdateRelease.parse(json,"0.2.2"));
        }
        JSONObject json=release();asset(json).put("digest","sha256:broken");
        assertThrows(IOException.class,()->UpdateRelease.parse(json,"0.2.2"));
        asset(json).put("digest",JSONObject.NULL);assertEquals("",UpdateRelease.parse(json,"0.2.2").sha256);
    }
    @Test public void allowsOnlyGithubDownloadRedirects() throws Exception {
        assertTrue(UpdateRelease.allowedHost(new URI("https://release-assets.githubusercontent.com/a?token=example")));
        for(String value:new String[]{"http://github.com/a","https://github.com.evil.test/a","https://127.0.0.1/a","file:///tmp/x.apk","https://github.com:444/a"})
            assertFalse(UpdateRelease.allowedHost(new URI(value)));
    }
    @Test public void extractsReleaseWithoutEvaluatingNotes() throws Exception {
        JSONObject json=release().put("body","<script>alert(1)</script>"+"x".repeat(10000));
        UpdateRelease parsed=UpdateRelease.parse(json,"0.2.2");
        assertEquals("0.2.3",parsed.version);assertEquals(70000000,parsed.bytes);
        assertEquals(8001,parsed.notes.length());assertEquals("a".repeat(64),parsed.sha256);
    }
}
