package ru.smotrim.tv;

import org.json.*;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;

/** Public GitHub release metadata. No credentials or arbitrary download hosts. */
final class UpdateRelease {
    static final String REPOSITORY = "Aleksei2507/smotrim-tv";
    static final String API = "https://api.github.com/repos/" + REPOSITORY + "/releases/latest";
    static final long MAX_APK_BYTES = 200L * 1024 * 1024;
    final String version, notes, url, sha256;
    final long bytes;

    UpdateRelease(String version, String notes, String url, String sha256, long bytes) {
        this.version=version; this.notes=notes; this.url=url; this.sha256=sha256; this.bytes=bytes;
    }

    static int compareVersions(String left, String right) throws IOException {
        String[] a=versionParts(left), b=versionParts(right);
        for(int i=0;i<Math.max(a.length,b.length);i++) {
            int comparison=Long.compare(i<a.length?Long.parseLong(a[i]):0, i<b.length?Long.parseLong(b[i]):0);
            if(comparison!=0)return comparison;
        }
        return 0;
    }

    private static String[] versionParts(String value) throws IOException {
        if(value==null||!value.matches("v?[0-9]{1,6}(\\.[0-9]{1,6}){1,3}"))
            throw new IOException("Некорректный номер версии в GitHub Releases");
        return value.replaceFirst("^v", "").split("\\.");
    }

    static UpdateRelease parse(JSONObject release, String installedVersion) throws Exception {
        if(release.optBoolean("draft")||release.optBoolean("prerelease"))return null;
        String tag=release.getString("tag_name");
        if(compareVersions(tag,installedVersion)<=0)return null;
        JSONArray assets=release.optJSONArray("assets"); JSONObject apk=null;
        if(assets!=null)for(int i=0;i<assets.length();i++) {
            JSONObject asset=assets.getJSONObject(i);
            if(asset.optString("name").toLowerCase(Locale.ROOT).endsWith(".apk")&&"uploaded".equals(asset.optString("state"))) {
                if(apk!=null)throw new IOException("В релизе несколько APK: нужен один универсальный файл");
                apk=asset;
            }
        }
        if(apk==null)throw new IOException("Новая версия опубликована без APK. Попробуйте позже");
        String url=apk.getString("browser_download_url");
        URI uri=new URI(url);
        if(!"https".equals(uri.getScheme())||!"github.com".equals(uri.getHost())||uri.getUserInfo()!=null||uri.getPort()!=-1||
                !uri.getPath().startsWith("/"+REPOSITORY+"/releases/download/")||uri.getPath().contains("/../"))
            throw new IOException("Недопустимый адрес обновления");
        long bytes=apk.getLong("size");
        if(bytes<=0||bytes>MAX_APK_BYTES)throw new IOException("Некорректный размер APK");
        String digest=apk.optString("digest", "");
        if(digest.equals("null"))digest="";
        if(!digest.isEmpty()&&!digest.matches("sha256:[a-fA-F0-9]{64}"))throw new IOException("Некорректная контрольная сумма APK");
        String notes=release.optString("body", "");
        if(notes.length()>8000)notes=notes.substring(0,8000)+"…";
        return new UpdateRelease(tag.replaceFirst("^v",""),notes,url,digest.isEmpty()?"":digest.substring(7).toLowerCase(Locale.ROOT),bytes);
    }

    static boolean allowedHost(URI uri) {
        String host=uri.getHost();
        return "https".equals(uri.getScheme())&&uri.getUserInfo()==null&&uri.getPort()==-1&&
                ("api.github.com".equals(host)||"github.com".equals(host)||"release-assets.githubusercontent.com".equals(host)||"objects.githubusercontent.com".equals(host));
    }
}
