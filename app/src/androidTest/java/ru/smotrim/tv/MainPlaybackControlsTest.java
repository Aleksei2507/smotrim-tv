package ru.smotrim.tv;

import org.junit.Test;
import static org.junit.Assert.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.*;
import androidx.media3.datasource.DefaultDataSource;
import android.net.Uri;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.*;

@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public class MainPlaybackControlsTest {
    static Object field(MainActivity activity,String name){try{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}catch(Exception e){throw new AssertionError(e);}}
    static void call(MainActivity activity,String name,Class<?> type,Object value){try{Method m=MainActivity.class.getDeclaredMethod(name,type);m.setAccessible(true);m.invoke(activity,value);}catch(Exception e){throw new AssertionError(e);}}
    @Test(timeout=30000) public void timerStopsActualPlayerAndSeekIsBounded()throws Exception{
        android.app.Instrumentation ins=InstrumentationRegistry.getInstrumentation();File clip=new File(ins.getTargetContext().getCacheDir(),"controls-test.mp4");
        try(InputStream in=ins.getContext().getAssets().open("playback.mp4");OutputStream out=new FileOutputStream(clip)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            CountDownLatch ready=new CountDownLatch(1);
            scenario.onActivity(a->{try{
                Field now=MainActivity.class.getDeclaredField("now");now.setAccessible(true);now.set(a,new JSONObject().put("kind","torrent"));
                MediaSource source=new ProgressiveMediaSource.Factory(new DefaultDataSource.Factory(a)).createMediaSource(MediaItem.fromUri(Uri.fromFile(clip)));
                call(a,"createPlayer",MediaSource.class,source);ExoPlayer p=(ExoPlayer)field(a,"player");p.setVolume(0);
                p.addListener(new Player.Listener(){public void onPlaybackStateChanged(int state){if(state==Player.STATE_READY)ready.countDown();}});
            }catch(Exception e){throw new AssertionError(e);}});
            assertTrue("Local clip prepared",ready.await(15,TimeUnit.SECONDS));
            scenario.onActivity(a->{ExoPlayer p=(ExoPlayer)field(a,"player");call(a,"commandPlayer",String.class,"seek:999999999");assertTrue(p.getCurrentPosition()<p.getDuration());call(a,"commandPlayer",String.class,"sleep:30");assertTrue(((SleepTimer)field(a,"sleepTimer")).remaining(SystemClock.elapsedRealtime())>0);call(a,"commandPlayer",String.class,"sleep:0");assertEquals(0,((SleepTimer)field(a,"sleepTimer")).remaining(SystemClock.elapsedRealtime()));
                ((SleepTimer)field(a,"sleepTimer")).set(30,SystemClock.elapsedRealtime()-1800001);((Runnable)field(a,"ticker")).run();assertNull(field(a,"player"));assertNull(field(a,"now"));});
        }finally{clip.delete();}
    }
}
