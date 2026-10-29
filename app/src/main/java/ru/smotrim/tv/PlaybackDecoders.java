package ru.smotrim.tv;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import java.util.ArrayList;
import java.util.List;

/** One software retry for the Realtek SD MPEG-4 runtime decoder failure. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class PlaybackDecoders implements MediaCodecSelector {
    private final MediaCodecSelector delegate;
    private volatile List<MediaCodecInfo> recoveryDecoders;

    PlaybackDecoders(){this(MediaCodecSelector.DEFAULT);}
    PlaybackDecoders(MediaCodecSelector delegate){this.delegate=delegate;}

    @Override public List<MediaCodecInfo> getDecoderInfos(String mime,boolean secure,boolean tunneling)
            throws MediaCodecUtil.DecoderQueryException {
        List<MediaCodecInfo> recovery=recoveryDecoders;
        if(recovery!=null&&MimeTypes.VIDEO_MP4V.equals(mime)&&!secure&&!tunneling)return recovery;
        return delegate.getDecoderInfos(mime,secure,tunneling);
    }

    boolean tryRecover(PlaybackException error){
        if(recoveryDecoders!=null||error.errorCode!=PlaybackException.ERROR_CODE_DECODING_FAILED
                ||!(error instanceof ExoPlaybackException))return false;
        ExoPlaybackException exo=(ExoPlaybackException)error;
        Format format=exo.rendererFormat;
        MediaCodecInfo failed=failedDecoder(error);
        if(exo.type!=ExoPlaybackException.TYPE_RENDERER||format==null
                ||!MimeTypes.VIDEO_MP4V.equals(format.sampleMimeType)
                ||format.width<=0||format.height<=0||format.width>1024||format.height>576
                ||format.drmInitData!=null||failed==null||failed.secure||failed.tunneling
                ||!failed.name.startsWith("OMX.realtek.")||failed.softwareOnly)return false;
        try{
            List<MediaCodecInfo> software=new ArrayList<>();
            for(MediaCodecInfo codec:delegate.getDecoderInfos(MimeTypes.VIDEO_MP4V,false,false)){
                if(codec.softwareOnly&&!codec.secure&&!codec.tunneling)software.add(codec);
            }
            if(software.isEmpty())return false;
            // TCL's c2.android.mpeg4.decoder advertises only 352x288 Simple Profile,
            // but decodes SD ASP/B-frame files. Do not discard it based on those caps.
            recoveryDecoders=java.util.Collections.unmodifiableList(software);
            return true;
        }catch(MediaCodecUtil.DecoderQueryException unavailable){return false;}
    }

    static String details(PlaybackException error){
        StringBuilder result=new StringBuilder(error.getErrorCodeName());
        if(error instanceof ExoPlaybackException){
            Format format=((ExoPlaybackException)error).rendererFormat;
            if(format!=null){
                if(format.sampleMimeType!=null)result.append(" · ").append(format.sampleMimeType);
                if(format.width>0&&format.height>0)result.append(" · ").append(format.width).append('×').append(format.height);
            }
        }
        MediaCodecInfo codec=failedDecoder(error);
        if(codec!=null)result.append(" · ").append(codec.name);
        // TCL keeps only seconds of logcat, so show the root cause of non-decoder failures on screen.
        Throwable cause=error.getCause();
        while(cause!=null&&cause.getCause()!=null)cause=cause.getCause();
        if(codec==null&&cause!=null&&cause.getMessage()!=null){
            String reason=cause.getMessage();
            result.append(" · ").append(reason.length()>160?reason.substring(0,160)+"…":reason);
        }
        return result.toString();
    }

    private static MediaCodecInfo failedDecoder(Throwable error){
        for(int depth=0;error!=null&&depth<16;depth++,error=error.getCause()){
            if(error instanceof MediaCodecDecoderException)return ((MediaCodecDecoderException)error).codecInfo;
        }
        return null;
    }
}
