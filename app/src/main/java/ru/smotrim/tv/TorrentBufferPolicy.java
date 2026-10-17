package ru.smotrim.tv;

/** Cache sizes are MiB. Memory observations are snapshots, not allocation guarantees. */
final class TorrentBufferPolicy {
    static final long MIB = 1024L * 1024;
    static final int DEFAULT_MB = 96;
    static final int MIN_MB = 64;
    private static final int[] OPTIONS = {64, 96, 128, 256, 512};

    static boolean supported(int mb) {
        for (int option : OPTIONS) if (mb == option) return true;
        return false;
    }

    static int requested(int mb) { return supported(mb) ? mb : DEFAULT_MB; }

    // The Go soft limit must grow with the cache, leaving room for the engine itself.
    static long runtimeLimitMiB(long cacheBytes) {
        return Math.max(224, (cacheBytes + MIB - 1) / MIB + 160);
    }

    static int effective(int requested, long total, long available, long threshold, boolean lowMemory) {
        requested = requested(requested);
        if (lowMemory || total <= 0 || available <= 0) return MIN_MB;
        // Decoder, Media3, WebView and Android all need headroom beyond the torrent cache.
        long reserve = Math.max(384 * MIB, Math.max(0, threshold) + 256 * MIB);
        for (int i = OPTIONS.length - 1; i >= 0; i--) {
            int mb = OPTIONS[i];
            if (mb > requested) continue;
            long cache = mb * MIB;
            long engineBudget = runtimeLimitMiB(cache) * MIB;
            if (engineBudget <= total / 4 && engineBudget <= available - reserve) return mb;
        }
        return MIN_MB;
    }
}
