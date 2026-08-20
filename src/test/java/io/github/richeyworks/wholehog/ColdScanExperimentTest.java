package io.github.richeyworks.wholehog;

import io.github.richeyworks.jerky.Jerky;
import io.github.richeyworks.smokehouse.SmokeHouse;
import io.github.richeyworks.smokehouse.SmokeHouseOptions;
import io.github.richeyworks.superbeefsort.external.SpillSerializer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PRE-REGISTERED EXPERIMENT (2026-08-20) — the cold-scan cost, measured. Jerky's columnar
 * cold format has been held since 2026-07-18 behind one trigger: "a benchmark showing
 * cold-segment scans as a real cost." This is that benchmark, in the composed organism's
 * own repo, ADR-022 methodology (seeded, warmup discarded, median of 3).
 *
 * <p><b>Question:</b> to answer one scan over a cold {@code .jerky} archive, what does the
 * current route cost — inflate the archive, recover it as a SmokeHouse, range-scan — against
 * the floor of just reading the archive's bytes once (which is the least ANY scan-friendly
 * format could do)?</p>
 *
 * <p><b>Pre-registered decision rule:</b> the columnar trigger fires if scan-via-restore
 * costs more than 5× the raw-read floor at the larger size — below that, the deferral holds,
 * now with its number. The verdict from the canonical run lives in
 * {@code docs/EXPERIMENT-2026-08-20-cold-triggers.md}; this test asserts only structural
 * truths (round-trip fidelity, all phases measured), never wall-clock, so it cannot flake.</p>
 */
class ColdScanExperimentTest {

    private static final int[] SIZES = {20_000, 60_000};
    private static final int ROUNDS = 4;                       // 1 warmup + median of 3

    private static SmokeHouseOptions<Long, String> opts() {
        return SmokeHouseOptions.of(SpillSerializer.forLongs(), SpillSerializer.forStrings())
                .segmentBytes(1 << 16)
                .indexTier(SmokeHouseOptions.IndexTier.STATIC);
    }

    @Test
    void coldScanVersusTheRawReadFloor(@TempDir Path root) throws IOException {
        StringBuilder report = new StringBuilder("\nCOLD-SCAN EXPERIMENT (median of 3, 1 warmup discarded)\n");
        for (int n : SIZES) {
            // Build the cold generation once: n records, backed up, cured.
            Path storeDir = root.resolve("store-" + n);
            Path backupDir = root.resolve("backup-" + n);
            Path archive = root.resolve("gen-" + n + ".jerky");
            Random rnd = new Random(42);
            try (SmokeHouse<Long, String> store = SmokeHouse.open(storeDir, opts())) {
                for (long k = 0; k < n; k++) {
                    store.put(k, "value-" + rnd.nextInt(1_000_000) + "-padding-padding-padding");
                }
                Files.createDirectories(backupDir);
                store.backup(backupDir);
            }
            Jerky.Cured cured = Jerky.cure(backupDir, archive);

            long[] raw = new long[ROUNDS];
            long[] inflate = new long[ROUNDS];
            long[] recover = new long[ROUNDS];
            long[] scan = new long[ROUNDS];
            long scanned = -1;
            for (int r = 0; r < ROUNDS; r++) {
                // Phase 0 — the floor: one pass over the archive's bytes.
                long t0 = System.nanoTime();
                byte[] bytes = Files.readAllBytes(archive);
                raw[r] = System.nanoTime() - t0;
                assertTrue(bytes.length > 0);

                // Phase 1 — inflate the archive into a directory.
                Path restored = root.resolve("restored-" + n + "-" + r);
                t0 = System.nanoTime();
                Jerky.restore(archive, restored);
                inflate[r] = System.nanoTime() - t0;

                // Phase 2 — recover it as a store.
                t0 = System.nanoTime();
                SmokeHouse<Long, String> cold = SmokeHouse.restore(restored, opts());
                recover[r] = System.nanoTime() - t0;

                // Phase 3 — the scan itself.
                AtomicLong count = new AtomicLong();
                t0 = System.nanoTime();
                cold.range(cold.firstKey(), cold.lastKey(), (k, v) -> count.incrementAndGet());
                scan[r] = System.nanoTime() - t0;
                cold.close();
                scanned = count.get();

                deleteRecursively(restored);
            }
            assertEquals(n, scanned, "the cold scan reads every record, every round");

            long rawMs = medianMs(raw);
            long inflateMs = medianMs(inflate);
            long recoverMs = medianMs(recover);
            long scanMs = medianMs(scan);
            long routeMs = inflateMs + recoverMs + scanMs;
            double ratio = rawMs == 0 ? Double.POSITIVE_INFINITY : (double) routeMs / rawMs;
            report.append(String.format(Locale.ROOT,
                    "  n=%,d  archive=%,dB (%.2f of raw)  floor(raw read)=%dms  "
                    + "inflate=%dms  recover=%dms  scan=%dms  route=%dms  route/floor=%.1fx%n",
                    n, cured.curedBytes(), cured.ratio(), rawMs, inflateMs, recoverMs, scanMs,
                    routeMs, ratio));
        }
        System.out.println(report);
    }

    private static long medianMs(long[] rounds) {
        // Discard round 0 (warmup), median the rest — ADR-022 discipline.
        long[] timed = new long[rounds.length - 1];
        System.arraycopy(rounds, 1, timed, 0, timed.length);
        java.util.Arrays.sort(timed);
        return timed[timed.length / 2] / 1_000_000;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
