package ru.smotrim.tv;

import androidx.media3.common.C;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.upstream.DefaultAllocator;

/** Separate compressed sample queue; codec/GPU memory is additional to this target. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class PlaybackLoadControl extends DefaultLoadControl {
    static final int TARGET_BYTES = 24 * 1024 * 1024;
    // Badly interleaved 4K files can fill the target with one track before others reach 0.5 s;
    // Media3 then reports "Playback stuck buffering and not loading". Allow a bounded overshoot.
    static final int STARVED_LIMIT_BYTES = 96 * 1024 * 1024;
    static final long STARVED_BUFFER_US = 2_000_000;
    private final DefaultAllocator allocator;

    private PlaybackLoadControl(DefaultAllocator allocator) {
        super(allocator,10000,30000,1000,2000,TARGET_BYTES,false,0,false);
        this.allocator=allocator;
    }
    static DefaultLoadControl create() {
        return new PlaybackLoadControl(new DefaultAllocator(true,C.DEFAULT_BUFFER_SEGMENT_SIZE));
    }

    @Override public boolean shouldContinueLoading(Parameters parameters) {
        return super.shouldContinueLoading(parameters)||starved(parameters.bufferedDurationUs,allocator.getTotalBytesAllocated());
    }
    static boolean starved(long bufferedUs,long allocatedBytes) {
        return bufferedUs<STARVED_BUFFER_US&&allocatedBytes<STARVED_LIMIT_BYTES;
    }
}
