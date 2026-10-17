package ru.smotrim.tv;

/** Cache sizes are MiB. Memory observations are snapshots, not allocation guarantees. */
final class TorrentBufferPolicy {
    static final long MIB = 1024L * 1024;
    static final int DEFAULT_MB = 96;
    private static final int[] OPTIONS = {96, 128, 256, 512};

    static boolean supported(int mb) {
        for (int option : OPTIONS) if (mb == option) return true;
        return false;
    }

    static int requested(int mb) { return supported(mb) ? mb : DEFAULT_MB; }

    // The Go soft limit must grow with the cache, leaving room for the engine itself.
    static long runtimeLimitMiB(long cacheBytes) {
        return Math.max(256, (cacheBytes + MIB - 1) / MIB + 160);
    }

    static int effective(int requested, long total, long available, long threshold, boolean lowMemory) {
        requested = requested(requested);
        if (lowMemory || total <= 0 || available <= 0) return DEFAULT_MB;
        long reserve = Math.max(192 * MIB, threshold);
        for (int i = OPTIONS.length - 1; i >= 0; i--) {
            int mb = OPTIONS[i];
            if (mb > requested) continue;
            long cache = mb * MIB;
            if (cache <= total / 3 && runtimeLimitMiB(cache) * MIB <= available - reserve) return mb;
        }
        return DEFAULT_MB;
    }
}
