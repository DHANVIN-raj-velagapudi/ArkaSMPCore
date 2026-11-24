# ArkaSMPCore

An all-in-one survival-multiplayer core plugin for **Paper 1.21+** (Java 21+). It replaces a stack of small
plugins (homes, RTP, TPA, spawn, claims, warps, AFK, leaderboards, server statistics) with one lightweight plugin
whose menus are **tap-only**, so they also work well for Bedrock players joining through Geyser/Floodgate.

## Features

- **Homes**, **spawn**, **TPA** requests and **RTP** (random teleport) with cooldowns, warm-ups and rank-based limits
- **Warps** (`/warp`) made by admins and **player warps** (`/pwarp`) with per-rank limits, optional rupee cost and
  safety checks before every teleport (lava, sealed obsidian rooms, void, tiny high platforms, no support block)
- **Land claims**: chunk-indexed rectangles, per-claim trust, claim blocks that can be bought with rupees or sent to
  other players, confirmation before unclaiming or deleting
- **Strict AFK** detection, a **cleaner** for ground items and stale entities, **leaderboards**
- **Server statistics** dashboard for admins (`/arka stats`): total players, weekly active, returning players,
  retention, peak online per day, busiest and quietest hour
- PlaceholderAPI expansion (`%arka_...%`), optional LuckPerms-style rank limits through permission nodes
- Small public API (`MoneyProvider`, `MoneyCharger`) so an economy plugin such as ArkaSMPEconomy can plug in

Command lists: [PLAYER_COMMANDS.txt](PLAYER_COMMANDS.txt) and [ADMIN_COMMANDS.txt](ADMIN_COMMANDS.txt).

## Requirements

- Paper (or a Paper fork) 1.21.4 or newer, Java 21+
- Optional: PlaceholderAPI, LuckPerms, [ArkaSMPEconomy](https://github.com/DHANVIN-raj-velagapudi/ArkaSMPEconomy)

## Build

```
mvn -q -B install
```

The jar is written to `target/ArkaSMPCore-1.0.0.jar`. `install` also puts it in your local Maven repository, which
ArkaSMPEconomy needs to compile.

## Install

1. Put the jar in `plugins/` and start the server once.
2. Edit `plugins/ArkaSMPCore/config.yml`, then restart the server.
3. When a new version adds config keys, the old `config.yml` does not get them automatically: copy the new sections
   from the jar's default config, or delete the file once so it is regenerated.

## License

Copyright (C) 2026 Velagapudi Dhanvin Raj

This program is free software: you can redistribute it and/or modify it under the terms of the
**GNU Affero General Public License, version 3** as published by the Free Software Foundation. It is distributed in
the hope that it will be useful, but **without any warranty**. See [LICENSE](LICENSE).

If you run a modified version on a server that other people connect to, the AGPL requires you to offer those
players the source code of your modified version.
