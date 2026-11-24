# ArkaSMPCore

**The all-in-one core plugin for an SMP server: homes, teleports, land claims, warps, AFK, leaderboards, server statistics, lag cleaning, all in a single lightweight jar.**

> **Why this exists.** We run a survival server (Arka SMP) and got tired of juggling a dozen separate plugins
> (each with its own config, permissions, lag and bugs). So we wrote **one** plugin that does everything an SMP
> needs, tuned for our players, many of whom join from **phones and consoles through Geyser/Floodgate**. It works
> well for us, so we are sharing it with all of you. Use it, change it, learn from it. It is free software (AGPL-3.0).

It is one of three plugins we built together as a suite:

| Plugin | What it does |
|---|---|
| **ArkaSMPCore** (this repo) | Homes, spawn, TPA, RTP, warps, claims, AFK, leaderboards, stats, cleaner |
| [ArkaSMPEconomy](https://github.com/DHANVIN-raj-velagapudi/ArkaSMPEconomy) | Rupee money, `/pay`, `/baltop`, `/trade`, item shop, `/sellgui` |
| [McBackup](https://github.com/DHANVIN-raj-velagapudi/McBackup) | Incremental hourly/daily/weekly backups to a cloud bucket |

ArkaSMPCore works **on its own**. The economy plugin is optional and only adds paid features (warp creation cost,
buying claim blocks).

---

## Contents

1. [Features](#features)
2. [Requirements](#requirements)
3. [Installation](#installation)
4. [Quick start for admins](#quick-start-for-admins)
5. [Player commands](#player-commands)
6. [Admin commands](#admin-commands)
7. [Permissions](#permissions)
8. [Rank limits (LuckPerms)](#rank-limits-luckperms)
9. [Placeholders (PlaceholderAPI)](#placeholders-placeholderapi)
10. [Configuration guide](#configuration-guide)
11. [Replacing other plugins](#replacing-other-plugins)
12. [Bedrock / Geyser notes](#bedrock--geyser-notes)
13. [Status and known limits](#status-and-known-limits)
14. [Building from source](#building-from-source)
15. [Contributing and support](#contributing-and-support)
16. [License](#license)

---

## Features

### Teleporting
- **Homes** (`/sethome`, `/home`, `/homes`, `/delhome`) with per-rank limits and a tap-only menu
- **Spawn** (`/spawn`, `/setspawn`), first-join and respawn teleport
- **TPA** (`/tpa`, `/tpahere`, `/tpaccept`, `/tpdeny`, `/tpcancel`) with clickable Accept/Deny in chat and a menu
- **RTP** (`/rtp`): random safe spot between a min and max radius, never inside a claim, per-world disable list
- Shared rules for all teleports: warm-up, cancel on move or damage, cooldowns, no teleporting out of PvP

### Warps with safety checks
- **Server warps** (`/warp`) made by admins and **player warps** (`/pwarp`) made by players
- Player warp limit **per rank**, and an optional **rupee cost** (needs ArkaSMPEconomy)
- Every warp is checked **when it is made and again right before every teleport**. The teleport is refused if the
  spot has lava or fire next to it, is a sealed obsidian cell, is a tiny platform high in the air, has no support
  block, is in the void or outside the world border, and so on
- Warps in other worlds (Nether/End) are allowed, with a warning before the teleport
- Never loads chunks on the main thread, so a warp check cannot freeze the server

### Land claims
- Claim with a golden shovel (two corners), 1 claim block per ground block
- Claim blocks: a starting amount plus hours of **active (non-AFK)** play, optional earn cap
- **Buy claim blocks with rupees** and **send unused claim blocks to other players** (with anti-alt rules: minimum
  play time, daily cap, only unused blocks)
- **Confirmation before** unclaiming or deleting claims
- Per-claim trust, claim inspection stick, glowing borders only the player sees
- Protects against explosions, fire, pistons (including sticky pull-out), hoppers, dispensers, liquids flowing in,
  mob griefing, animals, hanging entities, beds, and more
- `/trapped` gets a player out of someone else's claim safely
- Staff bypass needs the permission **and** an explicit toggle, so admins are protected by default

### Players and server health
- **Strict AFK**: only real input counts (not being pushed by water or pistons), macro detection, no play time or
  rewards while AFK, optional kick, reduced chunk load for AFK players
- **Lag cleaner** (ClearLag replacement): timed clearing with warnings, whitelist, optional mob removal
- `/lag`: TPS, MSPT, RAM and per-world counts
- **Leaderboards**: kills, deaths, money, playtime, exposed as placeholders for holograms, TAB or scoreboards

### Server statistics dashboard (admins)
`/arka stats` opens a tap-only dashboard with: players ever joined, online now, peak today, record peak, **weekly
active players** (more than 1 hour in the last 7 days), seen in 24 hours / 30 days, new this week, **returning
players**, **7-day retention**, **busiest and quietest hour** (good for planning events and restarts) and a 7-day
bar chart of the daily peak. The same numbers are available as placeholders.

### Built for everyone
- Every feature works by **tapping menus**, with no typing needed (important on mobile)
- Everything is configurable: menus, item icons, every message (MiniMessage colours)
- Fast: chunk-indexed claim lookups, debounced saves, async work off the main thread

---

## Requirements

| | |
|---|---|
| Server | **Paper** (or a Paper fork), built against the 1.21.4 API (`api-version: 1.21`) |
| Java | 21 or newer |
| Optional | **LuckPerms** (rank-based limits), **PlaceholderAPI** (placeholders), **Vault** (money leaderboard), **ArkaSMPEconomy** (paid features), **Geyser + Floodgate** (Bedrock players) |

---

## Installation

1. Download the jar (or [build it](#building-from-source)) and put it in your server's `plugins/` folder.
2. Start the server once. The plugin creates `plugins/ArkaSMPCore/config.yml`.
3. Edit the config (see the [guide](#configuration-guide)), then run `/arka reload`. No restart needed.
4. Set your spawn with `/setspawn`.
5. Give your staff the permissions they need ([Permissions](#permissions)).

**Updating:** when a new version adds config options, your existing `config.yml` does **not** receive them
automatically. Copy the new sections from the default config shipped inside the jar (open the jar as a zip) or
rename your old file and let the plugin generate a fresh one.

---

## Quick start for admins

```text
/setspawn                          set where new players start
/setwarp market                    create a server warp where you stand
/arka admin set claims.starting-blocks 500    change a number without editing the file
/arka admin claimblocks add Steve 200         give claim blocks
/arka stats                        open the statistics dashboard
/arka reload                       reload config.yml
```

Players can type `/arka` for the main menu.

---

## Player commands

No permission is needed for any of these. The full list is also in [PLAYER_COMMANDS.txt](PLAYER_COMMANDS.txt).

| Command | What it does |
|---|---|
| `/arka` | Main menu: homes, spawn, teleport requests, RTP, claims |
| `/sethome [name]`, `/home [name]`, `/homes`, `/delhome [name]` | Manage homes |
| `/spawn` | Go to spawn |
| `/tpa [player]`, `/tpahere [player]` | Ask to teleport to a player, or ask them to come to you |
| `/tpaccept`, `/tpdeny`, `/tpcancel` | Answer or cancel requests (aliases `/tpyes`, `/tpno`) |
| `/rtp` | Random teleport (aliases `/wild`, `/randomtp`) |
| `/warp [name]` | Open server warps or teleport to one |
| `/pwarp [name]`, `/pwarp set <name>`, `/pwarp delete <name>`, `/pwarp list`, `/pwarp info <name>` | Player warps |
| `/afk` | Toggle AFK |
| `/claim` | Claims menu (aliases `/landclaim`, `/lc`) |
| `/claim tool`, `info`, `blocks`, `list`, `show`, `delete`, `deleteall` | Claim management (delete asks for confirmation) |
| `/claim trust <player>`, `untrust <player>`, `trustlist` | Share a claim |
| `/claim buy [amount]` | Buy claim blocks with rupees (needs ArkaSMPEconomy) |
| `/claim send <player> <amount>` | Give unused claim blocks to another player |
| `/unclaim` | Delete the claim you stand in (asks first) |
| `/trapped` (alias `/stuck`) | Escape from inside someone else's claim |

**How to claim land:** run `/claim tool`, right-click one corner with the golden shovel, then right-click the
opposite corner. A claim costs 1 claim block per ground block. Sneak + right-click cancels a selection. Right-click
with a stick to see who owns the land.

---

## Admin commands

Most need `arka.admin`. The full list is in [ADMIN_COMMANDS.txt](ADMIN_COMMANDS.txt).

| Command | What it does |
|---|---|
| `/arka reload` | Reload `config.yml` |
| `/arka clear` | Run the cleaner now |
| `/arka stats` (`arka.stats`) | Statistics dashboard. `/arka stats text` prints the numbers in chat |
| `/lag` (`arka.lag`) | TPS, MSPT, RAM, per-world counts |
| `/setspawn` | Set the spawn |
| `/setwarp <name> [force]`, `/delwarp <name>` (`arka.warp.admin`) | Server warps. `force` skips the safety check |
| `/arka admin claimblocks <get\|add\|take\|set> <player> [amount]` | Manage claim blocks |
| `/arka admin claims <player>`, `deleteclaim [id]`, `deleteclaims <player>`, `claimtp <id>` | Manage claims |
| `/arka admin homes <player>`, `home <player> <name>`, `delhome <player> <name>` | Manage homes |
| `/arka admin homelimit <get\|set\|reset> <player> [n]` | Per-player home limit override |
| `/arka admin resetcooldown <player>`, `afk <player>` | Player tools |
| `/arka admin bypass` | Toggle claim bypass (needs `arka.claims.bypass`) |
| `/arka admin leaderboard <refresh\|reseed>` | Leaderboard tools |
| `/arka admin get <path>`, `set <path> <value>` | Read or change a config number/true-false/text in game |

---

## Permissions

| Permission | Default | Meaning |
|---|---|---|
| `arka.admin` | op | All admin commands |
| `arka.stats` | op | Open the statistics dashboard |
| `arka.lag` | op | `/lag` |
| `arka.warp.admin` | op | Create/delete server warps, delete any player warp |
| `arka.warp.free` | op | Make player warps without paying |
| `arka.claims.bypass` | op | Build in any claim (also needs `/arka admin bypass` by default) |
| `arka.bypass.warmup` | op | Skip teleport countdowns |
| `arka.bypass.cooldown` | op | Skip cooldowns |
| `arka.bypass.combat` | op | Teleport while in combat |
| `arka.afk.bypass` | false | Never marked AFK (builders, staff) |
| `arka.leaderboard.hide` | false | Hidden from leaderboards |
| `arka.homes.limit.<n>` | none | Home limit for a rank, e.g. `arka.homes.limit.10` |
| `arka.pwarps.limit.<n>` | none | Player-warp limit for a rank |

Regular players need **no** permissions.

---

## Rank limits (LuckPerms)

Home and player-warp limits can follow LuckPerms groups. In `config.yml`:

```yaml
homes:
  default-limit: 3
  ranks:
    member: 3
    vip: 6
    admin: 50
pwarps:
  default-limit: 1
  ranks:
    member: 1
    vip: 2
```

The name must match the LuckPerms **group** name exactly. A player gets the **highest** limit among their groups.
You can also use the permission form (`arka.homes.limit.10`) or override a single player with
`/arka admin homelimit set <player> <n>`.

---

## Placeholders (PlaceholderAPI)

Use them in TAB, DecentHolograms, scoreboards and chat. Identifier: `arka`.

| Placeholder | Value |
|---|---|
| `%arka_claimblocks%`, `%arka_claimblocks_total%`, `%arka_claimblocks_used%` | Claim blocks free / total / used |
| `%arka_claims%` | Number of claims |
| `%arka_homes%`, `%arka_homes_limit%` | Homes set / allowed |
| `%arka_playhours%`, `%arka_week_hours%` | Hours played / active hours in the last 7 days |
| `%arka_blocks_per_hour%` | Claim blocks earned per hour |
| `%arka_afk%` | The AFK tag while AFK, empty otherwise (put it before names in TAB) |
| `%arka_top_<category>_<n>_name%`, `_value%`, `_raw%` | Leaderboard rank `n` for `kills`, `deaths`, `money`, `playtime` |
| `%arka_top_position_<category>%`, `%arka_top_value_<category>%` | The viewer's own rank and value |
| `%arka_stats_total%` | Players who ever joined |
| `%arka_stats_weekly%` | Weekly active players (more than 1 hour in 7 days) |
| `%arka_stats_daily%`, `%arka_stats_monthly%` | Seen in the last 24 hours / 30 days |
| `%arka_stats_new_week%` | First joined in the last 7 days |
| `%arka_stats_online%`, `%arka_stats_peak_today%`, `%arka_stats_peak%` | Online now / peak today / record peak |
| `%arka_stats_returning%` | Came back after 7+ days away (last 7 days) |
| `%arka_stats_retention%` | % of new players still around a week later |
| `%arka_stats_busiest_hour%`, `%arka_stats_quietest_hour%` | e.g. `20:00-21:00` |
| `%arka_stats_week_hours%`, `%arka_stats_avg_week_hours%` | Total and average active hours of the week |

Example hologram line: `Weekly active players: %arka_stats_weekly%`

---

## Configuration guide

Everything lives in `plugins/ArkaSMPCore/config.yml`, with comments on every option. The sections you will touch most:

| Section | What you can change |
|---|---|
| `teleport` | Warm-up, cancel on move/damage, combat lock |
| `homes`, `spawn`, `tpa`, `rtp` | Limits, cooldowns, RTP radius and centre, disabled worlds |
| `warps`, `pwarps` | Warp safety thresholds, cost of a player warp, per-rank limits |
| `claims` | Starting blocks, blocks per hour, minimum size, protections, buy price, transfer rules, border look |
| `leaderboards` | Refresh rate, formats, who is hidden |
| `afk` | Timeout, macro detection, kick time, AFK tag |
| `cleaner` | Interval, warnings, item whitelist, mob removal |
| `stats` | Weekly-active threshold, time zone for the busiest hour |
| `menus`, `messages` | Every icon, title and chat message (MiniMessage: `<green>`, `<bold>`, ...) |

Numbers, true/false values and text can also be changed from inside the game:
`/arka admin set <path> <value>`, for example `/arka admin set claims.blocks-per-hour 150`.

---

## Replacing other plugins

ArkaSMPCore can replace the following. Remove the old plugin (or disable its commands) so both do not handle the
same command:

| Instead of | Use |
|---|---|
| Essentials (homes, spawn, tpa) | `/home`, `/spawn`, `/tpa` here |
| GriefPrevention / similar claim plugins | `/claim` here |
| RTP plugins, SetWarp / PlayerWarps plugins | `/rtp`, `/warp`, `/pwarp` |
| AFK plugins, ClearLag | AFK system and cleaner here |
| Leaderboard/stat plugins | `%arka_top_...%` and `/arka stats` |

If you keep Essentials for something else, add the commands you moved to its `disabled-commands` list in
`config.yml` (for example `home`, `sethome`, `spawn`, `tpa`, `warp`, `afk`).

---

## Bedrock / Geyser notes

Menus are plain chest inventories with tap-only buttons, so they work for Bedrock players through
Geyser/Floodgate. AFK has a Bedrock fallback that also counts real walking, because key presses are not always
reported (`afk.bedrock-movement-fallback`).

---

## Status and known limits

We built and tested this for our own server, and we are releasing it as it is. It is **young software**:

- Test on a **copy of your server** first, and keep backups (see [McBackup](https://github.com/DHANVIN-raj-velagapudi/McBackup)).
- Some limits we know about: `/home` does not run the warp-style safety check; the AFK macro detector cannot stop
  a macro that also sends commands; leaderboards re-sort every 10 seconds; statistics such as retention need about
  a week of data after installing before they are meaningful.
- Bugs are possible. Please report them (below).

---

## Building from source

Requires JDK 21+ and Maven.

```
mvn -q -B install
```

The jar is written to `target/ArkaSMPCore-1.0.0.jar`. `install` also puts it in your local Maven repository, which
[ArkaSMPEconomy](https://github.com/DHANVIN-raj-velagapudi/ArkaSMPEconomy) needs to compile.

---

## Contributing and support

- **Found a bug or want a feature?** Open an [issue](../../issues) with your server version, what you did and
  what happened (include the console error if there is one).
- **Want to improve it?** Fork it and send a pull request. Keep changes small and focused.
- Please keep the license: if you build on this and run it publicly, your changes must stay open too.

---

## License

Copyright (C) 2026 Velagapudi Dhanvin Raj

This program is free software: you can redistribute it and/or modify it under the terms of the
**GNU Affero General Public License, version 3** as published by the Free Software Foundation. It is distributed in
the hope that it will be useful, but **without any warranty**. See [LICENSE](LICENSE).

If you run a modified version on a server that other people connect to, the AGPL requires you to offer those
players the source code of your modified version.
