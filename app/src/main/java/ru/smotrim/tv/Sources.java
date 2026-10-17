package ru.smotrim.tv;

import org.json.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.concurrent.*;

/** Data adapters only. Remote HTML is parsed here, never displayed in the WebView. */
public final class Sources {
    public static final String IPTV = "https://iptv-org.github.io/iptv/languages/rus.m3u";
    public static final String CINEMA = "https://v3-cinemeta.strem.io";
    private static final String TRACKERS = "&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce&tr=udp%3A%2F%2Fopen.demonii.com%3A1337%2Fannounce";
    private Sources() {}

    public static String encode(String s) { try{return URLEncoder.encode(s, "UTF-8").replace("+", "%20");}catch(UnsupportedEncodingException e){throw new AssertionError(e);} }
    public static boolean webUrl(String s) {
        try { URI u = URI.create(s); return ("https".equals(u.getScheme()) || "http".equals(u.getScheme())) && u.getHost()!=null && u.getUserInfo()==null; }
        catch (Exception e) { return false; }
    }
    public static byte[] fetch(String url, int limit) throws IOException {
        if (!webUrl(url)) throw new IOException("Нужна ссылка http или https");
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(12000); c.setReadTimeout(18000);
        c.setRequestProperty("User-Agent","SmotrimTV/0.1 (Android TV)");
        c.setRequestProperty("Accept","application/json,text/html,application/x-bittorrent,*/*");
        try {
            int code=c.getResponseCode();
            if(code<200||code>=300) throw new IOException("Источник ответил HTTP "+code);
            try(InputStream in=c.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] b=new byte[16384]; int n;
                while((n=in.read(b))!=-1) { if(Thread.currentThread().isInterrupted())throw new InterruptedIOException(); if(out.size()+n>limit)throw new IOException("Ответ источника слишком большой");out.write(b,0,n); }
                return out.toByteArray();
            }
        } finally { c.disconnect(); }
    }
    public static String text(String url) throws IOException { return new String(fetch(url,4*1024*1024),StandardCharsets.UTF_8); }

    static String attribute(String line,String key) {
        Matcher m=Pattern.compile("(?:^|\\s)"+Pattern.quote(key)+"=\"([^\"]*)\"").matcher(line);
        return m.find()?m.group(1):"";
    }
    public static JSONArray parseM3u(String text) throws JSONException {
        JSONArray out=new JSONArray(); Set<String> urls=new HashSet<>(); String name="",group="",logo="",id="";JSONObject headers=new JSONObject();
        for(String raw:text.replace("\uFEFF","").split("\\r?\\n")) {
            String line=raw.trim();
            if(line.startsWith("#EXTINF:")) {
                headers=new JSONObject();boolean quote=false;int comma=-1;
                for(int i=0;i<line.length();i++){char c=line.charAt(i);if(c=='"')quote=!quote;if(c==','&&!quote){comma=i;break;}}
                name=comma<0?"":line.substring(comma+1).trim();group=attribute(line,"group-title");logo=attribute(line,"tvg-logo");id=attribute(line,"tvg-id");
                LiveChannels.putHeader(headers,"User-Agent",attribute(line,"http-user-agent"));LiveChannels.putHeader(headers,"Referer",attribute(line,"http-referrer"));
            } else if(line.startsWith("#EXTGRP:")) { group=line.substring(8).trim(); }
            else if(line.startsWith("#EXTVLCOPT:http-user-agent=")){LiveChannels.putHeader(headers,"User-Agent",line.substring(line.indexOf('=')+1));}
            else if(line.startsWith("#EXTVLCOPT:http-referrer=")){LiveChannels.putHeader(headers,"Referer",line.substring(line.indexOf('=')+1));}
            else if(!line.isEmpty()&&!line.startsWith("#")) {
                if(!name.isEmpty()&&webUrl(line)&&urls.add(line)&&!group.toLowerCase(Locale.ROOT).contains("xxx")) {
                    out.put(new JSONObject().put("id",id.isEmpty()?name:id).put("name",name).put("group",group.isEmpty()?"Другое":group).put("logo",webUrl(logo)?logo:"").put("url",line).put("headers",headers));
                }
                name="";group="";logo="";id="";headers=new JSONObject();
            }
        }
        return out;
    }
    public static JSONArray parseRutor(String html) throws JSONException {
        JSONArray out=new JSONArray();
        for(Element row:Jsoup.parse(html).select("#index tr")) {
            Element magnet=row.selectFirst("a[href^=magnet:]");Element title=row.selectFirst("a[href^=/torrent/]");
            if(magnet==null||title==null)continue;
            String size="";for(Element td:row.select("td")) if(td.text().matches("[\\d.,]+\\s+[KMGT]B"))size=td.text();
            Element seeds=row.selectFirst(".green");int count=0;
            if(seeds!=null)try{count=Integer.parseInt(seeds.text().replaceAll("\\D",""));}catch(NumberFormatException ignored){}
            String uri=magnet.attr("href");if(!uri.matches("(?i)magnet:.*xt=urn:btih:[a-f0-9]{40}.*"))continue;
            String name=title.text();
            out.put(new JSONObject().put("title",name).put("magnet",uri+TRACKERS).put("seeds",count).put("size",size).put("bytes",sizeBytes(size)).put("source","Rutor").put("quality",quality(name)));
        }
        return out;
    }
    static long sizeBytes(String size) {
        Matcher m=Pattern.compile("([\\d.,]+)\\s*([KMGT])B").matcher(size);
        if(!m.find())return 0;
        return (long)(Double.parseDouble(m.group(1).replace(',','.'))*Math.pow(1024,"KMGT".indexOf(m.group(2))+1));
    }
    public static String quality(String name) {
        String s=name.toLowerCase(Locale.ROOT);
        if(s.contains("2160")||s.contains("4k"))return "4K";
        if(s.contains("1080"))return "1080p";
        if(s.contains("720"))return "720p";
        return "Видео";
    }
    public static JSONArray parseApiBay(String json) throws JSONException {
        JSONArray rows=new JSONArray(json),out=new JSONArray();
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.getJSONObject(i);String hash=row.optString("info_hash"),name=row.optString("name");
            if(!hash.matches("(?i)[0-9a-f]{40}")||hash.matches("0{40}")||row.optString("id").equals("0"))continue;
            int category=row.optInt("category");if(category/100!=2)continue;
            long bytes=row.optLong("size");
            out.put(new JSONObject().put("title",name).put("magnet","magnet:?xt=urn:btih:"+hash+"&dn="+encode(name)+TRACKERS).put("seeds",row.optInt("seeders")).put("bytes",bytes).put("size",String.format(Locale.US,"%.2f GB",bytes/1073741824.0)).put("source","APIBay").put("quality",quality(name)));
        }
        return out;
    }
    public static JSONArray parseJacRed(String json) throws JSONException {
        JSONArray rows=new JSONArray(json),out=new JSONArray();
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);String magnet=row.optString("magnet"),name=row.optString("title");
            if(!magnet.matches("(?i)magnet:.*xt=urn:btih:[a-f0-9]{40}.*")||name.isEmpty())continue;
            int q=row.optInt("quality");long bytes=row.optLong("size");
            String quality=q>=2160?"4K":q>=1080?"1080p":q>=720?"720p":q>0?q+"p":quality(name);
            out.put(new JSONObject().put("title",name).put("magnet",magnet+TRACKERS).put("seeds",Math.max(0,row.optInt("sid"))).put("bytes",bytes).put("size",String.format(Locale.US,"%.2f GB",bytes/1073741824.0)).put("source","JacRed · "+row.optString("tracker","трекер")).put("quality",quality));
        }
        return out;
    }
    public static JSONObject torrents(String name,String imdb,String year) throws JSONException {
        JSONArray all=new JSONArray(),warnings=new JSONArray();
        ExecutorService pool=Executors.newFixedThreadPool(3);
        List<Callable<JSONArray>> jobs=Arrays.asList(
            ()->parseJacRed(text("https://jac.red/api/v1.0/torrents?search="+encode(name)+"&sort=sid"+(year.matches("\\d{4}")?"&relased="+year:""))),
            ()->parseRutor(text("https://rutor.info/search/0/0/000/0/"+encode(name+(year.isEmpty()?"":" "+year)))),
            ()->parseApiBay(text("https://apibay.org/q.php?q="+encode(imdb.matches("tt\\d+")?imdb:name)+"&cat=200")));
        String[] providers={"JacRed","Rutor","APIBay"};
        try {
            List<Future<JSONArray>> results=pool.invokeAll(jobs,28,TimeUnit.SECONDS);
            for(int i=0;i<results.size();i++)try{JSONArray a=results.get(i).get();for(int j=0;j<a.length();j++)all.put(a.get(j));}catch(Exception e){warnings.put(providers[i]+" недоступен");}
        }catch(InterruptedException e){Thread.currentThread().interrupt();warnings.put("Поиск отменён");}
        finally{pool.shutdownNow();}
        List<JSONObject> items=new ArrayList<>();Set<String> hashes=new HashSet<>();
        for(int i=0;i<all.length();i++){JSONObject o=all.getJSONObject(i);Matcher m=Pattern.compile("(?i)btih:([a-f0-9]{40})").matcher(o.getString("magnet"));if(m.find()&&hashes.add(m.group(1).toLowerCase(Locale.ROOT)))items.add(o);}
        items.sort((a,b)->Integer.compare(b.optInt("seeds"),a.optInt("seeds")));
        JSONArray sorted=new JSONArray();for(JSONObject item:items)sorted.put(item);
        return new JSONObject().put("items",sorted).put("warnings",warnings);
    }
    public static boolean videoFile(String path) { return path.toLowerCase(Locale.ROOT).matches(".*\\.(mkv|mp4|m4v|webm|avi|ts|mov|mpeg|mpg)"); }
    public static File safeFile(File root,String path) throws IOException {
        File candidate=new File(root,path).getCanonicalFile();
        if(!candidate.getPath().startsWith(root.getCanonicalPath()+File.separator))throw new IOException("Некорректный путь файла в раздаче");
        return candidate;
    }
}
