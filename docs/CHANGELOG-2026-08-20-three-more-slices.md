# 2026-08-20 — three more slices: the range travels, the meter travels, the run is a seed

## Slice 1 — range over the wire (`OP_RANGE`)

The protocol could count a range (`countRange`) but never deliver one — a wire client that
wanted the records had to get them key by key. `OP_RANGE` fetches `[lo, hi]`'s records in
key order; the reply is materialized on both ends, so the memory bound is the range's size —
the same honesty `countRange` always demanded of its callers, now stated on the fetch too.
Client: `wire.rangeQuery(lo, hi)` → records in key order. Metered under `rangeQueries`.

## Slice 2 — stats over the wire (`OP_STATS`)

The wire's own meter, readable by its clients: `wire.stats()` returns the server's
`WireStats` — observability reaching the wire's far end. Reading the meter is deliberately
NOT metered: a meter that counts being read muddies every reading. The test pins both the
faithful round-trip and the non-metering.

## Slice 3 — the run is a seed (`importSorted` / `seedFrom`)

Pure composition, no new machinery: the sorted run's decoder meets SmokeHouse's existing
`importInto` bulk seam. `SmokeHouse.importSorted(dir, opts, run)` builds a FRESH store from
a scan run — the run's state only: no log history, no tombstones, no generations. Composed
in the organism as `Organism.seedFrom(archive, dir)`: extract just `scan.run` from a cold
archive and get back a fully queryable store (order statistics included) of the preserved
moment, without inflating a single segment. When the log itself matters,
`Jerky.restore` + `SmokeHouse.restore` remains the full-fidelity road — the javadoc says so.

## Verification

SmokeHouse 74 tests, SmokeSignal 9, WholeHog 12 (the wire-agreement and history oracles
extended in place) — all green, zero warnings.
