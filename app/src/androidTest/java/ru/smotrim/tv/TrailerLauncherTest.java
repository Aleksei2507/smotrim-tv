package ru.smotrim.tv;

import android.content.ActivityNotFoundException;
import android.content.ContextWrapper;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TrailerLauncherTest {
    private static final String VIDEO = "P3uI5sLosKU";

    private static class HandlerContext extends ContextWrapper {
        final List<Intent> attempts = new ArrayList<>();
        final int availableAttempt;
        HandlerContext(int availableAttempt) { super(null); this.availableAttempt = availableAttempt; }
        @Override public void startActivity(Intent intent) {
            attempts.add(intent);
            if (attempts.size() != availableAttempt) throw new ActivityNotFoundException();
        }
    }

    @Test public void prefersTvYouTubeAndKeepsExactVideo() {
        HandlerContext context = new HandlerContext(1);
        assertTrue(TrailerLauncher.open(context, VIDEO));
        assertEquals(1, context.attempts.size());
        Intent intent = context.attempts.get(0);
        assertEquals("com.google.android.youtube.tv", intent.getPackage());
        assertEquals(Intent.ACTION_VIEW, intent.getAction());
        assertEquals("https://www.youtube.com/watch?v=" + VIDEO, intent.getDataString());
    }

    @Test public void fallsBackToOtherYouTubeThenSystemHandler() {
        for (int available : new int[]{2, 3}) {
            HandlerContext context = new HandlerContext(available);
            assertTrue(TrailerLauncher.open(context, VIDEO));
            assertEquals(available, context.attempts.size());
            assertEquals("com.google.android.youtube", context.attempts.get(1).getPackage());
            if (available == 3) assertNull(context.attempts.get(2).getPackage());
            for (Intent intent : context.attempts) assertEquals("https://www.youtube.com/watch?v=" + VIDEO, intent.getDataString());
        }
    }

    @Test public void missingOrBlockedHandlersDoNotCrash() {
        HandlerContext missing = new HandlerContext(0);
        assertFalse(TrailerLauncher.open(missing, VIDEO));
        assertEquals(3, missing.attempts.size());
        ContextWrapper blocked = new ContextWrapper(null) {
            @Override public void startActivity(Intent intent) { throw new SecurityException(); }
        };
        assertFalse(TrailerLauncher.open(blocked, VIDEO));
    }

    @Test public void rejectsUrlsAndMalformedIdsWithoutLaunching() {
        HandlerContext context = new HandlerContext(1);
        for (String id : new String[]{null, "", "short", "https://youtube.com/watch?v=" + VIDEO, VIDEO + "&x=1", "javascript:", "abcdefghij\n"}) {
            assertFalse(TrailerLauncher.open(context, id));
        }
        assertTrue(context.attempts.isEmpty());
    }
}
