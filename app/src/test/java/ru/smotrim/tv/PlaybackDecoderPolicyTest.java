package ru.smotrim.tv;

import static org.junit.Assert.*;
import androidx.media3.common.*;
import androidx.media3.exoplayer.*;
import androidx.media3.exoplayer.mediacodec.*;
import java.io.IOException;
import java.util.*;
import org.junit.Test;

@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public class PlaybackDecoderPolicyTest {
    private static final String MPEG4=MimeTypes.VIDEO_MP4V;
    private static MediaCodecInfo codec(String name,boolean software){
        return MediaCodecInfo.newInstance(name,MPEG4,MPEG4,null,!software,software,!software,false,false);
    }
    private static final MediaCodecInfo HARDWARE=codec("OMX.realtek.video.decoder",false);
    private static final MediaCodecInfo SOFTWARE=codec("c2.android.mpeg4.decoder",true);
    private static Format format(String mime,int width,int height){return new Format.Builder().setSampleMimeType(mime).setWidth(width).setHeight(height).build();}
    private static ExoPlaybackException failure(Format format,MediaCodecInfo codec){
        return ExoPlaybackException.createForRenderer(new MediaCodecDecoderException(new IllegalStateException("test"),codec),
                "video",0,format,C.FORMAT_HANDLED,false,PlaybackException.ERROR_CODE_DECODING_FAILED);
    }
    private static ExoPlaybackException sdFailure(){return failure(format(MPEG4,688,384),HARDWARE);}
    private static PlaybackDecoders selector(){return new PlaybackDecoders((mime,secure,tunnel)->Arrays.asList(HARDWARE,SOFTWARE));}

    @Test public void retriesOnlyOnceAndOnlyUsesSoftware()throws Exception{
        PlaybackDecoders d=selector();assertEquals(2,d.getDecoderInfos(MPEG4,false,false).size());
        assertTrue(d.tryRecover(sdFailure()));assertEquals(Collections.singletonList(SOFTWARE),d.getDecoderInfos(MPEG4,false,false));
        assertFalse(d.tryRecover(sdFailure()));
    }
    @Test public void unrelatedFormatsAndProtectedRequestsKeepOriginalSelector()throws Exception{
        PlaybackDecoders d=selector();assertTrue(d.tryRecover(sdFailure()));
        assertEquals(2,d.getDecoderInfos(MimeTypes.VIDEO_H264,false,false).size());
        assertEquals(2,d.getDecoderInfos(MPEG4,true,false).size());
        assertEquals(2,d.getDecoderInfos(MPEG4,false,true).size());
    }
    @Test public void hdHdrAndUnknownDimensionsNeverUseThisWorkaround(){
        for(Format f:new Format[]{format(MPEG4,1920,1080),format(MPEG4,688,-1),format(MPEG4,0,384),
                format(MimeTypes.VIDEO_H264,688,384),format(MimeTypes.VIDEO_H265,3840,2160),format(MimeTypes.VIDEO_DOLBY_VISION,3840,2160)})
            assertFalse(selector().tryRecover(failure(f,HARDWARE)));
    }
    @Test public void otherDecodersAndNetworkFailuresAreNotRetried(){
        assertFalse(selector().tryRecover(failure(format(MPEG4,688,384),codec("OMX.other.video.decoder",false))));
        assertFalse(selector().tryRecover(failure(format(MPEG4,688,384),SOFTWARE)));
        assertFalse(selector().tryRecover(ExoPlaybackException.createForSource(new IOException("offline"),PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)));
    }
    @Test public void noSoftwareDecoderLeavesNormalErrorHandling(){
        assertFalse(new PlaybackDecoders((m,s,t)->Collections.singletonList(HARDWARE)).tryRecover(sdFailure()));
    }
    @Test public void encryptedVideoLeavesNormalErrorHandling(){
        MediaCodecInfo secure=MediaCodecInfo.newInstance(HARDWARE.name,MPEG4,MPEG4,null,true,false,true,false,true);
        assertFalse(selector().tryRecover(failure(format(MPEG4,688,384),secure)));
    }
    @Test public void errorDetailsContainCodecAndSizeWithoutCauseMessage(){
        String message=PlaybackDecoders.details(sdFailure());
        assertTrue(message.contains("688×384"));assertTrue(message.contains(HARDWARE.name));assertFalse(message.contains("test"));
    }
    @Test public void unexpectedErrorDetailsShowRootCause(){
        String message=PlaybackDecoders.details(ExoPlaybackException.createForUnexpected(
                new IllegalStateException("Playback stuck buffering and not loading"),PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK));
        assertEquals("ERROR_CODE_FAILED_RUNTIME_CHECK · Playback stuck buffering and not loading",message);
    }

}
