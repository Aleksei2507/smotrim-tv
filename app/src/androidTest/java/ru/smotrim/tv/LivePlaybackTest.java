package ru.smotrim.tv;

import android.app.Instrumentation;
import android.graphics.ImageFormat;
import android.media.ImageReader;
import android.os.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public class LivePlaybackTest {
    @Test(timeout=45000) public void http404FallsBackAndPlaylistHeadersReachTheVideoServer()throws Exception{
        Instrumentation ins=InstrumentationRegistry.getInstrumentation();
        try(Fixture server=new Fixture()){
            JSONArray channels=Sources.parseM3u("#EXTM3U\n#EXTINF:-1 tvg-id=\"Demo@SD\",Demo\n"+server.url("/missing.m3u8")+"\n#EXTINF:-1 tvg-id=\"Demo@HD\" http-user-agent=\"FixturePlayer/1\" http-referrer=\"https://fixture.test/watch\",Demo HD\n"+server.url("/video.mp4"));
            AtomicInteger frames=new AtomicInteger();AtomicReference<String> error=new AtomicReference<>(),working=new AtomicReference<>();
            ImageReader reader=ImageReader.newInstance(854,480,ImageFormat.YUV_420_888,3);ExoPlayer[] player=new ExoPlayer[1];LivePlayback[] live=new LivePlayback[1];
            ins.runOnMainSync(()->{
                reader.setOnImageAvailableListener(r->{android.media.Image image=r.acquireLatestImage();if(image!=null){frames.incrementAndGet();image.close();}},new Handler(Looper.getMainLooper()));
                player[0]=new ExoPlayer.Builder(ins.getTargetContext()).build();player[0].setVolume(0);player[0].setVideoSurface(reader.getSurface());
                live[0]=new LivePlayback(player[0],LiveChannels.candidates(channels.optJSONObject(0),channels,new JSONArray(),""),new LivePlayback.Listener(){
                    public void connecting(String message){}
                    public void playing(String url){working.set(url);}
                    public void failed(String message){error.set(message);}
                });live[0].start(false);
            });
            try{
                long prepared=System.currentTimeMillis()+15000;while(working.get()==null&&error.get()==null&&System.currentTimeMillis()<prepared)Thread.sleep(100);
                assertNull(error.get());assertNotNull("Fallback prepared while paused",working.get());
                ins.runOnMainSync(()->{assertFalse("Fallback must preserve pause",player[0].getPlayWhenReady());player[0].play();});
                long deadline=System.currentTimeMillis()+25000;while(frames.get()<5&&error.get()==null&&System.currentTimeMillis()<deadline)Thread.sleep(100);
                assertNull(error.get());assertTrue("Video frames after fallback: "+frames.get(),frames.get()>=5);
                assertTrue(server.missing.get()>0);assertTrue(server.good.get()>0);assertEquals(0,server.denied.get());assertEquals(server.url("/video.mp4"),working.get());
            }finally{ins.runOnMainSync(()->{live[0].close();player[0].release();reader.close();});}
        }
    }
    @Test(timeout=20000) public void forbiddenSourceExplainsHttpStatusWithoutEndlessRetries()throws Exception{
        Instrumentation ins=InstrumentationRegistry.getInstrumentation();
        try(Fixture server=new Fixture()){
            CountDownLatch failure=new CountDownLatch(1);AtomicReference<String> message=new AtomicReference<>();ExoPlayer[] player=new ExoPlayer[1];LivePlayback[] live=new LivePlayback[1];
            JSONObject stream=new JSONObject().put("url",server.url("/video.mp4"));
            ins.runOnMainSync(()->{
                player[0]=new ExoPlayer.Builder(ins.getTargetContext()).build();
                live[0]=new LivePlayback(player[0],Collections.singletonList(stream),new LivePlayback.Listener(){
                    public void connecting(String text){}
                    public void playing(String url){}
                    public void failed(String text){message.set(text);failure.countDown();}
                });live[0].start();
            });
            try{assertTrue(failure.await(10,TimeUnit.SECONDS));assertTrue(message.get(),message.get().contains("HTTP 403"));assertEquals(1,server.denied.get());}
            finally{ins.runOnMainSync(()->{live[0].close();player[0].release();});}
            int afterClose=server.denied.get();Thread.sleep(1700);assertEquals(afterClose,server.denied.get());
        }
    }
    private static final class Fixture implements AutoCloseable {
        final ServerSocket listener=new ServerSocket(0,10,InetAddress.getByName("127.0.0.1"));
        final ExecutorService workers=Executors.newCachedThreadPool();
        final Set<Socket> clients=ConcurrentHashMap.newKeySet();
        final AtomicInteger missing=new AtomicInteger(),good=new AtomicInteger(),denied=new AtomicInteger();
        final byte[] video;
        Fixture()throws Exception{
            try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("playback.mp4");ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)out.write(b,0,n);video=out.toByteArray();
            }
            workers.execute(()->{while(!listener.isClosed())try{Socket socket=listener.accept();clients.add(socket);workers.execute(()->serve(socket));}catch(IOException ignored){break;}});
        }
        String url(String path){return "http://127.0.0.1:"+listener.getLocalPort()+path;}
        void serve(Socket socket){
            try(Socket client=socket){
                client.setSoTimeout(5000);BufferedReader in=new BufferedReader(new InputStreamReader(client.getInputStream(),StandardCharsets.US_ASCII));
                String first=in.readLine();if(first==null)return;Map<String,String> headers=new HashMap<>();String line;
                while((line=in.readLine())!=null&&!line.isEmpty()){int colon=line.indexOf(':');if(colon>0)headers.put(line.substring(0,colon).toLowerCase(Locale.ROOT),line.substring(colon+1).trim());}
                OutputStream out=client.getOutputStream();int status;
                if(first.contains("/missing.m3u8")){missing.incrementAndGet();status=404;}
                else if(!"FixturePlayer/1".equals(headers.get("user-agent"))||!"https://fixture.test/watch".equals(headers.get("referer"))){denied.incrementAndGet();status=403;}
                else{good.incrementAndGet();status=200;}
                if(status!=200){out.write(("HTTP/1.1 "+status+" Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));return;}
                int start=0,end=video.length-1;String range=headers.get("range");
                if(range!=null){String[] parts=range.substring(6).split("-",-1);start=Integer.parseInt(parts[0]);if(!parts[1].isEmpty())end=Math.min(end,Integer.parseInt(parts[1]));status=206;}
                out.write(("HTTP/1.1 "+status+" OK\r\nContent-Type: video/mp4\r\nContent-Length: "+(end-start+1)+"\r\nAccept-Ranges: bytes\r\n"+(status==206?"Content-Range: bytes "+start+"-"+end+"/"+video.length+"\r\n":"")+"Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(video,start,end-start+1);
            }catch(Exception ignored){}finally{clients.remove(socket);}
        }
        public void close()throws Exception{listener.close();for(Socket socket:clients)socket.close();workers.shutdownNow();}
    }
}
