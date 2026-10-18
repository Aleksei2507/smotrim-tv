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
public class TorrentStreamingTest {
    @Test(timeout=180000) public void verifiedPiecesCanBeReadAcrossBoundariesAndAfterSeeking()throws Exception{verifyCache(false);}
    @Test(timeout=180000) public void diskPiecesRollOverAndResumeWithoutDownloadingTheWholeMovie()throws Exception{verifyCache(true);}
    private void verifyCache(boolean disk)throws Exception{
        byte[] video=new byte[64*1024*1024+137];new Random(42).nextBytes(video);
        LocalTorrentFixture fixture=new LocalTorrentFixture(video);
        File dir=new File(System.getProperty("java.io.tmpdir"),"smotrim-stream-test-"+System.nanoTime());TorrentEngine engine=new TorrentEngine(new File(System.getProperty("smotrim.torrserver")),dir,24L*1024*1024,disk);
        try {
            JSONArray files=engine.prepare(fixture.url());assertEquals(1,files.length());engine.select(files.getJSONObject(0).getInt("index"));assertEquals(video.length,engine.size());
            for(int position:new int[]{0,65530,video.length-1000,video.length/2}){
                byte[] actual=new byte[333];int n=engine.read(position,actual,0,actual.length);assertTrue(n>0);assertArrayEquals(Arrays.copyOfRange(video,position,position+n),Arrays.copyOf(actual,n));
            }
            byte[] chunk=new byte[65536];int position=0;long peakDisk=0;
            while(position<video.length){int n=engine.read(position,chunk,0,chunk.length);assertTrue(n>0);assertArrayEquals(Arrays.copyOfRange(video,position,position+n),Arrays.copyOf(chunk,n));position+=n;if(position%(1024*1024)<n)peakDisk=Math.max(peakDisk,diskBytes(dir));}
            assertEquals(-1,engine.read(video.length,new byte[1],0,1));
            assertFalse("The movie must never be written to disk",engine.file().exists());
            long diskBytes=diskBytes(dir);
            assertTrue("Cache stays bounded on disk: "+peakDisk,peakDisk<(disk?44:2)*1024*1024);
            if(disk)assertTrue("Pieces were actually written to disk",peakDisk>1024*1024);
            else assertTrue("Only metadata on disk",diskBytes<2*1024*1024);
            assertEquals(disk?"disk":"ram",engine.status().getString("cacheStorage"));
            System.out.println((disk?"disk":"ram")+" peak disk bytes="+peakDisk+" cached="+engine.status().optLong("cached"));
            assertEquals(24L*1024*1024,engine.status().getLong("cacheLimit"));
            assertTrue("Cache remains smaller than movie",engine.status().getLong("cached")<44L*1024*1024);
            engine.close();assertFalse("Cache cleaned on exit",dir.exists());
            long sentBeforeResume=fixture.sentBytes.get();
            try(TorrentEngine resumed=new TorrentEngine(new File(System.getProperty("smotrim.torrserver")),dir,24L*1024*1024,disk)){
                JSONArray again=resumed.prepare(fixture.url());resumed.select(again.getJSONObject(0).getInt("index"));
                int savedPosition=video.length-1000;byte[] actual=new byte[333];int n=resumed.read(savedPosition,actual,0,actual.length);
                assertArrayEquals(Arrays.copyOfRange(video,savedPosition,savedPosition+n),Arrays.copyOf(actual,n));assertFalse(resumed.file().exists());
                assertTrue("Resume should not redownload the whole movie",fixture.sentBytes.get()-sentBeforeResume<44L*1024*1024);
            }
        }finally{engine.close();fixture.close();}
        assertFalse(dir.exists());
    }
    private static long diskBytes(File dir){File[] files=dir.listFiles();if(files==null)return 0;long total=0;for(File file:files)total+=file.isDirectory()?diskBytes(file):file.length();return total;}
}
