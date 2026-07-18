# WholeHog

[![CI](https://github.com/RicheyWorks/WholeHog/actions/workflows/ci.yml/badge.svg)](https://github.com/RicheyWorks/WholeHog/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 17](https://img.shields.io/badge/Java-17-orange.svg)](https://adoptium.net/)

Engine twelve of the ecosystem: **the integration organism** — the whole hog. Eleven engines
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
    o.wire().get(k);                          // the wire: loopback reads
}
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

## The ecosystem

Engines 1–6: [CSRBT](https://github.com/RicheyWorks/CSRBT) (index) · [SuperBeefSort](https://github.com/RicheyWorks/SuperBeefSort) (intake) · [SmokeHouse](https://github.com/RicheyWorks/SmokeHouse) (store) · [Carver](https://github.com/RicheyWorks/Carver) (read planner) · [Renderer](https://github.com/RicheyWorks/Renderer) (materialized views) · [Brine](https://github.com/RicheyWorks/Brine) (adaptive cache).
Engines 7–11: [PitBoss](https://github.com/RicheyWorks/PitBoss) (fleet conductor) · [DryAge](https://github.com/RicheyWorks/DryAge) (time travel) · [Twine](https://github.com/RicheyWorks/Twine) (atomic batches) · [SmokeSignal](https://github.com/RicheyWorks/SmokeSignal) (the wire) · [Jerky](https://github.com/RicheyWorks/Jerky) (cold archives).
Engine 12: **WholeHog** (this repo) — all of them, at once.

## Build

```bash
# Requires ALL ecosystem repos cloned as siblings (this is the point)
./gradlew build     # the composed oracle suite — the slowest suite in the ring, knowingly
./gradlew run       # the exhibit
```

Java 17+, Gradle 9.5.1 (bundled wrapper). MIT license.
