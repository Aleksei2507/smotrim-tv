package ru.smotrim.tv;

import android.content.Context;
import android.content.pm.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.*;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class ApkVerificationTest {
    private Context context(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
    private PackageInfo installed()throws Exception{
        PackageInfo actual=context().getPackageManager().getPackageInfo(context().getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo copy=new PackageInfo();copy.packageName=actual.packageName;copy.versionName=actual.versionName;
        copy.setLongVersionCode(actual.getLongVersionCode());copy.signingInfo=actual.signingInfo;return copy;
    }
    private PackageInfo next()throws Exception{PackageInfo result=installed();result.setLongVersionCode(result.getLongVersionCode()+1);result.versionName="9.0.0";return result;}
    @Test public void acceptsHigherVersionWithSameCertificate() throws Exception {
        ApkUpdates.verifyPackage(installed(),next(),"9.0.0");
    }
    @Test public void refusesAnotherCertificate() throws Exception {
        PackageInfo candidate=next();candidate.signingInfo=context().getPackageManager().getPackageInfo("android",PackageManager.GET_SIGNING_CERTIFICATES).signingInfo;
        IOException error=assertThrows(IOException.class,()->ApkUpdates.verifyPackage(installed(),candidate,"9.0.0"));
        assertTrue(error.getMessage().contains("Подпись APK отличается"));
    }
    @Test public void refusesVersionNameMismatch() throws Exception {
        IOException error=assertThrows(IOException.class,()->ApkUpdates.verifyPackage(installed(),next(),"8.0.0"));
        assertTrue(error.getMessage().contains("Версия APK не совпадает"));
    }
    @Test public void refusesInstalledVersion() {
        Context context=context();
        IOException error=assertThrows(IOException.class,()->ApkUpdates.verifyApk(context,new File(context.getApplicationInfo().sourceDir),BuildConfig.VERSION_NAME));
        assertTrue(error.getMessage().contains("уже установлена"));
    }
    @Test public void refusesAnotherPackage() throws Exception {
        Context context=context();
        File framework=new File(context.getPackageManager().getApplicationInfo("android",0).sourceDir);
        IOException error=assertThrows(IOException.class,()->ApkUpdates.verifyApk(context,framework,"99.0.0"));
        assertTrue(error.getMessage().contains("не является обновлением"));
    }
    @Test public void refusesDamagedArchive() throws Exception {
        Context context=context();File bad=File.createTempFile("bad-update",".apk",context.getCacheDir());
        try {
            Files.write(bad.toPath(),new byte[]{0x50,0x4b,0,0,1,2,3});
            assertThrows(IOException.class,()->ApkUpdates.verifyApk(context,bad,"99.0.0"));
        }finally{bad.delete();}
    }
    @Test public void updaterRetainsAutomaticCheckPreference() {
        Context context=context();boolean previous=context.getSharedPreferences("updates",0).getBoolean("autoCheck",true);
        ApkUpdates.Listener listener=new ApkUpdates.Listener(){public void changed(org.json.JSONObject state){} public void ready(){fail("No download requested");}};
        try(ApkUpdates first=new ApkUpdates(context,listener)){
            first.setAutoCheck(false);first.check(true);
            assertFalse(first.state().optBoolean("autoCheck"));assertNotEquals("checking",first.state().optString("status"));
            try(ApkUpdates second=new ApkUpdates(context,listener)){assertFalse(second.state().optBoolean("autoCheck"));}
        }finally{context.getSharedPreferences("updates",0).edit().putBoolean("autoCheck",previous).commit();}
    }
    @Test public void providerExposesOnlyUpdateDirectory() throws Exception {
        Context context=context();File directory=new File(context.getCacheDir(),"updates");directory.mkdirs();
        File allowed=new File(directory,"provider-test.apk");Files.write(allowed.toPath(),new byte[]{1,2,3});
        try {
            android.net.Uri uri=androidx.core.content.FileProvider.getUriForFile(context,context.getPackageName()+".updates",allowed);
            assertEquals("content",uri.getScheme());
            try(InputStream stream=context.getContentResolver().openInputStream(uri)){assertEquals(1,stream.read());}
            assertThrows(IllegalArgumentException.class,()->androidx.core.content.FileProvider.getUriForFile(context,context.getPackageName()+".updates",new File(context.getFilesDir(),"private.txt")));
        }finally{allowed.delete();}
    }
    @Test public void replacementBroadcastDeletesOnlyUpdateFiles() throws Exception {
        Context context=context();File dir=new File(context.getCacheDir(),"updates");dir.mkdirs();
        File apk=new File(dir,"update.apk"),part=new File(dir,"update.apk.part"),other=new File(context.getCacheDir(),"keep-test.txt");
        Files.write(apk.toPath(),new byte[]{1});Files.write(part.toPath(),new byte[]{2});Files.write(other.toPath(),new byte[]{3});
        try {
            UpdateCleanupReceiver receiver=new UpdateCleanupReceiver();
            receiver.onReceive(context,new android.content.Intent("other"));assertTrue(apk.isFile());
            receiver.onReceive(context,new android.content.Intent(android.content.Intent.ACTION_MY_PACKAGE_REPLACED));
            assertFalse(apk.exists());assertFalse(part.exists());assertTrue(other.isFile());
        }finally{apk.delete();part.delete();other.delete();}
    }
    @Test public void abandonedApkExpiresAfterOneDay() throws Exception {
        Context context=context();File dir=new File(context.getCacheDir(),"updates");dir.mkdirs();File apk=new File(dir,"update.apk");
        android.content.SharedPreferences prefs=context.getSharedPreferences("updates",0);
        String oldRelease=prefs.getString("release","{}"),oldDownloaded=prefs.getString("downloaded","");
        String url="https://github.com/"+UpdateRelease.REPOSITORY+"/releases/download/v9.0.0/update.apk";
        org.json.JSONObject release=new org.json.JSONObject().put("tag_name","v9.0.0").put("assets",new org.json.JSONArray().put(new org.json.JSONObject().put("name","update.apk").put("state","uploaded").put("size",3).put("browser_download_url",url)));
        prefs.edit().putString("release",release.toString()).putString("downloaded",url).commit();Files.write(apk.toPath(),new byte[]{1,2,3});
        ApkUpdates.Listener listener=new ApkUpdates.Listener(){public void changed(org.json.JSONObject state){}public void ready(){fail("No download requested");}};
        try(ApkUpdates updates=new ApkUpdates(context,listener)) {
            assertEquals("ready",updates.state().optString("status"));
            assertTrue(apk.setLastModified(System.currentTimeMillis()-25L*60*60*1000));
            assertEquals("available",updates.state().optString("status"));assertFalse(apk.exists());
            assertFalse(prefs.contains("downloaded"));
        }finally{apk.delete();prefs.edit().putString("release",oldRelease).putString("downloaded",oldDownloaded).commit();}
    }
}
