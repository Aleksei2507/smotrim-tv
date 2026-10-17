package ru.smotrim.tv;
import org.junit.Test;import static org.junit.Assert.*;import org.json.*;import java.io.*;import java.nio.charset.StandardCharsets;
public class ProgrammeGuideTest {
 private JSONObject parse(String xml,long now)throws Exception{return ProgrammeGuide.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),now);}
 @Test public void timeZones(){assertEquals(ProgrammeGuide.timestamp("20261017120000 +0300"),ProgrammeGuide.timestamp("20261017090000 +0000"));assertEquals(0,ProgrammeGuide.timestamp("bad"));}
 @Test public void nowNextAndUnknownChannel()throws Exception{
  long now=ProgrammeGuide.timestamp("20261017123000 +0300");
  String xml="<tv><channel id='x'><display-name>Пятница!</display-name></channel><programme channel='x' start='20261017120000 +0300' stop='20261017130000 +0300'><title>Эфир</title></programme><programme channel='x' start='20261017130000 +0300' stop='20261017140000 +0300'><title>Следом</title></programme></tv>";
  JSONObject data=parse(xml,now);JSONArray channels=new JSONArray("[{id:'Friday.ru',name:'Пятница! (1080p)',url:'one'},{id:'z',name:'Другой',url:'two'}]");JSONObject matched=ProgrammeGuide.match(data,channels,now);
  assertEquals("Эфир",matched.getJSONObject("one").getJSONObject("current").getString("title"));assertEquals("Следом",matched.getJSONObject("one").getJSONObject("next").getString("title"));assertFalse(matched.has("two"));assertEquals(0,ProgrammeGuide.match(data,channels,now+7200000).length());
 }
 @Test(expected=org.xml.sax.SAXException.class) public void externalEntityNotLoaded()throws Exception{JSONObject r=parse("<!DOCTYPE tv [<!ENTITY external SYSTEM 'file:///no-such-private-file'>]><tv><programme channel='x' start='20261017120000 +0300' stop='20261017130000 +0300'><title>&external;</title></programme></tv>",ProgrammeGuide.timestamp("20261017123000 +0300"));assertEquals(0,r.length());}
 @Test public void russianNtvDoesNotMatchForeignNames()throws Exception{JSONObject data=new JSONObject("{german:{names:['NTV'],programs:[{title:'Foreign',start:1,end:99999999}]},russian:{names:['НТВ'],programs:[{title:'Наш эфир',start:1,end:99999999}]}}");JSONObject out=ProgrammeGuide.match(data,new JSONArray("[{id:'NTV.ru@SD',name:'NTV (Russia) (576p)',url:'ntv'}]"),1000);assertEquals("Наш эфир",out.getJSONObject("ntv").getJSONObject("current").getString("title"));}
 @Test public void malformedXmlFails()throws Exception{try{parse("<tv><programme>",0);fail();}catch(org.xml.sax.SAXException expected){}}
 @Test public void boundedInput()throws Exception{try(InputStream in=new ProgrammeGuide.LimitedInput(new ByteArrayInputStream(new byte[10]),5)){try{while(in.read()!=-1){}fail();}catch(IOException expected){}}}
}
