# 2026-08-20 — three slices: Carver over history, the observer rides the fleet, the physical

All three are pure composition inside the organism — no upstream engine changed. That is
its own finding: the seams cut this week were the right ones, because this round needed
nothing new from any of them.

## Slice A — Carver over history (`seedIndexedFrom`)

`seedFrom` gave back a bare store; `seedIndexedFrom(archive, dir)` reopens the seeded moment
as an `IndexedStore` carrying the organism's own secondary and interval indexes —
`IndexedStore.build()` rebuilds derived indexes from existing contents, so yesterday's
community gets today's full query surface. Wrap it in `Carver.over(...)` and the read
planner runs cost-based plans over the preserved moment: time-travel analytics, from a cold
archive's sidecar, without inflating a segment. The history oracle pins a Carver plan over
the seeded moment against brute force over the recorded moment.

## Slice B — the observer rides the fleet (`replicaVitals`)

`Replica.store()` was always public, and a store is a store: the organism now attaches a
second Rub to the replica's store, metering the replication feed as it applies. The
observability oracle pins convergence at the meter level — after quiescence the replica's
gauge equals the primary's, gap-free — and the exhibit shows the two vitals lines matching
count for count. Honest bound in the javadoc: a rebootstrap replaces the replica's store,
and this observer stays on the store it was born on.

## Slice C — the physical (`report()`)

Every meter the engines have grown, one read-only call: rub's vitals, the pulse, the
replica's vitals, the wire's traffic, the batcher's work, the cache's stats. Read-only by
construction — nothing in it ticks a policy or advances state, and the oracle pins that
property the only way that matters: two consecutive physicals are identical on a quiet
organism. A physical never changes the patient. The Exhibit's ad-hoc meter lines collapse
into it.

## Verification

WholeHog 12 tests green (three oracles extended in place), zero warnings; the exhibit's
physical shows the replica converged exactly (keys=263, puts=2084, dels=312 on both lines).
