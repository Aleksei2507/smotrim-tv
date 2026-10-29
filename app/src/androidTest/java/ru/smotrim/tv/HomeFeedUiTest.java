package ru.smotrim.tv;

import android.app.Instrumentation;
import android.content.Context;
import android.os.*;
import android.webkit.*;
import androidx.webkit.WebViewAssetLoader;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Runs the production UI with local playback/catalog adapters; no network or torrent is needed. */
@RunWith(AndroidJUnit4.class)
public class HomeFeedUiTest {
    Instrumentation instrumentation;WebView web;FakeNative bridge;android.app.Activity host;
    final String url="https://appassets.androidplatform.net/assets/ui/index.html";
    public class FakeNative {
        volatile String feed,played;volatile int ready,playCount;
        @JavascriptInterface public void request(int id,String method,String args){
            Object answer=new JSONObject();try {
                if(method.equals("bootstrap"))answer=new JSONObject("{\"movies\":[],\"series\":[],\"channels\":[{\"id\":\"friday\",\"name\":\"Пятница!\",\"group\":\"General\",\"url\":\"https://example.org/live.m3u8\"}],\"customPlaylist\":true,\"bufferMb\":96}");
                else if(method.equals("catalog"))answer=new JSONObject("{\"items\":[]}");
                else if(method.equals("epg"))answer=new JSONObject("{\"channels\":{}}");
            }catch(Exception e){throw new RuntimeException(e);}
            String script="window.nativeReply("+id+","+answer+",null)";new Handler(Looper.getMainLooper()).post(()->web.evaluateJavascript(script,null));
        }
        @JavascriptInterface public void publishHome(String value){feed=value;}
        @JavascriptInterface public void homeReady(){ready++;}
        @JavascriptInterface public void play(String value){played=value;playCount++;}
        @JavascriptInterface public void command(String value){}
    }
    @Before public void setup() throws Exception {
        instrumentation=InstrumentationRegistry.getInstrumentation();Context context=instrumentation.getTargetContext();bridge=new FakeNative();
        host=instrumentation.startActivitySync(new android.content.Intent(context,HomeFeedTestActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.runOnMainSync(()->{
            web=new WebView(host);host.setContentView(web);web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);
            web.setWebChromeClient(new WebChromeClient(){@Override public boolean onConsoleMessage(ConsoleMessage m){android.util.Log.e("HomeFeedUiTest",m.message()+" at "+m.sourceId()+":"+m.lineNumber());return true;}});
            WebViewAssetLoader assets=new WebViewAssetLoader.Builder().addPathHandler("/assets/",new WebViewAssetLoader.AssetsPathHandler(context)).build();
            web.setWebViewClient(new WebViewClient(){@Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){return assets.shouldInterceptRequest(r.getUrl());}});
            web.addJavascriptInterface(bridge,"Native");web.loadUrl(url);
        });await(()->bridge.ready>0);
        js("localStorage.setItem('history',JSON.stringify({sample:{movie:{id:'test',name:'Тестовый фильм'},url:'magnet:?xt=urn:btih:123',fileIndex:7,position:123000,duration:500000,updated:100,episode:'Сезон 2 · Серия 3',seriesEpisode:{season:2,episode:3},files:[]}}));localStorage.setItem('channels',JSON.stringify({'https://example.org/live.m3u8':true}));");
        int ready=bridge.ready;instrumentation.runOnMainSync(()->web.reload());await(()->bridge.ready>ready);
    }
    @After public void close() throws Exception {if(web!=null){js("localStorage.clear()");instrumentation.runOnMainSync(()->{web.destroy();host.finish();});}}
    String js(String source)throws Exception {CountDownLatch latch=new CountDownLatch(1);String[] result={null};instrumentation.runOnMainSync(()->web.evaluateJavascript(source,v->{result[0]=v;latch.countDown();}));assertTrue(latch.await(10,TimeUnit.SECONDS));return result[0];}
    void await(java.util.function.BooleanSupplier done)throws Exception{long end=SystemClock.uptimeMillis()+15000;while(!done.getAsBoolean()&&SystemClock.uptimeMillis()<end)SystemClock.sleep(40);if(!done.getAsBoolean())fail("UI callback did not arrive: "+js("JSON.stringify({url:location.href,text:document.body?.innerText,native:typeof Native,reply:typeof nativeReply,feed:typeof HomeFeed})"));}
    @Test public void publishesRealHistoryResumesExactEpisodeAndDoesNotRestartActiveFilm() throws Exception {
        JSONObject data=new JSONObject(bridge.feed);assertEquals("Тестовый фильм",data.getJSONArray("history").getJSONObject(0).getString("title"));assertEquals(1,data.getJSONArray("channels").length());assertFalse(bridge.feed.contains("magnet:"));
        js("window.nativeEvent('home-open',{kind:'history',key:'sample'})");await(()->bridge.played!=null);
        JSONObject playing=new JSONObject(bridge.played);assertEquals(123000,playing.getLong("position"));assertEquals(7,playing.getInt("fileIndex"));assertEquals(3,playing.getJSONObject("seriesEpisode").getInt("episode"));
        js("window.nativeEvent('home-open',{kind:'history',key:'sample'})");SystemClock.sleep(100);assertEquals(1,bridge.playCount);
        js("window.nativeEvent('home-open',{kind:'channels',key:'friday'})");await(()->bridge.playCount==2);assertEquals("live",new JSONObject(bridge.played).getString("kind"));
        assertEquals("true",js("!!document.getElementById('player')"));
        js("window.nativeEvent('home-open',{kind:'tv',key:''})");assertEquals("false",js("!!document.getElementById('player')"));
        js("window.nativeEvent('home-open',{kind:'channels',key:'deleted-channel'})");assertEquals("false",js("!!document.getElementById('player')"));
    }
    @Test public void deletedHistoryDisappearsFromFeed() throws Exception {
        js("window.nativeEvent('home-open',{kind:'history',key:'missing'})");assertEquals(0,bridge.playCount);
        js("localStorage.removeItem('history')");int ready=bridge.ready;instrumentation.runOnMainSync(()->web.reload());await(()->bridge.ready>ready);assertEquals(0,new JSONObject(bridge.feed).getJSONArray("history").length());
    }
}
