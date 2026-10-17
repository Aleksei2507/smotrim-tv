package ru.smotrim.tv;

import androidx.media3.exoplayer.DefaultLoadControl;

/** Separate compressed sample queue; codec/GPU memory is additional to this target. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class PlaybackLoadControl {
    static final int TARGET_BYTES = 24 * 1024 * 1024;
    static DefaultLoadControl create() {
        return new DefaultLoadControl.Builder()
            .setBufferDurationsMs(10000,30000,1000,2000)
            .setTargetBufferBytes(TARGET_BYTES)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(0,false)
            .build();
    }
}
