package io.github.richeyworks.wholehog;

import io.github.richeyworks.dryage.DryAge;
import io.github.richeyworks.sizzle.ChaosPlan;
import io.github.richeyworks.sizzle.Sizzle;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
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

            // The sidecar (ADR 2026-08-20): the archive scans in key order, no resurrection.
            TreeMap<Long, String> viaColdScan = new TreeMap<>();
            assertEquals(moment.size(), Organism.coldScan(archive, viaColdScan::put));
            assertEquals(moment, viaColdScan, "coldScan reads the preserved moment exactly");

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

    /**
     * Subscriber four, named: Rub is the observability organ on the same tail. Over one churn it
     * must meter exactly the mutations the store committed, and its gauge must equal the store's
     * own size — the composed organism proving its watcher is wired, not just present.
     */
    @Test
    void theObservabilityOrganMetersTheComposedChurn(@TempDir Path root) throws IOException {
        Random rnd = new Random(21);
        TreeMap<Long, String> oracle = new TreeMap<>();
        try (Organism o = new Organism(root, 21)) {
            long before = o.primary().tailSequence();
            churn(o, oracle, rnd, 500);
            long committed = o.primary().tailSequence() - before;

            assertTrue(o.rub().awaitObserved(committed, AWAIT),
                    "Rub's tail feed must catch every committed mutation of the organism");
            assertEquals(committed, o.rub().mutationsObserved(), "metered = committed");
            assertEquals(oracle.size(), o.vitals().liveKeys(), "the gauge equals the live set");
            assertTrue(o.vitals().gapFree(), "the organism's churn must not overrun Rub's ring");
        }
    }

    /**
     * Ledger #2, resolved and proven: a write arriving OVER THE WIRE routes through the index
     * fan-out, so it reaches every subscriber — the secondary index Carver plans over, the
     * Renderer fold, the replica, and the oracle's own scan. Before the WriteRoute seam this
     * write would have landed on the primary alone and silently corrupted every secondary.
     */
    @Test
    void aWireWriteReachesEverySubscriber(@TempDir Path root) throws IOException {
        Random rnd = new Random(17);
        TreeMap<Long, String> oracle = new TreeMap<>();
        try (Organism o = new Organism(root, 17)) {
            churn(o, oracle, rnd, 200);                        // local traffic first

            try (var wire = o.wire()) {                        // then a WIRE client writes
                String v1 = Organism.value(7, 1_000, 2_000);
                String v2 = Organism.value(7, 3_000, 4_000);
                wire.put(900L, v1);
                wire.put(901L, v2);
                oracle.put(900L, v1);
                oracle.put(901L, v2);
                wire.delete(902L);                             // delete-of-absent: no-op false
                if (oracle.containsKey(0L)) {                  // and a real delete over the wire
                    wire.delete(0L);
                    oracle.remove(0L);
                }
            }
            assertTrue(o.awaitQuiescence(AWAIT), "views + replica catch the wire's writes");

            // Carver plans over the secondary index — the wire's attr-7 keys must be planned.
            List<Long> planned = new ArrayList<>(o.carver().query()
                    .keysBetween(900L, 901L).whereBetween(Organism.ATTR, 7, 7).keys());
            planned.sort(null);
            assertEquals(List.of(900L, 901L), planned,
                    "the secondary index saw the wire's writes");

            // The Renderer fold counted them.
            long attr7 = 0;
            for (String v : oracle.values()) {
                if (Organism.attrOf(v) == 7) attr7++;
            }
            assertEquals(attr7, o.byAttr().total(7), "the materialized view saw them");

            // The replica replicated them, and the store equals the oracle.
            assertEquals(oracle, scan(o.pitBoss().replica("exhibit").store()), "replica");
            assertEquals(oracle, scan(o.primary()), "the primary is the truth");
        }
    }

    /**
     * Engines 9 and 10, composed (2026-08-19): a BATCH staged by a wire client crosses the
     * socket as one request, lands through Twine — journaled, crash-atomic, index-fanned —
     * and every downstream subscriber converges on its net effect. A wire client gets the
     * organism's atomicity without knowing Twine exists.
     */
    @Test
    void aWireBatchLandsAtomicallyAndFansOut(@TempDir Path root) throws IOException {
        Random rnd = new Random(23);
        TreeMap<Long, String> oracle = new TreeMap<>();
        try (Organism o = new Organism(root, 23)) {
            churn(o, oracle, rnd, 200);

            String v1 = Organism.value(6, 100, 200);
            String v2 = Organism.value(6, 300, 400);
            try (var wire = o.wire()) {
                int applied = wire.batch()
                        .put(950L, v1)
                        .put(951L, v2)
                        .put(950L, v1)                          // overwrite inside the batch
                        .delete(951L)                           // and a delete of a batch-mate
                        .commit();
                assertEquals(4, applied, "the whole batch, applied");
            }
            oracle.put(950L, v1);                              // net effect: 950 present, 951 gone
            oracle.remove(951L);

            assertTrue(o.awaitQuiescence(AWAIT), "views + replica catch the wire batch");
            assertEquals(oracle, scan(o.primary()), "the net effect, exactly once");
            List<Long> planned = new ArrayList<>(o.carver().query()
                    .keysBetween(950L, 951L).whereBetween(Organism.ATTR, 6, 6).keys());
            assertEquals(List.of(950L), planned, "the secondary index saw the batch's net effect");
            assertEquals(oracle, scan(o.pitBoss().replica("exhibit").store()), "so did the replica");
        }
    }

    /**
     * Teardown is idempotent: an organism inside nested try-with-resources gets buried twice,
     * and the second burial must be a no-op — not a double-close cascade through eleven engines.
     */
    @Test
    void closingTwiceIsANoop(@TempDir Path root) throws IOException {
        Organism o = new Organism(root, 13);
        o.store().put(1L, Organism.value(1, 0, 100));
        o.close();
        o.close();                                             // must not throw
    }

    /**
     * Engine 14 against the composed organism: a Twine batch crashes mid-apply on the write path,
     * and after the organism is reopened, Twine's journal replay must have re-driven EVERY index
     * — the primary, the secondary/interval indexes Carver plans over, and the Renderer fold —
     * so the whole organism, not merely the store, recovered the batch exactly once.
     */
    @Test
    void theOrganismSurvivesAChaosBatchOnItsWritePath(@TempDir Path root) throws IOException {
        // Five puts; the third op crashes mid-apply.
        try (Organism o = new Organism(root, 5, ChaosPlan.crashOnceAtOp(3))) {
            var batch = o.twine().batch();
            for (int k = 0; k < 5; k++) {
                batch.put((long) k, Organism.value(k + 1, 100 * k, 100 * k + 500));
            }
            assertThrows(Sizzle.Crash.class, batch::commit, "the fault surfaces out of commit");
            assertEquals(1, o.chaosCrashes(), "exactly one injected fault");
        }

        // Reopen with a transparent plan: construction replays the committed journal into every index.
        TreeMap<Long, String> expected = new TreeMap<>();
        for (int k = 0; k < 5; k++) {
            expected.put((long) k, Organism.value(k + 1, 100 * k, 100 * k + 500));
        }
        try (Organism revived = new Organism(root, 5)) {
            assertTrue(revived.awaitQuiescence(AWAIT), "views + replica catch the replayed batch");

            // The store recovered the batch exactly once.
            assertEquals(expected, scan(revived.primary()), "store recovered the batch exactly once");

            // The indexes recovered too — Carver plans over them and must see all five keys.
            List<Long> planned = new ArrayList<>(revived.carver().query()
                    .keysBetween(0L, 4L).keys());
            planned.sort(null);
            assertEquals(new ArrayList<>(expected.keySet()), planned,
                    "the secondary/interval indexes replayed with the store");

            // The Renderer fold recovered too — five distinct attrs, one key each.
            assertEquals(5, revived.byAttr().groups(), "the materialized view replayed with the store");
        }
    }
}
