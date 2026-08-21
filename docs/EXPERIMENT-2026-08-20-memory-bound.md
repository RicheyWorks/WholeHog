# 2026-08-20 — the in-memory bound, priced

The last unpriced held item. SmokeHouse states its Bitcask trade loudly — *all keys in
memory* — and ADR-008 D2 (B+ tree disk pages) has been held behind "a workload showing the
in-memory bound is real." The bound itself never had a number. Now it does.

Pre-registered (decision rule in the test javadoc before the run), GC-fenced used-heap
deltas, fixed-shape values, with the **marginal** cost between sizes cancelling fixed
overheads. Checked in as `MemoryBoundExperimentTest`; the test asserts structure and sanity
bounds only, so it cannot flake on exact figures. Canonical run (cloud, JDK 21):

| n | retained heap | B/key (incl. fixed) |
|---|---|---|
| 100,000 | 19.9 MB | 199 |
| 300,000 | 56.2 MB | 187 |

**Marginal cost: 181 B per live key** — the whole in-memory apparatus (index entry, CSRBT
node, bookkeeping) per record. **An 8 GB heap carries ≈47 million keys at that rate.**

**Pre-registered rule:** D2 fires above 1 KB/key. **VERDICT: D2 STAYS HELD at 181 B/key** —
and its trigger graduates from a feeling to a threshold:

> Fire D2 when a target workload's live-key count approaches `heap ÷ 181 B` —
> ≈ 5.9M keys per GB of heap. Below that, the Bitcask trade is the right trade, priced.

For this ecosystem's own domain — classroom experiments, graduate research workloads, the
composed exhibit — key counts sit orders of magnitude under the line. The number also
retires a quieter worry on the record: the adaptive index's node overhead does not blow the
trade; 181 B/key is ordinary for a pointer-based ordered index carrying order statistics.

## Standing of the held items after this session

Every held item in the ecosystem now carries a price or a threshold:

- **ADR-008 D2 (disk pages):** held at 181 B/key; fires near heap ÷ 181 B live keys.
- **DryAge record-as-of:** held on its consumer trigger; the workaround priced at 191% of
  churn at 2k-op grain.
- Jerky's columnar trigger: fired at 524× and closed the same day (the scan sidecar, 33×).
- ADR-012 re-arming trigger #1: numbered at B* ≈ 128k-op regime blocks (ADR-018).

Nothing in the organism is held on intuition anymore.
