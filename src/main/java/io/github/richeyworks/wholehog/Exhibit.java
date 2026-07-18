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
            System.out.println("  11 engines attached: store+indexes, carver, renderer, "
                    + "brine, pitboss+replica, vault, twine, wire, watcher");

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
                System.out.println("  wire       size=" + wire.size()
                        + " countRange(100..200)=" + wire.countRange(100L, 200L));
            }
            System.out.println("  watcher    events=" + o.watchedEvents());
            System.out.println();
            System.out.println("  the log is the only truth; twelve engines kept it. done.");
        }
    }
}
