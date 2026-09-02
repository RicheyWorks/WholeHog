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
 *   <li><b>Every write path keeps the routing rule:</b> Carver plans over the indexed store,
 *       and the wire's writes route through the index fan-out via SmokeSignal's
 *       {@code WriteRoute} seam (2026-08-19 — the rule Twine's seam solved first, finally
 *       solved for the wire; ledger #2's "deliberately unsolved" is resolved). A wire client
 *       is now a first-class writer: its puts reach every secondary, every view, every
 *       replica, every cache invalidation.</li>
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
    private final long replicaLagMillis;
    private final Twine<Long, String> twine;
    private final SmokeSignalServer<Long, String> wireServer;
    private final Rub<Long, String> rub;
    private final Rub<Long, String> replicaRub;
    private volatile boolean closed;

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
        this(root, seed, writeChaos, 0);
    }

    /**
     * As {@link #Organism(Path, long, ChaosPlan)}, with the replication feed held back by
     * {@code replicaLagMillis} per event — {@link Sizzle#slow} on PitBoss's feed seam, the
     * honest way to put the replica behind the primary (2026-09-02; ADR-113 had held this as
     * "not cut for a harness"). With 0 the seam is transparent. Frames still arrive in order and
     * none are dropped: the replica is late, never wrong.
     */
    public Organism(Path root, long seed, ChaosPlan writeChaos, long replicaLagMillis) throws IOException {
        Objects.requireNonNull(writeChaos, "writeChaos");
        if (replicaLagMillis < 0) {
            throw new IllegalArgumentException("replicaLagMillis must be >= 0: " + replicaLagMillis);
        }
        this.replicaLagMillis = replicaLagMillis;
        Files.createDirectories(root);
        // Eleventh-pass W-1: the organism opens a dozen live resources — a store with its tail
        // thread, two tail-subscribing views/caches, a replication server and a replica, a
        // journal-dir lock, a wire server socket, two observers. If any step below throws, the
        // constructor completes abruptly and the caller gets no reference to close what already
        // opened — so a partial build used to leak every earlier resource (a bound server socket,
        // a live replica, tail subscriptions folding forever). Build under a guard that unwinds
        // what opened, in reverse, on any failure — the same "leave no dangling resource on the
        // error path" discipline the leaf engines keep (DryAge D1/D2, Jerky, Renderer R-1).
        boolean built = false;
        try {
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
        this.pitBoss = PitBoss.over(primary, options(), true,
                replicaLagMillis > 0 ? l -> Sizzle.slow(l, replicaLagMillis) : l -> l);
        this.pitBoss.addReplica("exhibit", root.resolve("replica"));
        this.vault = DryAge.vault(root.resolve("vault"), options());
        // Writes route through the indexes (never the primary), now via a Sizzle chaos seam so
        // the composed write path is fault-injectable end to end. Transparent under none().
        this.writeChaos = Sizzle.inject(indexed::put, indexed::delete, writeChaos);
        this.twine = Twine.over(this.writeChaos.puts(), this.writeChaos.deletes(),
                root.resolve("journal"),
                SpillSerializer.forLongs(), SpillSerializer.forStrings());
        // The wire, with its writes ROUTED (2026-08-19, ledger #2 resolved): reads answer from
        // the primary's read surface, writes land through the IndexedStore's fan-out — the
        // same routing rule Twine keeps, now kept by SmokeSignal's WriteRoute seam. Before
        // this seam, the wire was read-only in the organism by decree; now it is writable by
        // construction.
        // ...and wire BATCHes land whole through Twine (2026-08-19): any wire client gets the
        // organism's crash-atomic multi-key batches — journaled commit, idempotent replay,
        // index fan-out — without knowing Twine exists. Synchronized on the Twine because it
        // keeps the single-writer discipline (one in-flight batch) and wire sessions are
        // threads of their own.
        Twine<Long, String> batcher = this.twine;
        this.wireServer = SmokeSignalServer.serve(primary,
                new SmokeSignalServer.WriteRoute<Long, String>() {
                    @Override public void put(Long key, String value) throws IOException {
                        indexed.put(key, value);
                    }
                    @Override public boolean delete(Long key) throws IOException {
                        return indexed.delete(key);
                    }
                },
                ops -> {
                    synchronized (batcher) {
                        Twine<Long, String>.Batch batch = batcher.batch();
                        for (SmokeSignalServer.BatchOp<Long, String> op : ops) {
                            if (op.isPut()) {
                                batch.put(op.key(), op.value());
                            } else {
                                batch.delete(op.key());
                            }
                        }
                        batch.commit();
                    }
                },
                SpillSerializer.forLongs(), SpillSerializer.forStrings());
        // The fourth tail subscriber, promoted from a bare counter to the observability organ.
        this.rub = Rub.over(primary);
        // The observer rides the fleet (2026-08-20): a second Rub on the replica's own store,
        // metering the replication feed as it applies — pure composition, Replica.store() is
        // public and a store is a store.
        this.replicaRub = Rub.over(pitBoss.replica("exhibit").store());
            built = true;
        } finally {
            if (!built) {
                unwindPartialBuild();
            }
        }
    }

    /**
     * Best-effort teardown of a partially-constructed organism (W-1). Closes, in reverse of the
     * build, whichever resources were opened before the failure — read from the fields, which are
     * their default {@code null} until assigned, so an unopened resource is simply skipped. Every
     * close is swallowed: cleanup runs while an exception is already propagating, and one organ's
     * refusal to close must not mask the original failure or strand the others.
     */
    private void unwindPartialBuild() {
        closeQuietly(replicaRub);
        closeQuietly(rub);
        closeQuietly(wireServer);
        closeQuietly(twine);
        closeQuietly(brine);
        closeQuietly(renderer);
        closeQuietly(pitBoss);
        closeQuietly(indexed);
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception ignored) {
                // best-effort: the build is already failing
            }
        }
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

    /**
     * The replica's vitals — the fleet, observed (2026-08-20). Converges to {@link #vitals}
     * once the replica catches the tail. Honest bound: a {@code pitBoss().rebootstrap} replaces
     * the replica and its store, and this observer stays attached to the store it was born on —
     * after a rebootstrap, attach a fresh {@code Rub.over(pitBoss().replica("exhibit").store())}
     * if the fleet's vitals still matter to you.
     */
    public Vitals replicaVitals() throws IOException {
        return replicaRub.sample();
    }

    /**
     * The organism's physical (2026-08-20): every meter the engines have grown, one read-only
     * call — the store's vitals and pulse, the replica's vitals, the wire's traffic, the
     * batcher's work, the cache's stats. Read-only by construction: nothing here ticks a
     * policy or advances any state, so a physical never changes the patient.
     */
    public String report() throws IOException {
        StringBuilder r = new StringBuilder();
        r.append("rub        ").append(vitals().line()).append('\n');
        Vitals.Pulse pulse = rub.pulse();
        if (pulse != null) {
            r.append("pulse      ").append(pulse.line()).append('\n');
        }
        r.append("replica    ").append(replicaVitals().line()).append('\n');
        r.append("wire       ").append(wireServer.stats().line()).append('\n');
        r.append("twine      ").append(twine.stats().line()).append('\n');
        r.append("brine      ").append(brine.stats());
        return r.toString();
    }

    /** Mutations Rub has metered off the tail since standup — the promoted watcher's count. */
    public long watchedEvents() {
        return rub.mutationsObserved();
    }

    /** Faults the write-path chaos seam has injected — zero under the default {@code none()} plan. */
    /** Milliseconds every replicated event is held back by (0: the feed seam is transparent). */
    public long replicaLagMillis() {
        return replicaLagMillis;
    }

    public long chaosCrashes() {
        return writeChaos.crashesInjected();
    }

    /**
     * Preserve the current moment and cure it for cold storage — DryAge and Jerky in one
     * motion. Returns the generation; the {@code .jerky} lands beside the vault.
     *
     * <p>2026-08-19, ledger #6: this used to open an {@link DryAge.AgedView} (recovery pass on
     * a scratch copy), back the view's store up into a staging dir (a second copy), cure the
     * re-backup, then delete the staging — two copies and a recovery to read bytes that were
     * already CRC'd at capture. DryAge named {@code generationPath} for read-only archival
     * consumers, and {@code Jerky.cure} is read-only on its source by contract, so the archive
     * now cures straight off the vault's own preserved bytes.</p>
     */
    public long preserveAndCure(Path archiveDir) throws IOException {
        // withScanRun (ADR scan-sidecar, 2026-08-20): the generation carries its sorted run
        // from birth, so the cured archive is cold-scannable without resurrection.
        long generation = vault.preserve(primary(), true);
        Files.createDirectories(archiveDir);
        Jerky.Cured cured = Jerky.cure(vault.generationPath(generation),
                archiveDir.resolve("gen-" + generation + ".jerky"));
        if (!Jerky.verify(cured.archive())) {
            throw new IOException("fresh archive failed verification: " + cured.archive());
        }
        return generation;
    }

    /**
     * Scan a cured archive's records in key order WITHOUT resurrecting a store (ADR
     * scan-sidecar, 2026-08-20): extract the sorted run — only that entry is inflated — and
     * stream it. This is the route the 2026-08-20 experiment measured against the 524× cost
     * of inflate-everything → recover → index-walk. Works on any archive
     * {@link #preserveAndCure} produced; an archive cured without a run fails loudly, naming
     * what it does hold.
     *
     * @return the number of records scanned
     */
    public static int coldScan(Path archive, java.util.function.BiConsumer<Long, String> consumer)
            throws IOException {
        byte[] run = Jerky.extract(archive, DryAge.SCAN_RUN);
        return SmokeHouse.scanSorted(run, options(), consumer);
    }

    /**
     * The run is a seed (2026-08-20): revive a fully queryable store at {@code dir} straight
     * from a cold archive's sidecar — extract only {@code scan.run}, bulk-import it. The seed
     * holds the preserved moment's STATE, not its log: no history, no tombstones, no
     * generations — order statistics and range reads over a moment recorded then, born fresh
     * today. When the log itself matters, {@code Jerky.restore} + {@code SmokeHouse.restore}
     * remains the full-fidelity road.
     */
    public static SmokeHouse<Long, String> seedFrom(Path archive, Path dir) throws IOException {
        byte[] run = Jerky.extract(archive, DryAge.SCAN_RUN);
        return SmokeHouse.importSorted(dir, options(), run);
    }

    /**
     * Carver over history (2026-08-20): seed a store from a cold archive's run, then reopen
     * it as an {@link IndexedStore} carrying the organism's own secondary and interval
     * indexes — {@code build()} rebuilds them from the seeded contents, so yesterday's
     * community gets today's full query surface. Wrap it in {@code Carver.over(...)} and the
     * read planner runs cost-based plans over the preserved moment. Same honest bound as
     * {@link #seedFrom}: state only, no log history.
     */
    public static IndexedStore<Long, String> seedIndexedFrom(Path archive, Path dir)
            throws IOException {
        try (SmokeHouse<Long, String> seeded = seedFrom(archive, dir)) {
            // Seed, then close: build() below reopens the directory and rebuilds the indexes.
        }
        return IndexedStore.open(dir, options())
                .secondary(ATTR, Comparator.<Integer>naturalOrder(), Organism::attrOf)
                .interval(SPAN, Organism::startOf, Organism::endOf)
                .build();
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

    /**
     * Tear down in reverse dependency order. The store closes last; its dir persists.
     * Idempotent: a second close is a no-op — an organism inside nested try-with-resources
     * must not die of being buried twice.
     */
    @Override
    public void close() throws IOException {
        close(false);
    }

    /**
     * Die instead of closing (2026-09-02): every organ is released as in {@link #close()}, but the
     * store is {@link IndexedStore#abandon abandoned} -- no checkpoint -- so the next organism at
     * this root walks the road every real crash takes: the log scan, SuperBeefSort's re-sort and
     * profile, the born strategy. Until this, a restart always closed cleanly, and engine 2's
     * recovery had never once run under the harness.
     */
    public void crash() throws IOException {
        close(true);
    }

    private void close(boolean dirty) throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        rub.close();                                           // detaches the tail observer only
        replicaRub.close();                                    // and the fleet's observer
        wireServer.close();
        twine.close();                                         // releases the journal-dir lock (T1)
        brine.close();
        renderer.close();
        pitBoss.close();
        if (dirty) {
            indexed.abandon();
        } else {
            indexed.close();
        }
    }
}
