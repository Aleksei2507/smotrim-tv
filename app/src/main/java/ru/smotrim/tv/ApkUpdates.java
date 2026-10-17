package ru.smotrim.tv;

import android.content.*;
import android.content.pm.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Checks and downloads on a dedicated worker. Installation is a foreground user action. */
final class ApkUpdates implements AutoCloseable {
    interface Listener { void changed(JSONObject state); void ready(); }
    private final Context context;
    private final SharedPreferences prefs;
    private final Listener listener;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final File apk, part;
    private volatile HttpURLConnection connection;
    private volatile boolean closed;
    private boolean busy;
    private UpdateRelease release;
    private String status="idle", error="";
    private long received;

    ApkUpdates(Context context, Listener listener) {
        this.context=context.getApplicationContext();this.listener=listener;
        prefs=context.getSharedPreferences("updates",Context.MODE_PRIVATE);
        File dir=new File(context.getCacheDir(),"updates");dir.mkdirs();
        apk=new File(dir,"update.apk");part=new File(dir,"update.apk.part");part.delete();
        try { release=UpdateRelease.parse(new JSONObject(prefs.getString("release","{}")),BuildConfig.VERSION_NAME); }
        catch(Exception ignored) { release=null; }
        if(release!=null)status=apk.isFile()&&apk.length()==release.bytes&&release.url.equals(prefs.getString("downloaded",""))?"ready":"available";
        else { apk.delete();prefs.edit().remove("downloaded").apply(); }
    }

    synchronized JSONObject state() {
        expireCachedDownload();
        JSONObject result=new JSONObject();
        try {
            result.put("status",status).put("error",error).put("installedVersion",BuildConfig.VERSION_NAME)
                .put("repository",UpdateRelease.REPOSITORY).put("autoCheck",prefs.getBoolean("autoCheck",true))
                .put("checkedAt",prefs.getLong("checkedAt",0)).put("received",received);
            if(release!=null)result.put("version",release.version).put("notes",release.notes).put("bytes",release.bytes);
        } catch(JSONException ignored) {}
        return result;
    }

    synchronized void setAutoCheck(boolean enabled) { prefs.edit().putBoolean("autoCheck",enabled).apply();publish(); }
    private synchronized void publish() { if(!closed)listener.changed(state()); }
    private synchronized void fail(Exception e) { busy=false;status=release==null?"error":apk.isFile()?"ready":"available";error=message(e);publish(); }
    private static String message(Exception e) {
        if(e instanceof UnknownHostException||e instanceof SocketTimeoutException)return "GitHub недоступен. Проверьте интернет и попробуйте позже";
        return e.getMessage()==null?"Не удалось выполнить обновление":e.getMessage();
    }

    synchronized void check(boolean automatic) {
        if(closed||busy)return;
        if(automatic&&(!prefs.getBoolean("autoCheck",true)||System.currentTimeMillis()-prefs.getLong("attemptedAt",0)<86400000L))return;
        busy=true;status="checking";error="";publish();
        prefs.edit().putLong("attemptedAt",System.currentTimeMillis()).apply();
        worker.execute(()->{
            try {
                HttpURLConnection c=open(UpdateRelease.API); int code=c.getResponseCode();
                JSONObject json=null;UpdateRelease found=null;
                if(code!=404) { requireOk(c);try(InputStream in=c.getInputStream()){json=new JSONObject(new String(readLimited(in,1024*1024),StandardCharsets.UTF_8));}found=UpdateRelease.parse(json,BuildConfig.VERSION_NAME); }
                c.disconnect();connection=null;
                synchronized(this) {
                    release=found;error="";busy=false;
                    if(found==null) {status="current";apk.delete();prefs.edit().remove("downloaded").apply();}
                    else status=apk.isFile()&&apk.length()==found.bytes&&found.url.equals(prefs.getString("downloaded",""))?"ready":"available";
                    if(!status.equals("ready"))apk.delete();
                    prefs.edit().putLong("checkedAt",System.currentTimeMillis()).putString("release",json==null?"{}":json.toString()).apply();publish();
                }
            }catch(Exception e){fail(e);}finally{disconnect();}
        });
    }

    synchronized void download() {
        if(closed||busy)return;
        if(release==null){check(false);return;}
        final UpdateRelease selected=release;
        busy=true;error="";received=0;status="downloading";publish();
        worker.execute(()->{
            try {
                apk.delete();prefs.edit().remove("downloaded").apply();
                // Leave space for PackageInstaller's staging copy and extraction.
                if(apk.getParentFile().getUsableSpace()<selected.bytes*3+32L*1024*1024)
                    throw new IOException("Для обновления нужно освободить около "+((selected.bytes*3+32L*1024*1024)/1024/1024)+" МБ памяти телевизора");
                HttpURLConnection c=open(selected.url);requireOk(c);
                MessageDigest digest=MessageDigest.getInstance("SHA-256");long count=0,lastProgress=0;
                try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(part)) {
                    byte[] buffer=new byte[65536];int n;
                    while((n=in.read(buffer))!=-1) {
                        if(closed||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Загрузка отменена");
                        count+=n;if(count>selected.bytes)throw new IOException("Размер загруженного APK не совпадает с релизом");
                        digest.update(buffer,0,n);out.write(buffer,0,n);
                        if(System.currentTimeMillis()-lastProgress>400){lastProgress=System.currentTimeMillis();synchronized(this){received=count;publish();}}
                    }
                    out.getFD().sync();
                }
                if(count!=selected.bytes)throw new IOException("APK загружен не полностью. Повторите загрузку");
                String hash=hex(digest.digest());
                if(!selected.sha256.isEmpty()&&!selected.sha256.equals(hash))throw new IOException("Контрольная сумма APK не совпадает. Установка отменена");
                verifyApk(context,part,selected.version);
                Files.move(part.toPath(),apk.toPath(),StandardCopyOption.REPLACE_EXISTING);
                synchronized(this){prefs.edit().putString("downloaded",selected.url).apply();received=count;busy=false;status="ready";publish();}
                if(!closed)listener.ready();
            }catch(Exception e){part.delete();apk.delete();fail(e);}finally{disconnect();}
        });
    }

    synchronized File verifiedApk() throws Exception {
        expireCachedDownload();
        if(busy||release==null||!apk.isFile())throw new IOException("Сначала загрузите обновление");
        verifyApk(context,apk,release.version);return apk;
    }

    private void expireCachedDownload() {
        if(!busy&&apk.isFile()&&System.currentTimeMillis()-apk.lastModified()>=86400000L) {
            apk.delete();prefs.edit().remove("downloaded").apply();received=0;
            if("ready".equals(status))status=release==null?"idle":"available";
        }
    }

    static void verifyApk(Context context,File file,String expectedVersion) throws Exception {
        PackageManager pm=context.getPackageManager();
        PackageInfo candidate=pm.getPackageArchiveInfo(file.getAbsolutePath(),PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo installed=pm.getPackageInfo(context.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES);
        verifyPackage(installed,candidate,expectedVersion);
    }

    static void verifyPackage(PackageInfo installed,PackageInfo candidate,String expectedVersion) throws Exception {
        if(candidate==null||!installed.packageName.equals(candidate.packageName))throw new IOException("APK не является обновлением Смотрим");
        if(candidate.getLongVersionCode()<=installed.getLongVersionCode())throw new IOException("Эта версия уже установлена или устарела");
        if(!expectedVersion.equals(candidate.versionName))throw new IOException("Версия APK не совпадает с GitHub Releases");
        if(candidate.signingInfo==null||installed.signingInfo==null||!signers(candidate.signingInfo).equals(signers(installed.signingInfo)))
            throw new IOException("Подпись APK отличается от установленного приложения. Установка отменена");
    }

    private static Set<String> signers(SigningInfo info) throws Exception {
        Set<String> result=new HashSet<>();
        for(Signature s:info.getApkContentsSigners())result.add(hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray())));
        if(result.isEmpty())throw new IOException("В APK отсутствует подпись");return result;
    }
    static String hex(byte[] data) {StringBuilder out=new StringBuilder();for(byte b:data)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}

    private HttpURLConnection open(String address) throws Exception {
        URI uri=new URI(address);
        for(int redirects=0;redirects<=5;redirects++) {
            if(closed)throw new InterruptedIOException("Загрузка отменена");
            if(!UpdateRelease.allowedHost(uri))throw new IOException("Недопустимый адрес сервера обновлений");
            HttpURLConnection c=(HttpURLConnection)uri.toURL().openConnection();connection=c;
            c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent","Smotrim-TV/"+BuildConfig.VERSION_NAME);
            c.setRequestProperty("Accept",uri.getHost().equals("api.github.com")?"application/vnd.github+json":"application/octet-stream");
            int code=c.getResponseCode();
            if(code==301||code==302||code==303||code==307||code==308) {
                String location=c.getHeaderField("Location");c.disconnect();
                if(location==null)throw new IOException("GitHub не передал адрес APK");uri=uri.resolve(location);continue;
            }
            return c;
        }
        throw new IOException("Слишком много перенаправлений GitHub");
    }
    private static void requireOk(HttpURLConnection c) throws IOException {
        int status=c.getResponseCode();
        if(status==403||status==429)throw new IOException("GitHub временно ограничил запросы. Попробуйте позже");
        if(status!=200)throw new IOException("GitHub ответил HTTP "+status+". Попробуйте позже");
    }
    private static byte[] readLimited(InputStream in,int maximum) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] bytes=new byte[8192];int n;
        while((n=in.read(bytes))!=-1){if(out.size()+n>maximum)throw new IOException("Слишком большой ответ GitHub");out.write(bytes,0,n);}return out.toByteArray();
    }
    private void disconnect(){HttpURLConnection c=connection;connection=null;if(c!=null)c.disconnect();}
    @Override public void close(){closed=true;disconnect();worker.shutdownNow();}
}
