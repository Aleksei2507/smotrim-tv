package ru.smotrim.tv;

import android.content.*;
import java.io.File;

/** The system sends this only after this package has been replaced successfully. */
public final class UpdateCleanupReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent) {
        if(!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()))return;
        File directory=new File(context.getCacheDir(),"updates");
        new File(directory,"update.apk").delete();
        new File(directory,"update.apk.part").delete();
        context.getSharedPreferences("updates",Context.MODE_PRIVATE).edit().remove("downloaded").apply();
    }
}
