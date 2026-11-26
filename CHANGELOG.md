# Changelog

All notable changes to ArkaSMPCore. Versions follow `MAJOR.MINOR.PATCH`: a fix raises the last number, a new feature
the middle one, a breaking change the first. Every release raises the version.

## 1.10.0

### Added
- **Play time as text.** Under 1 hour only minutes (`45M`), from 1 hour hours and minutes (`3HR 20M`), from 24 hours days
  and hours with no minutes (`2 day 5HRS`). The wording is in `config.yml` under `playtime`
  (`format-minutes`, `format-hours`, `format-days`, using `{d}` `{h}` `{m}`).
- New placeholder `%arka_playtime%` (your active play time in that format).
- New service `com.arka.smp.core.api.PlayTimeFormat` (`format(ticks)`): other Arka plugins can load it from Bukkit's
  ServicesManager and show play time the same way, next to `PlayTimeProvider.activeTicks(id)`.

### Changed
- The **playtime leaderboard** now uses the same format. The old `leaderboards.playtime-format` and
  `leaderboards.playtime-days-format` options are no longer used (they can stay in your config, nothing reads them).

## 1.9.0

### Changed
- **The request window no longer pops up by itself.** A teleport request is now only a chat message with **[Accept]
  [Deny] [Block]**. Clicking [Accept], typing `/tpaccept` or `/tpa accept [player]` opens the request window (world,
  exact coordinates, distance, safety warning), and the player presses Accept or Deny there. With several requests and
  no name given, a list of senders opens first. `tpa.confirm-in-menu: false` makes `/tpaccept` accept at once like
  before. The old option `tpa.open-menu-on-request` is gone.

### Added
- **Turn requests off for good.** `/tpa toggle` (also `/tpa off`, `/tpa on`, or the button in the `/tpa` menu) stops
  anybody sending you a request. It is saved in `tpa-prefs.yml`, survives restarts, and is off by default (requests are
  allowed until you switch them off).
- **Block single players.** `/tpa block <player>`, `/tpa unblock <player>`, `/tpa blocklist` (each name in the list has a
  clickable [unblock]), plus "Block a player" and "Blocked players" buttons in the `/tpa` menu. Blocking also removes a
  request they already sent. The sender is only told "{player} is not accepting teleport requests", so a block is not
  revealed.
- New messages (`tpa-not-accepting`, `tpa-toggle-on/off`, `tpa-blocked`, `tpa-unblocked`, `tpa-blocklist-*` ...) and menu
  items (`menus.tpa.toggle-on`, `toggle-off`, `block`, `blocked`). If your config still has the old `tpa-received` and
  `tpa-here-received` texts they keep working ([Accept] now opens the window); delete them to get the new texts with
  the [Block] button.

## 1.8.1

### Fixed
- **The TPA request window did not open for players in creative mode.** With no window open, a creative player's own
  inventory view reports the type `CREATIVE` (survival players report `CRAFTING`), and the check "does the player
  already have another window open?" treated that as busy. Both are now treated as "nothing open". This was found
  from the new error log (`tpa.window`, `window=CREATIVE`).

## 1.8.0

### Added
- **Error log.** When something goes wrong the plugin now writes a readable entry to `plugins/ArkaSMPCore/logs/errors.log` and a
  short line to the console, so a problem can be sent for diagnosis as one small file. Each entry has a number
  (like `E-1A2`), the time, the plugin and server version, which feature failed, who was involved (player, command,
  arguments), and the full stack trace.
- **Automatic capture.** Every warning or error the plugin logs, and every error the server prints that comes from this
  plugin's code (events, scheduled tasks), is copied into the file too, so errors nobody expected are not lost.
- **Commands never spill errors on players.** A crash inside a command is logged and the player sees
  "Something went wrong... tell an admin this number: E-1A2" (`messages.error-internal`).
- `/arka admin errors` shows the last problems in game, `/arka admin errors test` writes a test entry to check the file works. Teleports that the server refuses are logged, and so is a TPA window that does not open (and why).
- The same problem is written at most 5 times a minute (the rest is counted), the file is rotated at 2 MB (3 old files
  are kept), writing happens on its own thread and cannot slow the game down.

### Fixed
- The TPA request window opens one tick after the request is sent and never throws; if it does not open, the error log
  says why (another window open, another plugin cancelled it, or the error).

## 1.7.0

### Added
- **Safe landing check for /tpa and /tpahere.** When a request is accepted, the exact spot the teleport would land on
  is checked with the same safety check warps and `/rtp` use (lava, fire, cactus, magma, void, a block at feet or head,
  no floor, a tiny platform over a long drop, a sealed cell). If it is unsafe, nobody is moved, both players are told
  why, and the request stays open so the player standing there can move to safe ground and accept again before it
  expires. The request window shows a red "landing spot looks UNSAFE" warning before you click Accept.
- Config: `tpa.safety-check` (default true; false turns the check off), and the messages `tpa-unsafe-yours`,
  `tpa-unsafe-theirs` and `menus.tpa-request.unsafe-warning` / `unsafe-accept`.

### Changed
- The warp safety messages are reused for TPA, so they follow `warps.safety` settings.
- If your `menus.tpa-request.world.lore` was created by 1.6.0, add the line `"{unsafe-line}"` to it to show the warning
  on the world icon (the Accept button shows it anyway).

## 1.6.0

### Added
- **Teleport request window.** When someone sends you `/tpa` or `/tpahere`, a window opens ("Incoming TPA Request" /
  "Incoming TPAHERE Request"): a red **Deny** pane on the left, a green **Accept** pane on the right, the sender's
  world and **exact X Y Z coordinates** with the distance from you, the sender's head with what they want and a
  "Beware of TP traps" warning, and a feather showing the seconds left. The chat message with clickable
  Accept/Deny is still sent. If you already have another window open, the request window is not forced on you.
- The same window opens from `/tpa` -> *Incoming requests* when you pick a sender.
- Config: `tpa.open-menu-on-request` (true/false) and the whole `menus.tpa-request` section for every text and icon.

## 1.5.0

### Added
- **Starter kit.** A player joining for the very first time gets a full stone tool set (sword, pickaxe, axe, shovel,
  hoe), full leather armour (worn automatically), 32 cooked steak, a golden shovel (the claim tool) and a crafting
  table, with a welcome message. The items are a list in `config.yml` (`starter-kit.items`, entries like
  `COOKED_BEEF:32`), and `starter-kit.enabled: false` turns it off. Players who have already joined before are not
  given anything.
- `/arka admin kit <player>` gives the kit again to an online player.

## 1.4.0

### Changed
- **Trust is per claim.** `/claim trust <player>` now trusts the player in the ONE claim you are standing in, and
  nowhere else. The `all` option is gone, so a friend can no longer be given every claim by mistake. Trust you
  already gave keeps working exactly as before (it was already stored per claim).
- `/claim trustlist` now lists every claim where you trusted somebody (claim number, corners, who), with a clickable
  **[untrust]** next to each name. Before, it only showed the claim you stood in.
- `/claim untrust <player>` removes the trust from the claim you stand in. `/claim untrust <player> <claim number>`
  removes it from that claim from anywhere (numbers are in `/claim list` and `/claim trustlist`), and
  `/claim untrust <player> all` removes it from every claim of yours.
- `/claim list` shows each claim's number.
- New messages: `trustlist-title`, `trustlist-empty`, `trustlist-hint`, `trust-no-such-claim`; `trust-added` and
  `trust-removed` can show `{claim}`; `trust-added-all` is no longer used.

## 1.3.0

### Added
- **Play-time service** (`PlayTimeProvider`): other Arka plugins (ArkaSMPStyle: earned titles) can read a player's
  active play time without touching Core's data. Nothing changes for players or admins.
- **Combat service** (`CombatStatus`): other Arka plugins can ask Core's PvP combat lock whether a player is in combat
  (ArkaSMPStyle refuses rank commands such as /anvil and /ec during a fight).
- **Claim block service** (`ClaimBlockBonus`): lets ArkaSMPStyle give the monthly bonus claim blocks of the paid ranks.

## 1.2.0

### Added
- **Safe upgrades and downgrades.** `data-version.properties` records the plugin version and data format. On every
  version change a snapshot of the data files is saved to `data-backups/` first (last 5 kept). An older jar that
  finds data from a newer format refuses to start instead of damaging it.

### Fixed
- An unreadable data file is kept as `<name>.broken-<time>` and never overwritten by a later failure.
- A config downgrade is reported as such in the console; all your settings are kept.

## 1.1.0

### Added
- **Automatic config updates.** `config.yml` stores the plugin version in `config-version`. On a new version the
  plugin backs up the old file (`config-backups/`), adds the new options with their comments, keeps all your
  values, and raises the version. Do not edit `config-version` by hand.
- **Warps**: `/warp` (server warps), `/pwarp` (player warps) with per-rank limits, an optional rupee cost, and
  safety checks when a warp is made and before every teleport.
- **Claim blocks**: buy with rupees (`/claim buy`) and send to other players (`/claim send`), with anti-alt rules.
- Confirmation before unclaiming or deleting claims.
- **Server statistics**: weekly active players, total joined, daily/monthly active, new players, returning players,
  retention, peak online per day, busiest and quietest hour. Admin dashboard `/arka stats` and `%arka_stats_*%`
  placeholders.
- `MoneyCharger` service so ArkaSMPEconomy can charge rupees.

### Fixed
- Claims now also protect against liquids flowing in, sticky-piston pull-outs, the second half of a bed and
  placed hanging entities.
- Thread-safety fixes for claim and AFK data read from async tasks.
- Warp and RTP safety checks no longer load chunks on the main thread.

## 1.0.0
- First release: homes, spawn, TPA, RTP, land claims, strict AFK, leaderboards, lag cleaner.
