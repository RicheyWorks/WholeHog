# 2026-08-19 — two slices: the wire learns to write, the vault cures in place

Both slices are the same species of change: an integration lesson this organism had already
recorded, turned into a named seam upstream, wired here, and proven in the oracle.

## Slice 1 — writes over the wire (ledger #2, resolved)

Finding #2 had stood since the organism's birth: *"the wire is read-only in a composed
organism — writes over SmokeSignal would bypass secondaries; deliberately unsolved and
documented until a consumer needs it."* The consumer arrived (this organism), so the seam
was cut where the lesson said it belonged:

- **SmokeSignal** gained `WriteRoute` — put is a last-writer-wins upsert, delete answers
  whether the key existed — and a `serve(store, writeRoute, …)` overload that routes every
  wire PUT/DELETE through it while reads stay on the served store. The plain overload is
  unchanged: the route is the store. New test drives a tagging route over the wire and
  proves writes are diverted, reads are not, and delete-of-absent stays a no-op `false`.
- **Organism** now serves the wire with writes routed through the `IndexedStore` fan-out.
  The read-only decree is deleted, not amended — a wire client is a first-class writer.
- **Oracle:** `aWireWriteReachesEverySubscriber` — a put arriving over the socket must show
  up in Carver's secondary-index plan, the Renderer fold, the replica, and the primary scan.
  The Exhibit now performs a wire write live and prints whether it is visible everywhere.

## Slice 2 — cure the vault in place (ledger #6... entry #5, the ceremony finding)

`preserveAndCure` cured history through a three-step dance: open an `AgedView` (recovery
pass on a scratch copy), back the view's store up into a staging dir (second copy), cure
the re-backup, delete the staging. Two copies and a recovery, to read bytes that were CRC'd
at capture and are immutable by the vault's founding rule.

- **DryAge** named the seam: `generationPath(generation)` hands read-only archival consumers
  the preserved generation's own directory, with the contract stated loudly (history's bytes
  never change; a consumer that writes into it is corrupting the vault). `asOf` remains the
  way to read a generation *as a store*. New test pins existence, non-disturbance, and the
  loud failure on unknown generations.
- **Organism.preserveAndCure** is now three lines of intent: preserve, cure the preserved
  bytes directly (`Jerky.cure` is read-only on its source by contract), verify. The existing
  `historyIsPreservedCuredAndStillTrue` oracle test pins that the archive still round-trips
  byte-true through `Jerky.restore` → `SmokeHouse.restore`.

## Verification

SmokeSignal 6 tests, DryAge 3 tests, WholeHog 9 tests — all green; Rub 5 / Sizzle 6
unchanged and green; zero build or javadoc warnings.
