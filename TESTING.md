# Testing TickTriage

Thanks for trying this. It is a beta and you are among the first people to run
it on a server with actual players on it.

## Before you install it

**This plugin's code was written primarily by an AI model**, directed by a human
who made the design and safety decisions. Every commit records it. You are
entitled to weigh that however you like before running it on your server.

If it helps: what the plugin does and does not touch is spelled out below, it
changes nothing by default, and every claim about it having been tested is
backed by [docs/ENGINEERING.md](docs/ENGINEERING.md), which also lists what has
*not* been verified.

## What it does

Watches your server, learns what its normal tick time and entity counts look
like, and when things get slow tells you **why** — with coordinates and the
config setting to change. It can also clear the excess at the one hotspot
responsible, but it will not do that on its own unless you turn that on.

## Am I allowed to run this?

Yes, freely. It is **GPL-3.0** free software. Run it on any server including a
monetised one, modify it, and share it. If you distribute a modified build, ship
your source alongside it — that is the only condition.

## Install

1. Requires **Paper or Folia 26.2** and **Java 25**.
2. Drop `TickTriage-0.1.0.jar` into `plugins/` and restart.
3. Wait about a minute. It needs 30 samples to learn your baseline before it
   says anything.
4. `/tt status`

That is the whole setup. There is nothing to configure to get started.

## What is safe by default

Out of the box **it does not change anything**. It watches and reports.

If you want to try the fix side, `/tt fix` always **dry runs first** and shows
you exactly what it would remove and what it would skip. Applying takes an
explicit `/tt fix confirm`.

When it does remove things, it will not touch:

- Anything dropped in the last 60 seconds — so a player's death drop is safe
- Anything named, on a lead, in a minecart, wearing armour, or holding items
- Anything inside a WorldGuard region or GriefPrevention claim
- Mobs, villagers, armour stands, item frames, minecarts — ever, under any
  setting. It only removes dropped items, XP orbs and arrows.

It also only clears the *excess* over your server's normal, inside 2 chunks of
the hotspot — not everything, everywhere. And `/tt undo` puts back what the last
fix removed, while the server stays up.

**Leave `auto-apply: false`.** That is the default. Do not turn it on during
testing.

## Commands

| Command | Does |
| --- | --- |
| `/tt status` | Health plus current settings |
| `/tt report` | Why the last slowdown happened |
| `/tt history` | Past incidents, and a 24-hour summary |
| `/tt fix` | Show the plan and dry-run it — changes nothing |
| `/tt fix confirm` | Actually apply it |
| `/tt undo` | Put back what the last fix removed |

`/tt help` lists them in game.

## What I actually need from you

**The thing I cannot test myself is whether it is ever wrong.** Every test so
far has been on an empty flat world with no players. Real servers have people
moving around, chunks loading, redstone, farms and mob AI — and I do not know
how the plugin behaves against any of that.

So the most useful reports are:

1. **Did it blame the wrong thing?** It said the cause was X, you looked, and X
   was fine. This is the single most valuable report and the one I most expect.
2. **Did it stay quiet while your server was obviously lagging?** The opposite
   failure, equally useful.
3. **Did `/tt fix` propose something that made you nervous?** You do not have to
   run it — the dry run output alone tells me a lot. Paste it.
4. **Did anything break?** Errors in console, TPS getting worse after install,
   commands not working.
5. **Was the wording confusing?** If a diagnosis did not tell you what to do
   next, it failed at its job.

Paste `/tt report` and `/tt status` output with any of these. The
`plugins/TickTriage/incidents.tsv` file is plain text and readable with `cat`
if you want to send a history.

## Known rough edges, so you do not waste time reporting them

- **Folia**: it loads and runs, but with nobody online it sees no entities at
  all, and the per-region census has never been exercised with real players.
- **Thresholds are guesses.** They were calibrated against a flat test world.
  `detector.floor-ms` in the config is the main dial if it is too noisy or too
  quiet. Tell me what value ended up working for you — that is useful data.
- On a server that is *always* slow, it reports that as a standing problem
  rather than an incident. That is intentional.
- Big servers: the sampler walks every entity every 2 seconds. Measured at about
  0.75 ms per 1,000 entities. If you have hundreds of thousands, raise
  `sample-interval-ticks`.

## Uninstalling

Delete the jar. It keeps nothing outside `plugins/TickTriage/` and makes no
network connections unless you configure a Discord webhook.

## Reporting

Open an issue, or send it however you prefer. Console logs and the exact output
beat a description every time.
