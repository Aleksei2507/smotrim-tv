package ru.smotrim.tv;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.media3.common.*;

@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class PlaybackTracks {
    static TrackSelectionParameters apply(Context context,TrackSelectionParameters base){
        SharedPreferences p=context.getSharedPreferences("playback",Context.MODE_PRIVATE);
        String language=p.getString("audioLanguage","ru");
        return base.buildUpon().setPreferredAudioLanguage(language.equals("auto")?null:language)
            .setPreferredTextLanguage(p.getString("textLanguage","ru")).setSelectUndeterminedTextLanguage(true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT,!p.getBoolean("subtitles",false)).build();
    }
    static void remember(Context context,int type,String language,boolean enabled){
        SharedPreferences.Editor p=context.getSharedPreferences("playback",Context.MODE_PRIVATE).edit();
        if(type==C.TRACK_TYPE_TEXT){p.putBoolean("subtitles",enabled);if(enabled&&language!=null&&!language.isEmpty())p.putString("textLanguage",language);}
        else if(language!=null&&!language.isEmpty())p.putString("audioLanguage",language);
        p.apply();
    }
}
