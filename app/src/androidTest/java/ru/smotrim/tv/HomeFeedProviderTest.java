package ru.smotrim.tv;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class HomeFeedProviderTest {
    Context context;String before;
    @Before public void setup(){context=InstrumentationRegistry.getInstrumentation().getTargetContext();before=context.getSharedPreferences("home-feed",0).getString("snapshot","{}");}
    @After public void restore(){HomeFeed.publish(context,before);}
    @Test public void opaqueKeysResolveLocallyAndFeedIsReadOnly() throws Exception {
        HomeFeed.publish(context,"{\"history\":[{\"key\":\"private-history-key\",\"title\":\"Фильм\",\"position\":123000,\"duration\":500000}],\"channels\":[{\"key\":\"https://stream.invalid/token\",\"title\":\"Канал\"}]}");
        try(Cursor cursor=context.getContentResolver().query(HomeFeed.URI,null,null,null,null)){
            assertEquals(2,cursor.getCount());assertTrue(cursor.moveToFirst());String key=cursor.getString(cursor.getColumnIndexOrThrow("key"));assertEquals(64,key.length());assertFalse(key.contains("private"));assertEquals(123000,cursor.getLong(cursor.getColumnIndexOrThrow("position")));
            assertEquals("private-history-key",HomeFeed.resolveAction(context,new JSONObject().put("kind","history").put("key",key)).getString("key"));
            assertTrue(cursor.moveToNext());assertFalse(cursor.getString(cursor.getColumnIndexOrThrow("key")).contains("https"));
        }
        assertNull(HomeFeed.resolveAction(context,new JSONObject().put("kind","history").put("key","missing")));
        try{context.getContentResolver().delete(HomeFeed.URI,null,null);fail("Mutable provider");}catch(UnsupportedOperationException expected){}
        try{context.getContentResolver().openFileDescriptor(Uri.parse("content://ru.smotrim.tv.home/art/../preferences"),"r");fail("Path traversal");}catch(java.io.FileNotFoundException expected){}
        android.content.pm.ProviderInfo info=context.getPackageManager().resolveContentProvider("ru.smotrim.tv.home",0);
        assertEquals(HomeFeed.PERMISSION,info.readPermission);
        assertEquals(android.content.pm.PermissionInfo.PROTECTION_SIGNATURE,context.getPackageManager().getPermissionInfo(HomeFeed.PERMISSION,0).protectionLevel&15);
    }
    @Test public void servesBoundedCachedArtworkWithoutNetwork()throws Exception {
        String image="artwork/tt0111161.jpg";
        HomeFeed.publish(context,new JSONObject().put("history",new JSONArray().put(new JSONObject().put("key","image-test").put("title","Постер").put("image",image))).toString());
        java.io.File file=new java.io.File(HomeFeed.directory(context),HomeFeed.hash(image)+".jpg");
        long until=android.os.SystemClock.uptimeMillis()+5000;while(!file.isFile()&&android.os.SystemClock.uptimeMillis()<until)android.os.SystemClock.sleep(40);
        assertTrue(file.isFile());assertTrue(file.length()<1024*1024);
        try(java.io.InputStream in=context.getContentResolver().openInputStream(Uri.parse("content://ru.smotrim.tv.home/art/"+HomeFeed.hash(image)))){
            android.graphics.Bitmap bitmap=android.graphics.BitmapFactory.decodeStream(in);assertNotNull(bitmap);assertTrue(bitmap.getWidth()<=800);assertTrue(bitmap.getHeight()<=500);bitmap.recycle();
        }
    }
}
