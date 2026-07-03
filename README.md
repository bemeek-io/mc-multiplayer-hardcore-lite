# Multiplayer Hardcore Plus

A Paper plugin: **if one player dies, everyone dies and the world regenerates**
in-process — no server restart, no world-folder fiddling by hand.

Written against the Paper 26.x API (Mojang-mapped, post-26.1 world storage).

## Why this works where the old 1.19 plugin didn't

- Bukkit refuses to unload the server's **primary** world. So this plugin never
  touches it — it's used only as a "limbo" holding pen. Gameplay happens in a
  separate set: `hardcore`, `hardcore_nether`, `hardcore_the_end`, which **can**
  be unloaded and deleted.
- It never hardcodes disk paths. Each world is deleted via
  `World.getWorldFolder()`, so Paper 26.1's new on-disk dimension layout is
  handled by the API instead of by assumptions about folder names.

## Prebuilt jar

`MultiplayerHardcorePlus-1.0.0.jar` is included and ready to drop in. It was
compiled (Java 21 bytecode, which runs on the Java 25 that Paper 26.x uses)
against a hand-written set of API stubs, since the build couldn't reach Paper's
Maven repo. Every Bukkit call was verified to match the real 26.x API
signatures, and only long-stable, non-deprecated methods are used — but it was
not run on a live server, so test it on yours before relying on it. If anything
misbehaves, the source is here to rebuild against the real API.

## Rebuild from source (optional — JDK 25)

1. Install **JDK 25** and Gradle 8.10+ (or just open the folder in IntelliJ with
   the Minecraft Development plugin and let it import).
2. In `build.gradle.kts`, set the paper-api version to match your server exactly.
   It's currently `26.2.1.build.+`. If Gradle can't resolve it, check
   https://repo.papermc.io and adjust (e.g. `26.2.build.+`).
3. Build:
   ```
   gradle build
   ```
   The jar lands in `build/libs/MultiplayerHardcorePlus-1.0.0.jar`.

## Install

1. Drop the jar into your server's `plugins/` folder.
2. **Do NOT set `hardcore=true`** in server.properties — the plugin simulates
   hardcore via the reset, and real hardcore mode would lock dead players into
   spectator and fight the reset.
3. Make sure `gameplay-world` in `config.yml` is **different** from your
   `level-name` (default `hardcore` vs `world` is fine).
4. Start the server. On first boot it generates the `hardcore` world set and
   routes players into it.

## config.yml

| Key | Meaning |
|-----|---------|
| `gameplay-world` | Base name of the world set (must differ from level-name) |
| `enable-nether` / `enable-end` | Generate + reset those dimensions |
| `countdown-seconds` | Delay between the death and the wipe |
| `world-border-radius` | Smaller border = faster resets (less to delete); 0 disables |
| `clear-inventory-on-reset` | Wipe inventories on a new world |

## Known caveats (please read)

- **The included jar was compiled against reconstructed API stubs**, not the
  real Paper jar (my sandbox can't reach Paper's Maven repo). Signatures were
  verified, but test on your server. Rebuild from source for a fully verified
  artifact.
- **World deletion runs on the main thread**, so there's a brief freeze
  proportional to how much terrain was explored. Keep `world-border-radius`
  modest (a few thousand) so resets stay quick.
- **End travel is the most fragile part.** Custom worlds aren't auto-linked, so
  the plugin wires portals itself and builds an obsidian arrival platform in the
  End. Test Nether and End travel before relying on them; if your group only
  cares about the overworld loop, set `enable-end: false`.
- **Not Folia-compatible** (uses the standard Bukkit scheduler). Regular Paper
  is fine.
- If a reset logs "Could not unload <world>", something is holding chunks in
  that world (another plugin, a chunk loader). The wipe is skipped that round to
  avoid corruption.
