# Quickstart — from zero to a running database in ~10 minutes

This is the "I've never done this before" guide. Follow it top to bottom and you'll have the whole
fourteen-engine system standing up on your own machine, printing its vitals. You do **not** need to
know Java. If any step goes sideways, the [Troubleshooting](#troubleshooting) section at the bottom
covers the usual snags — or paste the error to an AI assistant and it'll help.

> Prefer to be walked through it live? Open [Claude](https://claude.ai) or ChatGPT and paste:
> *“Walk me through installing Java 17 and running RicheyWorks/WholeHog from GitHub, one step at a
> time — I'm on Windows (or Mac), and I've never done this.”* Then use this page as the map.

## What you'll need (both free)

1. **Java 17 or newer** — the language it's written in. The easiest installer is
   [Adoptium Temurin](https://adoptium.net/temurin/releases/?version=17). Pick version 17, download,
   run the installer, accept the defaults.
2. **Git** — to download the code. [git-scm.com/downloads](https://git-scm.com/downloads).

Check they're installed — open a terminal (on Windows: "Command Prompt" or "PowerShell"; on Mac:
"Terminal") and run:

```bash
java -version     # should say 17 (or higher)
git --version     # should print a version number
```

If `java -version` prints something like `openjdk version "17.0.x"`, you're set.

## Step 1 — Get the code (all fourteen, side by side)

The engines are separate repositories that find each other **as siblings in one folder**, so make a
folder and clone them all into it. Copy-paste this whole block:

```bash
mkdir richeyworks && cd richeyworks
for repo in CSRBT SuperBeefSort SmokeHouse Carver Renderer Brine PitBoss \
            DryAge Twine SmokeSignal Jerky WholeHog Rub Sizzle ; do
  git clone https://github.com/RicheyWorks/$repo.git
done
```

On **Windows PowerShell**, use this instead (same idea):

```powershell
mkdir richeyworks ; cd richeyworks
'CSRBT','SuperBeefSort','SmokeHouse','Carver','Renderer','Brine','PitBoss',
'DryAge','Twine','SmokeSignal','Jerky','WholeHog','Rub','Sizzle' |
  ForEach-Object { git clone "https://github.com/RicheyWorks/$_.git" }
```

When it finishes you'll have fourteen folders sitting next to each other. That side-by-side layout
is the whole trick — it's how the build resolves the engines from live source with no extra setup.

## Step 2 — Run the demo

```bash
cd WholeHog
./gradlew run          # on Windows:  .\gradlew.bat run
```

The first run downloads the build tools and compiles everything, so give it a minute or two. That
`gradlew` file ships *with* the project — you don't install anything else.

## Step 3 — What you'll see

If it worked, the last stretch of output looks like this (real output, lightly trimmed):

```text
WholeHog — the organism, standing up in a temp folder
  13 engines attached: store+indexes, carver, renderer, brine, pitboss+replica, vault, twine, wire, rub, sizzle-seam

  the vitals, every engine:
  store      keys=261 median=149 garbage=75691B
  carver     drive SECONDARY_RANGE(attr) est=65 → PRIMARY_RANGE
  renderer   groups=8 top3=[5, 4, 6] caughtUp=true
  pitboss    FleetReport[primarySequence=2392, replicas=[ReplicaStatus[name=exhibit, lag=0, gapped=false]]]
  vault      generations=[0]
  jerky      gen-0.jerky verified
  wire       size=263 countRange(100..200)=88 wireWriteVisibleEverywhere=true

  the physical, one call:
  rub        keys=263 seq=2396 segs=22 live=9994B garbage=75750B (88.3%) puts=2084 dels=312 gaps=0
  replica    keys=263 seq=2396 segs=23 live=9994B garbage=75750B (88.3%) gaps=0
  twine      batches=197 ops=591 replays=0
  brine      Stats[gets=0, invalidations=2396, champion=SLRU(2/10,p1)]
  vault      retainNewest(1) released [0], kept [1] (aging is the caller's policy)

  chaos      Sizzle crashes a 5-op batch at op 3, mid-apply...
             caught: Sizzle injected a crash at op 3 (put 2)
             reopened; the batch landed exactly once → store keys=5

  the log is the only truth; fourteen engines kept it. done.

BUILD SUCCESSFUL
```

**That's the whole system working.** You just stood up a database, filled it with a couple thousand
operations, and watched every engine report in.

## Reading the output (the interesting parts)

You don't need to understand all of it, but a few lines are worth a look:

- `store keys=261 median=149` — the store holds 261 live records and can tell you the *middle* key
  instantly (that's [CSRBT](https://github.com/RicheyWorks/CSRBT)'s order statistics).
- `renderer groups=8 top3=[5, 4, 6]` — a live "top 3" that stayed correct as data changed, no
  recalculating ([Renderer](https://github.com/RicheyWorks/Renderer)).
- `pitboss ... lag=0, gapped=false` — a backup replica, perfectly in sync
  ([PitBoss](https://github.com/RicheyWorks/PitBoss)).
- `jerky gen-0.jerky verified` — a compressed backup, checksum-verified
  ([Jerky](https://github.com/RicheyWorks/Jerky)).
- The **chaos** block is the best part: [Sizzle](https://github.com/RicheyWorks/Sizzle) deliberately
  crashes a save halfway through, and after reopening, *the batch landed exactly once* — the
  recovery contract, proving itself in front of you ([Twine](https://github.com/RicheyWorks/Twine)).

Want to see the tests instead of the demo? `./gradlew build` runs the whole suite and prints
`BUILD SUCCESSFUL` when every engine's contracts hold.

## Where to go next

- **[The overview (ECOSYSTEM.md)](ECOSYSTEM.md)** — what all fourteen engines are, in plain words.
- **[What to build (USE-CASES.md)](USE-CASES.md)** — eight real applications and the engines behind them.
- Each engine's own repository README goes deep on that one piece.

## Troubleshooting

**`java: command not found` (or `'java' is not recognized`)** — Java isn't installed or isn't on your
PATH. Re-run the [Adoptium](https://adoptium.net/temurin/releases/?version=17) installer and, on
Windows, tick "Set JAVA_HOME" / "Add to PATH" if offered, then open a **new** terminal.

**`java -version` shows something older than 17** — install 17+ from Adoptium; the build needs it.

**`./gradlew: Permission denied` (Mac/Linux)** — run `chmod +x gradlew` once, then try again.

**On Windows, `./gradlew` does nothing** — use `.\gradlew.bat run` instead (backslash, and the
`.bat`).

**A build error mentioning another engine, or "project not found"** — the fourteen folders aren't
side by side. Make sure you ran the demo from *inside* the `WholeHog` folder, and that `WholeHog`
sits in the same parent folder as `CSRBT`, `SmokeHouse`, and the rest.

**Anything else** — copy the exact error text, paste it to [Claude](https://claude.ai) or ChatGPT,
and say "I'm setting up RicheyWorks/WholeHog and hit this." Reporting the exact message is the
fastest way through.
