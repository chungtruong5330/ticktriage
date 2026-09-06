# TickTriage

Diagnoses **why** a Minecraft server lagged, then clears the excess at the one
hotspot responsible — not everything, everywhere.

```
Lag incident: TPS fell to 5.5 for 6s

[CRITICAL] 4,207 dropped items in world 'world' around (412, 64, -1180) (100%)
  - 4,207 at peak versus a normal 190 (22.1x)
  - 3,449 of them clustered at (412, 64, -1180)
  Fix: Almost always an unfiltered mob or item farm near (412, 64, -1180). Raise
  merge-radius.item in spigot.yml, shorten the item despawn rate for 'world', or
  have staff inspect that build.
```

`spark` tells you the server is slow and hands you a profile. TickTriage tells
you which entity type, in which chunk, and which config key to change.

**Status: beta.** Verified on real Paper and Folia 26.2. Not yet run on a server
with players on it — see [Status](#status).

**Contains AI-generated content.** The code was written primarily by an AI
model. See [How this was built](#how-this-was-built).

---

## Requirements

- Paper or Folia **26.2**
- Java **25**

## Installation

1. Download the jar from [Releases](https://github.com/slateline/ticktriage/releases).
2. Drop it in `plugins/` and restart.
3. Wait about a minute while it learns your server's baseline.
4. `/tt status`

No configuration is required. It changes nothing until you ask it to.

## Commands

| Command | Description | Permission |
| --- | --- | --- |
| `/tt status` | Health summary and current settings | `ticktriage.use` |
| `/tt report` | Full diagnosis of the most recent incident | `ticktriage.use` |
| `/tt history [n]` | Past incidents and a 24-hour summary | `ticktriage.use` |
| `/tt fix` | Show the plan and dry-run it | `ticktriage.use` |
| `/tt fix confirm` | Apply the plan | `ticktriage.fix` |
| `/tt undo [id]` | Restore what a fix removed | `ticktriage.fix` |
| `/tt reset` | Clear the baseline and relearn | `ticktriage.admin` |
| `/tt help` | Command list | `ticktriage.use` |

Aliases: `/ticktriage`, `/tt`, `/triage`. All permissions default to op.

## What it diagnoses

| Rule | Fires when | Example advice |
| --- | --- | --- |
| `entity-flood` | One entity type far above its own baseline | Raise `merge-radius.item`; inspect the farm at these coordinates |
| `block-entity-flood` | Hoppers, spawners or furnaces spiking | Raise `ticks-per.hopper-transfer` |
| `chunk-load` | Loaded chunks spiking | Pre-generate with Chunky; set a world border |
| `memory-pressure` | Heap near full **and** real GC pause time | Aikar's flags, or find the leak |
| `chronic-overload` | Baseline tick time already over budget | Cut view-distance; spikes are a symptom |
| `player-surge` | Load scaled with player count | Nothing is broken — informational only |
| `unexplained` | Nothing tracked moved | Says so, and points at spark |

Thresholds are relative to each server's own baseline, so a large server is not
flagged simply for being large. A rule needs both a ratio and an absolute margin
before it fires.

## Safety

Remediation is opt-in and refuses by default.

- **Dry run first.** `/tt fix` changes nothing; applying requires `confirm`.
- **Allowlist.** Only dropped items, XP orbs and arrows may ever be removed.
  Mobs, villagers, armour stands, item frames and minecarts never are.
- **60-second age floor.** Recently dropped items are never touched, so a
  player's death drop is safe.
- **Claim-aware.** Nothing inside a WorldGuard region or GriefPrevention claim
  is removed. Whole worlds and explicit regions can also be protected in config.
- **Bounded.** Two chunks around the diagnosed hotspot, never server-wide.
- **Excess only.** Clears down to the server's own normal, not to zero.
- **Reversible.** `/tt undo` restores the last fix while the server is up.

Entities that are named, leashed, in a vehicle, carrying passengers, wearing
equipment, holding an inventory, reserved for a player, or deliberately made
permanent are always skipped, and the report says how many and why.

Anything TickTriage will not automate — mob floods, hopper chains, memory
problems — is reported as advice with the reason it was not automated.

## Configuration

Defaults are in `plugins/TickTriage/config.yml` and are safe as shipped. The
settings most worth knowing:

| Key | Default | Purpose |
| --- | --- | --- |
| `detector.floor-ms` | `55.0` | Tick time before a sample counts as a spike |
| `remediation.auto-apply` | `false` | Apply fixes without confirmation |
| `remediation.min-entity-age-seconds` | `60` | Never remove anything newer |
| `remediation.protected-worlds` | `[]` | Worlds to leave entirely alone |
| `alerts.discord-webhook` | `''` | Post incidents to Discord |
| `sample-interval-ticks` | `40` | How often to sample |

The plugin makes no network connections unless a Discord webhook is configured.

## Building

```bash
bash build-jar.sh        # -> build/TickTriage-0.1.0.jar
bash run-core-tests.sh   # 159 tests, no server required
```

`build-jar.sh` resolves dependencies with `curl` and needs JDK 25.
`build.gradle.kts` is kept in step for Gradle users.

## Project layout

```
core/    diagnosis engine, safety policy, remediation planning
         no Bukkit imports — which is why the tests run without a server
paper/   the only package that knows Bukkit exists
```

`Platform` abstracts Paper and Folia scheduling so every operation runs on the
thread allowed to touch the data it uses.

## Status

Verified on real Paper 26.2 and Folia 26.2, including live WorldGuard and
GriefPrevention. 159 tests, none of which require a server.

Not yet verified: behaviour on a server with players on it. Every test so far
used an empty flat world, and real lag comes from players, chunk loading,
redstone and mob AI. If you run a server, [TESTING.md](TESTING.md) explains what
would be most useful to report.

Detailed test results, benchmarks and design rationale are in
[docs/ENGINEERING.md](docs/ENGINEERING.md).

## How this was built

**The code in this repository was written primarily by Claude (Anthropic's
model), working from direction by [@slateline](https://github.com/slateline).**
Every commit records this in a `Co-Authored-By` trailer.

What that means concretely: the Java, the tests, the build scripts and the
documentation were AI-written. The human contribution was direction and
judgement — choosing the problem, deciding what the plugin should refuse to do,
calling which bugs mattered, setting the licence, and deciding when a claim was
not yet supported by evidence.

Some consequences worth stating:

- **It is not on Modrinth.** Their rules prohibit publishing projects that are
  primarily a product of AI output, and this one is. That is the correct reading
  of the rule, not an oversight.
- **Judge it on the record, not the byline.** Whether it works is answered by
  [docs/ENGINEERING.md](docs/ENGINEERING.md): what was measured, what was run on
  a real server, the four bugs that live testing caught, and — most importantly
  — the list of things still unverified. That evidence would be the right basis
  for trusting or distrusting any plugin.
- **This disclosure stays accurate.** If the balance of authorship changes
  through ongoing maintenance, this section changes with it.

## Contributing

Bug reports are more useful than features right now, particularly cases where a
diagnosis was wrong. Console output and the exact `/tt report` text beat a
description.

## Licence

GPL-3.0. Free to use on any server, including one that makes money, and free to
modify. Distributed modifications must publish their source.
