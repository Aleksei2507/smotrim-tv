package ru.smotrim.tv;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Minimal real BitTorrent seeder and HTTP tracker, isolated to loopback. */
final class LocalTorrentFixture implements AutoCloseable {
    static final int PIECE=64*1024;
    final byte[] video,metadata,hash;
    final ServerSocket http;
    final int peerPort;
    final org.libtorrent4j.SessionManager seeder=new org.libtorrent4j.SessionManager();
    final File seedDirectory;
    final ExecutorService threads=Executors.newCachedThreadPool();
    final Set<Socket> clients=ConcurrentHashMap.newKeySet();
    final java.util.concurrent.atomic.AtomicLong sentBytes=new java.util.concurrent.atomic.AtomicLong();
    LocalTorrentFixture(byte[] video)throws Exception{
        this.video=video;http=new ServerSocket(0,20,InetAddress.getByName("127.0.0.1"));try(ServerSocket port=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){peerPort=port.getLocalPort();}
        seedDirectory=java.nio.file.Files.createTempDirectory("smotrim-seed-").toFile();
        java.nio.file.Files.write(new File(seedDirectory,"fixture.mp4").toPath(),video);
        MessageDigest sha=MessageDigest.getInstance("SHA-1");ByteArrayOutputStream pieces=new ByteArrayOutputStream();
        for(int p=0;p<video.length;p+=PIECE)pieces.write(sha.digest(Arrays.copyOfRange(video,p,Math.min(video.length,p+PIECE))));
        TreeMap<String,Object> info=new TreeMap<>();info.put("length",video.length);info.put("name","fixture.mp4");info.put("piece length",PIECE);info.put("pieces",pieces.toByteArray());info.put("private",1);
        hash=sha.digest(encode(info));TreeMap<String,Object> meta=new TreeMap<>();meta.put("announce","http://127.0.0.1:"+http.getLocalPort()+"/announce");meta.put("info",info);metadata=encode(meta);
        org.libtorrent4j.SettingsPack settings=new org.libtorrent4j.SettingsPack().listenInterfaces("127.0.0.1:"+peerPort);
        settings.setEnableDht(false);settings.setEnableLsd(false);
        seeder.start(new org.libtorrent4j.SessionParams(settings));
        org.libtorrent4j.AddTorrentParams params=new org.libtorrent4j.AddTorrentParams();
        params.setTorrentInfo(new org.libtorrent4j.TorrentInfo(metadata));params.setSavePath(seedDirectory.getAbsolutePath());
        params.setFlags(params.getFlags().or_(org.libtorrent4j.TorrentFlags.SEED_MODE).and_(org.libtorrent4j.TorrentFlags.PAUSED.inv()).and_(org.libtorrent4j.TorrentFlags.AUTO_MANAGED.inv()));
        seeder.swig().async_add_torrent(params.swig());
        accept(http,false);
    }
    String url(){return "http://127.0.0.1:"+http.getLocalPort()+"/fixture.torrent";}
    void accept(ServerSocket listener,boolean bittorrent){threads.execute(()->{while(!listener.isClosed())try{Socket s=listener.accept();clients.add(s);threads.execute(()->{try(Socket socket=s){socket.setSoTimeout(30000);serve(socket);}catch(IOException ignored){}finally{clients.remove(s);}});}catch(IOException ignored){}});}
    void serve(Socket s)throws IOException{
        BufferedReader in=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.US_ASCII));String request=in.readLine(),line;
        while((line=in.readLine())!=null&&!line.isEmpty()){}
        byte[] body=metadata;
        if(request!=null&&request.contains("/announce")){TreeMap<String,Object> reply=new TreeMap<>();reply.put("interval",60);reply.put("complete",1);reply.put("incomplete",0);int port=peerPort;reply.put("peers",new byte[]{127,0,0,1,(byte)(port>>8),(byte)port});body=encode(reply);}
        OutputStream out=s.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: "+body.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
    }
    static byte[] encode(Object value)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();write(value,out);return out.toByteArray();}
    static void write(Object o,OutputStream out)throws IOException{
        if(o instanceof Number)out.write(("i"+o+"e").getBytes(StandardCharsets.US_ASCII));
        else if(o instanceof Map){out.write('d');for(Object key:((Map<?,?>)o).keySet()){write(key.toString(),out);write(((Map<?,?>)o).get(key),out);}out.write('e');}
        else{byte[] b=o instanceof byte[]?(byte[])o:o.toString().getBytes(StandardCharsets.UTF_8);out.write((b.length+":").getBytes(StandardCharsets.US_ASCII));out.write(b);}
    }
    public void close()throws IOException{http.close();seeder.stop();TorrentEngine.deleteTree(seedDirectory);for(Socket s:clients)s.close();threads.shutdownNow();}
}
