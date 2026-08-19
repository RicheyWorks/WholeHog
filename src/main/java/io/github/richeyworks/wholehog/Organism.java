package io.github.richeyworks.wholehog;

import io.github.richeyworks.brine.Brine;
import io.github.richeyworks.carver.Carver;
import io.github.richeyworks.dryage.DryAge;
import io.github.richeyworks.jerky.Jerky;
import io.github.richeyworks.pitboss.PitBoss;
import io.github.richeyworks.renderer.GroupView;
import io.github.richeyworks.renderer.Renderer;
import io.github.richeyworks.rub.Rub;
import io.github.richeyworks.rub.Vitals;
import io.github.richeyworks.sizzle.ChaosPlan;
import io.github.richeyworks.sizzle.Sizzle;
import io.github.richeyworks.smokehouse.IndexedStore;
import io.github.richeyworks.smokehouse.SmokeHouse;
import io.github.richeyworks.smokehouse.SmokeHouseOptions;
import io.github.richeyworks.smokesignal.SmokeSignalClient;
import io.github.richeyworks.smokesignal.SmokeSignalServer;
import io.github.richeyworks.superbeefsort.external.SpillSerializer;
import io.github.richeyworks.twine.Twine;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;

/**
 * WholeHog — engine twelve of the ecosystem: the integration organism. Eleven engines are
 * each oracle-tested in isolation; this class is where "in isolation" stops being the
 * qualifier. One store; every engine attached to it at once; one composed lifecycle.
 *
 * <p>The wiring, and the routing rules it enforces (each one a lesson from the engines'
 * own contracts):</p>
 * <ul>
 *   <li><b>Writes route through the {@link IndexedStore}</b> (secondary + interval indexes
 *       must see every mutation) — Twine ties over {@code indexed::put}/{@code indexed::delete}
 *       via the sink seam this engine named, never over the primary.</li>
 *   <li><b>The tail carries four consumers at once</b> — a Renderer view, Brine's
 *       invalidation, a replica's replication feed, and {@link Rub} (engine 13, the
 *       observability organ, the watcher promoted) — each on its own subscriber ring, none
 *       aware of the others. Proving they converge together is the four-subscriber test, the
 *       organism's reason to exist.</li>
 *   <li><b>Read paths stay read paths:</b> Carver plans over the indexed store; the wire
 *       serves reads (writes over the wire would bypass the indexes — the same rule Twine's
 *       seam solved, deliberately left unsolved for SmokeSignal and documented here).</li>
 *   <li><b>History flows sideways:</b> DryAge preserves generations off the same store;
 *       Jerky cures them for cold storage.</li>
 *   <li><b>The write path is chaos-testable:</b> Twine ties not straight over the indexed
 *       store but over a {@link Sizzle} (engine 14) seam wrapping it. With the default
 *       {@link ChaosPlan#none()} the seam is transparent; hand a fault plan to the chaos
 *       constructor and the organism's own crash-atomicity answers for itself
 *       (see the chaos test).</li>
 * </ul>
 *
 * <p>Values are {@code "aaa:sssss:eeeee"} strings — attribute, span start, span end — so one
 * record feeds the secondary, the interval index, Renderer's grouping, and Brine's cache all
 * at once. Keys are {@code Long}. Everything seeded, deterministic up to bounded awaits.</p>
 */
public final class Organism implements Closeable {

    public static final String ATTR = "attr";
    public static final String SPAN = "span";

    private final IndexedStore<Long, String> indexed;
    private final Carver<Long, String> carver;
    private final Renderer<Long, String> renderer;
    private final GroupView<Long, String, Integer> byAttr;
    private final Brine<Long, String> brine;
    private final PitBoss<Long, String> pitBoss;
    private final DryAge<Long, String> vault;
    private final Sizzle<Long, String> writeChaos;
    private final Twine<Long, String> twine;
    private final SmokeSignalServer<Long, String> wireServer;
    private final Rub<Long, String> rub;

    public static int attrOf(String v) {
        return Integer.parseInt(v.substring(0, 3));
    }

    public static int startOf(String v) {
        return Integer.parseInt(v.substring(4, 9));
    }

    public static int endOf(String v) {
        return Integer.parseInt(v.substring(10, 15));
    }

    public static String value(int attr, int start, int end) {
        return String.format("%03d:%05d:%05d", attr, start, end);
    }

    private static SmokeHouseOptions<Long, String> options() {
        return SmokeHouseOptions.of(SpillSerializer.forLongs(), SpillSerializer.forStrings())
                .segmentBytes(4096)
                .indexTier(SmokeHouseOptions.IndexTier.STATIC);   // deterministic organism
    }

    /** Stand the whole organism up under {@code root}: one store, every engine attached. */
    public Organism(Path root, long seed) throws IOException {
        this(root, seed, ChaosPlan.none());
    }

    /**
     * Stand the organism up with a {@link ChaosPlan} on the write path — {@link Sizzle} wraps
     * the indexed store's put/delete sinks, and Twine ties over the wrapped sinks. With
     * {@link ChaosPlan#none()} the seam is transparent and this is exactly the plain organism;
     * with a fault plan, a batch can crash mid-apply and the organism's crash-atomicity (Twine's
     * journal + idempotent replay, re-driving every index) is what has to bring it back.
     */
    public Organism(Path root, long seed, ChaosPlan writeChaos) throws IOException {
        Objects.requireNonNull(writeChaos, "writeChaos");
        Files.createDirectories(root);
        this.indexed = IndexedStore.open(root.resolve("store"), options())
                .secondary(ATTR, Comparator.<Integer>naturalOrder(), Organism::attrOf)
                .interval(SPAN, Organism::startOf, Organism::endOf)
                .build();
        SmokeHouse<Long, String> primary = indexed.primary();

        this.carver = Carver.over(indexed);
        this.renderer = Renderer.over(primary);
        this.byAttr = renderer.countBy("byAttr", Organism::attrOf,
                Comparator.<Integer>naturalOrder());
        this.brine = Brine.over(primary, 64, 512, seed);
        this.pitBoss = PitBoss.over(primary, options(), true);
        this.pitBoss.addReplica("exhibit", root.resolve("replica"));
        this.vault = DryAge.vault(root.resolve("vault"), options());
        // Writes route through the indexes (never the primary), now via a Sizzle chaos seam so
        // the composed write path is fault-injectable end to end. Transparent under none().
        this.writeChaos = Sizzle.inject(indexed::put, indexed::delete, writeChaos);
        this.twine = Twine.over(this.writeChaos.puts(), this.writeChaos.deletes(),
                root.resolve("journal"),
                SpillSerializer.forLongs(), SpillSerializer.forStrings());
        this.wireServer = SmokeSignalServer.serve(primary,
                SpillSerializer.forLongs(), SpillSerializer.forStrings());
        // The fourth tail subscriber, promoted from a bare counter to the observability organ.
        this.rub = Rub.over(primary);
    }

    // ── The organs, exposed for tests and the exhibit ────────────────────────────

    public IndexedStore<Long, String> store() {
        return indexed;
    }

    public SmokeHouse<Long, String> primary() {
        return indexed.primary();
    }

    public Carver<Long, String> carver() {
        return carver;
    }

    public Renderer<Long, String> renderer() {
        return renderer;
    }

    public GroupView<Long, String, Integer> byAttr() {
        return byAttr;
    }

    public Brine<Long, String> brine() {
        return brine;
    }

    public PitBoss<Long, String> pitBoss() {
        return pitBoss;
    }

    public DryAge<Long, String> vault() {
        return vault;
    }

    public Twine<Long, String> twine() {
        return twine;
    }

    public int wirePort() {
        return wireServer.port();
    }

    /** The wire's own server-side traffic counters — observability reaching the socket. */
    public SmokeSignalServer.WireStats wireStats() {
        return wireServer.stats();
    }

    /** A fresh read-only wire into the organism (caller closes it). */
    public SmokeSignalClient<Long, String> wire() throws IOException {
        return SmokeSignalClient.connect(wireServer.port(),
                SpillSerializer.forLongs(), SpillSerializer.forStrings());
    }

    /** The observability organ — tail-driven counters fused with the store gauge into vitals. */
    public Rub<Long, String> rub() {
        return rub;
    }

    /** A fresh vitals reading of the whole organism's store — the exhibit's one-line pulse. */
    public Vitals vitals() throws IOException {
        return rub.sample();
    }

    /** Mutations Rub has metered off the tail since standup — the promoted watcher's count. */
    public long watchedEvents() {
        return rub.mutationsObserved();
    }

    /** Faults the write-path chaos seam has injected — zero under the default {@code none()} plan. */
    public long chaosCrashes() {
        return writeChaos.crashesInjected();
    }

    /**
     * Preserve the current moment and cure it for cold storage — DryAge and Jerky in one
     * motion. Returns the generation; the {@code .jerky} lands beside the vault.
     */
    public long preserveAndCure(Path archiveDir) throws IOException {
        long generation = vault.preserve(primary());
        Files.createDirectories(archiveDir);
        Path genDir = archiveDir.resolve("staging-" + generation);
        // Cure straight from a fresh view's scratch? No — cure the vault generation itself
        // via an AgedView copy so the vault stays pristine and the archive is verified bytes.
        try (DryAge.AgedView<Long, String> view = vault.asOf(generation)) {
            // The view's scratch dir is private; cure from a fresh backup of the view's store.
            Files.createDirectories(genDir);
            view.store().backup(genDir);
            Jerky.Cured cured = Jerky.cure(genDir,
                    archiveDir.resolve("gen-" + generation + ".jerky"));
            if (!Jerky.verify(cured.archive())) {
                throw new IOException("fresh archive failed verification: " + cured.archive());
            }
        } finally {
            if (Files.exists(genDir)) {
                try (var walk = Files.walk(genDir)) {
                    for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(p);
                    }
                }
            }
        }
        return generation;
    }

    /** Await every tail consumer catching up to the primary's current sequence. */
    public boolean awaitQuiescence(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        long target = primary().tailSequence();
        boolean views = renderer.awaitCaughtUp(Math.max(1, deadline - System.currentTimeMillis()));
        boolean replica = pitBoss.replica("exhibit")
                .awaitCaughtUp(target, Math.max(1, deadline - System.currentTimeMillis()));
        return views && replica;
    }

    /** Tear down in reverse dependency order. The store closes last; its dir persists. */
    @Override
    public void close() throws IOException {
        rub.close();                                           // detaches the tail observer only
        wireServer.close();
        brine.close();
        renderer.close();
        pitBoss.close();
        indexed.close();
    }
}
