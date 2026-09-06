# Engineering notes

Test results, benchmarks and the reasoning behind the design. Kept out of the
README so that stays a reference rather than a journal.

## Contents

- [Why remediation exists](#why-remediation-exists)
- [Design decisions](#design-decisions)
- [Live-server results](#live-server-results)
- [Bugs found by running it](#bugs-found-by-running-it)
- [Performance](#performance)
- [Known limitations](#known-limitations)
- [Verification status](#verification-status)

---

## Why remediation exists

The plugin was first designed as diagnosis-only, with fixing as a later
addition. Download figures for comparable plugins argued the opposite.

Modrinth, September 2026:

| Plugin | What it does | Downloads |
| --- | --- | ---: |
| spark | Profiles, for experts | 21,528,558 |
| LagFixer | Fixes automatically | 310,465 |
| ClearLag++ | Fixes automatically | 172,401 |
| LagAssist | Analyses and prevents | 23,180 |
| Insights | Analyses chunks/entities | 13,641 |
| LagDetector | Detects only | 468 |

Tools that fix sit two orders of magnitude above tools that only analyse. The
conclusion drawn was that diagnosis is the differentiator but not the
deliverable — hence surgical remediation, where the diagnosis is what makes the
fix safe enough to trust.

## Design decisions

**Thresholds are relative to each server's own baseline.** There is no universal
healthy entity count; a large Skyblock server and a ten-player SMP have nothing
in common. A rule needs both a ratio *and* an absolute margin before it fires.
Ratio alone flags a world going from 2 armour stands to 6; absolute alone flags
a big server for being big.

**Medians, not means.** A lag spike is by definition an outlier, and a mean
baseline would be dragged toward the incident it is meant to be measured
against.

**Memory pressure requires two signals.** A heap sitting at 95% is normal for a
JVM with a large heap and no reason to collect. High usage alone is the most
misread number in server administration. Only high usage *and* real GC pause
time together count.

**One rule exists to say nothing is wrong.** `player-surge` reports that load
scaled with player count and stops there. A tool that always finds a culprit
trains people to ignore it.

**The safety policy lives in `core`.** The code that can destroy a player's
belongings is the code that most needs exhaustive tests, and those only run
offline if it has no Bukkit dependency. The executor supplies facts and carries
out decisions; it never makes them.

**Claim protection fails closed.** A provider that throws makes the location
protected, not unprotected. If WorldGuard or GriefPrevention is installed but
the hook fails to initialise, remediation stops entirely and logs loudly.
Carrying on with claim checking silently disabled is how you find out from a
player asking where their base went.

A single provider is still wrapped by the composite so its try/catch applies.
Returning the bare provider as an optimisation was a real bug, caught by the
test written for it.

## Live-server results

### Paper

Run on Paper 26.2 build 121 with a flat test world, driven over RCON.

Confirmed working: plugin loads and detects the platform;
`Bukkit.getAverageTickTime()` returns real milliseconds (0.2 ms idle); the full
diagnosis pipeline identified 34,540 items and located the hotspot chunk.

Remediation, against real entities:

- **Age floor**: inspected 32,066 freshly dropped items, removed **0**, all
  skipped as "dropped less than 60s ago"
- **Radius bounding**: 30,005 items existed, only **14,523 inspected**, because
  the fix stays within 2 chunks of the hotspot
- **Removal**: 14,518 removed; the 5 items named "Bobs Loot" survived
- **Undo**: restored 5,000 (its cap) and reported the remaining 9,518 as gone

### Folia

Run on Folia 26.2 build 7.

Confirmed: the plugin loads (Folia rejects plugins without `folia-supported`, so
loading proves that half); platform detection reports the regionised sampler;
`GlobalRegionScheduler` tasks ran across 111 samples; every command works; and
Folia logged no thread-safety violations, which matters because it is loud about
cross-region access.

Not confirmed: the census fan-out. It aims work at each online player's entity
scheduler, so with nobody online it never runs, and no headless client speaks
26.2 yet — mineflayer bundles a `minecraft-data` predating the version.

### Claim plugins

Tested against live WorldGuard 7.0.18 and GriefPrevention 16.18.7. Both are
built for 26.1 and run on 26.2. Both hooks initialise.

The test used a WorldGuard region covering only the western half of the hotspot,
so a working hook had to split by coordinate rather than pass or fail
everything:

```
  Inspected: 14,523
  Removed: 4,184
  Skipped 10,339:
    10,334 - inside a protected region or claim
    5 - has a custom name
```

That split is the verification. "Everything skipped" would mean a broken hook
failing closed; "nothing skipped" would mean the region never loaded.

It also proves the GriefPrevention hook is healthy, indirectly: the composite
fails closed, so had GriefPrevention thrown, nothing would have been removable.

## Bugs found by running it

Four defects that 141 passing unit tests did not catch. Two of them made the
plugin silently do nothing.

### 1. Chronic overload was unreachable

The server sat flat at 53.8 ms per tick (18.6 TPS) and the plugin reported "No
lag incidents".

Rules only ran inside a detected incident, and detection needs a spike above
both 55 ms *and* 1.5x baseline. On a uniformly slow server the baseline *is* the
problem, so the relative threshold became 80 ms and the rule written for exactly
that case could never fire. The unit test passed because its synthetic history
spiked to 190 ms over a 48 ms baseline — real chronically-slow servers do not
spike, they sit there.

Fixed with `DiagnosisRule.evaluateBaseline` and `Report.standing`.

### 2. Remediation removed nothing, ever

Applying a fix reported `Removed: 0`, `Skipped 32,061 - is marked persistent`.

The safety policy vetoed anything where `Entity#isPersistent()` was true. That
method means "gets saved to the world file" and is true for virtually every
entity, including ordinary dropped items — not "deliberately made permanent",
which is what the veto was written for. The plugin would have shipped as a
no-op.

Now uses `Item#isUnlimitedLifetime()` and
`LivingEntity#getRemoveWhenFarAway()`. Reading the real API while fixing this
also turned up `Item#getOwner()` — an item reserved for a specific player —
which is now protected and previously was not.

### 3. Fix results never reached the console

`/tt fix` printed the plan and nothing else. `PaperPlatform` routed work through
`BukkitScheduler#runTask`, which defers to the *next* tick even when already on
the main thread, so the RCON handler returned before the result existed. It now
runs inline when already on the main thread.

### 4. The block-entity census had never run

17,600 hoppers were placed and the plugin reported "no tracked metric moved
materially".

```java
private int samplesSinceBlockCensus = Integer.MAX_VALUE;   // force census first time
boolean due = ++samplesSinceBlockCensus >= blockCensusEvery;
```

`Integer.MAX_VALUE + 1` overflows to `Integer.MIN_VALUE`. The first check
compared minus two billion against the interval, failed, then counted upward one
sample at a time — the census would first have run after about 4.3 billion
samples, roughly 270 years at one every two seconds. Hoppers, spawners and
furnaces were never counted on any server, and `block-entity-flood` could never
fire.

It was silent in the worst way: a census that never runs is indistinguishable
from a server with no hoppers. Both samplers had it.

The scheduling now lives in `CensusSchedule` in `core`, counting down rather
than up, with five tests. The surrounding `catch` no longer swallows failures —
it logs once, because a census that starts failing should not look like good
news either.

## Performance

### What real lag costs

Thresholds were originally calibrated against synthetic data assuming ~4,000
dropped items meant a 181 ms tick. Measured on Paper 26.2:

| Ticking dropped items | Tick time | TPS |
| ---: | ---: | ---: |
| 0 | 0.2 ms | 20.0 |
| 6,000 | 0.2 ms | 20.0 |
| 30,000 | 21.2 ms | 20.0 |
| 44,451 | 33.0 ms | 20.0 |
| 68,001 | 53.8 ms | 18.6 |

Roughly **0.75 ms per 1,000 ticking items** above ~10k, and essentially free
below that — about a quarter of what the synthetic thresholds assumed. Tune
`detector.floor-ms` from this table rather than from intuition.

### Sampler cost

`Bench.java` benchmarks `WorldCensus`, the real production hot loop. The first
implementation used `Map<String,Integer>` counters and `Map<Long,Cluster>` chunk
buckets, which box an `Integer` and a `Long` for every entity on every sample:

| Entities | Before | After | |
| --- | --- | --- | --- |
| 1,000 | 0.07 ms | 0.04 ms | |
| 10,000 | 1.03 ms | 0.65 ms | |
| 50,000 | 14.28 ms | 3.28 ms | 4.4x |
| 150,000 | 87.25 ms | 11.58 ms | 7.5x |

14 ms at 50k entities is over a quarter of a tick; 87 ms at 150k blows the whole
budget. It also degraded superlinearly under GC pressure.

Replaced with an open-addressed primitive hash map keyed on a packed `long` and
mutable int counters — no allocation per entity once the tables have grown.
Scaling is linear, and a 150k-entity server spends ~0.6% of wall clock sampling
at the 2-second default.

That hand-written map is validated by differential tests: randomised input run
through both it and an obviously-correct `HashMap` reference, asserting they
agree on totals and hotspots.

### Block-entity census cost

Measured with 17,600 hoppers across ~1,500 loaded chunks, using the
snapshot-free `getTileEntities(false)` overload:

| Census interval | Steady tick time |
| --- | ---: |
| never running (bug 4) | 4.2 ms |
| every sample (2 s) | 4.8 ms |

About 0.6 ms amortised when run every two seconds, or roughly **24 ms for a
single pass** — half a tick budget, which is why it is rate limited. At the
default of every 15 samples that is ~0.04 ms amortised.

## Known limitations

**A sustained flood becomes the baseline.** Once more than half the sample
window contains it, `entity-flood` correctly sees no excess and falls back to
"no identifiable cause" while the server is still slow. With the default
two-hour window that takes about an hour. `chronic-overload` is the backstop.

**Folia sees only what is near players.** The census is seeded from each online
player, so with nobody online it sees no entities at all, and a forceloaded
chunk-loader farm in an empty corner is invisible. On Paper the same server is
fully censused. Seeding from `World#getForceLoadedChunks()` would mostly close
this.

**Paper 26.2 has no plugin `/reload`.** `/reload` is Mojang's datapack reload
and does not touch plugins. Running it left the plugin sampling at its normal
rate.

## Verification status

Verified:

- Compiles against paper-api 26.2, WorldGuard 7.0.18 and GriefPrevention
  16.18.4 on JDK 25
- 159 tests across five suites, none requiring a server
- Run on real Paper 26.2 and real Folia 26.2

| Suite | Tests | Covers |
| --- | ---: | --- |
| `CoreTests` | 45 | detection, baselines, ranking, standing problems, census scheduling |
| `CensusTests` | 20 | the hand-written hash map and its Folia merge |
| `RemedyTests` | 27 | what remediation refuses to do |
| `ProtectionTests` | 25 | claim protection, failing closed |
| `HistoryTests` | 42 | log encoding, de-duplication, JSON, webhook URLs |

The weighting is deliberate. About half the detection tests assert a rule does
*not* fire; most remediation tests assert the plugin refuses to act; the
protection tests are mostly about failing closed; the webhook tests are mostly
hostile URLs.

Not verified:

- **Behaviour on a populated server.** Every test used an empty flat world.
  Player movement, chunk generation, redstone and mob AI are the actual sources
  of lag on a real server and none were present.
- **The Folia census fan-out**, which needs a connected player.
- **A player-made GriefPrevention claim** with owners and flags.
- **Block-entity diagnosis under mixed load** — hoppers were tested as a uniform
  slab with nothing else happening.
