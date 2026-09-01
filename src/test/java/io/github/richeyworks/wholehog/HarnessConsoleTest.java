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
        assertEquals("{\"ok\":true,\"key\":7,\"found\":true,\"value\":{\"attr\":4,\"start\":50,\"end\":60}}",
                h("get 7"));
        assertEquals("{\"ok\":true,\"key\":5,\"found\":false}", h("contains 5"));
        assertEquals("{\"ok\":true,\"count\":3}", h("count 0 100"));
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
    void jsonStringsAreEscaped() {
        assertEquals("\"a\\\"b\\\\c\\nd\\te\"", HarnessConsole.str("a\"b\\c\nd\te"));
        assertEquals("\"\\u0001\"", HarnessConsole.str("\u0001"));
        assertEquals("\"null\"", HarnessConsole.str(null));
    }
}
