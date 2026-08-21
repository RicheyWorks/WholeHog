package io.github.richeyworks.wholehog;

import io.github.richeyworks.smokehouse.SmokeHouse;
import io.github.richeyworks.smokehouse.SmokeHouseOptions;
import io.github.richeyworks.superbeefsort.external.SpillSerializer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PRE-REGISTERED EXPERIMENT (2026-08-20) — the in-memory bound, priced. SmokeHouse states its
 * Bitcask trade loudly ("all keys in memory") and ADR-008 D2 (B+ tree disk pages) has been held
 * since its ADR behind "a workload showing the in-memory bound is real" — but the bound itself
 * never had a number. This experiment prices it: retained heap per live key, measured across
 * store sizes, converted into how many keys a given heap actually carries.
 *
 * <p><b>Method:</b> build a store of n keys (fixed-shape values), GC-fence (3× System.gc + settle)
 * and read used-heap before and after; the delta over n is the per-key cost of the whole
 * in-memory apparatus (index entries, CSRBT nodes, bookkeeping). The <em>marginal</em> cost
 * between the two largest sizes cancels fixed overheads. Heap deltas are noisy, so sizes are
 * large, values identical in shape, and the verdict uses the marginal figure.</p>
 *
 * <p><b>Pre-registered decision rule:</b> D2 FIRES if the marginal cost exceeds 1 KB/key
 * (an 8 GB heap would then carry under ~8M keys — small enough to bite real workloads in this
 * ecosystem's own domain). Below that, D2 stays held, and its trigger becomes concrete:
 * fire when a target workload's key count approaches heap÷(measured B/key). The test asserts
 * structure and sanity bounds, never exact wall-clock or byte figures — the canonical numbers
 * live in {@code docs/EXPERIMENT-2026-08-20-memory-bound.md}.</p>
 */
class MemoryBoundExperimentTest {

    private static final int SMALL = 100_000;
    private static final int LARGE = 300_000;

    private static SmokeHouseOptions<Long, String> opts() {
        return SmokeHouseOptions.of(SpillSerializer.forLongs(), SpillSerializer.forStrings())
                .segmentBytes(1 << 20)
                .indexTier(SmokeHouseOptions.IndexTier.STATIC);
    }

    @Test
    void heapPerLiveKeyMeasuredAndSane(@TempDir Path root) throws IOException {
        long costSmall = measure(root.resolve("s"), SMALL);
        long costLarge = measure(root.resolve("l"), LARGE);
        double perKeySmall = (double) costSmall / SMALL;
        double perKeyLarge = (double) costLarge / LARGE;
        // Marginal cost between sizes cancels fixed overhead — the honest per-key figure.
        double marginal = (double) (costLarge - costSmall) / (LARGE - SMALL);
        double keysPer8G = (8L << 30) / marginal;

        System.out.println(String.format(Locale.ROOT,
                "%nMEMORY-BOUND EXPERIMENT (GC-fenced used-heap deltas)%n"
                + "  n=%,d    heap=%,dB  (%.0f B/key incl. fixed)%n"
                + "  n=%,d    heap=%,dB  (%.0f B/key incl. fixed)%n"
                + "  marginal cost      = %.0f B/key%n"
                + "  an 8 GB heap holds ≈ %,.0fM keys at that rate%n",
                SMALL, costSmall, perKeySmall, LARGE, costLarge, perKeyLarge,
                marginal, keysPer8G / 1e6));

        // Structure + sanity only; the verdict document carries the canonical figures.
        assertTrue(costLarge > costSmall, "more keys must retain more heap");
        assertTrue(marginal > 16, "a keyed index cannot cost less than a pointer-and-a-half");
        assertTrue(marginal < 4096, "and if a key ever costs 4KB of heap, the experiment is broken");
    }

    private long measure(Path dir, int n) throws IOException {
        long before = usedHeapAfterGc();
        SmokeHouse<Long, String> store = SmokeHouse.open(dir, opts());
        for (long k = 0; k < n; k++) {
            store.put(k, "v-0123456789-0123456789");           // fixed-shape value
        }
        assertEquals(n, store.size());
        long with = usedHeapAfterGc();
        long retained = Math.max(0, with - before);
        store.close();
        return retained;
    }

    private static long usedHeapAfterGc() {
        for (int i = 0; i < 3; i++) {
            System.gc();
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }
}
