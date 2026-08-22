# What could you build with it?

*Concrete things this ecosystem is the foundation for — and exactly which engines carry each one.*

New here? Start with the [plain-English overview](ECOSYSTEM.md) first. This page goes one level
deeper: nine real applications, what each one actually needs, and how the fourteen engines add up
to it. The code sketches use WholeHog's composed `Organism` (or a single engine where that's
clearer) — they're illustrative, meant to show the shape, not to copy-paste into production.

**One honest frame, up front.** These engines are the trustworthy *floor* — the part that stores,
protects, sorts, and answers. You still write the app on top. And this is a hand-built,
research-grade engine and a learning tool, not a product with a support line: reach for it to
learn, to tinker, or because you want to build on something crafted by hand — and reach for an
established database (SQLite, Postgres, Redis) when you need a production system with a community
behind it.

---

## 1. A crash-proof notes or journaling app

**For:** anyone who has lost work to a crash and never wants to again.

**What it needs:** every save is permanent the instant it returns; a crash mid-save never corrupts
anything; and you can pull back any earlier version of a note.

**How the smokehouse does it:** [SmokeHouse](https://github.com/RicheyWorks/SmokeHouse) writes each
change to an append-only log (the bytes never change once written), [Twine](https://github.com/RicheyWorks/Twine)
makes a multi-part save all-or-nothing across a crash, and [DryAge](https://github.com/RicheyWorks/DryAge)
lets you open the notebook exactly as it stood at any past point — now down to a single edit.

```java
o.twine().batch().put(noteId, note).put(indexId, tag).commit();  // saved atomically, survives a crash
try (var yesterday = vault.asOf(morningSnapshot)) { … }          // read the note as it was earlier
```

## 2. A small shop's inventory & point of sale

**For:** a store owner who wants to know what's selling and see the books as they stood on any day.

**What it needs:** fast lookups by product, live "best sellers," range queries ("everything between
these two SKUs"), a view of any past day, and a safe nightly backup.

**How the smokehouse does it:** [Carver](https://github.com/RicheyWorks/Carver) plans each query the
cheap way, [Renderer](https://github.com/RicheyWorks/Renderer) keeps "top sellers by category" and
running revenue totals correct as sales land, [DryAge](https://github.com/RicheyWorks/DryAge) answers
"show me Tuesday's inventory," and [Jerky](https://github.com/RicheyWorks/Jerky) packs a
compressed, integrity-checked backup for cold storage.

```java
o.byCategory().top(5);                       // best sellers, always current
o.carver().query().keysBetween(sku1, sku2).where("aisle", 3).keys();
```

## 3. A live game leaderboard or high-score service

**For:** a game or app that ranks players in real time as scores pour in.

**What it needs:** instant "top 10," "what's my rank?," and percentiles over thousands of scores,
staying correct under a flood of updates — and other programs able to read it over a connection.

**How the smokehouse does it:** [CSRBT](https://github.com/RicheyWorks/CSRBT)'s order statistics make
rank, top-k, and percentile *tree walks* instead of re-sorts; [Renderer](https://github.com/RicheyWorks/Renderer)
holds the ranked board; [Brine](https://github.com/RicheyWorks/Brine) keeps the hottest entries in
memory; and [SmokeSignal](https://github.com/RicheyWorks/SmokeSignal) lets your game process talk to
the store over a local connection.

```java
board.top(10);            // the leaderboard
store.rankOf(playerKey);  // "you're #438"
store.percentileKey(90);  // the score at the 90th percentile
```

## 4. A personal finance ledger

**For:** anyone tracking money who needs every entry to be all-or-nothing and every past state
recoverable.

**What it needs:** a transaction touching several accounts either fully happens or not at all; the
history is never rewritten; and you can audit the ledger as of any date.

**How the smokehouse does it:** [Twine](https://github.com/RicheyWorks/Twine) makes the multi-account
posting atomic and crash-safe, [SmokeHouse](https://github.com/RicheyWorks/SmokeHouse)'s log is the
permanent record, [DryAge](https://github.com/RicheyWorks/DryAge) reconstructs any past balance, and
[Jerky](https://github.com/RicheyWorks/Jerky) archives it with a checksum so a stored backup can't
rot unnoticed.

```java
o.twine().batch().put(debit, fromAcct).put(credit, toAcct).commit();  // both, or neither
```

## 5. A sensor / IoT event logger

**For:** collecting a high volume of readings and rolling them up live, without falling over.

**What it needs:** cheap, fast appends; running averages and counts that stay current; the option to
keep only the newest N readings; backup copies kept in sync; and a health readout that flags when it
can't keep up.

**How the smokehouse does it:** [SmokeHouse](https://github.com/RicheyWorks/SmokeHouse) is built for
append-heavy writing and can retain just the newest N keys; [Renderer](https://github.com/RicheyWorks/Renderer)
folds readings into live per-sensor aggregates; [PitBoss](https://github.com/RicheyWorks/PitBoss)
keeps read replicas synced; and [Rub](https://github.com/RicheyWorks/Rub) reports throughput and
honestly flags any gap where it fell behind.

```java
o.pitBoss().tick();                  // keep replicas caught up, on your cadence
System.out.println(o.vitals());      // keys, writes, garbage %, and any gaps — the pulse
```

## 6. A local-first app backend (no cloud)

**For:** a desktop or on-device app that wants a real database engine, entirely on one machine.

**What it needs:** durable local storage, another process able to reach it over a connection, and a
warm backup copy standing by — with nothing leaving the machine.

**How the smokehouse does it:** [SmokeSignal](https://github.com/RicheyWorks/SmokeSignal) puts the
store on a loopback-only connection (said loudly: in-machine, no network, no auth — a doorway for
local programs, not a server), [SmokeHouse](https://github.com/RicheyWorks/SmokeHouse) is the store,
and [PitBoss](https://github.com/RicheyWorks/PitBoss) keeps a backup replica ready for a clean
hand-off.

```java
o.wire().put(k, v);   // a local process writes through the wire…
o.wire().get(k);      // …and reads back — every op lands on the store's single writer
```

## 7. An audit log or compliance trail

**For:** anything that must record *what happened and when*, provably un-rewritten.

**What it needs:** an append-only history that is never edited in place, integrity you can verify,
and the ability to reconstruct the exact state at any moment.

**How the smokehouse does it:** the ecosystem's founding rule is that
[SmokeHouse](https://github.com/RicheyWorks/SmokeHouse)'s log is the only truth and its bytes never
change once written; [Jerky](https://github.com/RicheyWorks/Jerky) archives it with per-file CRC
checks that refuse to restore corrupted bytes; and [DryAge](https://github.com/RicheyWorks/DryAge)
opens the record as of any past point. *(This gives tamper-**evidence** and point-in-time replay,
not cryptographic tamper-**proofing** — a different guarantee, honestly named.)*

## 8. A learning lab for how databases actually work

**For:** anyone learning systems programming who wants to see a real engine, not a toy.

**What it needs:** something you can run end to end, read the reasoning behind, and stress until it
breaks — then watch it recover.

**How the smokehouse does it:** [WholeHog](https://github.com/RicheyWorks/WholeHog)'s `./gradlew run`
stands the whole organism up and prints its vitals; every engine's README and `docs/` folder walks
through the design decisions; and [Sizzle](https://github.com/RicheyWorks/Sizzle) deliberately
crashes the write path at exact moments so you can watch the recovery contracts prove themselves.

```java
try (Organism o = new Organism(root, seed, ChaosPlan.crashOnceAtOp(3))) {  // crash mid-batch…
    o.twine().batch().put(1, v).put(2, v).put(3, v).commit();
}   // …reopen: the journal replays and the batch landed exactly once
```

## 9. A field-ecology teaching & analysis lab

**For:** ecology and biology students, TAs, professors, and field researchers.

**What it needs:** the standard field instruments (diversity, survivorship, dispersion, island
turnover, genetics), a way to bring your *own* field data with no coding, numbers reproducible
enough to cite, and clean output to drop into a lab report.

**How the smokehouse does it:** [CSRBT](https://github.com/RicheyWorks/CSRBT) carries a
**community-ecology layer** built on one idea — *a data structure under a workload behaves like a
habitat under an ecology.* Keys are species, how often a key is touched is that species' abundance,
inserts and removes are births and deaths, and time is counted in operations, so every number
reproduces exactly. On that footing it runs the real instruments: Shannon / Simpson / Hill
diversity, Deevey survivorship curves, Levins metapopulation dynamics, Morisita quadrat dispersion,
MacArthur–Wilson island turnover, Hardy–Weinberg, Punnett squares, mark–recapture, Newick
cladograms, dichotomous keys, and a flashcard trainer — each tested against hand-computed oracles.

The best part for a classroom: the **browser Workbench** ([`docs/ecology-lab.html`](https://github.com/RicheyWorks/CSRBT/blob/main/docs/ecology-lab.html))
runs all of it with **no install** — paste field counts (a spreadsheet paste or tally marks both
work) or a genotype census, compare two sites, run the mark–recapture calculator, build a Punnett
square or a cladogram — and it narrates every number in plain language ("*uneven — a few hot keys
carry most of the traffic*"). Want a graded experiment? Write a plain-text `.eco` protocol that
commits to a hypothesis *before* the run; the engine grades it and exports CSVs (Excel/Sheets/R-ready)
and a print-friendly report to hand in. The whole thing prints clean for a lab report.

*Where to look:* the [field guide](https://github.com/RicheyWorks/CSRBT/blob/main/docs/ECOLOGY-FIELD-GUIDE.md)
(plain-language, biology-first), the [essay](https://github.com/RicheyWorks/CSRBT/blob/main/docs/ESSAY-the-ecology-of-a-tree.md)
(the whole story), and the Workbench page itself.

*Honest frame:* the instruments measure a data structure with genuine field methods, and they
narrate *your* entered field data with those same methods — a teaching and analysis tool, not a
claim that a binary tree is literally a meadow.

---

## Picking engines by what you need

A quick index — the need on the left, the engine that answers it on the right:

| If you need… | Reach for |
|---|---|
| Durable storage that survives crashes | [SmokeHouse](https://github.com/RicheyWorks/SmokeHouse) |
| Several writes as one all-or-nothing change | [Twine](https://github.com/RicheyWorks/Twine) |
| "Show me the data as it was back then" | [DryAge](https://github.com/RicheyWorks/DryAge) |
| Rankings, top-k, percentiles, medians | [CSRBT](https://github.com/RicheyWorks/CSRBT) order statistics |
| Field-ecology instruments for teaching or field data | [CSRBT](https://github.com/RicheyWorks/CSRBT)'s [ecology layer](https://github.com/RicheyWorks/CSRBT/blob/main/docs/ECOLOGY-FIELD-GUIDE.md) |
| Live running totals and dashboards | [Renderer](https://github.com/RicheyWorks/Renderer) |
| The cheapest way to answer a query | [Carver](https://github.com/RicheyWorks/Carver) |
| A fast in-memory cache | [Brine](https://github.com/RicheyWorks/Brine) |
| Synced backup copies + safe failover | [PitBoss](https://github.com/RicheyWorks/PitBoss) |
| Another local program talking to the store | [SmokeSignal](https://github.com/RicheyWorks/SmokeSignal) |
| Compressed, integrity-checked backups | [Jerky](https://github.com/RicheyWorks/Jerky) |
| A health & throughput readout | [Rub](https://github.com/RicheyWorks/Rub) |
| To prove recovery works under failure | [Sizzle](https://github.com/RicheyWorks/Sizzle) |
| All of it, wired together and proven | [WholeHog](https://github.com/RicheyWorks/WholeHog) |

Not sure where to start? Open an AI assistant and paste: *“Given RicheyWorks/WholeHog, help me
sketch a &lt;the thing you want to build&gt; — which of its engines would I use, and how would they
fit together?”* — then follow the [overview](ECOSYSTEM.md)'s setup steps to run it.
