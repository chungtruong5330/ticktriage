# TickTriage

Finds out **why** a Minecraft server lagged, then fixes it **surgically** —
clearing the excess at the one hotspot responsible, not everything everywhere.

```
[CRITICAL] 4,207 dropped items in world 'world' around (412, 64, -1180) (100% confidence)
  - 4,207 at peak versus a normal 190 (22.1x)
  - 3,449 of them clustered at (412, 64, -1180)

----- what it proposes -----
Automatic fixes (1):
  - Clear up to 4,017 item entities in world within 2 chunks of (25, -74)
    why: Clears the 4,017 above this server's normal 190, inside 2 chunks of the
    hotspot only. Entities newer than 60s, named, tamed, equipped or carrying an
    inventory are skipped.

----- dry run -----
  Inspected: 4,664
  Would remove: 4,017
  Skipped 264:
    260 - dropped less than 60s ago
    3 - has a custom name
    1 - holds an inventory
  383 eligible items left in place - the plan only clears the excess over normal.
  Run with 'confirm' to apply.
```

A blunt cleanup plugin takes all 4,664. This takes 4,017 and explains the other
647.

**Paper & Folia 26.2 · JDK 25 · 154 tests · no runtime dependencies · no telemetry**

Verified end to end on a real Paper 26.2 server. See [Live-server results](#live-server-results).

```bash
bash build-jar.sh        # -> build/TickTriage-0.1.0.jar  (117 KB)
bash run-core-tests.sh   # 154 tests + benchmark + demo, no server needed
```

> Renamed from **LagDoctor**, which is taken on SpigotMC. `Lag*` is a crowded
> prefix — LagDetector, LagFixer, LagAssist and LagMonitor all exist. Verify
> `TickTriage` on BuiltByBit, Polymart/voxel.shop, Hangar and Modrinth before
> publishing; nothing turned up in searches, but that is not proof.

---

## Why it works this way

The original plan was diagnosis-as-product with fixing as a premium upsell. The
download numbers said that was backwards.

Modrinth, September 2026:

| Plugin | What it does | Downloads | Last update |
| --- | --- | ---: | --- |
| spark | Profiles, for experts | **21,528,558** | Jun 2026 |
| LagFixer | **Fixes automatically** | **310,465** | Aug 2026 |
| ClearLag++ | **Fixes automatically** | **172,401** | Aug 2026 |
| LagAssist | Analyses and prevents | 23,180 | Nov 2025 |
| Insights | Analyses chunks/entities | 13,641 | Jul 2026 |
| LagDetector | Detects only | 468 | Jan 2026 |
| LagDoctor (SpigotMC) | Diagnoses and explains | **41** | Mar 2026 |

Tools that **fix** sit at 170k–310k and are actively maintained. Tools that
**analyse** top out around 13k–23k. Tools that only **detect or explain** have
468 and 41 downloads and are both abandoned.

*(Caveat: LagDoctor's 41 is SpigotMC, not Modrinth, so that row isn't strictly
comparable. The Modrinth-internal ordering carries the argument.)*

Category demand is real and large — spark at 21.5M settles that — but
**explanation alone does not sell**. Owners want the problem gone.

**The opening is that the fixers fix bluntly.** LagFixer and ClearLag++ clear
all ground items everywhere on a timer, which is why owners complain about them
eating player storage and breaking intended farms. TickTriage knows *which*
entities, in *which* chunk, and whether that's abnormal for this server — so it
clears the excess at the hotspot and leaves the storage room at spawn alone.

Shotgun versus scalpel. The diagnosis engine isn't the product; it's what makes
the fixing safe enough to trust.

---

## The safety model

This is the only code in the project that destroys things, so it's built to
refuse by default.

**An allowlist, not a blocklist.** Only items, XP orbs, arrows, snowballs and
eggs can *ever* be auto-removed. Mobs are deliberately absent — culling mobs is
how blunt plugins end up deleting somebody's bred animals. Villagers, armour
stands, item frames, minecarts: never.

**Eight veto flags.** Even an allowlisted entity is spared if it is named,
tamed, leashed, riding or carrying something, marked persistent, wearing
equipment, or holding an inventory.

**Sixty-second minimum age.** A player who just died has their entire inventory
on the ground. Clearing it would be the worst thing this plugin could do, so
fresh drops are never touched.

**Claim protection.** Nothing inside a WorldGuard region or GriefPrevention
claim is ever removed. Whole worlds and explicit boxes can also be protected in
config, for servers that guard spawn by convention rather than with a plugin.

**Bounded radius.** Two chunks around the diagnosed hotspot — a 5×5 chunk area.
Never server-wide.

**Only the excess.** It clears down to *this server's own baseline*, not to
zero. The 190 items that are normal here survive.

**Oldest first.** If the cap is reached, what survives is the most recently
dropped — the material most likely to belong to a player still standing there.

**Loaded chunks only.** Loading a chunk to clean it would create the very work
the fix is meant to save.

**Dry run by default.** `/tt fix` shows what it would do. Applying takes an
explicit `confirm` and the `ticktriage.fix` permission. `remediation.auto-apply`
exists for owners who want it unattended, and ships **off**.

**Undo.** Applied fixes store the removed item stacks in memory; `/tt undo`
drops them back. Capped, doesn't survive a restart, and isn't a backup — it
exists for the one scenario that would otherwise end this plugin's reputation:
the fix took something it shouldn't have and an admin needs it back in the next
few minutes.

### Failing closed

Claim protection is built to fail *closed*, everywhere:

- A protection provider that throws makes the location **protected**, not
  unprotected.
- If WorldGuard or GriefPrevention is installed but the hook fails to
  initialise, remediation **stops entirely** and logs loudly. Carrying on with
  claim checking silently disabled is how you find out from a player asking
  where their base went.
- A single provider is still wrapped by the composite, specifically so its
  try/catch applies. (Returning the bare provider as an "optimisation" was a
  real bug here, caught by the test written for it.)

### What it refuses to automate, and says so

| Diagnosis | Response |
| --- | --- |
| Item / XP orb flood **with a hotspot** | **Automatic**, bounded, capped at the excess |
| Mob flood | Advice — "not on the removable allowlist, because clearing it risks destroying something a player owns" |
| Hopper / spawner flood | Advice — "fixing this means breaking blocks somebody placed" |
| Flood with **no** hotspot | Advice — "spread across the world, so any clear would be indiscriminate" |
| Excess under 100 | Advice — "not worth touching the world for" |
| Memory, chunk load, chronic overload | Advice — carries the config fix |
| Player surge (INFO) | Nothing. Nothing is broken. |

"I could see the problem but I'm not going to touch it, and here's why" is more
useful to an admin than either silence or a surprise.

---

## Commands

| Command | Does |
| --- | --- |
| `/tt report` | Full diagnosis of the most recent incident |
| `/tt status` | Health summary plus current settings |
| `/tt history [n]` | Past incidents, and a 24-hour summary |
| `/tt fix` | Show the plan and dry-run it |
| `/tt fix confirm` | Apply it (needs `ticktriage.fix`) |
| `/tt undo [op-id]` | Restore items from an applied fix |
| `/tt reset` | Clear the baseline and relearn (needs `ticktriage.admin`) |
| `/tt help` | Command list |

Aliases `/ticktriage`, `/tt`, `/triage`. Permissions `ticktriage.use`,
`ticktriage.fix`, `ticktriage.admin` (all op by default).

## Alerts and history

**Console** — CRITICAL incidents log a full diagnosis, rate-limited to one alert
per five minutes so one bad farm can't produce a thousand identical lines.

**Discord** — set `alerts.discord-webhook` and incidents post to a channel.
Owners live in Discord, not the console. Sent asynchronously; a dead webhook
logs once and then stays quiet rather than complaining every five minutes.

**On disk** — incidents append to `incidents.tsv` in the plugin folder as plain
tab-separated text, so an owner can answer "what happened while I was asleep"
with `cat`, without the server running. De-duplicated: the watcher re-analyses
the same window every 30 seconds, and without that guard one spike would be
written dozens of times.

Only Discord involves any network traffic, it goes to a URL the admin
configures, and nothing else phones home.

## What it diagnoses

| Rule | Fires when | Example fix given |
| --- | --- | --- |
| `entity-flood` | One entity type far above its own baseline | Raise `merge-radius.item`, inspect the farm at these coords |
| `block-entity-flood` | Hoppers, spawners, furnaces spiking | Raise `ticks-per.hopper-transfer` |
| `chunk-load` | Loaded chunks spiking | Pre-generate with Chunky, set a world border |
| `memory-pressure` | Heap near full **and** real GC pause time | Aikar's flags, or hunt the leak |
| `chronic-overload` | Baseline tick time already over budget | Cut view-distance; spikes are a symptom |
| `player-surge` | Load scaled with player count | Nothing is broken — INFO only |
| `unexplained` | Nothing tracked moved | Says so honestly, points at spark |

### Two detection decisions that matter

**Every threshold is relative to the server's own baseline.** There's no
universal healthy entity count — a large Skyblock server and a ten-player SMP
have nothing in common. A rule needs both a ratio *and* an absolute margin to
fire. Ratio alone flags a world going from 2 armour stands to 6; absolute alone
flags a big server for being big.

**Memory pressure requires two signals.** A heap at 95% is normal for a JVM with
a large heap and no reason to collect. High usage alone is the most misread
number in server administration, and owners buy RAM they don't need because of
it. Only high usage *and* real GC pause time together count.

---

## Paper and Folia

Runs on both. `folia-supported: true` is declared, but that flag alone means
nothing — Folia splits the world into regions that tick on separate threads, and
touching a chunk from the wrong thread is undefined behaviour rather than a
clean exception. So every operation that touches world state goes through a
`Platform` abstraction that aims it at the thread allowed to do it.

| | Paper | Folia |
| --- | --- | --- |
| Repeating tasks | Bukkit scheduler | `GlobalRegionScheduler` |
| Entity census | one pass over every loaded chunk | fanned out per region, then merged |
| Fixes | main thread | `RegionScheduler` at the target chunk |
| Undo | main thread | grouped by chunk, one region task each |

The census fan-out uses each player's **entity** scheduler rather than the
region scheduler, because it follows the player if they cross a region boundary
before the task fires. Each region counts its own chunks into a private
`WorldCensus`; those are merged with `absorb()` once every region reports.
Chunks are claimed with a concurrent set so two players standing together don't
double-count what they share.

**One honest behavioural difference.** On Folia the census sees the chunks
around players (6 either way by default), not every loaded chunk. A chunk loader
running an unattended farm in an empty corner of the world is invisible there,
where on Paper it would be counted. `/tt status` says which strategy is in use
rather than hiding it. Everything else — memory pressure, tick time, player
surge, all remediation — behaves identically.

The merge is the risky part of that design, so it's tested as a property:
splitting a population across 2, 8 and 16 shards and merging must give exactly
the same totals and hotspots as counting it in one pass, including the case
where every shard writes into the same few chunks.

---

## Not causing the problem you're diagnosing

The sampler's cost was measured, not assumed — `Bench.java` benchmarks
`WorldCensus`, the real production hot loop.

**The first measurement failed.** `Map<String,Integer>` counters and
`Map<Long,Cluster>` chunk buckets box an `Integer` and a `Long` for *every
entity on every sample*:

| Entities | Before | After | |
| --- | --- | --- | --- |
| 1,000 | 0.07 ms | **0.04 ms** | |
| 10,000 | 1.03 ms | **0.65 ms** | |
| 50,000 | 14.28 ms | **3.28 ms** | 4.4x |
| 150,000 | 87.25 ms | **11.58 ms** | 7.5x |

14 ms at 50k entities is over a quarter of a tick; 87 ms at 150k blows the whole
budget — the plugin *was* the lag, degrading superlinearly under GC pressure.

The fix: an open-addressed primitive hash map keyed on a packed `long`, with
mutable int counters. No allocation per entity once the tables have grown.
Linear now — a 150k-entity server spends ~0.6% of wall clock sampling at the
2-second default.

That hand-written map is validated by **differential tests**: randomised input
run through both it and an obviously-correct `HashMap` reference, asserting they
agree. Hand-rolled hash maps are exactly the code that's quietly wrong on the
paths nobody tries.

Block-entity counts need a walk of every loaded chunk, so they run once every 15
samples and are cached in between. Counts change slowly; TPS does not.

---

## Live-server results

Run on Paper 26.2 build 121 with a flat test world, driven over RCON. **This
found three bugs that 141 passing unit tests did not**, which is the whole
argument for doing it.

### What real lag actually costs

Every threshold here was originally calibrated against synthetic data assuming
~4,000 dropped items meant a 181 ms tick. Measured:

| Ticking dropped items | Tick time | TPS |
| ---: | ---: | ---: |
| 0 | 0.2 ms | 20.0 |
| 6,000 | 0.2 ms | 20.0 |
| 30,000 | 21.2 ms | 20.0 |
| 44,451 | 33.0 ms | 20.0 |
| 68,001 | 53.8 ms | 18.6 |

Roughly **0.75 ms per 1,000 ticking items** above ~10k, and essentially free
below that. Modern Paper is far better at this than the old folklore assumes: a
"lag machine" of 6,000 items costs nothing measurable. Tune
`detector.floor-ms` from this table, not from intuition.

### Bug 1: chronic overload was unreachable

The server sat flat at **53.8 ms per tick (18.6 TPS)** and the plugin said
**"No lag incidents."**

`ChronicOverloadRule` exists precisely for that server, but rules only ran
inside a detected incident, and detection needs a spike above both 55 ms *and*
1.5x baseline. On a uniformly slow server the baseline *is* 53.8 ms, so the
relative threshold became 80 ms and the rule could never fire. The unit test
passed because its synthetic history spiked to 190 ms over a 48 ms baseline -
real chronically-slow servers do not spike, they just sit there.

Rules can now report on the baseline alone via `evaluateBaseline`, and a report
can describe a standing condition with no incident attached.

### Bug 2: remediation removed nothing, ever

Applying a fix reported `Removed: 0` and `Skipped 32,061 - is marked persistent`.

The safety policy vetoed anything where `Entity#isPersistent()` was true. That
method means "gets saved to the world file", true for virtually every entity
including ordinary dropped items - not "deliberately made permanent", which is
what the veto was written for. **The plugin would have shipped as a no-op**,
refusing to remove anything while appearing to work.

It now uses the per-type signals that actually mean that:
`Item#isUnlimitedLifetime()` and `LivingEntity#getRemoveWhenFarAway()`. Reading
the real API while fixing this also turned up `Item#getOwner()` - an item
reserved for a specific player - which is now protected and previously was not.

### Bug 3: fix results never reached the console

`/ticktriage fix` printed the plan and then nothing. `PaperPlatform` routed work
through `BukkitScheduler#runTask`, which defers to the *next* tick even when
already on the main thread, so the RCON and console handler had returned before
the result existed. It now runs inline when already on the main thread.

### What the live run confirmed working

- Loads on Paper 26.2, detects the platform, reports no claim plugins correctly
- `Bukkit.getAverageTickTime()` returns real milliseconds (0.2 ms idle)
- Full diagnosis pipeline: incident detected, 34,540 items identified, hotspot
  chunk located, fix offered
- **The 60-second age floor**, against real entities: inspected 32,066, would
  remove **0**, all skipped as "dropped less than 60s ago" - the guarantee that
  stops it eating a player's death drop
- **Radius bounding**: 30,005 items existed, only 14,523 were inspected, because
  the fix stays within 2 chunks of the hotspot
- **Removal**: 14,518 removed, and the 5 items named "Bobs Loot" survived
- **Undo**: restored 5,000 (its cap) and said plainly that 9,518 were past the
  cap and gone

### Known limitation found while testing

A *sustained* flood eventually becomes the baseline. Once more than half the
sample window contains it, `entity-flood` correctly sees no excess and falls
back to "no identifiable cause" while the server is still slow. With the default
two-hour window that takes about an hour; it took a minute here because the
history had just been reset. `chronic-overload` is the backstop, which is
another reason Bug 1 mattered.

---

## Verification status

**Verified:**

- Compiles against real paper-api 26.2, WorldGuard 7.0.18 and GriefPrevention
  16.18.4 on JDK 25 — 72 classes, zero errors
- Produces a working 117 KB jar with correct `plugin.yml` (`api-version: '26.2'`)
- **154 tests** across five suites, all passing:

| Suite | Tests | Covers |
| --- | ---: | --- |
| `CoreTests` | 40 | detection, baselines, ranking, standing problems |
| `CensusTests` | 20 | the hand-written hash map and its Folia merge |
| `RemedyTests` | 27 | what remediation refuses to do |
| `ProtectionTests` | 25 | claim protection, failing closed |
| `HistoryTests` | 42 | log encoding, de-duplication, JSON, webhook URLs |

The weighting is deliberate. About half the detection tests assert a rule does
**not** fire; most remediation tests assert the plugin **refuses** to act; the
protection tests are mostly about failing closed; and the webhook tests are
mostly hostile URLs. Those are the product's entire argument, so that's where
the tests live.

**Still not verified:**

- **Everything Folia.** The scheduling is written to the documented API and the
  merge logic is property-tested, but no part of it has run on real Folia.
  Thread-ownership mistakes there are silent corruption rather than exceptions,
  so it needs testing on actual Folia before anyone relies on it.
- **The WorldGuard and GriefPrevention hooks.** They compile against the real
  APIs, and the test server ran correctly without either installed (reporting
  "Claim protection: none"), but neither has been exercised against a live
  instance.
- **The real false-positive rate on a populated server.** Everything above was
  measured with zero players on a flat world. Player movement, chunk loading,
  redstone and mob AI are the actual sources of lag on a real server, and none
  of them were present.
- **Block-entity cost.** No hoppers or spawners existed in the test world, so
  `Chunk#getTileEntities()` was never exercised at scale.
- Whether scheduler tasks survive `/reload`.

Running a live server means accepting the Minecraft EULA, which is yours to
accept. Download Paper 26.2, set `eula=true`, drop the jar in `plugins/`, and
leave it a week on a server you don't mind breaking. **Keep
`auto-apply: false` until you've watched the dry runs for a long time.**

---

## Architecture

```
core/            no Bukkit imports anywhere — this is why 154 tests run offline
  Snapshot           one immutable sample of server state
  WorldCensus        the per-entity hot loop; primitive hash map, benchmarked
  Baseline           what normal looks like, from medians
  IncidentDetector   finds lag windows (absolute AND relative thresholds)
  DiagnosisEngine    runs rules, ranks by severity then confidence
  DiagnosisTarget    the machine-readable half a fix can be aimed at
  IncidentLog        bounded, de-duplicated history
  IncidentRecord     one incident as one escaped TSV line
  Json / WebhookUrl  outbound-alert plumbing, both hostile-input tested
  rules/             one file per hypothesis
  remedy/
    SafetyPolicy         what may EVER be destroyed — allowlist plus veto flags
    ProtectionOracle     is this location somebody's claim?
    CompositeProtection  several providers, failing closed
    ConfiguredProtection admin-declared worlds and boxes
    RemovalFilter        the single gate — policy AND claims
    RemediationPlanner   decides what to automate and what to refuse
paper/           the only package that knows Bukkit exists
  Platform                 Paper vs Folia scheduling, behind one interface
  ServerSampler            live state -> Snapshot (Paper)
  FoliaSampler             regionised census, fanned out and merged
  RemediationExecutor      supplies entity facts, does the removing
  WorldGuardProtection     } claim adapters, loaded only when the
  GriefPreventionProtection } corresponding plugin is installed
  UndoStore                keeps removed stacks for a short window
  IncidentLogStore         incidents.tsv
  DiscordNotifier          async webhook POST
```

The safety policy lives in `core` on purpose: the code that can destroy a
player's belongings is the code that most needs exhaustive tests, and those only
run offline if it has no Bukkit dependency. The executor supplies facts and
carries out decisions; it never makes them.

Medians, not means, everywhere. A lag spike is by definition an outlier.

`build-jar.sh` is the build that has actually been run — it resolves every
dependency with curl and packages the jar without Gradle. `build.gradle.kts` is
kept in step with it and is the more normal workflow, but **it has never been
executed**: Gradle isn't available on the machine this was built on. Expect to
fix something the first time you run `gradle build`.

---

## Before you list it

1. **Run it on a *populated* server for a week** with `auto-apply: false`. The
   throwaway test above had zero players on a flat world; real lag comes from
   players, chunk loading, redstone and mob AI, none of which were present.
2. **Verify the name** on every marketplace.
3. **Fill in `plugin.yml`** — `website` still says `CHANGE_ME`.
4. **Read the piracy forums.** Cracked listings are a ranked list of what
   genuinely sells, and they'll tell you your realistic leakage rate.
5. **Free version first.** Diagnosis, history and dry-run free on SpigotMC and
   Modrinth for reach; automatic remediation and Discord alerts paid on
   BuiltByBit and Polymart. Reviews on the free one are the credibility you
   don't have yet.

## Roadmap

1. Folia testing on real Folia, and a populated Paper server; fix what breaks
2. Per-plugin attribution — hard, but the feature people would pay most for
3. A web dashboard for incident history, if owners ask for it

Suggested pricing: free tier diagnoses and dry-runs; paid (~$15–20) applies
fixes automatically and posts to Discord.
