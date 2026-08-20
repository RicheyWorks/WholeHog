# 2026-08-20 — three slices: batches over the wire, the pulse, and the aging policy

## Slice A — crash-atomic batches over the wire (ledger #6)

With ledger #2 resolved (the wire writes through the index fan-out), the next composition
hazard was already visible: a wire client sending N puts one at a time has no atomicity, and
a naive server-side loop would apply half a request on a mid-batch failure.

- **SmokeSignal**: `OP_BATCH` joins the protocol (new op code, never a repurposed one). The
  server reads the ENTIRE batch off the socket before touching its new `BatchRoute` seam —
  the route decides atomicity, the wire never applies half a request. The default route
  applies in order through the `WriteRoute` and says plainly that it is sequential, not
  atomic. Client side: `wire.batch().put(k, v).delete(k2).commit()` — one request, an
  applied-count reply, refuse-after-commit staging discipline. `WireStats` grew a `batches`
  counter (requests, not inner ops — those belong to the route's owner).
- **WholeHog**: the organism's batch route stages through Twine, synchronized on it (Twine
  keeps the single-writer discipline; wire sessions are threads). Any wire client gets
  journaled, crash-atomic, index-fanned multi-key batches without knowing Twine exists.
- **Oracle**: `aWireBatchLandsAtomicallyAndFansOut` — a batch with an internal overwrite and
  a delete-of-a-batch-mate lands as its net effect, exactly once, in the primary, Carver's
  secondary plan, and the replica. The Exhibit stages a live wire batch.

## Slice B — the pulse (Rub grows a derivative)

`Vitals` was a point; observability wants a slope. `later.since(earlier)` returns a `Pulse`:
ops elapsed (tail-sequence advance), puts/deletes/gaps metered across the interval, and how
the live set and garbage moved. Everything is op-relative — no clock, in the house tradition —
so pulses compare across machines and runs where wall-clock rates would not. Swapped samples
are refused loudly (the meters are monotonic; a negative delta means caller error, not
negative traffic). `Rub.pulse()` is the convenience over the last two retained ticks, `null`
below two — no derivative of one point. Locale-pinned `Pulse.line()`; the Exhibit prints the
churn's pulse.

## Slice C — the aging policy (DryAge retainNewest)

The vault grew forever, and `release()` left retention as N calls of caller ceremony.
`retainNewest(count)` is the policy as one call: keep the newest `count` generations, release
everything older, and return the released generations ascending — an audit line, because
dropping history deserves a record. Zero empties the vault; negative is refused; keeping more
than exist releases nothing. Caller-cadenced — the vault never ages on its own clock, because
it has none. The Exhibit preserves a second moment and ages the first one out on stage.

## Verification

SmokeSignal 8 tests, Rub 6, DryAge 4, WholeHog 10 — green; Sizzle 6 unchanged; zero build or
javadoc warnings.
