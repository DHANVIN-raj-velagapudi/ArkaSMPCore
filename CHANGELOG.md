# Changelog

All notable changes to ArkaSMPCore. Versions follow `MAJOR.MINOR.PATCH`: a fix raises the last number, a new feature
the middle one, a breaking change the first. Every release raises the version.

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
