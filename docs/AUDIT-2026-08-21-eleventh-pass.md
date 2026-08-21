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

## R-1 · S3 · FIXED — a failed view bootstrap leaked its tail subscription (Renderer)

`GroupView.register` bootstraps subscribe-then-sweep: it takes the tail subscription first, then
base-sweeps the current store state — an order the class documents as race-free because the fold is
replace-idempotent. But the sweep can throw — an unreadable record, or the caller's `groupOf` /
`weightOf` rejecting a value — and when it did, the exception propagated **without closing the
subscription just taken**. `Renderer.sumBy` only adds a view to its list *after* `register()`
returns, so a failed registration is a view nothing else holds: its subscription lingered on the
tail and folded every future mutation forever, on the tail thread, into an object no one could read
or close. Fix: `register` wraps the sweep and, on any failure, unsubscribes (`close()`, cleanup
failure suppressed onto the original) before rethrowing — the same "leave no dangling resource on the
error path" discipline as DryAge's D1/D2 and Jerky's cure().

**Probe:** `RendererTest.aFailedBootstrapDoesNotLeakItsTailSubscription` — a `groupOf` that throws
during the base sweep fails the registration; a subsequent store mutation must not re-invoke it. It
fails on the unfixed code (the leaked subscriber folds the new put, re-calling `groupOf`) and passes
with the fix. Renderer 6 green, javadoc clean.

## Examined and found sound — the Carver/Renderer/Brine/PitBoss row

- **Carver** (cost-based read planner) — correctness is independent of the plan: the driving path
  always returns a superset of the result and every other predicate is intersected, so a
  mis-estimate only costs performance, never rows. The driving-path result order is documented. The
  single-skip logic for a predicate the plan already drove is consistent between `drive()` and
  `run()`, including two predicates on one index. Clean.
- **Renderer/GroupView** (materialized aggregation) — the replace-idempotent fold makes the
  subscribe-then-sweep bootstrap genuinely race-free (double-application is a no-op); `addTotal`
  re-keys the ranked surface by (total, group) correctly, zero totals leave both maps, `top(k)`
  walks the order-statistics ranks with no off-by-one. Clean apart from R-1 (the error path).
- **Brine** (adaptive read-through cache) — coherence rides the tail under the same lock as reads;
  the documented commit→tail staleness window is the only staleness, and the tail invalidation
  always reconciles it. One note, not filed (situational and cross-module): `idOf` assigns a
  permanent dense id per key and is never trimmed, so a cache over an unbounded key universe grows
  without bound — but the evolution loop holds per-id state too, so this is an architectural
  bounded-universe assumption rather than a local leak Brine can fix alone.
- **PitBoss** (fleet conductor) — deliberately not a consensus system (documented non-goals);
  `promote` captures the dir before `close()`, `rebootstrap` and `tick` handle the gapped-replica
  path, duplicate replica names are rejected. Clean.

## W-1 · S3 · FIXED — the Organism constructor leaked every resource on a partial build (WholeHog)

The composition root opens about a dozen live resources in sequence — a store (with its tail
thread), a Carver, a Renderer with a tail-subscribed view, a Brine cache subscribed to the tail, a
PitBoss (a replication server plus a live replica), a DryAge vault, a Sizzle-wrapped write seam, a
Twine holding the journal-dir lock, a wire server socket, and two Rub observers. The constructor had
no guard: if any step threw, it completed abruptly and the caller received no reference to close what
had already opened, so a partial build leaked everything before the failure — a bound server socket,
a running replica, tail subscriptions folding forever, the store's threads. This is R-1's class of
bug at the composition level, and higher-impact. Fix: the build runs under a `try` whose `finally`
calls `unwindPartialBuild()` on failure, closing the opened resources in reverse (reading the fields,
which are `null` until assigned, so unopened organs are skipped; every close swallowed so cleanup
can't mask the original failure) — the same discipline as the leaf engines.

**Probe:** `OrganismTest.aFailedBuildUnwindsItsHalfBuiltResources` — a pre-existing
`journal/twine.lock` **directory** forces `Twine.over` to fail at construction, after the store,
renderer, Brine, and PitBoss are open. The proof is the replication layer's named threads
(`smokehouse-repl*`): a leaked PitBoss leaves its acceptor and the replica's apply thread alive, so
the count never returns to baseline. It fails on the unfixed constructor and passes with the fix.
(POSIX has no store-directory lock and open files stay deletable, so the leaked file handles alone
are invisible — the thread count is the deterministic observable.) WholeHog composite 14 green,
javadoc clean.

## Examined and found sound — Rub/Sizzle

- **Rub / Vitals** (observability) — the meter reads counters after the gauge so it never lies low
  (documented), `pulse()` takes the derivative of the two newest retained ticks in the right order,
  `since()` refuses swapped samples, and the derived ratios are computed, never stored. Clean.
- **Sizzle / ChaosPlan** (chaos) — the op counter advances even on an injected crash (the honest
  mid-apply model, so a retry gets past a once-at fault by op-index uniqueness), the probability rule
  is a pure splitmix64 hash of `(seed, op)` with correct `[0,1)` boundaries (the earlier
  `java.util.Random` correlation is gone), and `and()`/`withLatencyMillis` compose cleanly. Clean.

## Eleventh pass — status

Three territories hunted: **SmokeHouse core** (SH-1), the **Carver/Renderer/Brine/PitBoss row**
(R-1), and now **Rub/Sizzle + the WholeHog wiring sweep** (W-1) — three real findings, each
probe-verified, plus clean bills for the engines that held up. The un-hunted surface remaining is a
**deeper cut into SmokeHouse core** (IndexedStore's fan-out, replication's apply path, the wire
protocol beyond the tenth pass's framing ADR) if a twelfth pass is wanted.
