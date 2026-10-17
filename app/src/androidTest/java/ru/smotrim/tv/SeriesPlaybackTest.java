package ru.smotrim.tv;

import org.junit.Test;
import static org.junit.Assert.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import org.json.*;
import java.io.*;
import java.util.concurrent.*;

@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4.class)
public class SeriesPlaybackTest {
    @Test(timeout=90000) public void episodeWaitsForVerifiedFileAndResumesAtRequestedPosition()throws Exception{
        byte[] video;
        try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("playback.mp4");ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);video=out.toByteArray();
        }
        try(LocalTorrentFixture fixture=new LocalTorrentFixture(video);ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            JSONObject item=new JSONObject().put("kind","torrent").put("title","Series test").put("url",fixture.url()).put("seriesEpisode",new JSONObject().put("season",1).put("episode",2));
            scenario.onActivity(a->MainPlaybackControlsTest.call(a,"startPlayback",JSONObject.class,item));
            boolean[] waiting={false};
            for(int i=0;i<300&&!waiting[0];i++){
                scenario.onActivity(a->waiting[0]=(boolean)MainPlaybackControlsTest.field(a,"selectingFile"));
                if(!waiting[0])Thread.sleep(100);
            }
            assertTrue("Even a single-file series torrent must await the selected episode check",waiting[0]);
            scenario.onActivity(a->{
                assertNull(MainPlaybackControlsTest.field(a,"player"));
                MainPlaybackControlsTest.call(a,"commandPlayer",String.class,"file:");
                MainPlaybackControlsTest.call(a,"commandPlayer",String.class,"file:999999:20000");
                assertEquals(0L,MainPlaybackControlsTest.field(a,"resumePosition"));
                JSONArray files=(JSONArray)MainPlaybackControlsTest.field(a,"pendingFiles");
                int index=files.optJSONObject(0).optInt("index");
                MainPlaybackControlsTest.call(a,"commandPlayer",String.class,"file:"+index+":10000");
            });
            boolean[] ready={false};long[] position={0};
            for(int i=0;i<400&&!ready[0];i++){
                scenario.onActivity(a->{ExoPlayer p=(ExoPlayer)MainPlaybackControlsTest.field(a,"player");if(p!=null){p.setVolume(0);ready[0]=p.getPlaybackState()==Player.STATE_READY;position[0]=p.getCurrentPosition();}});
                if(!ready[0])Thread.sleep(100);
            }
            assertTrue("Native torrent player prepared",ready[0]);
            assertTrue("Episode resumes at 10 seconds without restarting from zero",position[0]>=10000);
            scenario.onActivity(a->MainPlaybackControlsTest.call(a,"commandPlayer",String.class,"stop"));
        }
    }
}
