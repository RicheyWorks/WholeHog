# 2026-08-19 — engines 13 and 14: the two findings, re-armed

WholeHog's CLAUDE.md carried a standing note: *"Rub (observability) and Sizzle (chaos) re-arm
from what this engine discovers."* Both were findings this organism had already made and left
as names. This change turns the names into engines and composes them here, under the one-oracle
rule — each joins `Organism` and `OrganismTest` in the same change.

## Rub — engine 13, observability

The four-subscriber test proved convergence with a **bare tail counter**: a `TailListener` on
`watchRange(0, MAX)` incrementing an `AtomicLong`. That counter was doing real observability
work with no name. It is now [Rub](https://github.com/RicheyWorks/Rub), a standalone engine.

- **A gauge fused with a meter.** `Rub.over(store)` subscribes the whole tail from now (the
  *meter*: puts, deletes, gaps counted as they commit); `sample()` reads the store's current
  size, segments, and live/garbage bytes (the *gauge*). `Vitals` carries both; ratios are
  derived on read so they cannot drift from their inputs.
- **Gaps are reported, not hidden** — a tail gap means the counters undercount, and
  `Vitals.gapFree()` surfaces it.
- **Depends on SmokeHouse only**, touching its public surface (`tail`, `size`, `segmentStats`,
  `garbageBytes`, `tailSequence`). The engine that watches everything reaches inside nothing.
- Wired into `Organism` as the fourth tail subscriber (`o.rub()`, `o.vitals()`); the old raw
  watcher is gone. `OrganismTest.theObservabilityOrganMetersTheComposedChurn` asserts metered ==
  committed and gauge == live set over one composed churn.
- Own suite: 4 tests green.

## Sizzle — engine 14, chaos

Finding #1 in this repo's ledger was Twine's sink seam: batches over an indexed store must fan
out through the indexes, so Twine gained `PutSink`/`DeleteSink`. [Sizzle](https://github.com/RicheyWorks/Sizzle)
is what makes that seam earn its keep.

- **Deterministic fault injection at the write seam.** `Sizzle.inject(put, delete, plan)` wraps
  Twine's sinks and throws `Sizzle.Crash` (a checked `IOException`) per a `ChaosPlan` —
  `crashOnceAtOp`, `crashEveryNthOp`, `crashWithProbability(seed, p)` (a splitmix64 hash of
  `(seed, op)`, reproducible), `.withLatencyMillis`. The crash precedes the delegate: the faulted
  op has not happened yet, the honest model of a process that died mid-apply.
- **Exactly-once under crash, demonstrated.** `SizzleTest` crashes the same batch at every op
  index in turn; after Twine's journal replays, the store's contents never change.
- Wired into `Organism`: writes route through a Sizzle seam over the indexed store (transparent
  under `ChaosPlan.none()`). `new Organism(root, seed, plan)` arms it.
  `OrganismTest.theOrganismSurvivesAChaosBatchOnItsWritePath` crashes a batch mid-apply, reopens
  the organism, and asserts Twine's replay re-drove **every** index — the primary, the
  secondary/interval indexes Carver plans over, and the Renderer fold — not just the store.
- Own suite: 4 tests green.

## Also: SmokeSignal grew server-side observability

So Rub's story could reach the wire, `SmokeSignalServer` gained connection/request counters and
a `WireStats stats()` (`o.wireStats()` on the organism). Purely additive; SmokeSignal's own suite
went 3 → 4 tests, still green.

## Verification

Full WholeHog composite green at **7 tests** (was 5). Ecosystem own-suites green:
SmokeHouse 70, Carver 9, Renderer 5, Brine 5, PitBoss 3, DryAge 2, Twine 4, SmokeSignal 4,
Jerky 3, Rub 4, Sizzle 4, WholeHog 7. `./gradlew run` stands up all fourteen engines and the
chaos demo recovers a crashed batch exactly once.
