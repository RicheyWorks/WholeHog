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
- **C5 · S3 — loadFailure maps decode errors to retryable FAILED.** A `MalformedInputException`
  (invalid UTF-8 = corrupt file) is reported `FAILED` ("retryable"), so an ADR-026 caller
  retries a deterministically-corrupt file forever instead of quarantining. Fix: classify
  `CharacterCodingException` → MALFORMED, `NoSuchFileException` → ABSENT before the generic rule.
- **C6 · S3 — deserializePreOrder ignores trailing garbage.** Returns when the root
  subtree completes without checking all tokens consumed; concatenated/torn data past a valid
  tree loads silently. Fix: refuse with MALFORMED on unconsumed non-empty tokens.
- **C7 · S3 — flat persistent save under-validates keys.** The flat save path checks only `';'`
  while the pre-order path also rejects empty/`#`/control chars, so `trySaveSnapshot` reports
  SAVED for a key (e.g. containing `\n`) that can never load. Fix: apply `requireEncodableKey`
  in the flat writer. ("trySave never lies" is the contract broken.)
- **C8 · S3 — commitAtomically silent non-atomic fallback.** On `AtomicMoveNotSupportedException`
  it falls back to copy-then-delete and still reports SAVED, so a crash mid-copy destroys the
  previous good snapshot on FUSE/CIFS/NFS mounts. Fix: fail the save or document degraded mode.
- **C9 · S4 — orphan .tmp staging files never swept.** A `kill -9` between staging-create and
  the `finally` leaks a full-snapshot-sized `.tmp` forever; a crash-looping service fills the
  volume. Fix: sweep dead-PID `.tmp` files on adapter construction.
- **C10 · S4 — phantom MORPH in the audit log.** `evaluateViaGenome` logs `decision=MORPH` and
  resets the candidate streak even when `setStrategy` refused (same-class/health-gate), so the
  "reconstructable from one line" audit line records a morph that never happened. Fix: return
  the `setStrategy` verdict from `applyStructure`, commit state only on `true`.
- **C11 · S3 — hybridFitness placeholder poisons ScoreCard.** A hard-coded `hybrid=0.0` is fed
  into the `ScoreCard` whose `range()`/`average()` it then takes, so HYBRID is structurally
  under-scored (0.185 vs 0.776) and can practically never be recommended. Fix: compute
  spread/centrality over the seven real scores.
- **C12 · S4 — exists-then-open races misclassify a concurrent delete** as FAILED instead of
  ABSENT (retention sweep racing a load). Fix: drop the pre-check, open directly, map
  `NoSuchFileException` → ABSENT.
- **C13 · S4 — control-plane morph views disagree.** `morphCount` increments but `morphLog`
  and `performanceMemory` don't, so `getMorphCount()` and `getMorphLog()` contradict. Fix:
  append a `MorphEvent` in `evaluateViaControlPlane` when `r.morphed()`.

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
- **D3 · S3 — retainNewest partial failure.** A throw mid-`release` loses the audit list and
  leaves a half-deleted generation that `generations()` still lists and `asOf` restores
  truncated. Fix: rename the doomed generation out of the `gen-` namespace before deleting;
  collect and return per-generation outcomes.
- **D4 · S3 — generation-number collision after a store rollback** throws "already preserved"
  and discards the new backup, bricking preserve until real history is manually released; worse,
  `retainNewest` sorts by number so it can release the *newest* data. Fix: namespace by
  (epoch, number), don't order retention purely by store-issued number.
- **T1 · S2 — two Twines sharing one journalDir** steal each other's committed batches (fixed
  JOURNAL/TMP names, no per-store namespacing): B replays A's ops into B's store with B's
  serializers, and A's `delete(journal)` then throws on an already-applied batch. Fix: a
  per-Twine token in the journal header, or an exclusive lock file held for the Twine's lifetime.
- **T2 · S3 — commit point not durable (no directory fsync).** The tmp data is fsynced but the
  rename's directory entry is not; power loss mid-apply can lose a batch the code called
  "inevitable." Fix: fsync the journal directory after the move, before apply.
- **T3 · S3 · FIXED — committed flag set before any I/O.** A transient pre-commit failure
  (disk-full on tmp, fsync EIO) leaves the batch permanently un-re-committable ("batch already
  committed"). Fix: set `committed = true` only after the ATOMIC_MOVE succeeds.
- **T4 · S3 — in-process apply failure wedges the Twine.** A sink throwing mid-apply leaves the
  journal and every future commit throwing "still applying" forever, with no documented recovery
  but closing and re-`over()`ing. Fix: an in-process `recover()`/retry from the surviving journal.
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
- **J1 · S? — Jerky (hunter cut off before reporting).** cure()'s stream flush/close ordering
  (trailer written through the unbuffered file stream while buffered body may be unflushed) was
  the lead being examined when the run ended. Queued for re-hunt; note the existing round-trip
  and targeted-extract tests currently pass, so if a bug exists it is in an untested corner
  (e.g. archives near a buffer boundary).

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
