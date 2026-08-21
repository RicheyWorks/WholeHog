# 2026-08-21 — the eleventh pass: SmokeHouse core

The tenth pass closed all 26 of its candidates. This pass opens the first of the four territories
the tenth left un-hunted: **SmokeHouse core** — the foundational log-structured store every other
engine wraps (Twine, DryAge, Jerky, the wire, replication). A defect here has the widest blast
radius, so it went first. Method: adversarial read of the crash-safety- and concurrency-critical
paths — the record codec, the segment log, the hint checkpoint, compaction, and the read paths —
with the house rule that fewer real findings beat padded noise, and every fix probe-verified.

## SH-1 · S2 · FIXED — a range() overlapping a compaction commit threw on a healthy store

`get()` reads its index entry under the lock, then reads the record **outside** it, and carries a
retry for exactly one reason it states: "a read can overlap a compaction commit that just repointed
its entry into the merged segment." `range()` had the same shape — snapshot the entries under the
lock, stream the values outside it — but **no** such guard. A compaction that committed between the
snapshot and a per-entry read deletes the old segment the snapshot names (compaction rewrites all
closed segments into one and deletes the originals under the lock), so `range()` threw
`"index pointed at an unreadable record"` — or an `UncheckedIOException`/`ClosedChannelException`
from the deleted file — on a perfectly healthy store. Auto-compaction is ridden by the pilot on the
write path, so this is reachable in ordinary operation, not just under an explicit `compact()`.

The fix has two layers, because the probe surfaced that the naive one-shot re-resolve was not
enough on its own:

1. **`SmokeHouse.range` now re-resolves per entry (bounded loop).** Each read that races a commit
   re-resolves the key's current location under the lock and retries; a key deleted or evicted
   mid-range is simply no longer live and is skipped (matching `get()`'s re-resolution to the
   current state). Bounded at 8 attempts so a genuine log/index divergence still surfaces rather
   than spinning. `get()`'s single-retry was bumped to the same bound — the same race, a smaller
   window (one key), but no reason to leave it at one.

2. **`SegmentLog.read` tolerates a cached reader channel closed under it.** `commitCompaction`
   closes victim reader channels (Windows cannot delete an open file), and it could close the exact
   channel a concurrent `read()` was mid-stream on → `ClosedChannelException`, even for a
   freshly-re-resolved location. The read now drops the stale cache entry and reopens (bytes at a
   committed offset never change, so a re-read is exact); if the segment itself was deleted the
   reopen throws `NoSuchFileException` and the caller re-resolves. This also hardens `get()` and
   every other reader for free.

**Probe:** `EleventhPassProbeTest.rangeSurvivesAConcurrentCompactionCommit` — one reader ranging the
whole keyspace against one writer that overwrites every key (piling up garbage) and compacts, over
and over. The keyset never changes, so every range must return exactly N keys and never throw. It
failed on the unfixed store (`ClosedChannelException` / `NoSuchFileException` out of `range`) and is
now stable across repeated runs. SmokeHouse 76 green, javadoc clean.

## Examined and found sound (this pass)

- **RecordCodec** — encode's CRC region (`flags..value`, big-endian) matches decode's byte-for-byte
  recomputation; torn/clean-EOF/oversize-length handling is correct. (One marginal note, not filed:
  decode allocates the value buffer up to `MAX_VALUE_BYTES` before the CRC check, so a corrupt-but-
  in-bounds length on a torn tail costs a transient allocation — bounded, local-only, and only on
  the last record.)
- **SegmentLog compaction commit/recovery** — the marker → delete → rename protocol and
  `finishPendingCompaction`'s case analysis are symmetric and idempotent across a crash at every
  step; the "all closed segments as one range" invariant is what stops a tombstone from being
  reclaimed before the dead records it shadows, so no resurrection.
- **HintFile** — whole-file CRC is validated first, then every entry is re-validated against the
  current segment sizes and strict key ordering; any doubt returns `null` → full scan.
- **put/delete garbage accounting** — both count the superseded record's bytes as garbage; a
  tombstone is counted as born-dead in its own segment. No undercount found.

## Still open

The eleventh pass has only just begun. SmokeHouse core had one real finding (SH-1). The other three
territories — Carver/Renderer/Brine/PitBoss; Rub/Sizzle; the WholeHog wiring sweep — are un-hunted.
