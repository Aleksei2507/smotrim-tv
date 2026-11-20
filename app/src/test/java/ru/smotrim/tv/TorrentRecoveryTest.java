package ru.smotrim.tv;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.Field;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class TorrentRecoveryTest {
    private final TorrentEngine engine=new TorrentEngine(new File("unused"));

    @Test public void temporaryTimeoutDoesNotDiscardLaterMetadata()throws Exception{
        JSONObject ready=new JSONObject().put("file_stats",new JSONArray().put(new JSONObject()));
        AtomicInteger requests=new AtomicInteger();
        JSONObject result=engine.awaitMetadata(new JSONObject(),10000,timeout->{
            assertTrue(timeout>0&&timeout<=5000);
            if(requests.getAndIncrement()==0)throw new SocketTimeoutException();
            return ready;
        });
        assertSame(ready,result);
        assertEquals(2,requests.get());
    }

    @Test public void pollFailureAndCancellationAreNotRetried(){
        IOException failure=new IOException("HTTP 403");
        assertSame(failure,assertThrows(IOException.class,()->engine.awaitMetadata(new JSONObject(),5000,timeout->{throw failure;})));
        assertThrows(InterruptedIOException.class,()->engine.awaitMetadata(new JSONObject(),5000,timeout->{
            engine.cancel();
            throw new SocketTimeoutException();
        }));
    }

    @Test public void metadataHasAnOverallDeadline(){
        assertThrows(IOException.class,()->engine.awaitMetadata(new JSONObject(),50,timeout->{throw new AssertionError("Deadline already reached");}));
    }

    @Test public void cleanEndOfShortResponseIsNotEndOfMovie()throws Exception{
        // Simulate an established stream which returns only one byte for a two-byte movie.
        setField("selectedFile",new File("movie.mp4"));
        setField("fileSize",2L);
        setField("streamPosition",0L);
        setField("stream",new ByteArrayInputStream(new byte[]{42}));
        byte[] output=new byte[4];
        assertEquals(1,engine.read(0,output,0,4));
        assertThrows(EOFException.class,()->engine.read(1,output,0,3));
        assertEquals(-1,engine.read(2,output,0,1));
    }

    private void setField(String name,Object value)throws ReflectiveOperationException{
        Field field=TorrentEngine.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(engine,value);
    }
}
