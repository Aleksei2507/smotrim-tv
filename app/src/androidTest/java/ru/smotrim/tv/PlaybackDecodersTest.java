package ru.smotrim.tv;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.Handler;
import androidx.media3.common.*;
import androidx.media3.datasource.AssetDataSource;
import androidx.media3.exoplayer.*;
import androidx.media3.exoplayer.mediacodec.*;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;

@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public class PlaybackDecodersTest {
    private static final String MPEG4=MimeTypes.VIDEO_MP4V;
    private static MediaCodecInfo codec(String name,boolean software){
        return MediaCodecInfo.newInstance(name,MPEG4,MPEG4,null,!software,software,!software,false,false);
    }
    private static final MediaCodecInfo HARDWARE=codec("OMX.realtek.video.decoder",false);
    private static Format format(String mime,int width,int height){return new Format.Builder().setSampleMimeType(mime).setWidth(width).setHeight(height).build();}
    private static ExoPlaybackException failure(Format format,MediaCodecInfo codec){
        return ExoPlaybackException.createForRenderer(new MediaCodecDecoderException(new IllegalStateException("test"),codec),
                "video",0,format,C.FORMAT_HANDLED,false,PlaybackException.ERROR_CODE_DECODING_FAILED);
    }
    private static ExoPlaybackException sdFailure(){return failure(format(MPEG4,688,384),HARDWARE);}

    /** Real renderer failure -> IDLE -> software prepare, keeping the same source and position. */
    @Test public void runtimeFailureResumesSameSourceWithSoftwareDecoder()throws Exception{
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context testContext=InstrumentationRegistry.getInstrumentation().getContext();
        CountDownLatch ended=new CountDownLatch(1);
        AtomicReference<Throwable> unexpected=new AtomicReference<>();
        AtomicReference<ExoPlayer> player=new AtomicReference<>();
        AtomicReference<String> decoder=new AtomicReference<>();
        AtomicInteger retries=new AtomicInteger();
        AtomicLong retryPosition=new AtomicLong(-1);
        AtomicLong recoveredPosition=new AtomicLong(-1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            PlaybackDecoders decoders=new PlaybackDecoders();
            RenderersFactory factory=(handler,videoListener,audioListener,text,metadata)->new Renderer[]{
                new MediaCodecVideoRenderer(context,decoders,0,handler,videoListener,0){
                    boolean injected;int renderCalls;
                    @Override public void render(long positionUs,long elapsedRealtimeUs)throws ExoPlaybackException{
                        if(!injected&&getCodecInfo()!=null&&++renderCalls>=20){
                            injected=true;throw sdFailure();
                        }
                        super.render(positionUs,elapsedRealtimeUs);
                        if(getCodecInfo()!=null)decoder.set(getCodecInfo().name);
                    }
                }
            };
            ExoPlayer p=new ExoPlayer.Builder(context,factory).build();player.set(p);
            p.addListener(new Player.Listener(){
                @Override public void onPlayerError(PlaybackException error){
                    if(!decoders.tryRecover(error)){unexpected.set(error);ended.countDown();return;}
                    retries.incrementAndGet();long position=p.getCurrentPosition();retryPosition.set(position);
                    new Handler(p.getApplicationLooper()).post(()->{p.seekTo(position);p.prepare();});
                }
                @Override public void onPlaybackStateChanged(int state){
                    if(state==Player.STATE_READY&&retries.get()>0)recoveredPosition.compareAndSet(-1,p.getCurrentPosition());
                    if(state==Player.STATE_ENDED)ended.countDown();
                }
            });
            p.setMediaSource(new ProgressiveMediaSource.Factory(()->new AssetDataSource(testContext))
                    .createMediaSource(MediaItem.fromUri("asset:///mpeg4-asp.avi")));
            p.seekTo(1000);p.prepare();p.play();
        });
        boolean finished=ended.await(20,TimeUnit.SECONDS);int[] output={0};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            ExoPlayer p=player.get();if(p==null)return;
            if(p.getVideoDecoderCounters()!=null){p.getVideoDecoderCounters().ensureUpdated();
                output[0]=p.getVideoDecoderCounters().renderedOutputBufferCount+p.getVideoDecoderCounters().skippedOutputBufferCount;}
            p.release();
        });
        assertTrue("Playback timeout",finished);
        if(unexpected.get()!=null)throw new AssertionError("Unexpected playback error",unexpected.get());
        assertEquals(1,retries.get());assertTrue("Lost playback position",retryPosition.get()>=1000);
        assertTrue("Playback restarted from beginning",recoveredPosition.get()>=retryPosition.get()-100);
        assertTrue("Software decoder not selected: "+decoder.get(),decoder.get()!=null&&(decoder.get().startsWith("c2.android.")||decoder.get().startsWith("OMX.google.")));
        assertTrue("No decoded frames after recovery",output[0]>0);
    }
}
