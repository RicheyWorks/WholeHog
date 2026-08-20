# 2026-08-20 — the ninth pass: hunting the week's new surface

Everything shipped since engines 13–14 were born had never had an adversarial pass: Rub,
Sizzle, the Write/Batch routes and the wire's new ops, the sidecar path (exportSorted /
scanSorted / importSorted, targeted extraction, scan-carrying preserves), retention, the
tail-ring seam, Twine's meter, and the organism's composition layer. This pass swept all of
it. Three findings confirmed and fixed, each with a probe or regression test; the rest of
the sweep's suspicions were examined and dismissed on the record below.

## Confirmed and fixed

**F1 — DryAge: a failed preserve leaked its staging directory into the vault, forever.**
`preserve` created `staging-*` inside the vault dir, then called `backup` (and now
`exportSorted`) with no failure handling — any throw abandoned the directory in the vault.
Fixed: a failed preserve deletes its staging and rethrows; the vault is left exactly as it
was found. Probe: preserve from a closed store must fail loudly AND leave zero `staging-*`
entries and an unchanged timeline.

**F2 — Twine: "one batch at a time" was documentation, not enforcement.**
`Batch.commit()` was `synchronized` — on the Batch, a one-shot object nothing else ever
locks — so two threads committing separate batches raced the shared `batch.twine.tmp` path
(overwrite, ATOMIC_MOVE collision, or a spurious "batch still applying"). Fixed: commit
serializes on the Twine itself, making the single-writer discipline true by construction.
External synchronization (WholeHog's wire batch route) keeps working, now redundantly.
Regression: 4 threads × 15 concurrent batches — every op lands exactly once, every batch on
the meter, no journal or tmp left behind.

**F3 — Jerky: the missing-name error path read the whole archive twice.**
`extract`'s not-found message called `names(archive)` — a second full read + CRC pass just
to say what IS archived. Fixed: names are collected during the same walk.

## Examined and dismissed

- `OP_RANGE` with `lo > hi` or over an empty store: inherits the store's own empty-range
  semantics; covered by the empty-range wire test.
- Wire `Batch.commit` marking `committed` before the send: matches Twine's own staging
  discipline; a failed batch is restaged, not retried — consistent by design.
- `Sizzle.Crash` escaping a wire batch route: an `IOException` kills the session rather
  than answering `REPLY_ERROR` — consistent with every store-level I/O failure on the wire
  (a broken store is not a bad request); the client sees its connection end.
- Rub attached to a rebootstrapped replica: observes the store it was born on; documented
  bound on `replicaVitals`, `close()` is safe on the dead subscription.
- `exportSorted` holding the store lock for the walk: identical discipline to `backup`.
- A hostile loopback client sending a huge batch count: loopback-only, no-auth is the
  wire's stated threat model since birth; unchanged.

## Verification

DryAge 6 tests, Twine 5, Jerky 4, WholeHog 12 — all green, zero warnings.
