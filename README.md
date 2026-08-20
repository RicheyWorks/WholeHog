# WholeHog

[![CI](https://github.com/RicheyWorks/WholeHog/actions/workflows/ci.yml/badge.svg)](https://github.com/RicheyWorks/WholeHog/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 17](https://img.shields.io/badge/Java-17-orange.svg)](https://adoptium.net/)

Engine twelve of the ecosystem: **the integration organism** — the whole hog. Thirteen engines
are each oracle-tested in isolation; WholeHog is where "in isolation" stops being the
qualifier. One store, every engine attached at once, one composed lifecycle, one oracle.

```java
try (Organism o = new Organism(root, seed)) {
    o.twine().batch().put(k, v).commit();     // writes: crash-atomic, index-fanned
    o.carver().query()...keys();              // reads: planned
    o.byAttr().top(3);                        // views: live off the tail
    o.brine().get(k);                         // cache: invalidated off the tail
    o.pitBoss().tick();                       // fleet: conducted
    o.preserveAndCure(archiveDir);            // history: preserved and dried
    o.wire().get(k);                          // the wire: reads off the primary
    o.wire().put(k, v);                       // wire writes: routed through every index
    o.vitals();                               // observability: the organism's pulse (Rub)
}

// ...and with a fault plan, the write path answers for its own crash-atomicity:
try (Organism o = new Organism(root, seed, ChaosPlan.crashOnceAtOp(3))) {   // Sizzle
    o.twine().batch().put(1, v).put(2, v).put(3, v).commit();   // throws Sizzle.Crash mid-apply
}   // reopen: Twine's journal replay re-drives every index — the batch landed exactly once
```

`./gradlew run` is the one-command exhibit: stand the organism up, churn it, print every
engine's vitals.

## What it proves

- **The four-subscriber tail test** — Renderer, Brine, a replica, and a watcher all ride one
  store's tail simultaneously and all converge on one churn. Plausible by contract; proven
  here.
- **The composed oracle** — one `TreeMap` checks the store scan, Carver's plans, Renderer's
  folds, Brine's reads, the replica's contents, the wire's replies, the vault's past, and the
  archive's round trip, on the same seeded stream.
- **The routing rules** — writes go through the `IndexedStore` (Twine ties over the sink seam
  this engine named — `Twine.over(indexed::put, indexed::delete, …)` — never the primary);
  the wire serves reads only. Composition mistakes become compile-visible or test-red here,
  not in production.

## Findings ledger

Discoveries this engine has forced upstream, on the record:

1. **Twine's sink seam** (found at design time): batches over an indexed store must fan out
   through the indexes; Twine gained `PutSink`/`DeleteSink` so composition routes correctly.
2. **The wire is read-only in a composed organism** — writes over SmokeSignal would bypass
   secondaries; deliberately unsolved and documented until a consumer needs it.
   **RESOLVED 2026-08-19:** the consumer arrived (this organism), SmokeSignal gained the
   `WriteRoute` seam, and the organism now serves the wire with writes routed through the
   `IndexedStore` fan-out. A wire client is a first-class writer; the oracle proves its puts
   reach every secondary, view, replica, and invalidation.
3. **The watcher wanted to be an organ** (2026-08-19) — the bare tail counter this organism
   used to prove four-subscriber convergence generalized into [Rub](https://github.com/RicheyWorks/Rub),
   engine 13. The organism now composes Rub as its fourth tail subscriber, and `vitals()` is the
   promoted watcher's readout — a gauge (store size, segments, live/garbage bytes) fused with a
   meter (puts/deletes/gaps observed).
4. **The write path wanted to be fault-injectable** (2026-08-19) — the sink seam finding (#1)
   earned its keep: [Sizzle](https://github.com/RicheyWorks/Sizzle), engine 14, wraps that seam,
   so the organism ties Twine over a chaos seam (transparent by default). The composed
   crash-atomicity — Twine's journal replay re-driving *every* index, not just the store — is now
   demonstrated in the oracle at every crash point, not asserted in a comment.
5. **Curing history needed no scratch copy** (2026-08-19) — `preserveAndCure` used to open an
   `AgedView` (a recovery pass on a scratch copy), re-back-up the view's store, cure the
   re-backup, and delete the staging: two copies and a recovery to read bytes that were CRC'd
   at capture. DryAge named `generationPath` for read-only archival consumers — `Jerky.cure`
   is read-only on its source by contract — and the dance is gone.
6. **Batches wanted to cross the wire whole** (2026-08-19) — with #2 resolved, half-applied
   wire batches became the next composition hazard. SmokeSignal gained `OP_BATCH` + the
   `BatchRoute` seam (the server reads the whole batch before touching the route, so the
   route decides atomicity); the organism ties the route to Twine, and any wire client gets
   crash-atomic multi-key batches — journaled commit, idempotent replay, index fan-out —
   without knowing Twine exists. The oracle proves the net effect lands exactly once,
   everywhere.

## The ecosystem

Engines 1–6: [CSRBT](https://github.com/RicheyWorks/CSRBT) (index) · [SuperBeefSort](https://github.com/RicheyWorks/SuperBeefSort) (intake) · [SmokeHouse](https://github.com/RicheyWorks/SmokeHouse) (store) · [Carver](https://github.com/RicheyWorks/Carver) (read planner) · [Renderer](https://github.com/RicheyWorks/Renderer) (materialized views) · [Brine](https://github.com/RicheyWorks/Brine) (adaptive cache).
Engines 7–11: [PitBoss](https://github.com/RicheyWorks/PitBoss) (fleet conductor) · [DryAge](https://github.com/RicheyWorks/DryAge) (time travel) · [Twine](https://github.com/RicheyWorks/Twine) (atomic batches) · [SmokeSignal](https://github.com/RicheyWorks/SmokeSignal) (the wire) · [Jerky](https://github.com/RicheyWorks/Jerky) (cold archives).
Engine 12: **WholeHog** (this repo) — all of them, at once.
Engines 13–14: [Rub](https://github.com/RicheyWorks/Rub) (observability) · [Sizzle](https://github.com/RicheyWorks/Sizzle) (chaos) — composed here as the fourth tail subscriber and the write-path chaos seam.

## Build

```bash
# Requires ALL ecosystem repos cloned as siblings (this is the point)
./gradlew build     # the composed oracle suite — the slowest suite in the ring, knowingly
./gradlew run       # the exhibit
```

Java 17+, Gradle 9.5.1 (bundled wrapper). MIT license.
