package ru.smotrim.tv;

import org.json.JSONObject;
import org.json.JSONArray;

/** Small Activity saved state; never stores video bytes, episode lists or catalog credentials. */
final class PlaybackSnapshot {
    static final String STATE_KEY="smotrim.playback";
    private static final int MAX_LENGTH=65536;

    static JSONObject capture(JSONObject item,long position,boolean playWhenReady) {
        if(item==null)return null;
        try {
            JSONObject copy=new JSONObject();
            for(String key:new String[]{"kind","url","title","fileIndex","historyKey"})if(item.has(key))copy.put(key,item.get(key));
            copy.put("position",Math.max(0,position)).put("paused",!playWhenReady);
            for(String key:new String[]{"movie","sourceMovie"}){
                JSONObject source=item.optJSONObject(key);if(source==null)continue;
                JSONObject media=new JSONObject();
                for(String field:new String[]{"id","historyId","name","nameRu","nameEn","originalName","type","poster","background","year","releaseInfo","imdb_id","imdbId","tmdbId","kinopoiskId","provider","providerId","searchHint","searchQuery","query"}){
                    Object value=source.opt(field);
                    if(value instanceof Number||value instanceof Boolean||value instanceof String&&((String)value).length()<=16384)media.put(field,value);
                }
                JSONArray ids=source.optJSONArray("ids");
                if(ids!=null){JSONArray aliases=new JSONArray();for(int i=0;i<Math.min(32,ids.length());i++){Object id=ids.opt(i);if(id instanceof String&&((String)id).length()<=256)aliases.put(id);}media.put("ids",aliases);}
                JSONObject episode=source.optJSONObject("selectedEpisode");if(episode!=null)media.put("selectedEpisode",episode(episode));
                copy.put(key,media);
            }
            JSONObject episode=item.optJSONObject("seriesEpisode");if(episode!=null)copy.put("seriesEpisode",episode(episode));
            JSONObject channel=item.optJSONObject("channel");if(channel!=null)copy.put("channel",channel);
            return restore(copy.toString());
        }catch(Exception ignored){return null;}
    }
    private static JSONObject episode(JSONObject source)throws Exception {
        return new JSONObject().put("season",source.optInt("season")).put("episode",source.optInt("episode"));
    }
    static JSONObject restore(String json) {
        if(json==null||json.length()>MAX_LENGTH)return null;
        try {
            JSONObject item=new JSONObject(json);String kind=item.optString("kind"),url=item.optString("url");
            if(!kind.equals("live")&&!kind.equals("torrent"))return null;
            if(url.length()>16384||!(url.startsWith("https://")||url.startsWith("http://")||kind.equals("torrent")&&url.startsWith("magnet:?")))return null;
            item.put("position",Math.max(0,item.optLong("position")));
            return item;
        }catch(Exception ignored){return null;}
    }
}
