package ru.smotrim.tv;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Exercises the packaged assets and Android JSON implementation, without starting playback. */
@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4.class)
public class GithubCatalogAndroidTest {
    @Test(timeout=30000) public void activityInitializesCatalogAndOpensRussianSeriesWithReadyVariants()throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            AtomicReference<GithubCatalog> catalog=new AtomicReference<>();
            scenario.onActivity(a->{try{Field f=MainActivity.class.getDeclaredField("githubCatalog");f.setAccessible(true);catalog.set((GithubCatalog)f.get(a));}catch(Exception e){throw new AssertionError(e);}});
            GithubCatalog c=catalog.get();assertNotNull("Packaged catalog initialized",c);assertTrue(c.state().getInt("count")>=34);
            assertTrue(c.items("","movie").length()>=16);assertTrue(c.items("","series").length()>=18);
            JSONObject selected=new JSONObject().put("id","tt10466872").put("type","series");JSONObject card=c.detail(selected);
            assertEquals("Дюна: Пророчество",card.getString("name"));assertTrue(card.getJSONArray("videos").length()>=6);
            JSONArray sources=c.sources(selected,1,3).getJSONArray("items");assertTrue(sources.length()>0);
            for(int i=0;i<sources.length();i++){JSONObject source=sources.getJSONObject(i);assertEquals("ru",source.getString("audioLanguage"));assertEquals(1,source.getInt("season"));assertEquals(-1,source.getInt("seeds"));}
            JSONObject show=c.detail(new JSONObject().put("id","tt31531192").put("type","series"));assertEquals(29,show.getJSONArray("videos").length());
            assertEquals(1,show.getJSONArray("videos").getJSONObject(0).getInt("season"));
        }
    }
}
