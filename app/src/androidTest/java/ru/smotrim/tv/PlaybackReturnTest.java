package ru.smotrim.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.webkit.WebView;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;
import static ru.smotrim.tv.MainPlaybackControlsTest.field;
import static ru.smotrim.tv.MainPlaybackControlsTest.call;

/** Actual WebView bridge, Android Home/launcher, TorrServer and Media3 with a local seeder. */
@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public class PlaybackReturnTest {
    private final Instrumentation ins=InstrumentationRegistry.getInstrumentation();
    private boolean onMain(BooleanSupplier check){boolean[] value={false};ins.runOnMainSync(()->value[0]=check.getAsBoolean());return value[0];}
    private void await(String message,BooleanSupplier check,long timeout)throws Exception{
        long end=System.currentTimeMillis()+timeout;
        while(System.currentTimeMillis()<end){if(check.getAsBoolean())return;Thread.sleep(100);}
        fail(message);
    }
    private String js(MainActivity a,String script)throws Exception{
        AtomicReference<String> result=new AtomicReference<>();CountDownLatch done=new CountDownLatch(1);
        ins.runOnMainSync(()->((WebView)field(a,"web")).evaluateJavascript(script,value->{result.set(value);done.countDown();}));
        assertTrue("JavaScript responds",done.await(5,TimeUnit.SECONDS));return result.get();
    }
    private void waitForUi(MainActivity a)throws Exception{
        await("Catalog bootstrap ready",()->{try{return "true".equals(js(a,"typeof window.nativeEvent==='function' && !!document.querySelector('.home-copy')"));}catch(Exception e){throw new AssertionError(e);}},20000);
    }
    private void home()throws Exception{
        try(ParcelFileDescriptor result=ins.getUiAutomation().executeShellCommand("input keyevent KEYCODE_HOME");InputStream in=new FileInputStream(result.getFileDescriptor())){byte[] bytes=new byte[256];while(in.read(bytes)!=-1){}}
    }
    private void launchAgain(){
        Intent launch=ins.getTargetContext().getPackageManager().getLaunchIntentForPackage(ins.getTargetContext().getPackageName());
        assertNotNull(launch);launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        ins.getTargetContext().startActivity(launch);
    }
    @Test(timeout=30000) public void preparationCompletingInBackgroundWaitsForReturn()throws Exception{
        File clip=new File(ins.getTargetContext().getCacheDir(),"background-return.mp4");
        try(InputStream in=ins.getContext().getAssets().open("playback.mp4");OutputStream out=new FileOutputStream(clip)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            AtomicReference<MainActivity> ref=new AtomicReference<>();scenario.onActivity(ref::set);MainActivity a=ref.get();
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
            ins.runOnMainSync(()->{
                try{
                    java.lang.reflect.Field now=MainActivity.class.getDeclaredField("now");now.setAccessible(true);now.set(a,new JSONObject().put("kind","torrent"));
                    androidx.media3.exoplayer.source.MediaSource source=new androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(new androidx.media3.datasource.DefaultDataSource.Factory(a))
                        .createMediaSource(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(clip)));
                    call(a,"createPlayer",androidx.media3.exoplayer.source.MediaSource.class,source);
                }catch(Exception e){throw new AssertionError(e);}
            });
            await("Prepared in background",()->onMain(()->((ExoPlayer)field(a,"player")).getPlaybackState()==Player.STATE_READY),15000);
            ins.runOnMainSync(()->{ExoPlayer p=(ExoPlayer)field(a,"player");assertFalse(p.getPlayWhenReady());assertFalse(p.isPlaying());p.play();assertFalse("Late play request cannot start background audio",p.getPlayWhenReady());});
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
            await("Playback starts on return",()->onMain(()->((ExoPlayer)field(a,"player")).isPlaying()),5000);
        }finally{clip.delete();}
    }
    @Test(timeout=180000) public void homeKeepsSessionAndRecreationRestoresFilePositionAndPause()throws Exception{
        ByteArrayOutputStream data=new ByteArrayOutputStream();
        try(InputStream in=ins.getContext().getAssets().open("playback.mp4")){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)data.write(b,0,n);}
        try(LocalTorrentFixture fixture=new LocalTorrentFixture(data.toByteArray());ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            AtomicReference<MainActivity> current=new AtomicReference<>();scenario.onActivity(current::set);MainActivity a=current.get();waitForUi(a);
            js(a,"window.nativeEvent('input',"+new JSONObject().put("kind","magnet").put("value",fixture.url())+")");
            await("Torrent plays",()->onMain(()->{ExoPlayer p=(ExoPlayer)field(a,"player");return p!=null&&p.isPlaying();}),60000);
            ExoPlayer p=(ExoPlayer)field(a,"player");TorrentEngine engine=(TorrentEngine)field(a,"torrent");Object web=field(a,"web"),generation=field(a,"playbackGeneration");
            ins.runOnMainSync(()->{p.setVolume(0);p.seekTo(5000);});
            for(boolean wasPaused:new boolean[]{false,true}){
                ins.runOnMainSync(()->p.setPlayWhenReady(!wasPaused));home();
                await("Activity is in background",()->onMain(()->!(boolean)field(a,"resumed")),10000);
                long[] position={0};ins.runOnMainSync(()->{
                    assertSame(p,field(a,"player"));assertSame(engine,field(a,"torrent"));assertNotNull(field(a,"now"));
                    assertFalse(p.getPlayWhenReady());assertFalse(p.isPlaying());assertFalse(engine.isClosed());position[0]=p.getCurrentPosition();
                });
                Thread.sleep(1200);
                ins.runOnMainSync(()->assertEquals("No playback behind Home",position[0],p.getCurrentPosition()));
                launchAgain();await("Original activity resumes",()->onMain(()->(boolean)field(a,"resumed")),10000);
                scenario.onActivity(returned->{assertSame(a,returned);assertSame(web,field(returned,"web"));assertSame(p,field(returned,"player"));assertSame(engine,field(returned,"torrent"));assertEquals(generation,field(returned,"playbackGeneration"));assertEquals(!wasPaused,p.getPlayWhenReady());});
                assertEquals("Player screen retained","true",js(a,"!!document.getElementById('player')"));
            }
            long[] savedPosition={0};int[] fileIndex={0};
            ins.runOnMainSync(()->{savedPosition[0]=p.getCurrentPosition();fileIndex[0]=((JSONObject)field(a,"now")).optInt("fileIndex",-1);});
            assertTrue(savedPosition[0]>=5000);assertTrue(fileIndex[0]>=0);
            scenario.recreate();scenario.onActivity(current::set);MainActivity restored=current.get();assertNotSame(a,restored);
            await("Restored player prepared",()->onMain(()->{ExoPlayer next=(ExoPlayer)field(restored,"player");return next!=null&&next.getPlaybackState()==Player.STATE_READY;}),60000);
            assertTrue("Old engine closed",engine.isClosed());
            ins.runOnMainSync(()->{
                ExoPlayer next=(ExoPlayer)field(restored,"player");assertFalse("Manual pause preserved",next.getPlayWhenReady());
                assertEquals(savedPosition[0],next.getCurrentPosition(),1000);
                assertEquals(fileIndex[0],((JSONObject)field(restored,"now")).optInt("fileIndex",-1));
            });
            assertEquals("Restored into player","true",js(restored,"!!document.getElementById('player')"));
            js(restored,"Native.command('stop')");
            await("Explicit exit closes playback",()->onMain(()->field(restored,"now")==null&&field(restored,"player")==null&&field(restored,"torrent")==null),10000);
        }
    }
}
