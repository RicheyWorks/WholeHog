package io.github.richeyworks.wholehog;

import io.github.richeyworks.rub.Vitals;
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

    private final Organism o;
    private final Path archive;
    private final PrintStream out;

    HarnessConsole(Organism o, Path archive, PrintStream out) {
        this.o = o;
        this.archive = archive;
        this.out = out;
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
        try (Organism o = new Organism(root.resolve("organism"), seed)) {
            HarnessConsole c = new HarnessConsole(o, root.resolve("archive"), out);
            out.println("{\"ok\":true,\"ready\":true,\"protocol\":\"" + PROTOCOL
                    + "\",\"seed\":" + seed + ",\"wirePort\":" + o.wirePort() + "}");
            c.serve(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
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
        } catch (IllegalArgumentException e) {
            return refuse("invalid_argument", e.getMessage());
        } catch (Exception e) {
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
            case "get":      return get(longArg(t, 1));
            case "contains": return contains(longArg(t, 1));
            case "range":    return range(longArg(t, 1), longArg(t, 2), intArg(t, 3, 1, RANGE_CAP_MAX));
            case "count":    return count(longArg(t, 1), longArg(t, 2));
            case "query":    return query(t);
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
        b.append(",\"generations\":").append(o.vault().generations().size());
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
        String v = Organism.value(intArg(t, 2, 0, 999), intArg(t, 3, 0, 99_999),
                intArg(t, 4, 0, 99_999));
        String via = t.length > 5 ? t[5] : "direct";
        if ("wire".equals(via)) {
            try (SmokeSignalClient<Long, String> w = o.wire()) {
                w.put(k, v);
            }
        } else if ("direct".equals(via)) {
            o.store().put(k, v);
        } else {
            throw new IllegalArgumentException("via must be direct or wire, not " + via);
        }
        return "{\"ok\":true,\"key\":" + k + ",\"via\":\"" + via + "\"}";
    }

    String delete(String[] t) throws IOException {
        long k = longArg(t, 1);
        String via = t.length > 2 ? t[2] : "direct";
        boolean existed;
        if ("wire".equals(via)) {
            try (SmokeSignalClient<Long, String> w = o.wire()) {
                existed = w.delete(k);
            }
        } else if ("direct".equals(via)) {
            existed = o.store().delete(k);
        } else {
            throw new IllegalArgumentException("via must be direct or wire, not " + via);
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
                b.put(longArg(t, i + 1), Organism.value(intArg(t, i + 2, 0, 999),
                        intArg(t, i + 3, 0, 99_999), intArg(t, i + 4, 0, 99_999)));
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

    String get(long k) throws IOException {
        String v = o.store().get(k);
        return "{\"ok\":true,\"key\":" + k + ",\"found\":" + (v != null)
                + (v == null ? "" : ",\"value\":" + val(v)) + "}";
    }

    String contains(long k) throws IOException {
        return "{\"ok\":true,\"key\":" + k + ",\"found\":" + (o.store().get(k) != null) + "}";
    }

    String range(long lo, long hi, int cap) throws IOException {
        if (lo > hi) {
            throw new IllegalArgumentException("range lo " + lo + " > hi " + hi);
        }
        List<String> recs = new ArrayList<>();
        int[] seen = {0};
        o.primary().range(lo, hi, (k, v) -> {
            if (seen[0]++ < cap) {
                recs.add(rec(k, v));
            }
        });
        return "{\"ok\":true,\"records\":[" + String.join(",", recs) + "],\"count\":" + seen[0]
                + ",\"truncated\":" + (seen[0] > cap) + "}";
    }

    String count(long lo, long hi) {
        if (lo > hi) {
            throw new IllegalArgumentException("count lo " + lo + " > hi " + hi);
        }
        return "{\"ok\":true,\"count\":" + o.primary().countRange(lo, hi) + "}";
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

    String pulse() {
        Vitals.Pulse p = o.rub().pulse();
        if (p == null) {
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
