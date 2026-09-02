package io.github.richeyworks.wholehog;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The console's own contract, in-process (ADR-112): every verb answers one JSON line, every
 * refusal carries a code the plugin maps, and nothing free-text crosses the seam unescaped.
 * The heavier evidence — the differential oracle through the gateway, every route, the
 * replay and refusal behaviour — is {@code CSRBT/tools/verify/verify_organism.py}, which
 * drives a real console over a real pipe; this test is the floor under it, so a verb that
 * breaks fails the organism's own suite before the harness ever launches.
 */
final class HarnessConsoleTest {

    @TempDir Path root;
    private Organism o;
    private HarnessConsole c;

    @BeforeEach
    void up() throws Exception {
        o = new Organism(root.resolve("organism"), 11);
        c = new HarnessConsole(o, root.resolve("archive"),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void down() throws Exception {
        o.close();
    }

    private String h(String line) {
        return c.answer(line);
    }

    @Test
    void everyWriteRouteLandsAndReadsBack() throws Exception {
        assertEquals("{\"ok\":true,\"key\":5,\"via\":\"direct\"}", h("put 5 3 100 200"));
        assertEquals("{\"ok\":true,\"key\":7,\"via\":\"wire\"}", h("put 7 4 50 60 wire"));
        assertTrue(h("batch p 9 1 1 2 | d 5 | p 11 2 3 4").startsWith("{\"ok\":true,\"ops\":3,"));
        assertTrue(o.awaitQuiescence(10_000));
        assertEquals("{\"ok\":true,\"key\":7,\"found\":true,\"value\":{\"attr\":4,\"start\":50,\"end\":60},\"via\":\"direct\"}",
                h("get 7"));
        assertEquals(h("get 7").replace("direct", "wire"), h("get 7 wire"), "the wire reads what the store reads");
        assertEquals("{\"ok\":true,\"key\":5,\"found\":false,\"via\":\"direct\"}", h("contains 5"));
        assertEquals("{\"ok\":true,\"count\":3,\"via\":\"direct\"}", h("count 0 100"));
        assertEquals("{\"ok\":true,\"count\":3,\"via\":\"wire\"}", h("count 0 100 wire"));
        String r = h("range 0 100 2");
        assertTrue(r.contains("\"count\":3,\"truncated\":true"), r);
        assertTrue(h("observe").contains("\"size\":3,"));
        assertTrue(h("observe").contains("\"twine\":{\"batchesCommitted\":1,\"opsApplied\":3,"));
    }

    @Test
    void refusalsCarryACodeAndTouchNothing() throws Exception {
        assertEquals("{\"ok\":false,\"code\":\"not_found\",\"why\":\"unknown verb bogus\"}", h("bogus"));
        assertTrue(h("range 5 1 10").startsWith("{\"ok\":false,\"code\":\"invalid_argument\""));
        assertTrue(h("put 1 1000 1 1").startsWith("{\"ok\":false,\"code\":\"invalid_argument\""));
        assertTrue(h("batch").startsWith("{\"ok\":false,\"code\":\"invalid_argument\""));
        assertTrue(h("batch x 1").startsWith("{\"ok\":false,\"code\":\"invalid_argument\""));
        assertTrue(h("put 1 1 1 1 teleport").startsWith("{\"ok\":false,\"code\":\"invalid_argument\""));
        assertTrue(h("put 1 1 9 1 wire").startsWith("{\"ok\":false,\"code\":\"invalid_argument\""),
                "start > end is refused before any route sees it, so both routes answer alike");
        assertTrue(h("coldscan 3").startsWith("{\"ok\":false,\"code\":\"not_found\""));
        assertTrue(h("observe").contains("\"size\":0,\"tailSequence\":0,"),
                "no refused write reached the store");
    }

    @Test
    void aNonNumberIsInvalidArgumentNotACrash() {
        String r = h("put x 1 1 1");
        assertTrue(r.startsWith("{\"ok\":false,\"code\":\"invalid_argument\",\"why\":\"not a number"), r);
    }

    @Test
    void preserveThenColdScanReadsTheMoment() throws Exception {
        h("put 1 1 1 1");
        h("put 2 1 1 1");
        String p = h("preserve");
        assertTrue(p.startsWith("{\"ok\":true,\"generation\":0,\"generations\":1,"), p);
        h("put 3 1 1 1");
        assertEquals("{\"ok\":true,\"generation\":0,\"records\":2}", h("coldscan 0"));
    }

    @Test
    void everyEngineAnswersByName() throws Exception {
        h("put 5 3 100 200");
        h("put 7 4 50 60 wire");
        h("put 9 1 500 900");
        assertTrue(o.awaitQuiescence(10_000));
        assertEquals("{\"ok\":true,\"kind\":\"median\",\"answer\":7,\"via\":\"wire\"}", h("order median wire"));
        assertEquals("{\"ok\":true,\"kind\":\"rank\",\"answer\":3,\"via\":\"direct\"}", h("order rank 9"));
        assertTrue(h("order nth 0").startsWith("{\"ok\":false,\"code\":\"invalid_argument\""),
                "ranks are 1-based and a bad rank is the caller's, not a crash");
        assertTrue(h("depth 7").contains("\"live\":true") && h("depth 8").contains("\"live\":false"));
        assertTrue(h("overlap 55 120 10").contains("\"count\":2"), h("overlap 55 120 10"));
        assertTrue(h("stab 600 10").contains("\"keys\":[9]"));
        assertTrue(h("groups 2").startsWith("{\"ok\":true,\"groups\":3,"));
        h("cacheget 7");
        assertTrue(h("cacheget 7").contains("\"hit\":true,\"storeRead\":false"));
        assertTrue(h("fleet").contains("\"lag\":0,\"gapped\":false"));
        assertTrue(h("replicaget 7").contains("\"found\":true"));
        assertTrue(h("preserve").contains("\"generation\":0"));
        h("put 7 9 1 2");
        assertTrue(h("asof 0 7").contains("\"value\":{\"attr\":4,"), "as-of reads the moment");
        assertTrue(h("verify 0").endsWith("\"verified\":true}"));
        assertTrue(h("names 0").contains("\"scan.run\""));
        assertTrue(h("segments").startsWith("{\"ok\":true,\"segments\":[{"));
        assertTrue(h("compact").contains("\"garbageAfter\":"));
        assertEquals("{\"ok\":true,\"replayed\":false,\"journalReplays\":0}", h("recover"));
        h("tick");
        assertTrue(h("history").contains("\"liveKeys\":3"));
        assertTrue(h("retain 1").contains("\"released\":[]"));
        assertTrue(h("restart").startsWith("{\"ok\":false,\"code\":\"invalid_argument\""),
                "a console built without a root cannot restart, and says so");
    }

    @Test
    void restartUnderChaosThenCleanReplaysTheBatchWhole() throws Exception {
        o.close();
        Path organismRoot = root.resolve("organism");
        HarnessConsole r = new HarnessConsole(new Organism(organismRoot, 11), root.resolve("archive"),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                organismRoot, 11);
        try {
            assertTrue(r.answer("restart once:2").contains("\"chaos\":\"once:2\""));
            String crash = r.answer("batch p 1 1 1 1 | p 2 1 1 1 | p 3 1 1 1");
            assertTrue(crash.startsWith("{\"ok\":false,\"code\":\"failed\",\"why\":\"Crash:"), crash);
            assertTrue(r.answer("observe").contains("\"chaosCrashes\":1"));
            String wedged = r.answer("batch p 4 1 1 1");
            assertTrue(wedged.startsWith("{\"ok\":false,\"code\":\"conflict\""),
                    "Twine's one-batch-at-a-time is the organism's rule, not its failure: " + wedged);
            String clean = r.answer("restart");
            assertTrue(clean.contains("\"chaos\":\"none\"") && clean.contains("\"journalReplays\":1"), clean);
            assertEquals("{\"ok\":true,\"count\":3,\"via\":\"direct\"}", r.answer("count 0 100"));
            assertTrue(r.answer("restart bogus").startsWith("{\"ok\":false,\"code\":\"invalid_argument\""));
            assertTrue(r.answer("restart none 9999").startsWith("{\"ok\":false,\"code\":\"invalid_argument\""));
            assertTrue(r.answer("restart none 0 9999").startsWith("{\"ok\":false,\"code\":\"invalid_argument\""));
            // The feed seam (2026-09-02): hold the replica behind the primary for real, read the
            // lag from the conductor's seat, then quiesce and read zero.
            assertTrue(r.answer("restart none 0 150").contains("\"replicaLagMs\":150"));
            assertTrue(r.answer("observe").contains("\"replicaLagMs\":150"));
            for (int k = 10; k < 16; k++) {
                r.answer("put " + k + " 1 1 1");
            }
            String held = r.answer("fleet");
            assertTrue(held.contains("\"lag\":") && !held.contains("\"lag\":0,"),
                    "six writes under a 150 ms feed: the fleet reports the replica behind: " + held);
            assertTrue(held.contains("\"gapped\":false"), "behind, not gapped: " + held);
            assertTrue(r.answer("quiesce 15000").contains("\"quiet\":true"));
            assertTrue(r.answer("fleet").contains("\"lag\":0,"), "and caught up once the feed lands");
            assertEquals("{\"ok\":true,\"count\":9,\"via\":\"direct\"}", r.answer("count 0 100"));
            assertTrue(r.answer("restart").contains("\"replicaLagMs\":0"));
            // Engine 2, observed (2026-09-02): a clean restart uses the checkpoint and sorts
            // nothing; a cold one dies without it, and the reopen scans the log and sorts.
            String warm = r.answer("recovery");
            assertTrue(warm.contains("\"hintUsed\":true") && warm.contains("\"sorted\":false"), warm);
            for (int k = 30; k < 60; k++) {
                r.answer("put " + k + " 1 1 1");
            }
            String cold = r.answer("restart none 0 0 cold");
            assertTrue(cold.contains("\"how\":\"cold\"") && cold.contains("\"sorted\":true"), cold);
            String rep = r.answer("recovery");
            assertTrue(rep.contains("\"sorted\":true") && rep.contains("\"comparisons\":")
                    && !rep.contains("\"sortStrategy\":\"\"") && !rep.contains("\"bornStrategy\":\"\""), rep);
            assertEquals("{\"ok\":true,\"count\":39,\"via\":\"direct\"}", r.answer("count 0 100"),
                    "recovery is correct, not just reported");
            assertTrue(r.answer("observe").contains("\"recovery\":{\"entries\":39,\"sorted\":true"));
            assertTrue(r.answer("restart none 0 0 lukewarm").startsWith("{\"ok\":false,\"code\":\"invalid_argument\""));
        } finally {
            r.close();
        }
        o = new Organism(organismRoot, 11);              // so @AfterEach has something to close
    }

    @Test
    void jsonStringsAreEscaped() {
        assertEquals("\"a\\\"b\\\\c\\nd\\te\"", HarnessConsole.str("a\"b\\c\nd\te"));
        assertEquals("\"\\u0001\"", HarnessConsole.str("\u0001"));
        assertEquals("\"null\"", HarnessConsole.str(null));
    }
}
