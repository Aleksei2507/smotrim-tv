package ru.smotrim.tv;

import org.junit.Test;
import static org.junit.Assert.*;
import androidx.media3.common.*;
import androidx.test.platform.app.InstrumentationRegistry;
import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;

@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public class PlaybackFeaturesTest {
    @Test public void trackPreferencesSurviveNewPlayerParameters(){
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        PlaybackTracks.remember(c,C.TRACK_TYPE_AUDIO,"en",true);
        PlaybackTracks.remember(c,C.TRACK_TYPE_TEXT,"ru",true);
        TrackSelectionParameters p=PlaybackTracks.apply(c,TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT);
        assertEquals("en",p.preferredAudioLanguages.get(0));assertEquals("ru",p.preferredTextLanguages.get(0));assertFalse(p.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT));
        PlaybackTracks.remember(c,C.TRACK_TYPE_TEXT,null,false);PlaybackTracks.remember(c,C.TRACK_TYPE_AUDIO,"auto",true);
        p=PlaybackTracks.apply(c,TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT);
        assertTrue(p.preferredAudioLanguages.isEmpty());assertTrue(p.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT));
        PlaybackTracks.remember(c,C.TRACK_TYPE_AUDIO,"ru",true);
    }
    @Test public void xmltvParsesOnAndroidWithoutExternalEntities()throws Exception{
        String xml="<tv><channel id='one'><display-name>Канал</display-name></channel><programme channel='one' start='20261017120000 +0300' stop='20261017130000 +0300'><title>Передача</title></programme></tv>";
        assertEquals(1,ProgrammeGuide.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),ProgrammeGuide.timestamp("20261017123000 +0300")).length());
    }
    @Test(expected=org.xml.sax.SAXException.class) public void androidRejectsDtd()throws Exception{ProgrammeGuide.parse(new ByteArrayInputStream("<!DOCTYPE tv [<!ENTITY x 'test'>]><tv/>".getBytes(StandardCharsets.UTF_8)),0);}
    @Test public void sleepDeadlineAndEpisodeModes(){SleepTimer t=new SleepTimer();t.set(60,1000);assertFalse(t.expired(3600999));assertTrue(t.expired(3601000));t.set(-1,1);assertTrue(t.afterEpisode());assertFalse(t.expired(Long.MAX_VALUE));t.clear();assertFalse(t.afterEpisode());}
}
