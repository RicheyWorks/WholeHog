# WholeHog — working notes for agents

Engine 12: the integration organism. `Organism` wires all eleven engines over one store;
`OrganismTest` is the composed oracle (four-subscriber tail test is the headline);
`Exhibit` is the one-command demo (`./gradlew run`).

## Build & test
- Nested composite including EVERY sibling repo — all must be cloned side by side.
  Gradle deduplicates the shared transitive includes.
- This is deliberately the slowest suite in the ring (real threads: tail, replication,
  wire). Every assertion that races a thread is behind a bounded await — keep it that way.

## Git is host-side
Same as the siblings: agent sandboxes cannot write `.git`. Run all git commands from a host
terminal (PowerShell). Stale `.git/index.lock` fix: `Remove-Item .git\index.lock -Force`.

## Invariants (do not break)
- **WholeHog is a consumer, never a shortcut.** It touches only public engine surfaces; if
  composition needs a new seam, name it upstream (the Twine sink seam is the precedent) and
  record it in the README's findings ledger.
- **Writes route through the IndexedStore** — Twine over the Sizzle seam wrapping
  `indexed::put`/`indexed::delete`, and the wire over SmokeSignal's `WriteRoute` seam
  (2026-08-19; ledger #2 resolved) — never the primary, on any path.
- **One oracle.** Every engine's answer checks against the same TreeMap on the same seeded
  stream. New engines joining the ecosystem join `Organism` and the oracle in the same PR.
- Findings go upstream + into the ledger. Rub (engine 13, observability) and Sizzle (engine 14,
  chaos) were the two findings this engine re-armed into real engines (2026-08-19): Rub is the
  promoted tail watcher (composed as the fourth subscriber; `o.rub()`/`o.vitals()`), Sizzle
  wraps Twine's sink seam so the write path is fault-injectable — `new Organism(root, seed,
  ChaosPlan)` ties Twine over it (transparent under `none()`). Both join the Organism and the
  oracle in the same change, per the one-oracle rule.
