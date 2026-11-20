package ru.smotrim.tv;

import androidx.media3.common.Player;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.exoplayer.ExoPlayer;
import org.json.JSONObject;
import org.junit.Test;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import static org.junit.Assert.*;

public class LivePlaybackTest {
    private int state=Player.STATE_READY;
    private boolean playWhenReady=true;
    private Runnable timer,queuedOpen;
    private String notice;
    private final ExoPlayer player=stub(ExoPlayer.class,(p,method,args)->{
        switch(method.getName()){
            case "getPlayWhenReady": return playWhenReady;
            case "getPlaybackState": return state;
            case "addListener": case "removeListener": return null;
            default: throw new AssertionError(method);
        }
    });
    private final HandlerWrapper handler=stub(HandlerWrapper.class,(p,method,args)->{
        switch(method.getName()){
            case "postDelayed": assertEquals(15000L,args[1]);timer=(Runnable)args[0];return true;
            case "post": queuedOpen=(Runnable)args[0];return true;
            case "removeCallbacksAndMessages": timer=null;queuedOpen=null;return null;
            default: throw new AssertionError(method);
        }
    });
    private final LivePlayback playback=new LivePlayback(player,Arrays.asList(new JSONObject(),new JSONObject()),new LivePlayback.Listener(){
        public void connecting(String message){notice=message;}
        public void playing(String url){}
        public void failed(String message){fail(message);}
    },handler);

    @Test public void persistentRebufferAfterReadyMovesToBackup(){
        playback.onPlaybackStateChanged(state);
        assertNull(timer);
        state=Player.STATE_BUFFERING;
        playback.onPlaybackStateChanged(state);
        assertNotNull(timer);
        timer.run();
        assertNotNull(queuedOpen);
        assertTrue(notice.contains("следующий"));
    }

    @Test public void pauseAndRecoveryCancelTimeoutAndResumeRearmsIt(){
        state=Player.STATE_BUFFERING;
        playback.onPlaybackStateChanged(state);
        assertNotNull(timer);
        playWhenReady=false;
        playback.onPlayWhenReadyChanged(false,Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST);
        assertNull(timer);
        playWhenReady=true;
        playback.onPlayWhenReadyChanged(true,Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST);
        assertNotNull(timer);
        state=Player.STATE_READY;
        playback.onPlaybackStateChanged(state);
        assertNull(timer);
        state=Player.STATE_BUFFERING;
        playback.onPlaybackStateChanged(state);
        playback.close();
        assertNull(timer);
    }

    private static <T>T stub(Class<T> type,InvocationHandler handler){
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler));
    }
}
