package io.github.richeyworks.wholehog;

import io.github.richeyworks.brine.Brine;
import io.github.richeyworks.dryage.DryAge;
import io.github.richeyworks.jerky.Jerky;
import io.github.richeyworks.pitboss.PitBoss;
import io.github.richeyworks.rub.Vitals;
import io.github.richeyworks.sizzle.ChaosPlan;
import io.github.richeyworks.smokehouse.SmokeHouse;
import io.github.richeyworks.smokesignal.SmokeSignalClient;
import io.github.richeyworks.smokesignal.SmokeSignalServer;
import io.github.richeyworks.twine.Twine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The organism as a harness target (ADR-112, 2026-09-01): one {@link Organism} driven over
 * stdin/stdout by a line protocol, so that the CSRBT automation contract — the gateway that
 * already fronts every kit page for AI agents, test runners and scripts — can front the
 * fourteen-engine organism through exactly the same four operations.
 *
 * <p>This class is deliberately NOT the contract. It holds no token, no policy and no risk
 * ladder; those live in {@code CSRBT/tools/harness_contract.py}, and the
 * {@code csrbt-organism} plugin there is the only client of this process. The console is a
 * seam of the same kind as Twine's {@code PutSink}: a narrow, typed place where something
 * outside the organism gets to press on it. Keeping it narrow is what keeps the risk of every
 * press declarable by the plugin rather than guessed by the caller.</p>
 *
 * <h2>Protocol</h2>
 * One request per line, whitespace-separated tokens; one JSON object per reply line. Keys are
 * longs, values are the organism's own {@code attr start end} triple (see
 * {@link Organism#value}). Every reply carries {@code "ok"}; a refusal carries {@code "why"}.
 * No free text crosses the seam in either direction, which is why there is no JSON parser on
 * this side and never needs to be.</p>
 *
 * <pre>
 *   observe                      → the value-redacted snapshot (meters only)
 *   sample N                     → up to N records in key order (the sensitive half)
 *   put K A S E [direct|wire]    → a write, by the route named
 *   delete K [direct|wire]
 *   batch p K A S E | d K ...    → one crash-atomic Twine batch ('|' separates ops)
 *   get K   contains K   range LO HI CAP   count LO HI
 *   query LO HI ALO AHI CAP      → Carver plan + keys (attr between ALO..AHI)
 *   report   tick   pulse   quiesce MS
 *   preserve                     → preserveAndCure into the console's archive dir
 *   coldscan GEN                 → records in gen-GEN.jerky, counted without resurrection
 *
 *   ADR-113 (2026-09-01), every engine's surface:
 *   get/count/range … [direct|wire]   reads by route, like writes
 *   order KIND [ARG] [via]       rank K | nth R (1-based) | median | percentile P | first | last | size
 *   depth K                      CSRBT's realized probe depth (the measuring read)
 *   overlap LO HI CAP            Carver over the SPAN interval index
 *   stab POINT CAP
 *   groups TOPK                  Renderer's fold: group count, top-k attrs with totals
 *   cacheget K                   Brine: the cache's answer + its stats
 *   fleet                        PitBoss tick → lag/gapped/rebootstrapped per replica
 *   replicaget K                 the replica's own store
 *   rebootstrap                  cold-start the replica mid-life
 *   generations | asof GEN K | retain N        DryAge
 *   verify GEN | names GEN       Jerky
 *   compact | segments           SmokeHouse
 *   recover | history            Twine journal replay | Rub's sample history
 *   restart [PLAN] [LATENCY] [REPLICA-LAG]
 *                                close + reopen at the same root; PLAN = none | once:N |
 *                                every:N | prob:SEED:P (Sizzle), LATENCY ms per write op,
 *                                REPLICA-LAG ms each replicated event is held back (the
 *                                feed seam; the replica is late, never wrong)
 *   quit
 * </pre>
 *
 * <p>Console-only, loopback-only, seeded. {@code main} takes {@code --root DIR --seed N}; the
 * archive lands at {@code DIR/archive}. Everything else about where this runs — enabling it,
 * authenticating a caller, deciding what a caller may press — is the gateway's, not this
 * file's.</p>
 */
public final class HarnessConsole {

    static final String PROTOCOL = "1.0";
    static final int RANGE_CAP_MAX = 1000;
    static final int QUIESCE_MS_MAX = 30_000;
    /** The most a restart may hold each replicated event back: a quiesce must still be able to converge. */
    static final long REPLICA_LAG_MS_MAX = 500;

    static final String REPLICA = "exhibit";

    private Organism o;
    private final Path archive;
    private final PrintStream out;
    private final Path organismRoot;
    private final long seed;
    private String chaos = "none";
    private int restarts;
    private boolean replicaObserverDetached;

    HarnessConsole(Organism o, Path archive, PrintStream out) {
        this(o, archive, out, null, 0);
    }

    HarnessConsole(Organism o, Path archive, PrintStream out, Path organismRoot, long seed) {
        this.o = o;
        this.archive = archive;
        this.out = out;
        this.organismRoot = organismRoot;
        this.seed = seed;
    }

    /** Close whatever is open. Idempotent, like the organism's own close. */
    void close() throws IOException {
        if (o != null) {
            o.close();
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = null;
        long seed = 42;
        for (int i = 0; i + 1 < args.length; i += 2) {
            if ("--root".equals(args[i])) {
                root = Path.of(args[i + 1]);
            } else if ("--seed".equals(args[i])) {
                seed = Long.parseLong(args[i + 1]);
            }
        }
        if (root == null) {
            root = Files.createTempDirectory("wholehog-harness");
        }
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        Path organismRoot = root.resolve("organism");
        HarnessConsole c = new HarnessConsole(new Organism(organismRoot, seed),
                root.resolve("archive"), out, organismRoot, seed);
        try {
            out.println("{\"ok\":true,\"ready\":true,\"protocol\":\"" + PROTOCOL
                    + "\",\"seed\":" + seed + ",\"wirePort\":" + c.o.wirePort() + "}");
            c.serve(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
        } finally {
            c.close();
        }
    }

    void serve(BufferedReader in) throws IOException {
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if ("quit".equals(line)) {
                out.println("{\"ok\":true,\"bye\":true}");
                return;
            }
            out.println(answer(line));
        }
    }

    /** One line in, one JSON line out: the mapping from a thrown refusal to a coded reply. */
    String answer(String line) {
        try {
            return handle(line.split("\\s+"));
        } catch (NumberFormatException e) {
            return refuse("invalid_argument", "not a number: " + e.getMessage());
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            // A rank outside [1, size] is the caller's argument, not the organism failing.
            return refuse("invalid_argument", e.getMessage());
        } catch (IllegalStateException e) {
            // Twine's "a committed batch is still applying; one batch at a time" (tenth-pass
            // T4): the organism refusing by its own rule, not the organism failing. The first
            // robot (ADR-114) filed it under failed; recover() is the way through.
            return refuse("conflict", e.getMessage());
        } catch (Exception e) {
            return refuse("failed", e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (Error e) {
            // A StackOverflowError or OutOfMemoryError in one verb is that verb failing,
            // not the console: report it and keep serving. The first robot (ADR-117) found
            // one in a morph's health check, and the console died with it.
            return refuse("failed", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ── one handler per verb ────────────────────────────────────────────────

    String handle(String[] t) throws IOException {
        switch (t[0]) {
            case "observe":  return observe();
            case "sample":   return sample(intArg(t, 1, 0, RANGE_CAP_MAX));
            case "put":      return put(t);
            case "delete":   return delete(t);
            case "batch":    return batch(t);
            case "get":      return get(longArg(t, 1), via(t, 2));
            case "contains": return contains(longArg(t, 1), via(t, 2));
            case "range":    return range(longArg(t, 1), longArg(t, 2), intArg(t, 3, 1, RANGE_CAP_MAX), via(t, 4));
            case "count":    return count(longArg(t, 1), longArg(t, 2), via(t, 3));
            case "query":    return query(t);
            case "order":    return order(t);
            case "depth":    return depth(longArg(t, 1));
            case "overlap":  return span(false, intArg(t, 1, 0, 99_999), intArg(t, 2, 0, 99_999), intArg(t, 3, 1, RANGE_CAP_MAX));
            case "stab":     return span(true, intArg(t, 1, 0, 99_999), intArg(t, 1, 0, 99_999), intArg(t, 2, 1, RANGE_CAP_MAX));
            case "groups":   return groups(intArg(t, 1, 1, 1000));
            case "cacheget": return cacheget(longArg(t, 1));
            case "fleet":    return fleet();
            case "replicaget": return replicaget(longArg(t, 1));
            case "rebootstrap": return rebootstrap();
            case "generations": return generations();
            case "asof":     return asof(longArg(t, 1), longArg(t, 2));
            case "retain":   return retain(intArg(t, 1, 0, 1_000_000));
            case "verify":   return verify(longArg(t, 1));
            case "names":    return names(longArg(t, 1));
            case "compact":  return compact();
            case "segments": return segments();
            case "recover":  return recover();
            case "history":  return history();
            case "restart":  return restart(t);
            case "report":   return ok("report", str(o.report()));
            case "tick":     return ok("vitals", vitals(o.rub().tick()));
            case "pulse":    return pulse();
            case "quiesce":  return quiesce(intArg(t, 1, 0, QUIESCE_MS_MAX));
            case "preserve": return preserve();
            case "coldscan": return coldscan(longArg(t, 1));
            default:
                return refuse("not_found", "unknown verb " + t[0]);
        }
    }

    /** Meters only. Never a key, never a value: this is what READ may see. */
    String observe() throws IOException {
        SmokeSignalServer.WireStats w = o.wireStats();
        Twine.TwineStats tw = o.twine().stats();
        StringBuilder b = new StringBuilder("{\"ok\":true,\"ready\":true");
        b.append(",\"size\":").append(o.primary().size());
        b.append(",\"tailSequence\":").append(o.primary().tailSequence());
        List<Long> gens = o.vault().generations();
        b.append(",\"generations\":").append(gens.size());
        b.append(",\"generationIds\":").append(gens);
        b.append(",\"wirePort\":").append(o.wirePort());
        b.append(",\"watchedEvents\":").append(o.watchedEvents());
        b.append(",\"chaosCrashes\":").append(o.chaosCrashes());
        b.append(",\"garbageBytes\":").append(o.primary().garbageBytes());
        b.append(",\"wire\":{\"connectionsAccepted\":").append(w.connectionsAccepted())
                .append(",\"gets\":").append(w.gets()).append(",\"puts\":").append(w.puts())
                .append(",\"deletes\":").append(w.deletes())
                .append(",\"sizeQueries\":").append(w.sizeQueries())
                .append(",\"rangeQueries\":").append(w.rangeQueries())
                .append(",\"batches\":").append(w.batches())
                .append(",\"errors\":").append(w.errors()).append('}');
        b.append(",\"twine\":{\"batchesCommitted\":").append(tw.batchesCommitted())
                .append(",\"opsApplied\":").append(tw.opsApplied())
                .append(",\"journalReplays\":").append(tw.journalReplays()).append('}');
        b.append(",\"rub\":").append(vitals(o.vitals()));
        b.append(",\"replica\":").append(vitals(o.replicaVitals()));
        Brine.Stats bs = o.brine().stats();
        b.append(",\"brine\":{\"gets\":").append(bs.gets()).append(",\"valueHits\":").append(bs.valueHits())
                .append(",\"storeReads\":").append(bs.storeReads())
                .append(",\"invalidations\":").append(bs.invalidations())
                .append(",\"residentValues\":").append(bs.residentValues())
                .append(",\"champion\":").append(str(String.valueOf(bs.champion()))).append('}');
        b.append(",\"segments\":").append(o.primary().segmentStats().size());
        b.append(",\"chaos\":").append(str(chaos)).append(",\"restarts\":").append(restarts);
        b.append(",\"replicaLagMs\":").append(o.replicaLagMillis());
        b.append(",\"replicaObserverDetached\":").append(replicaObserverDetached);
        return b.append('}').toString();
    }

    /** The sensitive half of a snapshot: records, in key order, capped. */
    String sample(int cap) throws IOException {
        List<String> recs = new ArrayList<>();
        int[] seen = {0};
        Long first = o.primary().firstKey(), last = o.primary().lastKey();
        if (first != null && cap > 0) {
            o.primary().range(first, last, (k, v) -> {
                if (seen[0]++ < cap) {
                    recs.add(rec(k, v));
                }
            });
        }
        return "{\"ok\":true,\"records\":[" + String.join(",", recs) + "],\"truncated\":"
                + (seen[0] > cap) + ",\"medianKey\":" + o.primary().medianKey()
                + ",\"firstKey\":" + first + ",\"lastKey\":" + last + "}";
    }

    String put(String[] t) throws IOException {
        long k = longArg(t, 1);
        String v = triple(t, 2);
        String via = via(t, 5);
        if ("wire".equals(via)) {
            try (SmokeSignalClient<Long, String> w = o.wire()) {
                w.put(k, v);
            }
        } else {
            o.store().put(k, v);
        }
        return "{\"ok\":true,\"key\":" + k + ",\"via\":\"" + via + "\"}";
    }

    String delete(String[] t) throws IOException {
        long k = longArg(t, 1);
        String via = via(t, 2);
        boolean existed;
        if ("wire".equals(via)) {
            try (SmokeSignalClient<Long, String> w = o.wire()) {
                existed = w.delete(k);
            }
        } else {
            existed = o.store().delete(k);
        }
        return "{\"ok\":true,\"key\":" + k + ",\"existed\":" + existed + ",\"via\":\"" + via + "\"}";
    }

    /** {@code batch p K A S E | d K | p ...} — one Twine batch, journaled, crash-atomic. */
    String batch(String[] t) throws IOException {
        Twine<Long, String>.Batch b = o.twine().batch();
        int i = 1, ops = 0;
        while (i < t.length) {
            if ("|".equals(t[i])) {
                i++;
                continue;
            }
            if ("p".equals(t[i])) {
                if (i + 4 >= t.length) {
                    throw new IllegalArgumentException("batch put needs K A S E");
                }
                b.put(longArg(t, i + 1), triple(t, i + 2));
                i += 5;
            } else if ("d".equals(t[i])) {
                b.delete(longArg(t, i + 1));
                i += 2;
            } else {
                throw new IllegalArgumentException("batch op must be p or d, not " + t[i]);
            }
            ops++;
        }
        if (ops == 0) {
            throw new IllegalArgumentException("an empty batch is not a batch");
        }
        b.commit();
        return "{\"ok\":true,\"ops\":" + ops + ",\"batchesCommitted\":"
                + o.twine().stats().batchesCommitted() + "}";
    }

    String get(long k, String via) throws IOException {
        String v = "wire".equals(via) ? wire(w -> w.get(k)) : o.store().get(k);
        return "{\"ok\":true,\"key\":" + k + ",\"found\":" + (v != null)
                + (v == null ? "" : ",\"value\":" + val(v)) + ",\"via\":\"" + via + "\"}";
    }

    String contains(long k, String via) throws IOException {
        String v = "wire".equals(via) ? wire(w -> w.get(k)) : o.store().get(k);
        return "{\"ok\":true,\"key\":" + k + ",\"found\":" + (v != null) + ",\"via\":\"" + via + "\"}";
    }

    String range(long lo, long hi, int cap, String via) throws IOException {
        if (lo > hi) {
            throw new IllegalArgumentException("range lo " + lo + " > hi " + hi);
        }
        List<String> recs = new ArrayList<>();
        int[] seen = {0};
        if ("wire".equals(via)) {
            // The wire materializes the whole range (OP_RANGE, 2026-08-20); the cap is applied
            // here, after the fact, and the count is the wire's own.
            Map<Long, String> got = wire(w -> w.rangeQuery(lo, hi));
            for (Map.Entry<Long, String> e : got.entrySet()) {
                if (seen[0]++ < cap) {
                    recs.add(rec(e.getKey(), e.getValue()));
                }
            }
        } else {
            o.primary().range(lo, hi, (k, v) -> {
                if (seen[0]++ < cap) {
                    recs.add(rec(k, v));
                }
            });
        }
        return "{\"ok\":true,\"records\":[" + String.join(",", recs) + "],\"count\":" + seen[0]
                + ",\"truncated\":" + (seen[0] > cap) + ",\"via\":\"" + via + "\"}";
    }

    String count(long lo, long hi, String via) throws IOException {
        if (lo > hi) {
            throw new IllegalArgumentException("count lo " + lo + " > hi " + hi);
        }
        int n = "wire".equals(via) ? wire(w -> w.countRange(lo, hi)) : o.primary().countRange(lo, hi);
        return "{\"ok\":true,\"count\":" + n + ",\"via\":\"" + via + "\"}";
    }

    /**
     * Order statistics — CSRBT's reason to exist, surfaced through the store and, by the same
     * names, over the wire. {@code rank K} / {@code nth R} / {@code median} / {@code percentile P}
     * / {@code first} / {@code last} / {@code size}. Empty-store answers are {@code null}, not 0.
     */
    String order(String[] t) throws IOException {
        if (t.length < 2) {
            throw new IllegalArgumentException("order needs a kind");
        }
        String kind = t[1];
        int argAt = ("rank".equals(kind) || "nth".equals(kind) || "percentile".equals(kind)) ? 2 : -1;
        String via = via(t, argAt < 0 ? 2 : 3);
        boolean w = "wire".equals(via);
        SmokeHouse<Long, String> p = o.primary();
        Object answer;
        switch (kind) {
            case "rank": {
                long k = longArg(t, 2);
                answer = w ? wire(c -> c.rankOf(k)) : p.rankOf(k);
                break;
            }
            case "nth": {
                int r = intArg(t, 2, 1, Integer.MAX_VALUE);          // ranks are 1-based, like the store's
                if (r > p.size()) {
                    // Checked here so both routes answer alike: direct threw IndexOutOfBounds
                    // (mapped to invalid_argument); the wire wrapped the server's refusal in an
                    // IOException (failed). Same input, two codes -- the first robot's finding.
                    throw new IllegalArgumentException("nth rank " + r + " out of bounds [1," + p.size() + "]");
                }
                answer = w ? wire(c -> c.nthKey(r)) : p.nthKey(r);
                break;
            }
            case "median":     answer = w ? wire(SmokeSignalClient::medianKey) : p.medianKey(); break;
            case "percentile": {
                int pct = intArg(t, 2, 0, 100);
                answer = w ? wire(c -> c.percentileKey(pct)) : p.percentileKey(pct);
                break;
            }
            case "first":      answer = w ? wire(SmokeSignalClient::firstKey) : p.firstKey(); break;
            case "last":       answer = w ? wire(SmokeSignalClient::lastKey) : p.lastKey(); break;
            case "size":       answer = w ? wire(SmokeSignalClient::size) : p.size(); break;
            default:
                throw new IllegalArgumentException("order kind must be rank|nth|median|percentile|first|last|size, not " + kind);
        }
        return "{\"ok\":true,\"kind\":\"" + kind + "\",\"answer\":" + answer + ",\"via\":\"" + via + "\"}";
    }

    /** The measuring read: nodes touched for a live key, {@code ~depth} (negative) for an absent one. */
    String depth(long k) {
        int d = o.primary().searchDepth(k);
        return "{\"ok\":true,\"key\":" + k + ",\"depth\":" + d + ",\"live\":" + (d >= 1) + "}";
    }

    String query(String[] t) throws IOException {
        long lo = longArg(t, 1), hi = longArg(t, 2);
        int alo = intArg(t, 3, 0, 999), ahi = intArg(t, 4, 0, 999), cap = intArg(t, 5, 1, RANGE_CAP_MAX);
        if (lo > hi || alo > ahi) {
            throw new IllegalArgumentException("query bounds must be lo <= hi");
        }
        var q = o.carver().query().keysBetween(lo, hi).whereBetween(Organism.ATTR, alo, ahi);
        String plan = q.explain().toString();
        List<Long> keys = q.keys();
        List<String> ks = new ArrayList<>();
        for (int i = 0; i < Math.min(cap, keys.size()); i++) {
            ks.add(String.valueOf(keys.get(i)));
        }
        return "{\"ok\":true,\"plan\":" + str(plan) + ",\"count\":" + keys.size()
                + ",\"keys\":[" + String.join(",", ks) + "],\"truncated\":" + (keys.size() > cap) + "}";
    }

    String span(boolean stab, int lo, int hi, int cap) throws IOException {
        if (lo > hi) {
            throw new IllegalArgumentException("overlap lo " + lo + " > hi " + hi);
        }
        var q = stab ? o.carver().query().stabbing(Organism.SPAN, lo)
                     : o.carver().query().overlapping(Organism.SPAN, lo, hi);
        String plan = q.explain().toString();
        List<Long> keys = q.keys();
        List<String> ks = new ArrayList<>();
        for (int i = 0; i < Math.min(cap, keys.size()); i++) {
            ks.add(String.valueOf(keys.get(i)));
        }
        return "{\"ok\":true,\"plan\":" + str(plan) + ",\"count\":" + keys.size()
                + ",\"keys\":[" + String.join(",", ks) + "],\"truncated\":" + (keys.size() > cap) + "}";
    }

    /** Renderer's materialized fold: how many attribute groups, and the heaviest {@code k}. */
    String groups(int topK) {
        var g = o.byAttr();
        List<String> tops = new ArrayList<>();
        for (Integer attr : g.top(topK)) {
            tops.add("{\"attr\":" + attr + ",\"total\":" + g.total(attr) + "}");
        }
        return "{\"ok\":true,\"groups\":" + g.groups() + ",\"top\":[" + String.join(",", tops)
                + "],\"appliedSequence\":" + g.appliedSequence() + ",\"gapped\":" + g.gapped() + "}";
    }

    /** Brine's answer, and whether it came from the cache or the store. */
    String cacheget(long k) throws IOException {
        Brine.Stats before = o.brine().stats();
        String v = o.brine().get(k);
        Brine.Stats after = o.brine().stats();
        return "{\"ok\":true,\"key\":" + k + ",\"found\":" + (v != null)
                + (v == null ? "" : ",\"value\":" + val(v))
                + ",\"hit\":" + (after.valueHits() > before.valueHits())
                + ",\"storeRead\":" + (after.storeReads() > before.storeReads())
                + ",\"champion\":" + str(String.valueOf(after.champion())) + "}";
    }

    /** PitBoss's tick: the fleet report, one status per replica. */
    String fleet() throws IOException {
        PitBoss.FleetReport r = o.pitBoss().tick();
        List<String> rs = new ArrayList<>();
        for (PitBoss.ReplicaStatus st : r.replicas()) {
            rs.add("{\"name\":" + str(st.name()) + ",\"lag\":" + st.lag() + ",\"gapped\":" + st.gapped()
                    + ",\"rebootstrapped\":" + st.rebootstrapped() + "}");
        }
        return "{\"ok\":true,\"primarySequence\":" + r.primarySequence() + ",\"replicas\":["
                + String.join(",", rs) + "]}";
    }

    String replicaget(long k) throws IOException {
        String v = o.pitBoss().replica(REPLICA).store().get(k);
        return "{\"ok\":true,\"key\":" + k + ",\"found\":" + (v != null)
                + (v == null ? "" : ",\"value\":" + val(v)) + ",\"replica\":" + str(REPLICA) + "}";
    }

    /**
     * Cold-start the replica mid-life. Honest bound, from Organism's own javadoc: the replica
     * observer (the {@code replica} vitals in every snapshot) stays attached to the store it was
     * born on, so from here until the next restart the snapshot says {@code replicaObserverDetached}.
     */
    String rebootstrap() throws IOException {
        o.pitBoss().rebootstrap(REPLICA);
        replicaObserverDetached = true;
        return "{\"ok\":true,\"replica\":" + str(REPLICA) + ",\"replicaObserverDetached\":true}";
    }

    String generations() throws IOException {
        List<String> gs = new ArrayList<>();
        for (Long g : o.vault().generations()) {
            gs.add(String.valueOf(g));
        }
        return "{\"ok\":true,\"generations\":[" + String.join(",", gs) + "]}";
    }

    /** One key as of a preserved generation — a scratch copy is recovered and released. */
    String asof(long gen, long k) throws IOException {
        if (!o.vault().generations().contains(gen)) {
            return refuse("not_found", "no generation " + gen + " in the vault");
        }
        try (DryAge.AgedView<Long, String> past = o.vault().asOf(gen)) {
            String v = past.store().get(k);
            return "{\"ok\":true,\"generation\":" + gen + ",\"key\":" + k + ",\"found\":" + (v != null)
                    + (v == null ? "" : ",\"value\":" + val(v)) + ",\"size\":" + past.store().size() + "}";
        }
    }

    String retain(int n) throws IOException {
        List<String> rel = new ArrayList<>();
        for (Long g : o.vault().retainNewest(n)) {
            rel.add(String.valueOf(g));
        }
        return "{\"ok\":true,\"released\":[" + String.join(",", rel) + "],\"generations\":"
                + o.vault().generations().size() + "}";
    }

    String verify(long gen) {
        Path a = archive.resolve("gen-" + gen + ".jerky");
        if (!Files.exists(a)) {
            return refuse("not_found", "no archive for generation " + gen);
        }
        return "{\"ok\":true,\"generation\":" + gen + ",\"verified\":" + Jerky.verify(a) + "}";
    }

    String names(long gen) throws IOException {
        Path a = archive.resolve("gen-" + gen + ".jerky");
        if (!Files.exists(a)) {
            return refuse("not_found", "no archive for generation " + gen);
        }
        List<String> ns = new ArrayList<>();
        for (String n : Jerky.names(a)) {
            ns.add(str(n));
        }
        return "{\"ok\":true,\"generation\":" + gen + ",\"names\":[" + String.join(",", ns) + "]}";
    }

    String compact() throws IOException {
        long before = o.primary().garbageBytes();
        long reclaimed = o.primary().compact();
        return "{\"ok\":true,\"reclaimed\":" + reclaimed + ",\"garbageBefore\":" + before
                + ",\"garbageAfter\":" + o.primary().garbageBytes() + ",\"segments\":"
                + o.primary().segmentStats().size() + "}";
    }

    String segments() throws IOException {
        List<String> ss = new ArrayList<>();
        for (SmokeHouse.SegmentStat st : o.primary().segmentStats()) {
            ss.add("{\"id\":" + st.segmentId() + ",\"bytes\":" + st.bytes() + ",\"garbageBytes\":"
                    + st.garbageBytes() + ",\"active\":" + st.active() + "}");
        }
        return "{\"ok\":true,\"segments\":[" + String.join(",", ss) + "]}";
    }

    /** Replay Twine's journal now: true if a batch was waiting, false if the journal was clean. */
    String recover() throws IOException {
        boolean replayed = o.twine().recover();
        return "{\"ok\":true,\"replayed\":" + replayed + ",\"journalReplays\":"
                + o.twine().stats().journalReplays() + "}";
    }

    String history() {
        List<String> hs = new ArrayList<>();
        for (Vitals v : o.rub().history()) {
            hs.add(vitals(v));
        }
        return "{\"ok\":true,\"history\":[" + String.join(",", hs) + "]}";
    }

    /**
     * Close the organism and reopen it at the same root — the crash-recovery road (Twine's
     * journal replays into every index on construction) — optionally under a Sizzle plan.
     * Chaos is a constructor seam on the organism, so this is the only honest way to arm it.
     */
    String restart(String[] t) throws IOException {
        if (organismRoot == null) {
            throw new IllegalArgumentException("restart needs a console started with a root");
        }
        String planName = t.length > 1 ? t[1] : "none";
        long latency = t.length > 2 ? longArg(t, 2) : 0;
        if (latency < 0 || latency > 5_000) {
            throw new IllegalArgumentException("latency must be 0..5000 ms");
        }
        long replicaLag = t.length > 3 ? longArg(t, 3) : 0;
        if (replicaLag < 0 || replicaLag > REPLICA_LAG_MS_MAX) {
            throw new IllegalArgumentException("replica lag must be 0.." + REPLICA_LAG_MS_MAX + " ms");
        }
        ChaosPlan plan = plan(planName);
        if (latency > 0) {
            plan = plan.withLatencyMillis(latency);
        }
        o.close();
        o = new Organism(organismRoot, seed, plan, replicaLag);
        chaos = planName + (latency > 0 ? "+" + latency + "ms" : "");
        restarts++;
        replicaObserverDetached = false;
        return "{\"ok\":true,\"restarts\":" + restarts + ",\"chaos\":" + str(chaos)
                + ",\"replicaLagMs\":" + replicaLag
                + ",\"wirePort\":" + o.wirePort() + ",\"size\":" + o.primary().size()
                + ",\"journalReplays\":" + o.twine().stats().journalReplays() + "}";
    }

    static ChaosPlan plan(String spec) {
        String[] p = spec.split(":");
        switch (p[0]) {
            case "none":  return ChaosPlan.none();
            case "once":  return ChaosPlan.crashOnceAtOp(Long.parseLong(part(p, 1)));
            case "every": return ChaosPlan.crashEveryNthOp(Long.parseLong(part(p, 1)));
            case "prob":  return ChaosPlan.crashWithProbability(Long.parseLong(part(p, 1)),
                                  Double.parseDouble(part(p, 2)));
            default:
                throw new IllegalArgumentException("plan must be none | once:N | every:N | prob:SEED:P, not " + spec);
        }
    }

    private static String part(String[] p, int i) {
        if (i >= p.length) {
            throw new IllegalArgumentException("plan " + p[0] + " needs " + i + " argument(s)");
        }
        return p[i];
    }

    interface WireCall<R> {
        R call(SmokeSignalClient<Long, String> w) throws IOException;
    }

    /** One fresh loopback client per call — the same route the write side takes. */
    <R> R wire(WireCall<R> call) throws IOException {
        try (SmokeSignalClient<Long, String> w = o.wire()) {
            return call.call(w);
        }
    }

    static String via(String[] t, int i) {
        String via = i < t.length ? t[i] : "direct";
        if (!"direct".equals(via) && !"wire".equals(via)) {
            throw new IllegalArgumentException("via must be direct or wire, not " + via);
        }
        return via;
    }

    String pulse() {
        Vitals.Pulse p = o.rub().pulse();
        if (p == null) {
            // ok: the read succeeded and the answer is "no pulse yet". The first robot filed
            // an ok:false here under failed, and a reading that does not exist is not a failure.
            return "{\"ok\":true,\"pulse\":null,\"why\":\"fewer than two ticks\"}";
        }
        return "{\"ok\":true,\"pulse\":{\"opsElapsed\":" + p.opsElapsed() + ",\"putsObserved\":"
                + p.putsObserved() + ",\"deletesObserved\":" + p.deletesObserved()
                + ",\"gapsObserved\":" + p.gapsObserved() + ",\"liveKeysDelta\":" + p.liveKeysDelta()
                + ",\"garbageBytesDelta\":" + p.garbageBytesDelta() + "}}";
    }

    String quiesce(int ms) {
        boolean quiet = o.awaitQuiescence(ms);
        return "{\"ok\":true,\"quiet\":" + quiet + ",\"tailSequence\":" + o.primary().tailSequence() + "}";
    }

    String preserve() throws IOException {
        long gen = o.preserveAndCure(archive);
        return "{\"ok\":true,\"generation\":" + gen + ",\"generations\":"
                + o.vault().generations().size() + ",\"archive\":" + str(archive.resolve("gen-" + gen + ".jerky").toString()) + "}";
    }

    String coldscan(long gen) throws IOException {
        Path a = archive.resolve("gen-" + gen + ".jerky");
        if (!Files.exists(a)) {
            return refuse("not_found", "no archive for generation " + gen);
        }
        int[] n = {0};
        int scanned = Organism.coldScan(a, (k, v) -> n[0]++);
        return "{\"ok\":true,\"generation\":" + gen + ",\"records\":" + scanned + "}";
    }

    // ── emission: hand-written JSON, because nothing here is free text ──────

    /**
     * The organism's value, validated here before any route sees it. The interval index
     * refuses start > end; direct, that surfaced as invalid_argument, and over the wire as a
     * server refusal wrapped in an IOException -- failed. The first robot (ADR-114) found the
     * same bad input answered with two codes depending on the route it happened to pick.
     */
    static String triple(String[] t, int i) {
        int attr = intArg(t, i, 0, 999), start = intArg(t, i + 1, 0, 99_999), end = intArg(t, i + 2, 0, 99_999);
        if (start > end) {
            throw new IllegalArgumentException("span start " + start + " > end " + end);
        }
        return Organism.value(attr, start, end);
    }

    static String vitals(Vitals v) {
        return "{\"tailSequence\":" + v.tailSequence() + ",\"liveKeys\":" + v.liveKeys()
                + ",\"segments\":" + v.segments() + ",\"liveBytes\":" + v.liveBytes()
                + ",\"garbageBytes\":" + v.garbageBytes() + ",\"putsObserved\":" + v.putsObserved()
                + ",\"deletesObserved\":" + v.deletesObserved() + ",\"gapsObserved\":"
                + v.gapsObserved() + "}";
    }

    static String rec(Long k, String v) {
        return "{\"key\":" + k + ",\"value\":" + val(v) + "}";
    }

    static String val(String v) {
        return "{\"attr\":" + Organism.attrOf(v) + ",\"start\":" + Organism.startOf(v)
                + ",\"end\":" + Organism.endOf(v) + "}";
    }

    static String ok(String field, String jsonValue) {
        return "{\"ok\":true,\"" + field + "\":" + jsonValue + "}";
    }

    static String refuse(String code, String why) {
        return "{\"ok\":false,\"code\":\"" + code + "\",\"why\":" + str(why) + "}";
    }

    static String str(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : String.valueOf(s).toCharArray()) {
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.append('"').toString();
    }

    static long longArg(String[] t, int i) {
        if (i >= t.length) {
            throw new IllegalArgumentException(t[0] + " needs an argument at position " + i);
        }
        return Long.parseLong(t[i]);
    }

    static int intArg(String[] t, int i, int lo, int hi) {
        int v = Math.toIntExact(longArg(t, i));
        if (v < lo || v > hi) {
            throw new IllegalArgumentException(t[0] + " argument " + i + " must be " + lo + ".." + hi
                    + ", got " + v);
        }
        return v;
    }
}
