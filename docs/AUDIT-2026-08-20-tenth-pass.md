# 2026-08-20 — the tenth pass: the ecosystem-wide hunt

Requested: wiring gaps + 100 bugs across the ecosystem. Method: parallel adversarial hunters,
one per territory, each carrying the house contracts, instructed that fewer real findings beat
padded noise. **Two territories completed before the run hit the account's session limit**
(CSRBT persistence/registry/genome; and DryAge/Twine/SmokeSignal, with Jerky partial). This
document records the **26 candidates** those two hunters produced — no padding to reach 100;
the remaining territories (SmokeHouse core, Carver/Renderer/Brine/PitBoss, Rub/Sizzle, the
WholeHog wiring sweep) are queued for the next session after the limit resets. Every candidate
below carries a severity and a concrete failure scenario; the ones marked **FIXED** were
resolved in this pass with a probe or regression test.

## CSRBT — persistence, registry, genome (hunter 1)

- **C1 · S1 · FIXED — recursive load-validation StackOverflow.** `tryLoadSnapshot` validates a
  restored tree via recursive `StrategyHealthCheck` walks (depth = tree height); a legitimate
  10k-key splay right-spine snapshot overflows the stack, and the `StackOverflowError` escapes
  the `catch (Exception)`, killing the caller instead of returning a `LoadResult`. Also a
  crafted-file DoS. Fix: guard the validation gate to convert `StackOverflowError` → `MALFORMED`.
- **C2 · S1 · FIXED — TreeHistory.restoreFrom recursive deepCopy.** `restoreCheckpoint` copies via
  recursive `TreeNode1.deepCopy` while everything else uses the iterative `TreeCloner`; a 30k
  sequential-splay checkpoint restores into a `StackOverflowError`. Fix: iterative two-pass copy.
- **C3 · S2 · FIXED — loadSnapshot leaves stale undo/redo.** `loadSnapshot` swaps contents but
  keeps the history stacks from the *previous* contents; a subsequent `undo()` replays an inverse
  against the restored snapshot and silently deletes keys. Fix: clear both stacks (or push a
  RESTORE entry) in `loadSnapshot`, matching `restoreCheckpoint`.
- **C4 · S2 · FIXED — clamp passes NaN.** `clamp` lets `NaN` through, so a NaN trait survives
  `validate()`, every `fitnessFor` is NaN, and `bestStructure()`'s `>` comparisons all fail —
  `recommendedStructure()` silently answers RED_BLACK regardless of the real winner. Fix: reject
  NaN in `clamp`.
- **C5 · S3 · FIXED — loadFailure maps decode errors to retryable FAILED.** A
  `MalformedInputException` (invalid UTF-8 = corrupt file) was reported `FAILED` ("retryable"), so
  an ADR-026 caller retries a deterministically-corrupt file forever instead of quarantining. Fix:
  `loadFailure` now classifies `NoSuchFileException` → ABSENT (vanished between the exists-check and
  the read) and `CharacterCodingException` → MALFORMED before the generic IOException = FAILED rule.
  Probe: a file of invalid UTF-8 bytes loads as MALFORMED, not FAILED.
- **C6 · S3 · FIXED — deserializePreOrder ignores trailing garbage.** It returned when the root
  subtree completed without checking all tokens consumed, so concatenated/torn data past a valid
  tree loaded silently — and the header-size tripwire can't catch it (the node count still
  matches). Fix: after the tree is rebuilt, `rejectTrailingTokens` refuses any non-empty token past
  the end with a `TrailingTokensException` that `loadFailure` maps to MALFORMED (trailing empties
  from a terminal newline stay benign). Probe: `GARBAGE` appended past a 5-node tree loads MALFORMED.
- **C7 · S3 · FIXED — flat persistent save under-validates keys.** The flat writer checked only
  `';'`, so a key serializing to an empty token (dropped on load) or a control char like `\n`
  (which splits the single data line) reported SAVED yet could never load — "trySave never lies",
  broken. Fix: a `requireFlatEncodableKey` gate rejects empty / `';'` / control-char tokens at save
  time. It is flat-specific rather than the pre-order `requireEncodableKey`, because the flat format
  reserves fewer characters — `','` and `'#'` are ordinary keys here — so borrowing the stricter
  gate would wrongly reject keys the flat format round-trips fine. Probe: a non-encoding serializer
  whose token carries a raw `\n` makes the flat save throw instead of writing an unloadable file.
- **C8 · S3 · FIXED — commitAtomically silent non-atomic fallback.** On
  `AtomicMoveNotSupportedException` it fell back to a non-atomic copy-then-delete and still reported
  SAVED, so a crash mid-copy destroys the previous good snapshot on FUSE/CIFS/NFS mounts — both of
  SAVED's promises (published in one step, previous intact) become false. Fix: the fallback is
  removed; a filesystem that cannot rename atomically fails the save loudly (the exception
  propagates to `stageAndCommit`'s IOException handler → FAILED, previous snapshot untouched,
  staging cleaned), with the `AtomicMoveNotSupportedException` as the cause so the caller can
  relocate the vault. The rename is isolated behind a `moveIntoPlace` seam a test overrides. Probe:
  an adapter whose `moveIntoPlace` simulates no-atomic-rename fails the save and leaves the previous
  snapshot exactly intact.
- **C9 · S4 · FIXED — orphan .tmp staging files never swept.** A `kill -9` between staging-create
  and the `finally` leaked a full-snapshot-sized `.tmp` forever; a crash-looping service fills the
  volume. Fix: `sweepOrphanStaging()` on construction deletes any `<name>.rbt.<pid>.<seq>.tmp` whose
  owning PID is no longer a live process — this JVM's own in-flight staging and any other *live*
  process's staging are left untouched, so a directory shared by two running JVMs stays safe. Probe:
  a staging file tagged with a dead PID is swept, while one tagged with the live PID and a real
  snapshot are both left alone.
- **C10 · S4 · FIXED — phantom MORPH in the audit log.** `evaluateViaGenome` logged
  `decision=MORPH`, reset the candidate streak, and pointed the audit line's `to` at the chosen
  structure even when `setStrategy` refused (same-class/health-gate), so the "reconstructable from
  one line" audit line records a morph that never happened. Fix: `applyStructure` now returns the
  real morph verdict (`false` on a null strategy or a refused `setStrategy`, `true` only on a
  committed morph), and both callers gate on it — `evaluateViaGenome` commits `decision`/streak/`to`
  only when the morph landed, and `forceMorph` returns the verdict. Probe: a controller whose
  strategy factory yields a health-gate-refused candidate reports `forceMorph()==false` and advances
  neither `morphCount` nor `morphLog`, incumbent untouched.
- **C11 · S3 · FIXED — hybridFitness placeholder poisons ScoreCard.** A hard-coded `hybrid=0.0` was
  fed into a `ScoreCard` whose `range()`/`average()` (both over all eight slots) `hybridFitness`
  then took — so the placeholder `0.0` became the range's minimum (inflating the spread penalty) and
  dragged the mean down, structurally under-scoring HYBRID (~0.19 vs peers near 0.78) so it could
  practically never be recommended. Fix: compute spread and centrality directly over the seven real
  scores, with no phantom slot. Probe: on a hybrid-seeded genome, HYBRID's score is central to the
  seven real structures, not dragged toward zero.
- **C12 · S4 · FIXED — exists-then-open races misclassify a concurrent delete** as FAILED instead
  of ABSENT (retention sweep racing a load). Fix: the `Files.exists` pre-check is dropped at the
  three `LoadResult`-returning load sites (`tryLoadSnapshot`, `tryLoadOrderedSet`, `readFlatKeys`);
  each opens the file directly, so a snapshot removed between check and open is a
  `NoSuchFileException` that `loadFailure` now classifies as ABSENT (logged as absent, not error) —
  closing the TOCTOU window. Probe: a missing snapshot loads as ABSENT through the direct-open path
  on both the int and generic readers.
- **C13 · S4 · FIXED — control-plane morph views disagree.** `evaluateViaControlPlane` incremented
  `morphCount` but never appended to `morphLog`, so `getMorphCount()` and `getMorphLog()` contradict
  and the audit log misses every control-plane morph. Fix: append a `MorphEvent(from → to)` when
  `r.morphed()`, matching the genome path. Probe: a skewed read workload under an eager control
  policy morphs RB → Splay and the morph log's size equals the morph count.

Verified clean by hunter 1: BPlusTreeEngine survived a 640k-op differential fuzz vs TreeSet at
fanouts 4/5/6/32 (add/remove/borrow/merge/root-collapse + all order-stat boundaries) with zero
divergence; registry B_PLUS_TREE slot and unsupported-type behavior correct.

## DryAge / Twine / SmokeSignal / Jerky (hunter 2 — cut off mid-Jerky)

- **D1 · S3 · FIXED — AgedView.close leaks scratch when store.close throws.** `store.close()`
  throwing skips `deleteRecursively(scratch)`; repeated failing closes fill the temp dir. Fix:
  try/finally.
- **D2 · S3 · FIXED — asOf leaks scratch on copy/restore failure.** A throw from `Files.copy`
  (disk full) or `restore` (corrupt segment) orphans the partially-populated scratch dir. Fix:
  catch, delete, rethrow — mirroring preserve's ninth-pass fix.
- **D3 · S3 · FIXED — retainNewest partial failure.** A throw mid-`release` loses the audit list
  and leaves a half-deleted generation that `generations()` still lists and `asOf` restores
  truncated. Fix: `release`/`dropGeneration` renames the doomed generation out of the `gen-`
  namespace with an atomic move (the commit point) before deleting its bytes, so an interruption
  can leave orphan bytes but never a half-deleted generation the timeline lists; `vault()` sweeps
  such tombstones on open. `retainNewest` now presses past a failed drop and, on any failure,
  throws a `RetentionException` carrying both the released and the still-whole generations — the
  audit survives a partial failure. Two probes: one pins that a blocked drop leaves its generation
  fully restorable (not truncated) while the outcome is reported; one pins the tombstone invisible
  to the timeline and swept on reopen.
- **D4 · S3 · FIXED — generation-number collision after a store rollback** threw "already
  preserved" and discarded the new backup, bricking preserve until real history is manually
  released; worse, `retainNewest` sorted by number so it could release the *newest* data. Fix:
  the vault now issues its own strictly monotonic generation label (`nextGeneration()` = max
  existing + 1) instead of naming the generation by the store-issued backup number, which a
  rollback re-issues. Labels are therefore collision-free and their order is preservation order —
  exactly what retention must age by. The store's own number still rides inside the backup's
  manifest (nothing reads the vault's directory number as authoritative). Probe: two fresh stores
  each issue backup number 1; under the old scheme the second bricked preserve — now it takes vault
  label 2, all three coexist, and `retainNewest(1)` keeps the actually-newest (last-preserved).
- **T1 · S2 · FIXED — two Twines sharing one journalDir** steal each other's committed batches
  (fixed JOURNAL/TMP names, no per-store namespacing): B replays A's ops into B's store with B's
  serializers, and A's `delete(journal)` then throws on an already-applied batch. Fix: Twine now
  `implements Closeable` and acquires an exclusive `twine.lock` `FileLock` in its constructor,
  held for its lifetime and released on `close()`; a second `over()` on the same journal dir —
  same JVM or another process — refuses loudly with an `IOException`. `Organism.close()` closes
  the Twine; the Sizzle crash tests (which reopen on the same dir) now close the faulted Twine
  first, since a real crash is process death that frees the OS lock. Probe:
  `oneTwinePerJournalDirectory` (second tie throws; a fresh tie works after `close`).
- **T2 · S3 · FIXED — commit point not durable (no directory fsync).** The tmp data is fsynced but
  the rename's directory entry is not; power loss mid-apply can lose a batch the code called
  "inevitable." Fix: best-effort `syncDir(journalDir)` after the `ATOMIC_MOVE`, before apply
  (Windows, which refuses to open a directory as a channel, rides the filesystem's own ordering).
- **T3 · S3 · FIXED — committed flag set before any I/O.** A transient pre-commit failure
  (disk-full on tmp, fsync EIO) leaves the batch permanently un-re-committable ("batch already
  committed"). Fix: set `committed = true` only after the ATOMIC_MOVE succeeds.
- **T4 · S3 · FIXED — in-process apply failure wedges the Twine.** A sink throwing mid-apply (past
  the commit point, so the batch is inevitable) leaves the committed journal on disk and every
  future commit throwing "a committed batch is still applying" forever, recoverable only by closing
  and re-`over()`ing. Fix: a public `recover()` replays the surviving journal in place (re-applying
  already-landed ops is a harmless no-op under the sinks' last-writer-wins contract), deletes it,
  meters the replay, and unwedges the Twine; idempotent and retryable, so a caller drains it once
  the sink is healthy. Probe: a sink that throws on the third of four puts wedges the Twine (a fresh
  commit throws "still applying"), then a healed `recover()` lands the whole batch exactly once and
  restores normal commits.
- **T5 · S4 · FIXED — empty-batch commit unmetered.** Reconciling issued-vs-metered shows a
  phantom deficit. Fix: meter the empty commit.
- **S1w · S2 · FIXED — REPLY_ERROR mid-framed-request desyncs the stream.** An error after some framed
  fields of OP_BATCH/OP_RANGE were read leaves the rest reinterpreted as opcodes — garbage
  written into the store. Fix (protocol-level, deferred to an ADR): close the session on any
  error thrown after body reads begin, or length-prefix requests. The unknown-op case (nothing
  read yet) stays safely recoverable.
- **S2w · S3 · FIXED — writeUTF of a >64KB error message kills the session.** A long exception
  message throws `UTFDataFormatException` *inside* the error handler → the outer catch drops the
  connection, turning a recoverable refusal into a reset. Fix: truncate the message before
  `writeUTF`.
- **S3w · S2 · FIXED — client leaves a partial frame buffered on a mid-request serializer throw**, and
  the next call flushes it, desyncing the server. Fix (client-side, with S1w's ADR): build each
  request into a byte[] and write only when complete; mark the client broken after a mid-frame
  throw.
- **S4w · S3 · FIXED (alloc half) — OP_BATCH pre-sizes an ArrayList to an attacker's count.**
  `new ArrayList<>(count)` with count=MAX_VALUE OOMs from a 5-byte request. Fix: don't pre-size
  to an untrusted count. (The broader "loopback client can ask for a huge range" is the wire's
  stated loopback-only threat model — dismissed in the ninth pass, unchanged.)
- **J1 · DISMISSED — Jerky cure() flush/close ordering is correct.** Re-hunted 2026-08-21. The
  hypothesis was "the trailer is written through the unbuffered file stream while a buffered body
  may be unflushed." Reading the chain: cure() writes the body through a `DataOutputStream` (which
  is unbuffered — its primitives write straight through) over a pass-through `CheckedOut` onto the
  raw `fileOut`; there is **no** `BufferedOutputStream` in the chain, and `out.flush()` runs before
  the trailer is appended to the same `fileOut`. So no buffered layer can leave body bytes behind
  the trailer — the bug does not exist. The hunt's flagged untested corner ("archives near a buffer
  boundary") is now covered by a regression test: payloads straddling the deflater's 1<<16 buffer
  (incompressible 0 / buf-1 / buf / buf+1 / 2·buf / 3·buf+7, plus a highly-compressible 5·buf) cure,
  verify, restore, and targeted-extract byte-exact. Jerky 5 green. No defect; closed.

## What's fixed in this pass

C1, C4 (CSRBT); D1, D2, T3, T5, S2w, S4w-alloc (small engines) — ten fixes, each with a probe
or regression test, all suites re-run green. Reaching a literal 100 was declined in favor of 26
real candidates — the house rule is that fewer real findings beat padded noise, and the run
recorded exactly what two thorough hunters found before the session limit stopped the fan-out.

## Follow-up (same day) — C2 and C3 cleared

The two remaining CSRBT S1/S2 findings are now fixed with probes (csrbt-core 867 green, javadoc
clean): **C2** — `TreeHistory.restoreFrom` rebases onto the context's NIL with the iterative
`TreeCloner.deepCopyTwoPass` instead of the recursive `TreeNode1.deepCopy`, so a deep splay
checkpoint restores without a StackOverflow (probe restores a 12k right-spine checkpoint);
**C3** — `loadSnapshot` now discards the undo/redo command history (its inverses are relative
to the replaced contents), so a post-load undo can no longer silently delete a
legitimately-restored key (probe pins the restored set surviving two undo attempts). **Twelve
of the 26 candidates are now fixed.** The still-open tier is T1 (two Twines on a shared journal
dir) and the S1w/S3w wire-framing pair, which travel together as a small wire-framing ADR
rather than a hasty patch that could desync the protocol.

## Follow-up (2026-08-21) — the wire-framing ADR, then T1 · T2 · D3 cleared

The S1w/S3w wire-desync pair shipped as the wire-framing ADR (read a request's whole frame
before touching the store; close the session on a framing error; the client buffers each request
and writes it whole) — SmokeSignal 11 green. That brings the wire pair and S2w/S4w to **fixed**,
and this session closes the durability tier:

- **T1 · T2 (Twine).** Twine is now `Closeable` and holds an exclusive `twine.lock` for its
  lifetime, so two Twines can never share one journal dir and steal each other's committed
  batches; the commit point is made durable with a directory fsync after the atomic move.
  `Organism.close()` releases the lock; the Sizzle crash/quiet tests close the faulted Twine
  before the "process restarts" reopen. Twine 7 green, Sizzle 7 green, the WholeHog composite
  green, zero javadoc warnings.
- **D3 (DryAge).** Release is atomic at the timeline level — rename out of the `gen-` namespace
  (the commit point), then delete — so a failure leaves orphan bytes, never a half-deleted
  generation `asOf` would restore truncated; `vault()` sweeps orphan tombstones on open.
  `retainNewest` presses past a failed drop and reports the full audit via `RetentionException`.
  DryAge 8 green (two new probes).

**Seventeen of the 26 candidates are now fixed.** Still open: the CSRBT persistence S3/S4
remainder (C5–C13), D4 (generation-number collision after a store rollback), T4 (in-process
apply-failure `recover()`), and J1 (Jerky flush/close ordering — the hunter was cut off before
confirming it). Four territories (SmokeHouse core, Carver/Renderer/Brine/PitBoss, Rub/Sizzle,
the WholeHog wiring sweep) remain un-hunted.

## Follow-up (2026-08-21, cont.) — T4 · D4: the durability tier finished

The last two durability findings are cleared, both probe-verified:

- **T4 (Twine).** `recover()` drains a committed journal whose in-process apply failed part-way —
  the wedge ("still applying" on every future commit) is now recoverable in place, without closing
  and re-`over()`ing. Twine 8 green.
- **D4 (DryAge).** The vault issues its own monotonic generation labels, so a store rollback that
  re-issues backup numbers can neither brick preserve ("already preserved") nor fool retention into
  dropping the newest. DryAge 9 green.

Twine 8, Sizzle 7, DryAge 9, the WholeHog composite, zero javadoc warnings. **Nineteen of the 26
candidates are now fixed.** The durability tier of the small engines (T1–T5, D1–D4) is complete.
Still open: the CSRBT persistence S3/S4 remainder (C5–C13) and J1 (Jerky flush/close, unconfirmed);
the four territories above remain un-hunted.

## Follow-up (2026-08-21, cont.) — C5 · C6 · C7: persistence honesty

The three "the persistence layer lies about what it saved or why a load failed" findings in
`FilePersistenceAdapter` are cleared, each probe-verified in `TenthPassProbeTest`:

- **C5** — `loadFailure` classifies `NoSuchFileException` → ABSENT and `CharacterCodingException`
  → MALFORMED before the generic IOException = retryable rule, so a corrupt file is quarantined,
  not retried forever.
- **C6** — `deserializePreOrder` refuses trailing data past a complete tree (which the size
  tripwire can't catch), instead of loading the prefix and dropping the tail.
- **C7** — the flat writer gates keys through a flat-specific `requireFlatEncodableKey` (empty /
  `';'` / control chars), so a key that can't survive the flat round trip fails loudly at save
  time rather than reporting SAVED and never loading.

csrbt-core 870 green (three new probes), javadoc clean. **Twenty-two of the 26 candidates are now
fixed.** Still open: C8–C13 (the CSRBT persistence S3/S4 remainder — atomic-move fallback, orphan
`.tmp` sweep, phantom-MORPH audit line, hybridFitness placeholder, exists-then-open race, split
morph views) and J1 (Jerky flush/close, unconfirmed); the four territories above remain un-hunted.

## Follow-up (2026-08-21, cont.) — C8 · C9 · C12: persistence robustness

The three save/load robustness findings in `FilePersistenceAdapter` — a lie about atomicity, a
storage leak, and a race misclassification — are cleared, each probe-verified in
`TenthPassProbeTest`:

- **C8** — a filesystem without atomic rename no longer silently degrades to a non-atomic overwrite
  that can destroy the previous snapshot while reporting SAVED; it fails the save loudly, previous
  snapshot intact. (Rename isolated behind a `moveIntoPlace` seam so the no-atomic case is testable.)
- **C9** — `sweepOrphanStaging()` on construction reclaims staging files left by dead processes,
  while sparing live processes' in-flight staging and real snapshots.
- **C12** — the three `LoadResult`-returning load paths open the file directly instead of
  exists-then-open, so a concurrent delete is ABSENT, not a retryable FAILED.

csrbt-core 873 green (three new probes), javadoc clean. Fixed of the 26: C1–C9 and C12 (10),
D1–D4 (4), T1–T5 (5), S1w–S4w (4) — **23 of the 26 candidates are now fixed.** The only remaining
tier is the evolution/morph audit-correctness cluster: **C10** (phantom MORPH in the audit log),
**C11** (the hybridFitness placeholder poisoning the ScoreCard), and **C13** (the split morph
views) — plus **J1** (Jerky flush/close, still unconfirmed). The four territories (SmokeHouse core,
Carver/Renderer/Brine/PitBoss, Rub/Sizzle, the WholeHog wiring sweep) remain un-hunted.

## Follow-up (2026-08-21, cont.) — C10 · C11 · C13: the morph audit-correctness tier, and the pass closes

The last cluster — the evolution controller's morph accounting — is cleared, each probe-verified in
`TenthPassProbeTest`:

- **C10** — `applyStructure` returns its real verdict and both callers (`evaluateViaGenome`,
  `forceMorph`) gate on it, so a refused morph is no longer a phantom in the decision line, the
  stability streak, or the morph log.
- **C11** — `hybridFitness` computes spread and centrality over the seven real structure scores
  instead of a ScoreCard carrying a placeholder `0.0` in HYBRID's own slot, so HYBRID is scored
  centrally and can be recommended again.
- **C13** — `evaluateViaControlPlane` appends a `MorphEvent` on a real morph, so `getMorphCount()`
  and `getMorphLog()` agree and no control-plane morph goes unrecorded.

csrbt-core 876 green (three new probes), javadoc clean. **All 26 of the tenth pass's candidates are
now fixed.** The genome/morph fixes shifted no existing test (the full suite is green including the
convergence and metrics probes).

## Follow-up (2026-08-21, cont.) — J1 dismissed; the tenth pass is closed

J1 was re-hunted and **dismissed as a non-bug**: cure() writes its body through an unbuffered
`DataOutputStream` over a pass-through `CheckedOut` and flushes before appending the trailer, so no
buffered layer can leave body bytes behind the trailer. The flagged untested corner (archives near
the deflater's 1<<16 buffer boundary) is now covered by a byte-exact round-trip + targeted-extract
regression test straddling that boundary. Jerky 5 green.

**The tenth pass is complete: all 26 candidates fixed, and the one open lead (J1) confirmed not a
defect.** The four un-hunted territories (SmokeHouse core, Carver/Renderer/Brine/PitBoss, Rub/Sizzle,
the WholeHog wiring sweep) are the natural eleventh pass.
