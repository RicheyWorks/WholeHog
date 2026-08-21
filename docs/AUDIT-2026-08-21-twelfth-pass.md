# 2026-08-21 — the twelfth pass: SmokeHouse core, deeper

The eleventh pass swept the four un-hunted territories at the surface (SH-1, R-1, W-1). This pass
cuts deeper into the one engine with the widest blast radius, **SmokeHouse core** — the parts the
eleventh pass named but did not open: the `IndexedStore` fan-out and the replication apply path.
Method unchanged: adversarial read of the consistency- and crash-critical seams, one real finding
beats padded noise, every fix probe-verified.

## RP-1 · S2 · FIXED — a replica could apply a non-contiguous frame and hold a state that never existed

A `Replica` bootstraps from a shipped backup, then applies the primary's tail frames through its own
`put`/`delete`. The apply loop trusted the wire completely: it read each frame's `sequence`, applied
it, and set `appliedSequence = sequence` — **without ever checking the sequence was contiguous**. Its
entire safety rested on the server sending `FRAME_GAP` before any gap, which the server derives from
the tail's drop-oldest signal.

That signal is not airtight. In `Tail.Sub`, a dropped event sets a single `volatile boolean gapped`
on the **producer** side (`offer`, after it has already `poll()`ed the victim and `offer()`ed the
new event), and the **consumer** side (`run`) checks-and-clears it before each delivery. There is no
happens-before tying the `gapped = true` write to the consumer's `take()` of the first surviving
event, so `onGap` can be delivered one event *late* — after a surviving, non-contiguous event rather
than before it. The server frames that event and ships it; the replica, checking nothing, applies it
and only then receives `FRAME_GAP`. The result is a replica holding a **suffix without its prefix** —
a state that corresponds to no point in the primary's history. For an auto-re-bootstrapped replica
the next tick heals it, but a report-only replica (PitBoss's `autoRebootstrap=false`) keeps serving
that torn state, breaking the documented "consistent-but-stale prefix … honest, never wrong."

Fix (defense in depth, in the replica where it belongs): the apply loop now refuses a frame whose
`sequence != appliedSequence + 1` — a hole means events were missed, so it gaps, keeps the clean
prefix it has, and lets the operator re-bootstrap. This makes the replica correct regardless of any
timing weakness in the tail's gap signal, at the cost of one comparison per frame. (The tail's
flag-based gap detection is left as-is: with the replica now self-checking, the signal is an
optimization, not the sole guarantee. Tightening the tail so `onGap` is always positioned exactly at
the drop is a possible follow-up, but the contiguity check is the load-bearing correctness fix.)

**Probe:** `ReplicationTest.aReplicaGapsOnANonContiguousFrameInsteadOfApplyingAHole` — a fake primary
ships an empty backup (baseSequence 0), one good frame (seq 1), then a frame that skips seq 2 (seq 3),
exactly as a late `FRAME_GAP` would let through. The replica must gap and must not apply the post-hole
frame. It fails on the unfixed apply loop (which applies seq 3 and never gaps) and passes with the
fix. SmokeHouse 77 green, javadoc clean.

## Examined and found sound (this pass)

- **IndexedStore fan-out** — `put` runs every extractor and stages the whole new fan-out *before*
  the primary write (a rejected value leaves the store untouched), reads the old value first, and
  retracts stale index entries by recomputing them from that old value (pure extractors by
  contract); `delete` mirrors it. Idempotent re-puts, attribute changes, and per-key composite
  distinctness all fan out correctly. `Builder.build` backfills every index from the recovered store
  and — unlike the Organism before W-1 — already closes the store if a rebuild throws. Clean.
- **ReplicationServer / FrameWriter** — subscribe-before-backup makes the ship race-free by the same
  replace-idempotent argument as Renderer's bootstrap; `onEvent`/`onGap`/`flushAndGoLive` are all
  synchronized on the writer and correctly defer a during-bootstrap gap (`pendingGap`) and stop
  framing once `dead`. The only related weakness is the tail's gap-signal placement, now covered on
  the replica side by RP-1.
- **Replica bootstrap** — the shipped-file loop validates names against path traversal, reads exact
  sizes, and fails loudly on a short stream; `lagSequence`/`awaitCaughtUp` use the tail-sequence
  arithmetic correctly.

## Still open

SmokeHouse core still has surface if wanted: the wire protocol internals beyond the tenth pass's
framing ADR, the CSRBT index-tier selection path, and the pilot/auto-compaction cost model. Otherwise
the ecosystem has now had eleven engines hunted across twelve passes.
