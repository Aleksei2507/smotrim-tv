package ru.smotrim.tv;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SearchVpnContractTest {
    @Test public void playerCanReadSignatureProtectedVpnRoute() {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals(PackageManager.PERMISSION_GRANTED,context.checkSelfPermission("ru.smotrim.vpn.permission.CONTROL_FILTER"));
        Uri uri=Uri.parse("content://ru.smotrim.vpn.filter/state");
        assertNotNull(context.getPackageManager().resolveContentProvider(uri.getAuthority(),0));
        Bundle state=context.getContentResolver().call(uri,"state",null,null);
        assertNotNull(state);assertTrue(state.getBoolean("available"));
        Bundle route=context.getContentResolver().call(uri,"search-route",null,null);
        assertNotNull(route);assertEquals(1,route.getInt("version"));
        assertEquals("OFF",route.getString("phase"));
        assertFalse(route.containsKey("password"));
    }
}
