package ru.smotrim.tv;

import org.json.JSONObject;
import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class CatalogHttpTest {
    private static final String LIVE="https://cinemeta-live.strem.io/meta/series/tt31531192.json";
    private static class Response extends HttpURLConnection {
        final int status;final String location,body;boolean disconnected;
        Response(URL url,int status,String location,String body){super(url);this.status=status;this.location=location;this.body=body;}
        public int getResponseCode(){return status;}
        public String getHeaderField(String name){return "Location".equals(name)?location:null;}
        public InputStream getInputStream(){return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));}
        public void disconnect(){disconnected=true;}
        public boolean usingProxy(){return false;}
        public void connect(){}
    }
    @Test public void followsPublicCinemetaRedirectAndClosesBothConnections()throws Exception{
        List<Response> opened=new ArrayList<>();
        JSONObject value=MediaCatalog.request("cinemeta","/meta/series/tt31531192.json","unused",url->{
            Response r=new Response(url,opened.isEmpty()?307:200,LIVE,"{\"meta\":{\"id\":\"tt31531192\"}}");opened.add(r);return r;
        });
        assertEquals("tt31531192",value.getJSONObject("meta").getString("id"));assertEquals(2,opened.size());assertEquals(LIVE,opened.get(1).getURL().toString());
        for(Response r:opened){assertTrue(r.disconnected);assertFalse(r.getInstanceFollowRedirects());assertNull(r.getRequestProperty("Authorization"));assertNull(r.getRequestProperty("X-API-KEY"));}
    }
    @Test public void blocksUntrustedDestinationsAndRedirectsForCredentialedProviders()throws Exception{
        URL current=new URL(LIVE);
        for(String destination:Arrays.asList("https://example.com/meta","https://cinemeta-live.strem.io.example.com/meta","http://cinemeta-live.strem.io/meta","https://user@cinemeta-live.strem.io/meta","https://cinemeta-live.strem.io:8443/meta","")){
            try{MediaCatalog.redirect("cinemeta",current,destination);fail(destination);}catch(IOException expected){}
        }
        for(String provider:Arrays.asList("kp","tmdb")){
            List<Response> opened=new ArrayList<>();
            try{MediaCatalog.request(provider,"/test","test-credential",url->{Response r=new Response(url,302,LIVE,"");opened.add(r);return r;});fail(provider);}catch(IOException expected){}
            assertEquals(1,opened.size());assertTrue(opened.get(0).disconnected);
        }
    }
    @Test public void boundsRedirectLoops()throws Exception{
        List<Response> opened=new ArrayList<>();
        try{MediaCatalog.request("cinemeta","/test","",url->{Response r=new Response(url,308,LIVE,"");opened.add(r);return r;});fail();}catch(IOException expected){}
        assertEquals(4,opened.size());for(Response r:opened)assertTrue(r.disconnected);
    }
}
