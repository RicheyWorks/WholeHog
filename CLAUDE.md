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
- **Writes route through the IndexedStore** — Twine over `indexed::put`/`indexed::delete`;
  never the primary; the wire stays read-only in the organism until a consumer forces the
  seam.
- **One oracle.** Every engine's answer checks against the same TreeMap on the same seeded
  stream. New engines joining the ecosystem join `Organism` and the oracle in the same PR.
- Findings go upstream + into the ledger; Rub (observability) and Sizzle (chaos) re-arm
  from what this engine discovers.
