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
    final ServerSocket peer;
    final ExecutorService threads=Executors.newCachedThreadPool();
    final Set<Socket> clients=ConcurrentHashMap.newKeySet();
    final java.util.concurrent.atomic.AtomicLong sentBytes=new java.util.concurrent.atomic.AtomicLong();
    LocalTorrentFixture(byte[] video)throws Exception{
        this.video=video;http=new ServerSocket(0,20,InetAddress.getByName("127.0.0.1"));
        peer=new ServerSocket(0,20,InetAddress.getByName("127.0.0.1"));peerPort=peer.getLocalPort();
        MessageDigest sha=MessageDigest.getInstance("SHA-1");ByteArrayOutputStream pieces=new ByteArrayOutputStream();
        for(int p=0;p<video.length;p+=PIECE)pieces.write(sha.digest(Arrays.copyOfRange(video,p,Math.min(video.length,p+PIECE))));
        TreeMap<String,Object> info=new TreeMap<>();info.put("length",video.length);info.put("name","fixture.mp4");info.put("piece length",PIECE);info.put("pieces",pieces.toByteArray());info.put("private",1);
        hash=sha.digest(encode(info));TreeMap<String,Object> meta=new TreeMap<>();meta.put("announce","http://127.0.0.1:"+http.getLocalPort()+"/announce");meta.put("info",info);metadata=encode(meta);
        accept(http,false);accept(peer,true);
    }
    String url(){return "http://127.0.0.1:"+http.getLocalPort()+"/fixture.torrent";}
    void accept(ServerSocket listener,boolean bittorrent){threads.execute(()->{while(!listener.isClosed())try{Socket s=listener.accept();clients.add(s);threads.execute(()->{try(Socket socket=s){socket.setSoTimeout(30000);if(bittorrent)servePeer(socket,false);else serve(socket);}catch(IOException ignored){}finally{clients.remove(s);}});}catch(IOException ignored){}});}
    // Minimal BEP 3 seeder: deterministic bytes, no JNI or external peers.
    void servePeer(Socket socket,boolean initiate)throws IOException{
        DataInputStream in=new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        DataOutputStream out=new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        if(initiate)handshake(out);
        byte[] greeting=new byte[68];greeting[0]=in.readByte();if(greeting[0]!=19)return;in.readFully(greeting,1,67);
        if(!Arrays.equals(hash,Arrays.copyOfRange(greeting,28,48)))return;
        if(!initiate)handshake(out);
        int count=(video.length+PIECE-1)/PIECE;byte[] field=new byte[(count+7)/8];
        for(int i=0;i<count;i++)field[i/8]|=(byte)(0x80>>(i%8));
        out.writeInt(field.length+1);out.writeByte(5);out.write(field);out.writeInt(1);out.writeByte(1);out.flush();
        while(!socket.isClosed()){
            int size=in.readInt();if(size==0)continue;if(size<1||size>65536)return;
            int type=in.readUnsignedByte();byte[] message=new byte[size-1];in.readFully(message);
            if(type!=6||message.length!=12)continue;
            DataInputStream request=new DataInputStream(new ByteArrayInputStream(message));
            int index=request.readInt(),begin=request.readInt(),length=request.readInt();long position=(long)index*PIECE+begin;
            if(index<0||begin<0||length<1||length>16384||begin+length>PIECE||position+length>video.length)return;
            out.writeInt(9+length);out.writeByte(7);out.writeInt(index);out.writeInt(begin);out.write(video,(int)position,length);out.flush();sentBytes.addAndGet(length);
        }
    }
    void handshake(DataOutputStream out)throws IOException{out.writeByte(19);out.write("BitTorrent protocol".getBytes(StandardCharsets.US_ASCII));out.write(new byte[8]);out.write(hash);out.write("-SMTEST-000000000000".getBytes(StandardCharsets.US_ASCII));out.flush();}
    void connectClient(String request){
        // Initiate plaintext BEP 3 too: the real engine prefers MSE for outgoing connections.
        java.util.regex.Matcher port=java.util.regex.Pattern.compile("[?&]port=(\\d+)").matcher(request);
        if(!port.find())return;int target=Integer.parseInt(port.group(1));
        if(target<1||target>65535)return;
        threads.execute(()->{try(Socket client=new Socket("127.0.0.1",target)){clients.add(client);client.setSoTimeout(30000);try{servePeer(client,true);}finally{clients.remove(client);}}catch(IOException ignored){}});
    }
    void serve(Socket s)throws IOException{
        BufferedReader in=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.US_ASCII));String request=in.readLine(),line;
        while((line=in.readLine())!=null&&!line.isEmpty()){}
        byte[] body=metadata;
        if(request!=null&&request.contains("/announce")){TreeMap<String,Object> reply=new TreeMap<>();reply.put("interval",60);reply.put("complete",1);reply.put("incomplete",0);int port=peerPort;reply.put("peers",new byte[]{127,0,0,1,(byte)(port>>8),(byte)port});body=encode(reply);connectClient(request);}
        OutputStream out=s.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: "+body.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
    }
    static byte[] encode(Object value)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();write(value,out);return out.toByteArray();}
    static void write(Object o,OutputStream out)throws IOException{
        if(o instanceof Number)out.write(("i"+o+"e").getBytes(StandardCharsets.US_ASCII));
        else if(o instanceof Map){out.write('d');for(Object key:((Map<?,?>)o).keySet()){write(key.toString(),out);write(((Map<?,?>)o).get(key),out);}out.write('e');}
        else{byte[] b=o instanceof byte[]?(byte[])o:o.toString().getBytes(StandardCharsets.UTF_8);out.write((b.length+":").getBytes(StandardCharsets.US_ASCII));out.write(b);}
    }
    public void close()throws IOException{http.close();peer.close();for(Socket s:clients)s.close();threads.shutdownNow();}
}
