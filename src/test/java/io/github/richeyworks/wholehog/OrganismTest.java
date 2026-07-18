package io.github.richeyworks.wholehog;

import io.github.richeyworks.dryage.DryAge;
import io.github.richeyworks.smokehouse.SmokeHouse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The composed oracle: one churn stream drives the whole organism through Twine batches and
 * direct indexed writes, and then EVERY engine's answer is checked against one {@code TreeMap}
 * — the store scan, Carver's plans, Renderer's folds, Brine's reads, the replica's contents,
 * the wire's replies, the vault's past, and the archive's round trip. The four-subscriber
 * tail test is the headline: Renderer + Brine + replication + a watcher all converge on the
 * same churn, none aware of the others.
 */
class OrganismTest {

    private static final long AWAIT = 15_000;

    private static void churn(Organism o, TreeMap<Long, String> oracle, Random rnd, int ops)
            throws IOException {
        for (int i = 0; i < ops; i++) {
            if (rnd.nextInt(10) == 0) {                        // every tenth op: a Twine batch
                var batch = o.twine().batch();
                for (int b = 0; b < 3; b++) {
                    long key = rnd.nextInt(150);
                    String v = Organism.value(rnd.nextInt(8), rnd.nextInt(10_000),
                            rnd.nextInt(10_000) + 10_000);
                    batch.put(key, v);
                    oracle.put(key, v);
                }
                batch.commit();
            } else if (rnd.nextInt(6) == 0) {
                long key = rnd.nextInt(150);
                o.store().delete(key);
                oracle.remove(key);
            } else {
                long key = rnd.nextInt(150);
                String v = Organism.value(rnd.nextInt(8), rnd.nextInt(10_000),
                        rnd.nextInt(10_000) + 10_000);
                o.store().put(key, v);
                oracle.put(key, v);
            }
        }
    }

    private static TreeMap<Long, String> scan(SmokeHouse<Long, String> store)
            throws IOException {
        TreeMap<Long, String> out = new TreeMap<>();
        if (store.size() > 0) {
            store.range(store.firstKey(), store.lastKey(), out::put);
        }
        return out;
    }

    @Test
    void fourTailSubscribersConvergeOnOneChurn(@TempDir Path root) throws IOException {
        Random rnd = new Random(42);
        TreeMap<Long, String> oracle = new TreeMap<>();
        try (Organism o = new Organism(root, 42)) {
            churn(o, oracle, rnd, 600);
            assertTrue(o.awaitQuiescence(AWAIT), "renderer + replica must both catch the tail");

            // Subscriber 1 — the Renderer view equals a brute-force fold.
            TreeMap<Integer, Long> counts = new TreeMap<>();
            for (String v : oracle.values()) {
                counts.merge(Organism.attrOf(v), 1L, Long::sum);
            }
            assertEquals(counts.size(), o.byAttr().groups(), "renderer group count");
            for (Map.Entry<Integer, Long> e : counts.entrySet()) {
                assertEquals((long) e.getValue(), o.byAttr().total(e.getKey()),
                        "renderer count(" + e.getKey() + ")");
            }

            // Subscriber 2 — the replica equals the oracle exactly.
            assertEquals(oracle, scan(o.pitBoss().replica("exhibit").store()),
                    "replica contents");
            assertEquals(0, o.pitBoss().replica("exhibit").lagSequence());

            // Subscriber 3 — Brine reads equal the oracle once invalidation drains (its tail
            // subscriber is independent of the two awaitQuiescence covers — bounded poll).
            long deadline = System.currentTimeMillis() + AWAIT;
            for (long key = 0; key < 150; key++) {
                String want = oracle.get(key);
                String got = o.brine().get(key);
                while (!java.util.Objects.equals(want, got)
                        && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(2);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    got = o.brine().get(key);
                }
                assertEquals(want, got, "brine get(" + key + ")");
            }

            // Subscriber 4 — the watcher saw every committed mutation.
            assertTrue(o.watchedEvents() >= oracle.size(),
                    "watcher must have seen at least every live record's landing");

            // And the store itself, the truth they all derive from.
            assertEquals(oracle, scan(o.primary()), "the primary is the truth");
        }
    }

    @Test
    void carverPlansOverTheLiveOrganismMatchBruteForce(@TempDir Path root) throws IOException {
        Random rnd = new Random(7);
        TreeMap<Long, String> oracle = new TreeMap<>();
        try (Organism o = new Organism(root, 7)) {
            churn(o, oracle, rnd, 400);
            long kLo = 20, kHi = 120;
            int aLo = 2, aHi = 5, sLo = 3_000, sHi = 12_000;

            List<Long> expected = new ArrayList<>();
            for (Map.Entry<Long, String> e : oracle.subMap(kLo, true, kHi, true).entrySet()) {
                String v = e.getValue();
                int a = Organism.attrOf(v);
                boolean overlaps = Organism.startOf(v) <= sHi && Organism.endOf(v) >= sLo;
                if (a >= aLo && a <= aHi && overlaps) {
                    expected.add(e.getKey());
                }
            }
            List<Long> actual = new ArrayList<>(o.carver().query()
                    .keysBetween(kLo, kHi)
                    .whereBetween(Organism.ATTR, aLo, aHi)
                    .overlapping(Organism.SPAN, sLo, sHi)
                    .keys());
            actual.sort(null);
            assertEquals(expected, actual, "carver over the live organism");
        }
    }

    @Test
    void theWireAndTheStoreAgree(@TempDir Path root) throws IOException {
        Random rnd = new Random(11);
        TreeMap<Long, String> oracle = new TreeMap<>();
        try (Organism o = new Organism(root, 11)) {
            churn(o, oracle, rnd, 300);
            try (var wire = o.wire()) {
                assertEquals(oracle.size(), wire.size(), "size over the wire");
                for (long key = 0; key < 150; key += 7) {
                    assertEquals(oracle.get(key), wire.get(key), "wire get(" + key + ")");
                }
                assertEquals(oracle.subMap(30L, true, 110L, true).size(),
                        wire.countRange(30L, 110L), "order statistics over the wire");
            }
        }
    }

    @Test
    void historyIsPreservedCuredAndStillTrue(@TempDir Path root, @TempDir Path archiveDir,
                                             @TempDir Path restoreDir) throws IOException {
        Random rnd = new Random(3);
        TreeMap<Long, String> oracle = new TreeMap<>();
        try (Organism o = new Organism(root, 3)) {
            churn(o, oracle, rnd, 300);
            TreeMap<Long, String> moment = new TreeMap<>(oracle);
            long generation = o.preserveAndCure(archiveDir);

            churn(o, oracle, rnd, 300);                        // life goes on

            try (DryAge.AgedView<Long, String> past = o.vault().asOf(generation)) {
                assertEquals(moment, scan(past.store()), "the vault's past is the moment");
            }
            Path archive = archiveDir.resolve("gen-" + generation + ".jerky");
            io.github.richeyworks.jerky.Jerky.restore(archive, restoreDir.resolve("revived"));
            try (SmokeHouse<Long, String> revived = SmokeHouse.restore(
                    restoreDir.resolve("revived"),
                    io.github.richeyworks.smokehouse.SmokeHouseOptions.of(
                            io.github.richeyworks.superbeefsort.external.SpillSerializer.forLongs(),
                            io.github.richeyworks.superbeefsort.external.SpillSerializer.forStrings()))) {
                assertEquals(moment, scan(revived), "cured, restored, still the moment");
            }
        }
    }

    @Test
    void theFleetSurvivesARebootstrapMidChurn(@TempDir Path root) throws IOException {
        Random rnd = new Random(99);
        TreeMap<Long, String> oracle = new TreeMap<>();
        try (Organism o = new Organism(root, 99)) {
            churn(o, oracle, rnd, 300);
            o.pitBoss().rebootstrap("exhibit");                // cold start, mid-life
            churn(o, oracle, rnd, 300);
            assertTrue(o.awaitQuiescence(AWAIT));
            assertEquals(oracle, scan(o.pitBoss().replica("exhibit").store()),
                    "the reborn replica rejoins the organism's truth");
        }
    }
}
