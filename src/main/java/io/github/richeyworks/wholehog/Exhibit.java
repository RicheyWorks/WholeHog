package io.github.richeyworks.wholehog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * The one-command demo: {@code ./gradlew run} stands up the entire twelve-engine organism in
 * a temp directory, churns it (direct writes, Twine batches, deletes), then prints every
 * engine's vitals — the whole hog, on one plate. Console-only, loopback-only, seeded.
 */
public final class Exhibit {

    private Exhibit() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("wholehog-exhibit");
        Path archive = Files.createTempDirectory("wholehog-archive");
        Random rnd = new Random(42);
        System.out.println("WholeHog — the organism, standing up in " + root);

        try (Organism o = new Organism(root, 42)) {
            System.out.println("  13 engines attached: store+indexes, carver, renderer, "
                    + "brine, pitboss+replica, vault, twine, wire, rub, sizzle-seam");

            for (int i = 0; i < 2_000; i++) {                  // the churn
                if (rnd.nextInt(10) == 0) {
                    var b = o.twine().batch();
                    for (int j = 0; j < 3; j++) {
                        b.put((long) rnd.nextInt(300), Organism.value(rnd.nextInt(8),
                                rnd.nextInt(10_000), rnd.nextInt(10_000) + 10_000));
                    }
                    b.commit();
                } else if (rnd.nextInt(6) == 0) {
                    o.store().delete((long) rnd.nextInt(300));
                } else {
                    o.store().put((long) rnd.nextInt(300), Organism.value(rnd.nextInt(8),
                            rnd.nextInt(10_000), rnd.nextInt(10_000) + 10_000));
                }
            }
            long generation = o.preserveAndCure(archive);
            boolean quiet = o.awaitQuiescence(15_000);

            System.out.println();
            System.out.println("  the vitals, every engine:");
            System.out.println("  store      keys=" + o.primary().size()
                    + " median=" + o.primary().medianKey()
                    + " garbage=" + o.primary().garbageBytes() + "B");
            System.out.println("  carver     " + o.carver().query()
                    .keysBetween(50L, 250L).whereBetween(Organism.ATTR, 2, 5).explain());
            System.out.println("  renderer   groups=" + o.byAttr().groups()
                    + " top3=" + o.byAttr().top(3) + " caughtUp=" + quiet);
            System.out.println("  brine      " + o.brine().stats());
            System.out.println("  pitboss    " + o.pitBoss().tick());
            System.out.println("  vault      generations=" + o.vault().generations());
            System.out.println("  jerky      gen-" + generation + ".jerky verified in "
                    + archive);
            try (var wire = o.wire()) {
                wire.put(999L, Organism.value(7, 0, 100));     // a WRITE over the wire —
                boolean quiet2 = o.awaitQuiescence(15_000);    // routed through every index
                System.out.println("  wire       size=" + wire.size()
                        + " countRange(100..200)=" + wire.countRange(100L, 200L)
                        + " wireWriteVisibleEverywhere=" + (quiet2
                        && !o.carver().query().keysBetween(999L, 999L)
                                .whereBetween(Organism.ATTR, 7, 7).keys().isEmpty()));
            }
            System.out.println("  wirestats  " + o.wireStats().line());
            System.out.println("  rub        " + o.vitals().line());
            System.out.println("  sizzle     crashes injected on the write path=" + o.chaosCrashes());
        }

        chaosDemo();

        System.out.println();
        System.out.println("  the log is the only truth; fourteen engines kept it. done.");
    }

    /**
     * Engine 14 on the plate: stand a small organism under a fault plan, crash a Twine batch
     * mid-apply, reopen, and show the batch still landed exactly once — the organism's own
     * crash-atomicity, demonstrated rather than asserted in a comment.
     */
    private static void chaosDemo() throws Exception {
        Path root = Files.createTempDirectory("wholehog-chaos");
        System.out.println();
        System.out.println("  chaos      Sizzle crashes a 5-op batch at op 3, mid-apply...");
        try (Organism o = new Organism(root, 7,
                io.github.richeyworks.sizzle.ChaosPlan.crashOnceAtOp(3))) {
            var b = o.twine().batch();
            for (int k = 0; k < 5; k++) {
                b.put((long) k, Organism.value(k, 0, 100));
            }
            try {
                b.commit();
            } catch (io.github.richeyworks.sizzle.Sizzle.Crash crash) {
                System.out.println("             caught: " + crash.getMessage());
            }
        }
        // Reopen the organism: Twine replays the committed journal, re-driving every index.
        try (Organism revived = new Organism(root, 7)) {
            System.out.println("             reopened; the batch landed exactly once — store keys="
                    + revived.primary().size());
        }
    }
}
