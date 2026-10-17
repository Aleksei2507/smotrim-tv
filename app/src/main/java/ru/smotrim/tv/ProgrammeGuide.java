package ru.smotrim.tv;

import org.json.*;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;
import javax.xml.parsers.SAXParserFactory;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Bounded XMLTV ingestion. Only programmes for the next 24 hours are retained. */
final class ProgrammeGuide {
    static final String DEFAULT_URL="https://epg.pw/xmltv/epg_RU.xml.gz";
    static String normalize(String s){return s.toLowerCase(Locale.ROOT).replaceAll("(?i)\\(\\d+[pi]\\)","").replaceAll("\\[[^]]*\\]","").replaceAll("(?i)\\b(?:hd|uhd|fhd)\\b","").replaceAll("[^\\p{L}\\p{N}+]","");}
    static String knownName(String id){
        if(!id.matches(".+\\.ru@(SD|HD)$"))return "";
        switch(id.substring(0,id.indexOf('.'))){
            case "NTV":return "НТВ";case "Friday":return "Пятница";case "ChannelOne":return "Первый канал";
            case "Russia1":return "Россия 1";case "Russia24":return "Россия 24";case "RussiaK":return "Россия К";
            case "Carousel":return "Карусель";case "STS":case "CTC":return "СТС";case "TNT":return "ТНТ";
            case "REN":case "RENTV":return "Рен ТВ";case "TV3":return "ТВ-3";case "TVCentre":return "ТВ Центр";case "Mir":return "Мир";
            default:return "";
        }
    }
    static long timestamp(String value){
        try{String s=value.trim();if(!s.matches("\\d{14}\\s+[+-]\\d{4}"))return 0;
            return OffsetDateTime.parse(s.replaceAll("\\s+"," "),DateTimeFormatter.ofPattern("yyyyMMddHHmmss xx",Locale.ROOT)).toInstant().toEpochMilli();
        }catch(Exception ignored){return 0;}
    }
    static JSONObject parse(InputStream stream,long now)throws Exception{
        Map<String,List<String>> names=new HashMap<>();Map<String,List<JSONObject>> programs=new HashMap<>();
        SAXParserFactory factory=SAXParserFactory.newInstance();factory.setNamespaceAware(false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        try{factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false);}catch(SAXNotRecognizedException|SAXNotSupportedException ignored){}
        XMLReader reader=factory.newSAXParser().getXMLReader();reader.setEntityResolver((publicId,systemId)->new InputSource(new StringReader("")));
        reader.setProperty("http://xml.org/sax/properties/lexical-handler",new org.xml.sax.ext.DefaultHandler2(){@Override public void startDTD(String name,String publicId,String systemId)throws SAXException{throw new SAXException("XMLTV с DTD не поддерживается");}});
        reader.setContentHandler(new DefaultHandler(){
            String channel,programmeChannel;long start,end;String title="";StringBuilder text=new StringBuilder();int count;
            public void startElement(String uri,String local,String q,Attributes a)throws SAXException{
                if(q.equals("channel")){channel=a.getValue("id");if(channel!=null&&names.size()<10000)names.put(channel,new ArrayList<>());}
                if(q.equals("programme")){programmeChannel=a.getValue("channel");start=timestamp(Optional.ofNullable(a.getValue("start")).orElse(""));end=timestamp(Optional.ofNullable(a.getValue("stop")).orElse(""));title="";}
                text.setLength(0);
            }
            public void characters(char[] ch,int offset,int length){if(text.length()<2000)text.append(ch,offset,Math.min(length,2000-text.length()));}
            public void endElement(String uri,String local,String q)throws SAXException{
                if(q.equals("display-name")&&channel!=null&&names.containsKey(channel)&&names.get(channel).size()<10)names.get(channel).add(text.toString().trim());
                if(q.equals("title")&&programmeChannel!=null&&title.isEmpty())title=text.toString().trim();
                if(q.equals("channel"))channel=null;
                if(q.equals("programme")){
                    if(programmeChannel!=null&&end>now&&start<now+86400000L&&start>0&&end>start&&!title.isEmpty()&&count<60000){try{
                        List<JSONObject> list=programs.computeIfAbsent(programmeChannel,k->new ArrayList<>());if(list.size()<200){list.add(new JSONObject().put("title",title).put("start",start).put("end",end));count++;}
                    }catch(JSONException e){throw new SAXException(e);}}
                    programmeChannel=null;
                }
            }
        });
        reader.parse(new InputSource(new LimitedInput(stream,128L*1024*1024)));
        JSONObject result=new JSONObject();
        for(Map.Entry<String,List<JSONObject>> entry:programs.entrySet()){
            entry.getValue().sort(Comparator.comparingLong(p->p.optLong("start")));
            result.put(entry.getKey(),new JSONObject().put("names",new JSONArray(names.getOrDefault(entry.getKey(),Collections.emptyList()))).put("programs",new JSONArray(entry.getValue())));
        }
        return result;
    }
    static JSONObject match(JSONObject data,JSONArray channels,long now)throws JSONException{
        Map<String,String> aliases=new HashMap<>();Set<String> ambiguous=new HashSet<>();
        Iterator<String> ids=data.keys();while(ids.hasNext()){String id=ids.next();JSONArray names=data.getJSONObject(id).getJSONArray("names");for(int i=0;i<names.length();i++){String n=normalize(names.optString(i));String previous=aliases.putIfAbsent(n,id);if(previous!=null&&!previous.equals(id))ambiguous.add(n);}}
        JSONObject result=new JSONObject();
        for(int i=0;i<channels.length();i++){JSONObject c=channels.getJSONObject(i);String id=c.optString("id");if(!data.has(id)){String known=knownName(id);String name=normalize(known.isEmpty()?c.optString("name"):known);id=ambiguous.contains(name)?null:aliases.get(name);}if(id==null||!data.has(id))continue;
            JSONArray available=data.getJSONObject(id).getJSONArray("programs"),future=new JSONArray();JSONObject current=null;
            for(int j=0;j<available.length();j++){JSONObject p=available.getJSONObject(j);if(p.optLong("end")<=now)continue;if(p.optLong("start")<=now)current=p;else if(future.length()<20)future.put(p);}
            if(current!=null||future.length()>0)result.put(c.getString("url"),new JSONObject().put("current",current==null?JSONObject.NULL:current).put("next",future.length()>0?future.get(0):JSONObject.NULL).put("programs",future));
        }
        return result;
    }
    static synchronized JSONObject load(File file,String source,JSONArray channels,boolean force)throws Exception{
        long now=System.currentTimeMillis();JSONObject saved=null;String error="";
        try{if(file.isFile()&&file.length()<20*1024*1024)saved=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));}catch(Exception ignored){}
        if(saved!=null&&!source.equals(saved.optString("source")))saved=null;
        if(force||saved==null||now-saved.optLong("updated")>6*3600000L){
            try{URL url=new URL(source);if(!url.getProtocol().equals("https")&&!url.getProtocol().equals("http"))throw new IOException("Нужна HTTP-ссылка на XMLTV");
                HttpURLConnection c=(HttpURLConnection)url.openConnection();c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setRequestProperty("User-Agent","Smotrim-TV/0.3");
                try{if(c.getResponseCode()!=200)throw new IOException("Источник программы недоступен ("+c.getResponseCode()+")");
                    PushbackInputStream in=new PushbackInputStream(new LimitedInput(c.getInputStream(),12L*1024*1024),2);byte[] magic=new byte[2];int n=in.read(magic);if(n>0)in.unread(magic,0,n);
                    try(InputStream xml=n==2&&(magic[0]&255)==31&&(magic[1]&255)==139?new GZIPInputStream(in):in){JSONObject data=parse(xml,now);if(data.length()==0)throw new IOException("В источнике нет актуальной программы передач");saved=new JSONObject().put("source",source).put("updated",now).put("data",data);}
                    File temp=new File(file.getPath()+".tmp");Files.write(temp.toPath(),saved.toString().getBytes(StandardCharsets.UTF_8));Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);
                }finally{c.disconnect();}
            }catch(Exception e){error="Не удалось обновить программу передач";if(saved==null)return new JSONObject().put("channels",new JSONObject()).put("error",error).put("source",source);}
        }
        return new JSONObject().put("channels",match(saved.getJSONObject("data"),channels,now)).put("updated",saved.optLong("updated")).put("source",source).put("error",error);
    }
    static final class LimitedInput extends FilterInputStream {
        long remaining;LimitedInput(InputStream in,long limit){super(in);remaining=limit;}
        private void used(int n)throws IOException{if(n>0&&(remaining-=n)<0)throw new IOException("Слишком большая программа передач");}
        public int read()throws IOException{int n=in.read();used(n<0?0:1);return n;}
        public int read(byte[] b,int off,int len)throws IOException{int n=in.read(b,off,(int)Math.min(len,remaining+1));used(n);return n;}
    }
}
