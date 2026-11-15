package ru.smotrim.tv;

import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import javax.net.ssl.SSLSocket;
import okhttp3.*;
import okhttp3.tls.*;
import org.junit.Test;

public class SearchHttpTest {
    @Test public void searchDestinationIsRestricted() {
        for (String host : new String[]{"jac.red", "jac-red.ru", "rutor.info", "apibay.org"})
            assertTrue(SearchHttp.allowed(HttpUrl.parse("https://"+host+"/search")));
        for (String url : new String[]{"http://jac.red/", "https://jac.red:444/", "https://jac.red.evil.org/",
                "https://user:password@jac.red/", "https://127.0.0.1/", "https://example.com/video.mp4"})
            assertFalse(url, SearchHttp.allowed(HttpUrl.parse(url)));
    }

    @Test public void authenticatesConnectWithoutSendingProxyPasswordToIndexer() throws Exception {
        withProxy("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n[]", false);
    }

    @Test public void redirectCannotEscapeHttpsSearchAllowlist() throws Exception {
        withProxy("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1/private\r\nContent-Length: 0\r\nConnection: close\r\n\r\n", true);
    }

    @Test public void unavailableProxyDoesNotFallBackToDirect() throws Exception {
        int port;
        try(ServerSocket free = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { port=free.getLocalPort(); }
        OkHttpClient client=SearchHttp.client(port,"test-secret").newBuilder()
                .dns(host->{throw new AssertionError("Destination DNS leaked outside proxy: "+host);}).build();
        try { SearchHttp.get("https://jac.red/api/v1.0/torrents",client); fail("Expected proxy failure"); }
        catch(IOException expected) { }
        finally { client.connectionPool().evictAll(); }
    }

    @Test public void unavailableVpnWarningIsShownOnce() throws Exception {
        org.json.JSONObject result=Sources.torrents("test","","",url->{throw new Sources.SearchUnavailable("Обновите VPN");});
        assertEquals(4,result.getInt("failedProviders"));
        assertEquals("Обновите VPN",result.getJSONArray("warnings").getString(0));
        assertEquals(1,result.getJSONArray("warnings").length());
    }

    private static void withProxy(String response,boolean rejectRedirect) throws Exception {
        HeldCertificate certificate=new HeldCertificate.Builder().addSubjectAlternativeName("jac.red").build();
        HandshakeCertificates serverTls=new HandshakeCertificates.Builder().heldCertificate(certificate).build();
        HandshakeCertificates clientTls=new HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate()).build();
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try(ServerSocket proxy=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            proxy.setSoTimeout(8000);
            Future<?> request=worker.submit(()->{
                try(Socket socket=proxy.accept()) {
                    socket.setSoTimeout(8000);
                    String connect=headers(socket.getInputStream());
                    assertTrue(connect.startsWith("CONNECT jac.red:443 HTTP/1.1"));
                    assertTrue(connect.contains("Proxy-Authorization: "+Credentials.basic("search","test-secret")));
                    socket.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    try(SSLSocket tls=(SSLSocket)serverTls.sslSocketFactory().createSocket(socket,"jac.red",443,true)) {
                        tls.setUseClientMode(false);tls.setSoTimeout(8000);tls.startHandshake();
                        String origin=headers(tls.getInputStream());
                        assertTrue(origin.startsWith("GET /api/v1.0/torrents HTTP/1.1"));
                        assertFalse(origin.toLowerCase().contains("proxy-authorization"));
                        assertFalse(origin.contains("test-secret"));
                        tls.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));tls.getOutputStream().flush();
                    }
                }catch(IOException e){throw new UncheckedIOException(e);}
            });
            OkHttpClient client=SearchHttp.client(proxy.getLocalPort(),"test-secret").newBuilder()
                    .sslSocketFactory(clientTls.sslSocketFactory(),clientTls.trustManager())
                    .dns(host->{throw new AssertionError("Destination DNS must go through CONNECT");}).build();
            try {
                if(rejectRedirect) {
                    try { SearchHttp.get("https://jac.red/api/v1.0/torrents",client);fail("Unsafe redirect accepted"); }
                    catch(IOException expected){assertTrue(expected.getMessage().contains("адрес"));}
                }else assertEquals("[]",SearchHttp.get("https://jac.red/api/v1.0/torrents",client));
                request.get(10,TimeUnit.SECONDS);
            }finally{client.connectionPool().evictAll();}
        }finally{worker.shutdownNow();}
    }
    private static String headers(InputStream in) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();int c;
        while(out.size()<16384&&(c=in.read())!=-1){out.write(c);if(out.toString("US-ASCII").endsWith("\r\n\r\n"))return out.toString("US-ASCII");}
        throw new IOException("Incomplete headers");
    }
}
