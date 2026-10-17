package ru.smotrim.tv;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** Opens catalog trailers in a separate app, outside the privileged WebView. */
final class TrailerLauncher {
    private TrailerLauncher() {}

    static boolean open(Context context, String videoId) {
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) return false;
        Uri url = Uri.parse("https://www.youtube.com/watch?v=" + videoId);
        // Trying the intent directly also works without package-visibility queries.
        for (String packageName : new String[]{"com.google.android.youtube.tv", "com.google.android.youtube", null}) {
            Intent intent = new Intent(Intent.ACTION_VIEW, url);
            if (packageName != null) intent.setPackage(packageName);
            try {
                context.startActivity(intent);
                return true;
            } catch (ActivityNotFoundException | SecurityException unavailable) {
                // Try the next installed handler; a TV may have no browser at all.
            }
        }
        return false;
    }
}
