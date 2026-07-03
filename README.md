# Multiplayer Hardcore Lite

A Paper plugin for shared-life multiplayer hardcore: **every death drains the
whole team's max health and makes mobs stronger. When the health pool runs
dry, the world is wiped and regenerated in-process** — no server restart, no
world-folder fiddling by hand. After a reset, each player picks a few items
from their old inventory to carry into the new world.

Written against the Paper 26.2 API (Java 25).

## How a run plays out

1. Everyone starts with the normal 20 max HP (10 hearts).
2. When **any** player dies:
   - **Everyone** — online, offline, and players yet to join — loses 2 max HP
     (1 heart). This only lowers the cap; nobody takes damage from it.
   - Hostile mobs get stronger: +15% max health and +10% attack damage per
     accumulated death (applied to newly spawning and already-loaded mobs).
   - The dying player respawns as usual and the run continues.
3. When the pool is exhausted (10th death on default settings), the gameplay
   world set (`hardcore`, `hardcore_nether`, `hardcore_the_end`) is deleted
   and regenerated with a fresh seed. Max health and mob strength reset.
4. After the reset, every player chooses **3 items or stacks** from the
   inventory they last played with (main inventory, armor, and offhand) to
   bring into the new world.

### Item carry-over rules

- Players online at the reset choose immediately via a chest menu; click an
  item to keep it, or the barrier to forfeit remaining picks.
- Players **offline** at the reset get the menu the next time they log in.
- If several resets happen while someone is offline, they pick from the last
  inventory **they actually played on** — snapshots are frozen until the owed
  picks are spent, so nobody gets skipped past.
- Closing the menu early is safe: picks stay pending and `/mhkeep` (or the
  next login) reopens it.

## Commands

| Command | Permission | Description |
|---------|------------|-------------|
| `/mhreset [seed]` | `mhplus.reset` (op) | Force a world reset now. Accepts a numeric seed or a word (hashed like vanilla). |
| `/mhstatus` | everyone | Show the current attempt number, deaths, remaining team max HP, and mob strength bonuses. |
| `/mhkeep` | everyone | Reopen your pending "choose items to keep" menu after a reset. |

## config.yml

| Key | Default | Meaning |
|-----|---------|---------|
| `gameplay-world` | `hardcore` | Base name of the world set. **Must differ from `level-name`** in server.properties. |
| `enable-nether` / `enable-end` | `true` | Generate + reset those dimensions alongside the overworld. |
| `countdown-seconds` | `5` | Delay between the pool-emptying death and the wipe. |
| `world-border-radius` | `0` | Smaller border = faster resets (less terrain to delete); 0 disables. |
| `clear-inventory-on-reset` | `true` | Wipe inventories/XP on a new world (keep-picks are given back afterwards). |
| `hp-loss-per-death` | `2` | Max HP everyone loses per death (2 HP = 1 heart). Default means the 10th death resets the world. |
| `mob-health-bonus-per-death` | `0.15` | +15% hostile mob max health per accumulated death. |
| `mob-damage-bonus-per-death` | `0.10` | +10% hostile mob attack damage per accumulated death. |
| `keep-items-count` | `3` | Items/stacks each player may carry through a reset. 0 disables carry-over. |

`attempts`, `deaths`, and `seed` at the bottom of the file are managed by the
plugin — don't edit them. Inventory snapshots live in
`plugins/MultiplayerHardcoreLite/snapshots.yml`.

Upgrading from the old **MultiplayerHardcorePlus** jar? Just swap the jar: on
first boot the plugin adopts the old `plugins/MultiplayerHardcorePlus/` data
folder, keeps reading the old per-player generation tags (so nobody gets
spuriously wiped), and strips mob buffs saved under the old name before
applying new ones.

## Install

1. Grab the jar from the [latest GitHub release](../../releases/latest) (built
   automatically on every push to `main`) and drop it into your server's
   `plugins/` folder.
2. **Do NOT set `hardcore=true`** in server.properties — the plugin simulates
   hardcore via the shared health pool and reset; real hardcore mode would
   lock dead players into spectator and fight the plugin.
3. Make sure `gameplay-world` in `config.yml` is **different** from your
   `level-name` (default `hardcore` vs `world` is fine).
4. Start the server. On first boot it generates the `hardcore` world set and
   routes players into it.

## Why the reset works

- Bukkit refuses to unload the server's **primary** world, so this plugin
  never touches it — it's used only as a "limbo" holding pen during resets.
  Gameplay happens in a separate world set that **can** be unloaded and
  deleted.
- Worlds are deleted via `World.getWorldFolder()`, so Paper's on-disk
  dimension layout is handled by the API instead of assumptions about folder
  names.

## Build from source

Requires JDK 25 (the Gradle wrapper is included):

```
./gradlew build
```

The jar lands in `build/libs/MultiplayerHardcoreLite-<version>.jar`. Local
builds are versioned `2.0.0-dev`; CI stamps real versions from git tags.

## CI / CD

- **`.github/workflows/release.yml`** — on every push to `main`: builds the
  jar with JDK 25, bumps the patch version from the latest `v*` tag (first
  release is `v2.0.0`), and publishes a GitHub release with the jar attached.
- **`.github/workflows/deploy.yml`** — runs after a successful release (and on
  manual dispatch). It reaches the on-prem [discopanel](https://docs.discopanel.app/api/)
  host through Cloudflare WARP and uses the discopanel Connect API to find
  every server that already has MultiplayerHardcorePlus installed, upload the
  new jar, swap out the old one, and restart the servers that are running.
  First-time installs are done once by hand in the discopanel UI; the
  workflow keeps them current from then on. Secrets: `DISCOPANEL_HOST`,
  `DISCOPANEL_API_TOKEN` (create in the discopanel UI), `CLOUDFLARE_ORG`,
  `CLOUDFLARE_CLIENT_ID`, `CLOUDFLARE_CLIENT_SECRET`.

## Known caveats (please read)

- **World deletion runs on the main thread**, so there's a brief freeze
  proportional to how much terrain was explored. Keep `world-border-radius`
  modest (a few thousand) so resets stay quick.
- **End travel is the most fragile part.** Custom worlds aren't auto-linked,
  so the plugin wires portals itself and builds an obsidian arrival platform
  in the End. Test Nether and End travel before relying on them; if your
  group only cares about the overworld loop, set `enable-end: false`.
- **Not Folia-compatible** (uses the standard Bukkit scheduler). Regular
  Paper is fine.
- If a reset logs "Could not unload <world>", something is holding chunks in
  that world (another plugin, a chunk loader). The wipe is skipped that round
  to avoid corruption.
- Mob strength buffs are attribute modifiers keyed to this plugin, so they
  are idempotent and disappear with the world on reset. Mobs already spawned
  when a death happens are re-buffed in place; mobs in unloaded chunks catch
  up the next time the death counter changes.
