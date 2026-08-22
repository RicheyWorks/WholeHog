# Slice — 2026-08-22: record-granularity as-of

DryAge's own doctrine named this seam and left it uncut: *"Coordinates are generations, not
timestamps — v1's granularity is 'when you called preserve'. Record-granularity as-of (a
bounded-recovery stop condition on `SmokeHouse.open`) is the named next seam, to be cut upstream
when a consumer shows the generation granularity isn't enough."* This slice cuts it, with the
demonstrating consumer in hand.

## The consumer that showed the need

A caller preserves a store **once**, after a whole run of mutations, and later needs an
*intermediate* state that no `preserve` ever captured. Generation coordinates can only answer "the
moment you called preserve" — here, the final state. Nothing in the vault could reconstruct "the
store three mutations in, before that delete." That is the gap `DryAgeTest`'s
`recordGranularityReconstructsAMomentNoGenerationCaptured` puts on the record: it preserves once
after `put/put/put/delete/put`, then reads back the store as of records 0, 2, 3, and 4 — moments a
single generation could not hold — checking each against a hand-built oracle, with order statistics
(first/last/nth) rebuilt over the bounded prefix.

## The cut — two engines, one seam

**SmokeHouse (0.2.1 → 0.3.0, minor — new capability).** `open` is factored into a private
`recover(dir, opts, applyLimit)`; the public `open` is `recover(…, -1)` (unbounded) and the new
`openAsOfRecord(dir, opts, maxRecords)` is `recover(…, maxRecords)`. A bounded recovery replays
exactly the first `maxRecords` records of the log in write order and stops. It **bypasses the hint
checkpoint** — the hint describes the log's *final* state and is meaningless for a prefix, so a
bounded open always cold-scans. The stop lives in `SegmentLog.scanBounded`, which stops across
segment boundaries so a small bound never reads a large tail; every index tier and read surface is
then built over the prefix state exactly as a normal open builds over the whole. `countRecords(dir)`
(and `SegmentLog.countRecords`) reads the segment files **read-only** — no active segment is rolled
— so it can count a preserved, immutable generation in place.

**DryAge (0.2.1 → 0.3.0, minor).** `asOf(generation, upToRecords)` opens a generation as of a record
prefix, on the same scratch copy `asOf(generation)` already uses (the vault stays pristine);
`recordCount(generation)` reports the generation's record count so a caller can choose a bound. Both
public `asOf`s now share one `openView(generation, applyLimit)` helper, so the tenth-pass D2
scratch-cleanup guard covers both paths.

## Honest bound, stated loudly

The record coordinate is faithful **only for an uncompacted generation**. A generation's segments
are the live log's bytes at preserve time — `backup` copies segment prefixes and never compacts — so
a generation preserved before any compaction replays mutation by mutation. If the live store had
already compacted, overwrites and tombstones were reclaimed before capture, and "record N" counts
the *surviving* records, not the original history. The generation is still whole and readable; it is
simply no longer replayable step by step. This bound is documented on `SmokeHouse.openAsOfRecord` and
in DryAge's class notes.

A bounded store is **read-only**, like every historical view: the recovered index reflects only the
prefix while the log file still holds every record, so a write would append past a tail the index
does not know. `AgedView` already carries the read-only contract.

## Verification

Full WholeHog composite `BUILD SUCCESSFUL`; SmokeHouse 79 tests green, DryAge 10 (including the new
probe), zero javadoc warnings on both. The probe is structural — it asserts reconstructed states
against an oracle, never a clock — so it cannot flake.
