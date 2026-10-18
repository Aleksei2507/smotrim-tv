package ru.smotrim.tv;

import android.os.Handler;
import android.os.Looper;
import androidx.media3.common.*;
import androidx.media3.datasource.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import org.json.JSONObject;
import java.io.IOException;
import java.util.*;

/** Bounded recovery of one live channel. All methods and callbacks run on the player thread. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class LivePlayback implements AutoCloseable,Player.Listener {
    public interface Listener {
        void connecting(String message);
        void playing(String url);
        void failed(String message);
    }
    private final ExoPlayer player;
    private final List<JSONObject> streams;
    private final Listener listener;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private int index,retries,attempt;
    private boolean closed,pending;
    private Throwable lastError;
    public LivePlayback(ExoPlayer player,List<JSONObject> streams,Listener listener){
        this.player=player;this.streams=streams;this.listener=listener;player.addListener(this);
    }
    public void start(){
        start(true);
    }
    public void start(boolean playWhenReady){
        if(closed)return;
        if(streams.isEmpty()){listener.failed("В плейлисте нет подходящей ссылки на этот канал.");return;}
        player.setPlayWhenReady(playWhenReady);
        open();
    }
    private void open(){
        if(closed)return;pending=false;int token=++attempt;
        JSONObject stream=streams.get(index);Map<String,String> headers=LiveChannels.headers(stream);
        String agent=headers.remove("User-Agent");
        DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory()
                .setUserAgent(agent==null?"SmotrimTV/0.2.1 (Android TV)":agent)
                .setDefaultRequestProperties(headers).setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(8000).setReadTimeoutMs(10000);
        listener.connecting((retries>0?"Повторное подключение":"Открываем эфир")+" · источник "+(index+1)+" из "+streams.size());
        DefaultLoadErrorHandlingPolicy policy=new DefaultLoadErrorHandlingPolicy(1){
            @Override public long getRetryDelayMsFor(LoadErrorHandlingPolicy.LoadErrorInfo info){
                int status=httpStatus(info.exception);
                return status==401||status==403||status==404||status==410?C.TIME_UNSET:super.getRetryDelayMsFor(info);
            }
        };
        player.setMediaSource(new DefaultMediaSourceFactory(http).setLoadErrorHandlingPolicy(policy).createMediaSource(MediaItem.fromUri(stream.optString("url"))));
        player.prepare();
        handler.postDelayed(()->{if(!closed&&!pending&&token==attempt&&player.getPlaybackState()!=Player.STATE_READY)recover(new IOException("Сервер не начал передачу за 15 секунд"),false);},15000);
    }
    @Override public void onPlayerError(PlaybackException error){
        int status=httpStatus(error);
        boolean retry=status==408||status==429||status>=500||status==0&&(error.errorCode==PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED||error.errorCode==PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT);
        recover(error,retry);
    }
    @Override public void onPlaybackStateChanged(int state){
        if(closed||pending)return;
        if(state==Player.STATE_READY){handler.removeCallbacksAndMessages(null);listener.playing(streams.get(index).optString("url"));}
        else if(state==Player.STATE_ENDED)recover(new IOException("Источник завершил эфир"),false);
    }
    private void recover(Throwable error,boolean retry){
        if(closed||pending)return;pending=true;lastError=error;handler.removeCallbacksAndMessages(null);
        if(retry&&retries==0){retries++;listener.connecting("Связь прервалась. Повторяем подключение…");handler.postDelayed(this::open,1500);return;}
        if(index+1<streams.size()){index++;retries=0;listener.connecting("Источник недоступен. Пробуем следующий…");handler.post(this::open);return;}
        listener.failed(errorMessage(lastError,streams.size()));
    }
    static int httpStatus(Throwable error){
        for(int i=0;error!=null&&i<12;i++,error=error.getCause())if(error instanceof HttpDataSource.InvalidResponseCodeException)return ((HttpDataSource.InvalidResponseCodeException)error).responseCode;
        return 0;
    }
    static String errorMessage(Throwable error,int count){
        int status=httpStatus(error);String reason;
        if(status==401||status==403)reason="Сервер ограничил доступ к эфиру (HTTP "+status+"). Возможно ограничение по сети или региону.";
        else if(status==404||status==410)reason="Ссылка на эфир больше недоступна (HTTP "+status+"). Попробуйте обновить телеканалы в настройках.";
        else if(status>=500)reason="Сервер телеканала временно недоступен (HTTP "+status+").";
        else if(status>0)reason="Сервер телеканала ответил HTTP "+status+".";
        else if(error instanceof PlaybackException&&((PlaybackException)error).errorCode>=4000&&((PlaybackException)error).errorCode<5000)reason="Телевизор не смог декодировать этот поток.";
        else reason="Не удалось получить эфир. Проверьте интернет или попробуйте позже.";
        return reason+" Проверено источников: "+count+". Можно повторить подключение или выбрать другой канал.";
    }
    @Override public void close(){closed=true;attempt++;handler.removeCallbacksAndMessages(null);player.removeListener(this);}
}
