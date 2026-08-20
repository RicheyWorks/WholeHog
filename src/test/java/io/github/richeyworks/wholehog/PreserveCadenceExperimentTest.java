package io.github.richeyworks.wholehog;

import io.github.richeyworks.dryage.DryAge;
import io.github.richeyworks.smokehouse.SmokeHouse;
import io.github.richeyworks.smokehouse.SmokeHouseOptions;
import io.github.richeyworks.superbeefsort.external.SpillSerializer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PRE-REGISTERED EXPERIMENT (2026-08-20) — the preserve cadence, measured. DryAge's
 * record-granularity as-of (a bounded-recovery stop condition cut into SmokeHouse) has been
 * held since the engine's birth behind "a consumer shows the generation granularity isn't
 * enough." No consumer can be manufactured honestly — but the WORKAROUND can be priced: if
 * time travel needs finer grain, the caller preserves more often. This experiment measures
 * what that costs, so the held seam has a number instead of a feeling.
 *
 * <p><b>Question:</b> over one 60k-op churn, what does checkpointing every N ops cost — in
 * preserve latency and in vault bytes — for N in {2k, 10k, 30k}?</p>
 *
 * <p><b>Pre-registered decision rule:</b> the workaround is VIABLE (seam stays held) if the
 * densest cadence keeps total preserve time under ~25% of the churn's own time and vault
 * growth is roughly linear in checkpoint count (backups are prefix copies — each checkpoint
 * re-copies the live prefix, so the vault should grow superlinearly ONLY if segment churn
 * outpaces retention; seeing which is the point of measuring). Verdict in
 * {@code docs/EXPERIMENT-2026-08-20-cold-triggers.md}; the test asserts structure, not
 * wall-clock.</p>
 */
class PreserveCadenceExperimentTest {

    private static final int OPS = 60_000;
    private static final int[] CADENCES = {2_000, 10_000, 30_000};

    private static SmokeHouseOptions<Long, String> opts() {
        return SmokeHouseOptions.of(SpillSerializer.forLongs(), SpillSerializer.forStrings())
                .segmentBytes(1 << 16)
                .indexTier(SmokeHouseOptions.IndexTier.STATIC);
    }

    @Test
    void thePriceOfCheckpointingOftener(@TempDir Path root) throws IOException {
        StringBuilder report = new StringBuilder("\nPRESERVE-CADENCE EXPERIMENT (one 60k-op churn per cadence)\n");
        for (int cadence : CADENCES) {
            Path storeDir = root.resolve("store-" + cadence);
            Path vaultDir = root.resolve("vault-" + cadence);
            Random rnd = new Random(7);
            try (SmokeHouse<Long, String> store = SmokeHouse.open(storeDir, opts())) {
                DryAge<Long, String> vault = DryAge.vault(vaultDir, opts());
                long churnNanos = 0;
                long preserveNanos = 0;
                int checkpoints = 0;
                for (int i = 1; i <= OPS; i++) {
                    long t0 = System.nanoTime();
                    long key = rnd.nextInt(5_000);
                    if (rnd.nextInt(6) == 0) {
                        store.delete(key);
                    } else {
                        store.put(key, "value-" + i + "-padding-padding");
                    }
                    churnNanos += System.nanoTime() - t0;
                    if (i % cadence == 0) {
                        t0 = System.nanoTime();
                        vault.preserve(store);
                        preserveNanos += System.nanoTime() - t0;
                        checkpoints++;
                    }
                }
                long vaultBytes = sizeOf(vaultDir);
                assertEquals(OPS / cadence, checkpoints, "every checkpoint taken");
                assertEquals(checkpoints, vault.generations().size(), "every checkpoint preserved");
                assertTrue(vaultBytes > 0);

                report.append(String.format(Locale.ROOT,
                        "  every %,d ops: %d checkpoints  churn=%dms  preserve=%dms "
                        + "(%.1f%% of churn, %.1fms/checkpoint)  vault=%,dB (%,dB/checkpoint)%n",
                        cadence, checkpoints, churnNanos / 1_000_000, preserveNanos / 1_000_000,
                        100.0 * preserveNanos / Math.max(1, churnNanos),
                        preserveNanos / 1e6 / Math.max(1, checkpoints),
                        vaultBytes, vaultBytes / Math.max(1, checkpoints)));
            }
        }
        System.out.println(report);
    }

    private static long sizeOf(Path dir) throws IOException {
        long[] total = {0};
        try (var walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                try {
                    total[0] += Files.size(p);
                } catch (IOException ignored) {
                    // sizing only
                }
            });
        }
        return total[0];
    }
}
