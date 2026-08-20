# 2026-08-20 — the cold triggers, measured

Two held items had been waiting on numbers. Both experiments are pre-registered (decision
rules written before the runs — see the test javadocs), seeded, warmup-discarded,
median-of-3 where phases are timed (ADR-022 methodology), and checked in as
`ColdScanExperimentTest` / `PreserveCadenceExperimentTest` so any machine can reproduce
them. The canonical run below is the verdict of record (cloud runner, JDK 21).

## Experiment 1 — the cold-scan cost (Jerky's columnar trigger)

**Held since 2026-07-18:** the scan-friendly columnar cold format, behind "a benchmark
showing cold-segment scans as a real cost."

**Method:** build a store of n records, back up, cure to `.jerky`. Measure the full route to
answering one scan over the cold archive — inflate → recover as a SmokeHouse → range-scan —
against the floor of reading the archive's bytes once (the least ANY format could do).

| n | archive | floor (raw read) | inflate | recover | scan | route total | route/floor |
|---|---|---|---|---|---|---|---|
| 20,000 | 272 KB (0.23×) | ~0 ms | 12 ms | 55 ms | 336 ms | 403 ms | ≫100× |
| 60,000 | 818 KB (0.23×) | 1 ms | 29 ms | 61 ms | 434 ms | 524 ms | **524×** |

**Pre-registered rule:** fires above 5× the floor at the larger size.

**VERDICT: the trigger FIRES — 524× against a 5× bar.** And the decomposition names the
enemy precisely: inflate is cheap (29 ms) and recovery tolerable (61 ms); the dominant cost
is the *scan itself* (434 ms) — running an ordered index walk with per-record reads over a
store that was recovered solely to be read once, front to back. That is exactly the access
pattern a columnar/scan-friendly layout serves with one sequential pass. The columnar cold
format is now an unlocked slice with its number attached; it should be designed as its own
session (format ADR first — it is a persisted format, and persisted formats are forever).

## Experiment 2 — the preserve cadence (DryAge's record-as-of trigger)

**Held since the engine's birth:** record-granularity as-of (a bounded-recovery stop
condition cut into SmokeHouse), behind "a consumer shows the generation granularity isn't
enough." No consumer can be manufactured honestly — but the workaround everyone would reach
for ("just preserve more often") can be priced.

**Method:** one 60k-op churn (~5k live keys); checkpoint the vault every N ops; measure
preserve time against the churn's own time, and vault growth.

| cadence | checkpoints | churn | preserve total | preserve/churn | per checkpoint | vault |
|---|---|---|---|---|---|---|
| every 2,000 ops | 30 | 475 ms | 907 ms | **191%** | 30.2 ms / 1.39 MB | 41.8 MB |
| every 10,000 ops | 6 | 320 ms | 220 ms | 69% | 36.8 ms / 1.57 MB | 9.4 MB |
| every 30,000 ops | 2 | 305 ms | 78 ms | 26% | 39.2 ms / 2.03 MB | 4.1 MB |

**Pre-registered rule:** the workaround is viable if the densest cadence stays under ~25% of
churn time with roughly linear vault growth.

**VERDICT: the workaround FAILS the bar — 191% against 25%.** Each checkpoint is a full
prefix copy (~the live store's size), so dense checkpointing roughly *doubles* the write
path's cost and the vault grows with checkpoint count × store size. Two consequences, kept
honest: "preserve more often" is now measured dead as a substitute for record-granularity —
nobody should be told it again; AND the seam itself **stays held**, because its trigger was
never cost — it was a consumer, and no consumer has arrived. What changes is the seam's
justification when one does: the fallback it would replace is now known to cost ~2× the
workload at fine grain.

## Standing after this session

- Jerky columnar cold format: **trigger fired at 524×** — next major slice, format-ADR first.
- DryAge record-granularity as-of: **held**, consumer trigger unchanged; the workaround is
  priced at 191%-of-churn / 1.4 MB per checkpoint at 2k-op grain.
