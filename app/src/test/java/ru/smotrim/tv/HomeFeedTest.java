package ru.smotrim.tv;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class HomeFeedTest {
    @Test public void validatesAndDropsUnrelatedFields() throws Exception {
        JSONObject input=new JSONObject("{\"history\":[{\"key\":\"a\",\"title\":\"Movie\",\"image\":\"file:///private/token\",\"position\":900,\"duration\":500,\"url\":\"magnet:secret\"},{\"key\":\"a\",\"title\":\"duplicate\"},{\"key\":\"\",\"title\":\"empty\"}],\"token\":\"secret\"}");
        JSONObject result=HomeFeed.sanitize(input);JSONArray rows=result.getJSONArray("history");assertEquals(1,rows.length());assertEquals(500,rows.getJSONObject(0).getLong("position"));assertEquals("",rows.getJSONObject(0).getString("image"));assertFalse(result.toString().contains("secret"));
    }
    @Test public void onlyLocalArtworkAndHttpsImages() {
        assertEquals("",HomeFeed.safeImage("http://example.org/image.jpg"));assertEquals("",HomeFeed.safeImage("https://name:password@example.org/a"));assertEquals("",HomeFeed.safeImage("artwork/../token.jpg"));
        assertEquals("artwork/tt123.jpg",HomeFeed.safeImage("artwork/tt123.jpg"));assertEquals(64,HomeFeed.hash("image").length());
    }
    @Test public void longPrivateHistoryKeysRemainResolvable() throws Exception {
        String key="link:magnet:?xt="+"x".repeat(1400);
        JSONObject snapshot=HomeFeed.sanitize(new JSONObject().put("history",new JSONArray().put(new JSONObject().put("key",key).put("title","Своя ссылка"))));
        assertEquals(key,snapshot.getJSONArray("history").getJSONObject(0).getString("key"));
        assertEquals(64,HomeFeed.opaqueKey("history",key).length());
    }
}
