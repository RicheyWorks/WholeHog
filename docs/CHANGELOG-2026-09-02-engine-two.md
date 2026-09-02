# Changelog — 2026-09-02 — engine 2, observed

Cut for CSRBT's ADR-122. SuperBeefSort is the recovery engine, and nothing
it measured survived `SmokeHouse.open`; through the harness it had never run,
because a restart always closed cleanly and a checkpointed reopen skips the
sort.

## SmokeHouse

- `RecoveryReport` + `recovery()`; `abandon()` (and `IndexedStore.abandon()`):
  die without the checkpoint.
- **Fix — the recovery sort was sorting a `TreeMap`.** The last-writer-wins
  map is in arrival order now, so the profile SuperBeefSort measures is the
  log's disorder and the strategy the index is born as is advised from the
  workload. First report on 400 random keys before the fix: `insertion, 326
  comparisons, sortedness 1.0, 0 inversions`; after: `intro, 1576
  comparisons, sortedness 0.52, 9315 inversions` (200 keys, via the
  organism).
- `RecoveryReportTest`. Suite **82**.
- Observed once, not touched: `EleventhPassProbeTest.rangeSurvivesAConcurrent
  CompactionCommit` failed on a loaded sandbox ("sustained compaction") and
  passed on the next four runs.

## WholeHog

- `Organism.crash()`: every organ released as in `close()`, the store
  abandoned.
- `HarnessConsole`: `restart … [clean|cold]`, `recovery`; `observe` and
  `restart` carry the report's headline. `HarnessConsoleTest` extended. Suite
  **21** green.
- `docs/atlas.html` regenerated (SmokeHouse 82).
