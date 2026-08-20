# 2026-08-20 — three slices: the ring gets a knob, the gap gets proven, the batcher gets a meter

## Slice A — the configurable tail ring (SmokeHouse)

The tail's bound — how many committed mutations it retains for replay, and how far behind a
single subscriber may fall before drop-oldest fires — was a hard constant (4096), which made
the gap contract *unreachable by any honest test*. `SmokeHouseOptions.tailRing(capacity)`
names the seam (default unchanged; floor 8), and one knob now governs both the shared replay
ring and each subscriber's queue, because they were always the same idea: the distance a
consumer may lag before being told the truth.

Semantics, stated plainly: a tiny ring bounds EVERY subscriber — under a burst, even a fast
consumer may legitimately gap at capacity 8. `TailGapTest` pins both sides: a slow consumer
on a ring-8 store is told via `onGap()` and genuinely misses events; the control (a fast
consumer on a default ring, same churn) sees all 400, gap-free. (The first draft of that test
asserted a fast consumer stays gap-free *on the tiny ring* — the test was wrong, not the
code, and the correction is part of the record.)

## Slice B — Sizzle.slow: chaos for tail consumers

`Sizzle.slow(listener, millis)` stalls every delivery before delegating — the honest way to
make a consumer fall behind. Composed with `tailRing(8)`, the drop-oldest contract becomes
*triggerable*: the wrapped listener genuinely lags, the ring genuinely overruns, `onGap()`
genuinely fires — no mocks, no fake gaps. And `onGap` passes through undelayed: chaos slows
the consumer, never the truth about what the consumer missed. This closes the tail-chaos
story that was deliberately skipped earlier when the ring was a constant.

## Slice C — Twine.stats: the batcher's meter

Twine was the last unmetered organ. `stats()` reports batches committed, ops applied
(commit-path and replay-path both — replayed ops count as applied, because they were), and
journal replays at construction: a nonzero `journalReplays` is the observable trace of a
crash the exactly-once contract absorbed. The crafted-journal crash test now asserts the
crash is on the meter, and the Exhibit prints the organism's twine line.

## Verification

SmokeHouse 75 tests, Sizzle 7, Twine 4, WholeHog 12 — all green, zero warnings.
