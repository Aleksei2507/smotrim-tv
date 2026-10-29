package ru.smotrim.tv;

import static org.junit.Assert.*;
import androidx.media3.common.*;
import androidx.media3.exoplayer.*;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.Allocation;
import java.util.ArrayList;
import org.junit.Test;

@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public class PlaybackLoadControlTest {
    private static LoadControl.Parameters buffered(long us){
        return new LoadControl.Parameters(PlayerId.UNSET,Timeline.EMPTY,new MediaPeriodId(new Object()),0,us,1f,true,false,C.TIME_UNSET);
    }
    @Test public void starvedOnlyBelowTwoSecondsAndLimit(){
        assertTrue(PlaybackLoadControl.starved(400_000,PlaybackLoadControl.TARGET_BYTES));
        assertFalse(PlaybackLoadControl.starved(PlaybackLoadControl.STARVED_BUFFER_US,PlaybackLoadControl.TARGET_BYTES));
        assertFalse(PlaybackLoadControl.starved(400_000,PlaybackLoadControl.STARVED_LIMIT_BYTES));
    }
    // Reproduces the TCL 4K Dolby Vision log: target reached with < 500 ms buffered.
    @Test public void keepsLoadingPastTargetWhileStarvedThenStops(){
        // DefaultLoadControl warns here; android.util.Log stubs cannot format it on the JVM.
        androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF);
        DefaultLoadControl control=PlaybackLoadControl.create();
        control.onPrepared(PlayerId.UNSET);control.onTracksSelected(buffered(0),TrackGroupArray.EMPTY,new ExoTrackSelection[0]);
        ArrayList<Allocation> blocks=new ArrayList<>();
        while(control.getAllocator().getTotalBytesAllocated()<PlaybackLoadControl.TARGET_BYTES)blocks.add(control.getAllocator().allocate());
        assertTrue(control.shouldContinueLoading(buffered(400_000)));
        assertFalse("Enough media keeps the 24 MiB target",control.shouldContinueLoading(buffered(5_000_000)));
        while(control.getAllocator().getTotalBytesAllocated()<PlaybackLoadControl.STARVED_LIMIT_BYTES)blocks.add(control.getAllocator().allocate());
        assertFalse(control.shouldContinueLoading(buffered(400_000)));
        for(Allocation block:blocks)control.getAllocator().release(block);
        control.onReleased(PlayerId.UNSET);
    }
}
