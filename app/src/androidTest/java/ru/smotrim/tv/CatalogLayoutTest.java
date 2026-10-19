package ru.smotrim.tv;

import android.app.Instrumentation;
import android.content.Context;
import android.os.SystemClock;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.webkit.WebViewAssetLoader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Loads the APK's real HTML, CSS and renderer in Android WebView, without external requests. */
public class CatalogLayoutTest {
    private static final String HOST = "catalog-layout.test";
    private static final String URL = "https://" + HOST + "/assets/ui/index.html";
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private WebView web;
    private CountDownLatch pageLoaded = new CountDownLatch(1);
    private JSONArray movies, series;

    @Before public void setUp() throws Exception {
        Context context = instrumentation.getTargetContext();
        JSONObject index;
        try (InputStream in = context.getAssets().open("catalog/v1/index.json")) {
            index = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        movies = new JSONArray(); series = new JSONArray();
        JSONArray items = index.getJSONArray("items");
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            ("series".equals(item.getString("type")) ? series : movies).put(item);
        }
        byte[] movieData = new JSONObject().put("metas", movies).toString().getBytes(StandardCharsets.UTF_8);
        byte[] seriesData = new JSONObject().put("metas", series).toString().getBytes(StandardCharsets.UTF_8);
        WebViewAssetLoader assets = new WebViewAssetLoader.Builder().setDomain(HOST)
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(context)).build();
        instrumentation.runOnMainSync(() -> {
            web = new WebView(context);
            web.getSettings().setJavaScriptEnabled(true);
            web.getSettings().setDomStorageEnabled(true);
            web.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view, String url) { pageLoaded.countDown(); }
                @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    if (!HOST.equals(request.getUrl().getHost())) return json(new byte[0]);
                    String path = request.getUrl().getPath();
                    if ("/assets/movies.json".equals(path)) return json(movieData);
                    if ("/assets/series.json".equals(path)) return json(seriesData);
                    if ("/assets/preview-channels.json".equals(path)) return json("[]".getBytes(StandardCharsets.UTF_8));
                    WebResourceResponse response = assets.shouldInterceptRequest(request.getUrl());
                    return response == null ? json(new byte[0]) : response;
                }
            });
            web.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));
            web.layout(0, 0, 1920, 1080);
            web.loadUrl(URL);
        });
        ready();
    }

    private static WebResourceResponse json(byte[] bytes) {
        return new WebResourceResponse("application/json", "UTF-8", new ByteArrayInputStream(bytes));
    }

    @After public void tearDown() {
        if (web != null) instrumentation.runOnMainSync(() -> { web.stopLoading(); web.destroy(); });
    }

    private String evaluate(String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> web.evaluateJavascript(script, value -> { result.set(value); done.countDown(); }));
        assertTrue("WebView JavaScript completed", done.await(5, TimeUnit.SECONDS));
        return result.get();
    }

    private void ready() throws Exception {
        assertTrue("Local page loaded", pageLoaded.await(10, TimeUnit.SECONDS));
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if ("true".equals(evaluate("!!document.querySelector('[data-action=\"hero-about\"], [data-action=\"hero-detail\"], .home-copy .synopsis')"))) return;
            SystemClock.sleep(50);
        }
        fail("Catalog preview loaded: " + evaluate("document.body.innerText"));
    }

    @Test public void textStaysInsideCardsAtEveryTextSize() throws Exception {
        JSONObject favorites = new JSONObject();
        for (int i = 0; i < movies.length(); i++) favorites.put(movies.getJSONObject(i).getString("id"), movies.getJSONObject(i));
        for (String size : new String[]{"normal", "large", "largest"}) {
            evaluate("localStorage.setItem('favorites'," + JSONObject.quote(favorites.toString()) + ");"
                + "localStorage.setItem('readability',JSON.stringify({size:'" + size + "',contrast:true}));true");
            pageLoaded = new CountDownLatch(1);
            instrumentation.runOnMainSync(() -> web.reload());
            ready();
            for (String screen : new String[]{"catalog", "series", "saved"}) {
                evaluate("document.querySelector('[data-action=\"nav:" + screen + "\"]').click();true");
                JSONObject report = new JSONObject(evaluate("(() => {"
                    + "const cards=[...document.querySelectorAll('.movie-card')], errors=[];"
                    + "const footer=document.querySelector('.hints').getBoundingClientRect();"
                    + "const scale=document.querySelector('#tv').getBoundingClientRect().width/1920;"
                    + "for(const card of cards){const r=card.getBoundingClientRect();"
                    + "if(r.width<=0||r.height<=0)errors.push('empty card');"
                    + "for(const selector of ['.movie-title','.movie-meta']){"
                    + "const el=card.querySelector(selector),t=el.getBoundingClientRect(),css=getComputedStyle(el);"
                    + "if(t.left<r.left-1||t.right>r.right+1)errors.push(selector+' spills horizontally '+Math.round(t.width)+' > '+Math.round(r.width));"
                    + "if(t.bottom>r.bottom+1)errors.push(selector+' spills below card');"
                    + "if(css.overflowX!=='hidden')errors.push(selector+' is not clipped');"
                    + "if(selector==='.movie-meta'&&(css.textOverflow!=='ellipsis'||css.whiteSpace!=='nowrap'))errors.push('metadata does not truncate');"
                    + "}if(r.bottom+13*scale>=footer.top)errors.push('focus ring overlaps hints');}"
                    + "return {count:cards.length,errors};})()"));
                assertEquals(size + " " + screen + " has two full rows", 10, report.getInt("count"));
                assertEquals(size + " " + screen + ": " + report, 0, report.getJSONArray("errors").length());
            }
        }
    }
}
