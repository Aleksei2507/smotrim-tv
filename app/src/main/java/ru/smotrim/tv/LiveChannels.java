package ru.smotrim.tv;

import org.json.*;
import java.util.*;

/** Playlist metadata and alternatives for the same broadcast, never a time-shifted feed. */
public final class LiveChannels {
    private LiveChannels() {}

    public static void putHeader(JSONObject headers,String key,String value)throws JSONException {
        if(value==null||value.isEmpty()||value.length()>2048||value.indexOf('\r')>=0||value.indexOf('\n')>=0)return;
        if(key.equals("User-Agent")||key.equals("Referer")&&Sources.webUrl(value))headers.put(key,value);
    }
    public static Map<String,String> headers(JSONObject stream){
        Map<String,String> out=new HashMap<>();JSONObject clean=new JSONObject(),raw=stream.optJSONObject("headers");
        if(raw!=null)for(String key:new String[]{"User-Agent","Referer"})try{putHeader(clean,key,raw.optString(key,""));}catch(JSONException ignored){}
        for(Iterator<String> keys=clean.keys();keys.hasNext();){String key=keys.next();out.put(key,clean.optString(key));}
        return out;
    }
    static String broadcast(String id){
        int at=id.lastIndexOf('@');if(at<1)return id;
        String feed=id.substring(at+1).toUpperCase(Locale.ROOT);
        return feed.matches("SD|HD|FHD|UHD|4K")?id.substring(0,at)+"@MAIN":id;
    }
    public static List<JSONObject> candidates(JSONObject selected,JSONArray playlist,JSONArray backups,String preferred){
        LinkedHashMap<String,JSONObject> all=new LinkedHashMap<>();add(all,selected);
        String id=selected.optString("id");
        if(!id.isEmpty()){
            ArrayList<JSONObject> exact=new ArrayList<>(),quality=new ArrayList<>();
            for(JSONArray list:new JSONArray[]{playlist,backups})for(int i=0;i<list.length();i++){
                JSONObject row=list.optJSONObject(i);if(row==null||row.optString("url").equals(selected.optString("url")))continue;String candidate=row.optString("id");
                if(id.equals(candidate))exact.add(row);else if(broadcast(id).equals(broadcast(candidate)))quality.add(row);
            }
            for(int i=0;i<Math.max(exact.size(),quality.size());i++){
                if(i<exact.size())add(all,exact.get(i));if(i<quality.size())add(all,quality.get(i));
            }
        }
        ArrayList<JSONObject> result=new ArrayList<>();
        if(all.containsKey(preferred))result.add(all.remove(preferred));
        for(JSONObject row:all.values()){if(result.size()==6)break;result.add(row);}
        return result;
    }
    private static void add(Map<String,JSONObject> streams,JSONObject row){
        String url=row.optString("url");if(Sources.webUrl(url))streams.putIfAbsent(url,row);
    }
    public static JSONArray backupsFromApi(String json,JSONArray playlist)throws JSONException{
        Set<String> bases=new HashSet<>();for(int i=0;i<playlist.length();i++)bases.add(playlist.getJSONObject(i).optString("id").split("@",2)[0]);
        JSONArray out=new JSONArray(),rows=new JSONArray(json);
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);if(row==null||row.isNull("channel")||row.isNull("feed")||!bases.contains(row.optString("channel"))||!Sources.webUrl(row.optString("url")))continue;
            JSONObject headers=new JSONObject();if(!row.isNull("user_agent"))putHeader(headers,"User-Agent",row.optString("user_agent",""));if(!row.isNull("referrer"))putHeader(headers,"Referer",row.optString("referrer",""));
            out.put(new JSONObject().put("id",row.getString("channel")+"@"+row.getString("feed")).put("name",row.optString("title")).put("url",row.getString("url")).put("headers",headers));
        }
        return out;
    }
}
