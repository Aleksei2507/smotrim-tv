package ru.smotrim.tv;

import org.junit.Test;
import org.json.JSONArray;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Real engine with a local BitTorrent seeder and tracker. */
@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class AndroidPlaybackTest {
    @Test(timeout=120000) public void verifiedPiecesCanBeReadAcrossBoundariesAndAfterSeeking()throws Exception{checkPlayback(TorrentEngine.CACHE_BYTES);}
    @Test(timeout=120000) public void largerBufferSupportsPlaybackAndSeeking()throws Exception{checkPlayback(256L*1024*1024);}
    @Test(timeout=120000) public void smallBufferSupportsPlaybackAndSeeking()throws Exception{checkPlayback(64L*1024*1024);}
    private void checkPlayback(long cacheBytes)throws Exception{
        ByteArrayOutputStream clip=new ByteArrayOutputStream();try(InputStream input=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("playback.mp4")){byte[] buffer=new byte[16384];int count;while((count=input.read(buffer))!=-1)clip.write(buffer,0,count);}byte[] video=clip.toByteArray();
        System.setProperty("smotrim.torrserver.log",new File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),"torrserver-test.log").getAbsolutePath());
        LocalTorrentFixture fixture=new LocalTorrentFixture(video);
        File dir=new File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),"stream-test-"+System.nanoTime());TorrentEngine engine=new TorrentEngine(new File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationInfo().nativeLibraryDir,"libtorrserver.so"),dir,cacheBytes);
        try {
            JSONArray files=engine.prepare(fixture.url());assertEquals(1,files.length());engine.select(files.getJSONObject(0).getInt("index"));assertEquals(video.length,engine.size());assertEquals(cacheBytes,engine.status().getLong("cacheLimit"));
            for(int position:new int[]{0,65530,video.length-1000,video.length/2}){
                byte[] actual=new byte[333];int n=engine.read(position,actual,0,actual.length);assertTrue(n>0);assertArrayEquals(Arrays.copyOfRange(video,position,position+n),Arrays.copyOf(actual,n));
            }
            assertEquals(-1,engine.read(video.length,new byte[1],0,1));assertFalse("No video file on disk",engine.file().exists());
            java.util.concurrent.atomic.AtomicInteger frames=new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicReference<String> error=new java.util.concurrent.atomic.AtomicReference<>();
            android.media.ImageReader reader=android.media.ImageReader.newInstance(854,480,android.graphics.ImageFormat.YUV_420_888,3);
            androidx.media3.exoplayer.ExoPlayer[] player=new androidx.media3.exoplayer.ExoPlayer[1];
            android.app.Instrumentation instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();
            instrumentation.runOnMainSync(()->{
                reader.setOnImageAvailableListener(r->{android.media.Image image=r.acquireLatestImage();if(image!=null){frames.incrementAndGet();image.close();}},new android.os.Handler(android.os.Looper.getMainLooper()));
                player[0]=new androidx.media3.exoplayer.ExoPlayer.Builder(instrumentation.getTargetContext()).setLoadControl(PlaybackLoadControl.create()).build();
                player[0].setVideoSurface(reader.getSurface());player[0].setVolume(0);
                player[0].addListener(new androidx.media3.common.Player.Listener(){@Override public void onPlayerError(androidx.media3.common.PlaybackException e){error.set(e.getErrorCodeName());}});
                player[0].setMediaSource(new androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(()->new TorrentDataSource(engine)).createMediaSource(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(engine.file()))));player[0].seekTo(10000);player[0].prepare();player[0].play();
            });
            try{long until=System.currentTimeMillis()+40000;while(frames.get()<5&&error.get()==null&&System.currentTimeMillis()<until)Thread.sleep(200);assertNull(error.get());assertTrue("Decoded video frames: "+frames.get()+"; engine="+engine.status(),frames.get()>=5);}
            finally{instrumentation.runOnMainSync(()->{assertTrue("Playback resumes at saved timestamp",player[0].getCurrentPosition()>=10000);player[0].release();reader.close();});}
        }finally{engine.close();fixture.close();}
        assertFalse(dir.exists());
    }
}
