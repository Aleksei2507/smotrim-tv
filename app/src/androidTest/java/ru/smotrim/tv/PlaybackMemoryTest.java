package ru.smotrim.tv;

import org.junit.Test;
import static org.junit.Assert.*;
import androidx.test.core.app.ActivityScenario;
import androidx.media3.common.*;
import androidx.media3.exoplayer.*;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.Allocation;
import android.app.AlertDialog;
import android.webkit.WebView;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public class PlaybackMemoryTest {
    @Test public void pauseCancelsPlayIntentBeforePlayerIsReady(){
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{
                MainPlaybackControlsTest.call(a,"createPlayer",androidx.media3.exoplayer.source.MediaSource.class,null);
                ExoPlayer player=(ExoPlayer)MainPlaybackControlsTest.field(a,"player");player.play();assertFalse(player.isPlaying());assertTrue(player.getPlayWhenReady());
                MainPlaybackControlsTest.call(a,"commandPlayer",String.class,"pause");assertFalse(player.getPlayWhenReady());
                MainPlaybackControlsTest.call(a,"commandPlayer",String.class,"pause");assertTrue(player.getPlayWhenReady());
            });
        }
    }
    @Test public void shortHighBitrateBufferStopsAtByteTargetAndCanStart() {
        DefaultLoadControl control=PlaybackLoadControl.create();PlayerId id=PlayerId.UNSET;
        LoadControl.Parameters p=new LoadControl.Parameters(id,Timeline.EMPTY,new MediaPeriodId(new Object()),0,200000,1f,true,false,C.TIME_UNSET);
        control.onPrepared(id);control.onTracksSelected(p,TrackGroupArray.EMPTY,new ExoTrackSelection[0]);
        assertTrue(control.shouldContinueLoading(p));assertFalse(control.shouldStartPlayback(p));
        ArrayList<Allocation> blocks=new ArrayList<>();
        while(control.getAllocator().getTotalBytesAllocated()<PlaybackLoadControl.TARGET_BYTES)blocks.add(control.getAllocator().allocate());
        assertFalse("Do not fill a time-based reserve beyond the sample byte target",control.shouldContinueLoading(p));
        assertTrue("Byte target permits playback even when high bitrate keeps duration short",control.shouldStartPlayback(p));
        for(Allocation block:blocks)control.getAllocator().release(block);
        control.onReleased(id);assertEquals(0,control.getAllocator().getTotalBytesAllocated());
    }
    @Test(timeout=45000) public void rendererCrashStopsPlayerAndRecreatesInterfaceWithoutFinishingActivity()throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            AtomicReference<WebView> previous=new AtomicReference<>();
            scenario.onActivity(a->{
                MainPlaybackControlsTest.call(a,"createPlayer",androidx.media3.exoplayer.source.MediaSource.class,null);
                WebView web=(WebView)MainPlaybackControlsTest.field(a,"web");previous.set(web);web.loadUrl("chrome://crash");
            });
            boolean[] recovered={false};
            for(int i=0;i<200&&!recovered[0];i++){
                scenario.onActivity(a->recovered[0]=MainPlaybackControlsTest.field(a,"recoveryDialog")!=null);
                if(!recovered[0])Thread.sleep(100);
            }
            assertTrue("Renderer loss handled inside the app",recovered[0]);
            scenario.onActivity(a->{
                assertFalse(a.isFinishing());assertNull(MainPlaybackControlsTest.field(a,"player"));assertNull(MainPlaybackControlsTest.field(a,"web"));
                try{assertTrue(PlaybackHealth.snapshot(a).getString("exitReason").contains("WebView"));}catch(Exception e){throw new AssertionError(e);}
                ((AlertDialog)MainPlaybackControlsTest.field(a,"recoveryDialog")).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            boolean[] loaded={false};
            for(int i=0;i<150&&!loaded[0];i++){
                scenario.onActivity(a->{WebView web=(WebView)MainPlaybackControlsTest.field(a,"web");loaded[0]=web!=null&&web!=previous.get()&&web.getProgress()==100&&web.getUrl()!=null&&web.getUrl().endsWith("/assets/ui/index.html");});
                if(!loaded[0])Thread.sleep(100);
            }
            assertTrue("Fresh interface loads after the asynchronous dialog button callback",loaded[0]);
        }
    }
}
