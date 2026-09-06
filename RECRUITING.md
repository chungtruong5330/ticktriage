# Finding testers

Notes to self. Not part of the plugin.

## Before posting anywhere

**The licence blocks this.** As written, `LICENSE` grants nobody permission to
use the plugin, and the repo is private. A tester would have no legal right to
run it and no way to get it. Pick one:

- **Add a testing grant** to the proprietary licence — narrowest change, keeps
  every option open. Something like: permission to run unmodified copies on
  servers you operate, for evaluation, revocable, no redistribution.
- **Go MIT** — simplest, and the strongest signal for getting installs, but a
  one-way door: anything published stays free for whoever has it.
- **Test only with people you know**, informally, and skip public recruitment
  entirely.

Nothing below works until this is settled.

## Where the people actually are

| Place | Why | Notes |
| --- | --- | --- |
| **r/admincraft** | Where server admins argue about what plugins actually work | The single best fit. Read their self-promotion rules first — most subreddits require you to be a participant, not a drive-by poster. |
| **SpigotMC forums** | Huge, and specifically has resource and plugin-development sections | Slow but broad reach. |
| **PaperMC Discord** | Where the technically-minded owners are | Best audience for a *performance* plugin — they will actually read a diagnosis and tell you it is wrong. |
| **Modrinth** | Publish as a beta release | Not recruitment exactly, but it makes the jar obtainable and gives a download counter, which is the cheapest possible demand signal. |
| **Hangar** (PaperMC's own) | Same, and closer to the Paper audience | Worth doing alongside Modrinth. |

Do **not** start with BuiltByBit or Polymart. Those are storefronts; showing up
with an unproven v0.1 sets the wrong first impression on the platforms where a
paid version would eventually live.

## What to ask for

Ask for **one specific thing**, not "please test my plugin". The specific ask:

> Run it for a week and tell me if it ever blames the wrong thing.

That is the actual open question, it is easy to answer, and it does not require
anyone to trust the fix side of the plugin at all.

## Draft post

Adjust tone per venue; r/admincraft is more casual than the Spigot forums.

---

**Looking for a few servers to tell me my lag plugin is wrong**

I wrote a plugin that watches a server, learns its normal, and when TPS drops
tries to tell you *why* — which entity type, which chunk, and the config key to
change. Not a profiler; it is aimed at the case where you know it lagged at 8pm
and do not want to read a flame graph.

It can also clear the excess at the hotspot it identified, but that is off by
default and dry-runs first. It never touches mobs, villagers, item frames or
anything in a WorldGuard/GriefPrevention claim, and it will not clear anything
dropped in the last 60 seconds, so death drops are safe.

Here is my problem: **I have only ever tested it on an empty flat world.** I
have no idea how it behaves against real players, real chunk loading, real
redstone. It has already been wrong in ways I only found by running it — four
separate bugs, two of which made it silently do nothing.

So I am after a handful of servers willing to run it read-only for a week and
tell me when it blames the wrong thing. That is the only report I really need.
Paper or Folia 26.2, Java 25.

[link] — the TESTING.md there says exactly what to look for.

Happy to answer anything, and equally happy to hear that the idea is wrong.

---

## Practical

- Publish a **GitHub release** with the jar attached, so there is one link that
  is not "clone and build it yourself".
- The repo has to be public, or the link is useless.
- Expect roughly one useful reply per ten views. A handful of real testers is a
  good outcome; do not be discouraged by silence on the first post.
- Whatever `detector.floor-ms` values testers settle on are the most valuable
  data you will get — those are the calibration numbers for a real release.
