# Changelog — 2026-09-02 — the replication feed seam, and a lag that is a reading

Cut for CSRBT's ADR-120 ("nothing held"), which had carried since ADR-113 that
*the replica cannot be held behind the primary without a `Sizzle.slow` seam on
the replication tail*. Three engines, one line of plumbing each.

## SmokeHouse 0.3.0 → (unreleased)

- `ReplicationServer.serve(store, opts, feed)`: a `UnaryOperator<TailListener>`
  applied to each client's frame writer before it is subscribed to the tail.
  Identity by default; the existing two-argument `serve` is unchanged. The
  wrapper runs on the tail subscriber's thread, so what it does is what the
  replica experiences; `onGap` must pass through.
- `ReplicationTest.theFeedSeamHoldsAReplicaBehindItsPrimaryAndItStillConverges`:
  a 40 ms feed, 25 writes, the replica behind, then converged, every frame
  through the wrapper. Suite 79 → **80**.

## PitBoss 0.1.0 → (unreleased)

- `over(primary, opts, autoRebootstrap, feed)` passes the seam through.
- **Fix:** `tick()` measures a replica's lag from the conductor's seat — the
  primary's committed sequence minus what the replica has applied. The first
  time the feed was held back, the fleet reported lag 0 for a replica twenty
  frames behind: `Replica.lagSequence()` learns the primary's sequence *from
  the frames*, and a held-back replica has not received the one that would
  tell it. `ReplicaStatus` says so.
- `PitBossTest.aHeldBackReplicaReportsItsLagFromTheConductorsSeat`. Suite 3 →
  **4**.

## WholeHog 0.2.1 → (unreleased)

- `Organism(root, seed, plan, replicaLagMillis)`: `Sizzle.slow(listener,
  millis)` on PitBoss's feed; `replicaLagMillis()`. The three-argument
  constructor is `…, 0`.
- `HarnessConsole`: `restart [PLAN] [LATENCY] [REPLICA-LAG]` (0–500 ms);
  `observe` and `restart` report `replicaLagMs`.
- `HarnessConsoleTest.restartUnderChaosThenCleanReplaysTheBatchWhole` extended:
  `restart none 0 150`, six puts, `fleet` reports the replica behind and not
  gapped, `quiesce` lands it, `count` is 9, a plain restart lets the feed go.
  Suite **21** green.
- `docs/atlas.html`: the Whole Hog Atlas's source, in the repository. Its
  engine table and stamp are regenerated between markers by CSRBT's
  `tools/atlas.py` from `tools/ecosystem_ledger.json` and each repo's build
  file; `verify_ecosystem` fails on drift. The published Atlas was a month
  stale on seven versions and eleven suite counts, and still listed
  record-granularity as-of as held ten days after it was cut.
