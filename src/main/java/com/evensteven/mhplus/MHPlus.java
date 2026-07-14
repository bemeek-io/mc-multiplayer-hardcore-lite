package com.evensteven.mhplus;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Per-player hardcore: each death grants a timed "Death" stack that cuts that
 * player's max health. Stacks expire independently. When any player's max
 * health hits zero the gameplay world set is wiped and regenerated in-process.
 * After a reset, players with no active Death stacks choose a few items from
 * the inventory they last played with to carry into the new world.
 *
 * Commands:
 *   /mhreset [seed]  - force a reset now, optionally with a seed.
 *   /mhstatus        - show the state of the current run / your Death stacks.
 *   /mhkeep          - reopen a pending "choose items to keep" menu.
 */
public final class MHPlus extends JavaPlugin implements CommandExecutor {

    private World limbo;
    private World over;
    private World nether;
    private World end;

    private String baseName;
    private boolean enableNether;
    private boolean enableEnd;
    private int countdownSeconds;
    private double borderRadius;
    private boolean clearInventory;
    private boolean clearEnderChest;

    private double hpLossPerStack;
    private long deathDurationMs;
    private int keepItemCount;

    private long currentSeed;
    private Long pendingSeed; // if set, the next regeneration uses this exact seed
    private int attempts;
    private int deaths; // deaths in the current run (flavor / status only)
    private volatile boolean resetting = false;

    private NamespacedKey genKey;
    // Key written by the plugin back when it was named MultiplayerHardcorePlus;
    // still read (and cleaned up) so a rename doesn't wipe players.
    private NamespacedKey legacyGenKey;
    private SnapshotStore snapshots;
    private DeathStacks deathStacks;
    private KeepSelection selection;
    private final Random random = new Random();

    @Override
    public void onEnable() {
        migrateLegacyDataFolder();
        saveDefaultConfig();
        baseName = getConfig().getString("gameplay-world", "hardcore");
        enableNether = getConfig().getBoolean("enable-nether", true);
        enableEnd = getConfig().getBoolean("enable-end", true);
        countdownSeconds = Math.max(1, getConfig().getInt("countdown-seconds", 5));
        borderRadius = getConfig().getDouble("world-border-radius", 0.0);
        clearInventory = getConfig().getBoolean("clear-inventory-on-reset", true);
        clearEnderChest = getConfig().getBoolean("clear-ender-chest-on-reset", false);
        // Configured in hearts (1 heart = 2 HP); stored internally as HP.
        hpLossPerStack = getConfig().getDouble("hearts-per-death-stack", 2.0) * 2.0;
        deathDurationMs = TimeUnit.HOURS.toMillis(
                Math.max(1L, getConfig().getLong("death-duration-hours", 48L)));
        stripLegacyConfigKeys();
        keepItemCount = Math.max(0, getConfig().getInt("keep-items-count", 3));
        attempts = getConfig().getInt("attempts", 0);
        deaths = getConfig().getInt("deaths", 0);
        currentSeed = getConfig().contains("seed") ? getConfig().getLong("seed") : random.nextLong();

        genKey = new NamespacedKey(this, "seen_generation");
        legacyGenKey = NamespacedKey.fromString("multiplayerhardcoreplus:seen_generation");
        limbo = Bukkit.getWorlds().get(0);

        if (baseName.equalsIgnoreCase(limbo.getName())) {
            getLogger().severe("gameplay-world must NOT equal your server level-name ("
                    + limbo.getName() + "). Change it in config.yml. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        snapshots = new SnapshotStore(this);
        deathStacks = new DeathStacks(this, deathDurationMs);
        selection = new KeepSelection(this, snapshots);

        loadOrCreateWorlds(currentSeed);
        getServer().getPluginManager().registerEvents(new GameListener(this), this);
        getServer().getPluginManager().registerEvents(selection, this);
        if (getCommand("mhreset") != null) {
            getCommand("mhreset").setExecutor(this);
        }
        if (getCommand("mhstatus") != null) {
            getCommand("mhstatus").setExecutor(this);
        }
        if (getCommand("mhkeep") != null) {
            getCommand("mhkeep").setExecutor(this);
        }

        // Expire stacks and reapply max HP about once a minute.
        new BukkitRunnable() {
            @Override
            public void run() {
                if (resetting) {
                    return;
                }
                deathStacks.pruneExpired();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    applyMaxHealth(p);
                }
            }
        }.runTaskTimer(this, 20L * 60L, 20L * 60L);

        if (Bukkit.isHardcore()) {
            getLogger().warning("hardcore=true is set in server.properties. The plugin cancels the"
                    + " forced spectator-on-death so deaths can apply Death stacks, but"
                    + " consider setting hardcore=false to avoid fighting the server.");
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            handleArrival(p);
        }
        getLogger().info("Enabled. Current run: attempt #" + attempts + ", " + deaths
                + " death(s) this run.");
    }

    @Override
    public void onDisable() {
        getConfig().set("attempts", attempts);
        getConfig().set("deaths", deaths);
        getConfig().set("seed", currentSeed);
        saveConfig();
    }

    private void stripLegacyConfigKeys() {
        boolean dirty = false;
        for (String key : new String[] {
                "hp-loss-per-death",
                "hearts-lost-per-death",
                "mob-health-bonus-per-death",
                "mob-damage-bonus-per-death"
        }) {
            if (getConfig().contains(key)) {
                getConfig().set(key, null);
                dirty = true;
            }
        }
        if (dirty) {
            saveConfig();
        }
    }

    // ----------------------------------------------------- legacy migration

    /**
     * The plugin used to be named MultiplayerHardcorePlus, which put its data
     * (config.yml, snapshots.yml) in a different folder. On first boot under
     * the new name, adopt the old folder so no run state or pending item
     * picks are lost.
     */
    private void migrateLegacyDataFolder() {
        File current = getDataFolder();
        if (current.exists()) {
            return;
        }
        File legacy = new File(current.getParentFile(), "MultiplayerHardcorePlus");
        if (!legacy.isDirectory()) {
            return;
        }
        if (legacy.renameTo(current)) {
            getLogger().info("Migrated data folder from MultiplayerHardcorePlus.");
            return;
        }
        try (Stream<Path> walk = Files.walk(legacy.toPath())) {
            for (Path src : walk.toList()) {
                Path dst = current.toPath().resolve(legacy.toPath().relativize(src).toString());
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            getLogger().info("Copied data folder from MultiplayerHardcorePlus (old folder left in place).");
        } catch (IOException e) {
            getLogger().severe("Failed to migrate MultiplayerHardcorePlus data folder: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase()) {
            case "mhreset":
                return cmdReset(sender, args);
            case "mhstatus":
                return cmdStatus(sender);
            case "mhkeep":
                return cmdKeep(sender);
            default:
                return false;
        }
    }

    private boolean cmdReset(CommandSender sender, String[] args) {
        if (resetting) {
            sender.sendMessage("§cA reset is already in progress.");
            return true;
        }
        Long seed = null;
        if (args.length > 0) {
            try {
                seed = Long.parseLong(args[0]);
            } catch (NumberFormatException ex) {
                seed = (long) args[0].hashCode(); // word seeds, like vanilla
            }
        }
        sender.sendMessage("§aForcing world reset"
                + (seed != null ? " (seed " + seed + ")" : " (random seed)") + "...");
        forceReset(seed);
        return true;
    }

    private boolean cmdStatus(CommandSender sender) {
        sender.sendMessage("§6Attempt #" + attempts + " §7| §c" + deaths + " death(s) this run");
        if (sender instanceof Player p) {
            int stacks = deathStacks.activeCount(p.getUniqueId());
            double max = maxHealthFor(p);
            sender.sendMessage("§7Your Death stacks: §c" + stacks
                    + " §7(−" + fmt(stacks * hpLossPerStack / 2.0) + " hearts)"
                    + " §7| max HP: §a" + fmt(Math.max(0.0, max))
                    + " §7(" + fmt(Math.max(0.0, max) / 2.0) + " hearts)");
            Long next = deathStacks.nextExpiry(p.getUniqueId());
            if (next != null) {
                long hoursLeft = Math.max(0L, (next - System.currentTimeMillis() + 3_599_999L) / 3_600_000L);
                sender.sendMessage("§7Next Death expires in about §e" + hoursLeft + " hour"
                        + (hoursLeft == 1 ? "" : "s") + "§7.");
            }
        }
        return true;
    }

    private boolean cmdKeep(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (resetting) {
            p.sendMessage("§cWait for the reset to finish.");
            return true;
        }
        if (!snapshots.hasPending(p.getUniqueId())) {
            p.sendMessage("§7You have no pending item picks.");
            return true;
        }
        selection.openIfPending(p);
        return true;
    }

    private static String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private void announce(String msg) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(msg);
        }
        getLogger().info(msg);
    }

    // ---------------------------------------------------------------- worlds

    private void loadOrCreateWorlds(long seed) {
        over = loadOrCreate(baseName, World.Environment.NORMAL, seed);
        configureWorld(over, true);
        if (enableNether) {
            nether = loadOrCreate(baseName + "_nether", World.Environment.NETHER, seed);
            configureWorld(nether, false);
        }
        if (enableEnd) {
            end = loadOrCreate(baseName + "_the_end", World.Environment.THE_END, seed);
            configureWorld(end, false);
        }
    }

    private World loadOrCreate(String name, World.Environment env, long seed) {
        World existing = Bukkit.getWorld(name);
        if (existing != null) {
            return existing;
        }
        return new WorldCreator(name).environment(env).seed(seed).createWorld();
    }

    private void configureWorld(World w, boolean primary) {
        if (w == null) {
            return;
        }
        w.setDifficulty(Difficulty.HARD);
        // Custom worlds inherit hardcore=true from server.properties; a
        // hardcore world forces dead players into spectator. Deaths must
        // respawn normally so Death stacks can apply.
        w.setHardcore(false);
        w.setGameRule(GameRules.KEEP_INVENTORY, false);
        w.setGameRule(GameRules.IMMEDIATE_RESPAWN, true);
        if (primary) {
            WorldBorder border = w.getWorldBorder();
            border.setCenter(0, 0);
            if (borderRadius > 0) {
                border.setSize(borderRadius * 2);
            } else {
                border.setSize(60000000.0); // vanilla maximum = effectively no border
            }
        }
    }

    // ------------------------------------------------------------ health / Death

    public double baseMaxHealth() {
        return Attribute.MAX_HEALTH.getDefaultValue(); // 20.0
    }

    public double maxHealthFor(Player p) {
        return baseMaxHealth() - deathStacks.activeCount(p.getUniqueId()) * hpLossPerStack;
    }

    /** Sets the player's max-health attribute from their active Death stacks. */
    public void applyMaxHealth(Player p) {
        AttributeInstance inst = p.getAttribute(Attribute.MAX_HEALTH);
        if (inst == null) {
            return;
        }
        double max = Math.max(1.0, maxHealthFor(p));
        inst.setBaseValue(max);
        if (p.getHealth() > max) {
            p.setHealth(max);
        }
        syncDeathEffect(p);
    }

    /**
     * Shows Death stacks as a vanilla Poison HUD buff (icon + roman level +
     * countdown to the next stack expiry). Damage from that poison is cancelled
     * in GameListener so it stays cosmetic.
     */
    public void syncDeathEffect(Player p) {
        int stacks = deathStacks.activeCount(p.getUniqueId());
        if (stacks <= 0) {
            p.removePotionEffect(PotionEffectType.POISON);
            return;
        }
        Long next = deathStacks.nextExpiry(p.getUniqueId());
        long remainingMs = next == null ? deathDurationMs : Math.max(50L, next - System.currentTimeMillis());
        int ticks = (int) Math.min(Integer.MAX_VALUE, remainingMs / 50L);
        // ambient=false, particles=true, icon=true — shows in the top-right buff bar
        p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, ticks, stacks - 1, false, true, true), true);
    }

    /**
     * Called for every player death in a managed world (inventory already
     * destroyed by the listener). Adds a Death stack; if that player's max
     * HP hits zero the world resets.
     */
    public void recordDeath(Player dead) {
        deaths++;
        getConfig().set("deaths", deaths);
        saveConfig();

        deathStacks.add(dead.getUniqueId());
        double newMax = maxHealthFor(dead);
        if (newMax <= 0.0) {
            // Inventory was deleted on death; do not capture or grant picks to
            // the player who just gained (another) Death stack.
            triggerReset(dead.getName() + " has died, and their last hearts are spent.");
            return;
        }

        applyMaxHealth(dead);
        int stacks = deathStacks.activeCount(dead.getUniqueId());
        announce("§4☠ §c" + dead.getName() + " has died. §7They gain §cDeath §7(−"
                + fmt(hpLossPerStack / 2.0) + " hearts for "
                + TimeUnit.MILLISECONDS.toHours(deathDurationMs) + "h). §c"
                + stacks + " §7stack" + (stacks == 1 ? "" : "s")
                + " §7(§c" + fmt(newMax / 2.0) + "§7 hearts left). Inventory destroyed.");
    }

    // ------------------------------------------------------------ reset flow

    public void triggerReset(String causeMsg) {
        beginReset(causeMsg, null);
    }

    public boolean forceReset(Long seed) {
        if (resetting) {
            return false;
        }
        beginReset("Manual reset triggered.", seed);
        return true;
    }

    private void beginReset(String causeMsg, Long seed) {
        if (resetting) {
            return;
        }
        resetting = true;
        pendingSeed = seed;
        announce("§4☠ §c" + causeMsg + " §7The world will be reborn.");

        new BukkitRunnable() {
            int remaining = countdownSeconds;

            @Override
            public void run() {
                if (remaining <= 0) {
                    cancel();
                    holdThenRegenerate();
                    return;
                }
                announce("§e§lWorld resets in §c§l" + remaining + "§e§l...");
                remaining--;
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void holdThenRegenerate() {
        // Freeze what clean players are carrying BEFORE they leave the world.
        // Skip anyone with active Death — they get no carryover, and the
        // terminal death left an empty inventory we must not overwrite a
        // useful older snapshot with.
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (deathStacks.hasActive(p.getUniqueId())) {
                continue;
            }
            if (!snapshots.hasPending(p.getUniqueId())) {
                snapshots.captureIfNotGeneration(p, attempts);
            }
        }

        Location hold = limbo.getSpawnLocation();
        limbo.getChunkAt(hold).load();
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.setGameMode(GameMode.SPECTATOR);
            p.teleport(hold);
        }
        Bukkit.getScheduler().runTaskLater(this, this::regenerate, 2L);
    }

    private void regenerate() {
        unloadAndDelete(end);
        unloadAndDelete(nether);
        unloadAndDelete(over);

        currentSeed = (pendingSeed != null) ? pendingSeed : random.nextLong();
        pendingSeed = null;
        loadOrCreateWorlds(currentSeed);

        attempts++; // advance the run; offline players now lag behind this number
        deaths = 0;
        getConfig().set("attempts", attempts);
        getConfig().set("deaths", deaths);
        getConfig().set("seed", currentSeed);
        saveConfig();

        // Carryover only for players who had no Death stacks at wipe time.
        snapshots.offerPicksToAll(keepItemCount, id -> !deathStacks.hasActive(id));
        deathStacks.clearAll();

        over.getChunkAt(over.getSpawnLocation()).load();
        for (Player p : Bukkit.getOnlinePlayers()) {
            wipePlayer(p);
        }
        announce("§aA fresh world has been generated. Attempt #" + attempts
                + " (seed " + currentSeed + "). Good luck.");
        resetting = false;

        for (Player p : Bukkit.getOnlinePlayers()) {
            selection.openIfPendingLater(p, 40L);
        }
    }

    private void unloadAndDelete(World w) {
        if (w == null) {
            return;
        }
        File folder = w.getWorldFolder();
        if (!Bukkit.unloadWorld(w, false)) {
            getLogger().warning("Could not unload " + w.getName() + "; world not wiped this round.");
            return;
        }
        deleteRecursively(folder);
    }

    private void deleteRecursively(File folder) {
        if (folder == null || !folder.exists()) {
            return;
        }
        try (Stream<Path> walk = Files.walk(folder.toPath())) {
            walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (IOException e) {
            getLogger().severe("Failed to delete world folder " + folder + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------ player handling

    public void wipePlayer(Player p) {
        p.teleport(over.getSpawnLocation());
        p.setGameMode(GameMode.SURVIVAL);
        if (clearInventory) {
            p.getInventory().clear();
            p.getInventory().setArmorContents(null);
            p.setExp(0f);
            p.setLevel(0);
            p.setTotalExperience(0);
        }
        // Ender chest contents live in per-player data under the primary
        // world, which resets never touch — left alone, they carry over.
        if (clearEnderChest) {
            p.getEnderChest().clear();
        }
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        for (PotionEffect eff : p.getActivePotionEffects()) {
            p.removePotionEffect(eff.getType());
        }
        applyMaxHealth(p);
        p.setHealth(Math.max(1.0, maxHealthFor(p)));
        p.getPersistentDataContainer().set(genKey, PersistentDataType.INTEGER, attempts);
    }

    /**
     * After a respawn: a hardcore-flagged world may have shoved the player
     * into spectator, and the fresh player entity may have lost the reduced
     * max-health attribute. Fix both.
     */
    public void handleRespawned(Player p) {
        if (resetting) {
            return;
        }
        applyMaxHealth(p);
        if (isManaged(p.getWorld()) && p.getGameMode() == GameMode.SPECTATOR) {
            p.setGameMode(GameMode.SURVIVAL);
        }
    }

    /**
     * Safety net for the cancelled hardcore-death spectator switch: a tick
     * later, make sure the player really is in survival with their personal
     * max health, whatever the server did after our event handlers ran.
     */
    public void ensureSurvivalNextTick(Player p) {
        Bukkit.getScheduler().runTask(this, () -> {
            if (!p.isOnline() || resetting) {
                return;
            }
            applyMaxHealth(p);
            if (isManaged(p.getWorld()) && p.getGameMode() == GameMode.SPECTATOR) {
                p.setGameMode(GameMode.SURVIVAL);
            }
        });
    }

    public void handleArrival(Player p) {
        if (resetting) {
            p.setGameMode(GameMode.SPECTATOR);
            p.teleport(limbo.getSpawnLocation());
            return;
        }
        applyMaxHealth(p);
        Integer seen = p.getPersistentDataContainer().get(genKey, PersistentDataType.INTEGER);
        if (seen == null && legacyGenKey != null) {
            seen = p.getPersistentDataContainer().get(legacyGenKey, PersistentDataType.INTEGER);
            if (seen != null) {
                p.getPersistentDataContainer().set(genKey, PersistentDataType.INTEGER, seen);
                p.getPersistentDataContainer().remove(legacyGenKey);
            }
        }
        if (seen == null || seen < attempts) {
            wipePlayer(p);
        } else if (!isManaged(p.getWorld())) {
            p.teleport(over.getSpawnLocation());
        }
        selection.openIfPendingLater(p, 40L);
    }

    // -------------------------------------------------------------- helpers

    public boolean isResetting() { return resetting; }
    public World getLimbo() { return limbo; }
    public World getOver() { return over; }
    public World getNether() { return nether; }
    public World getEnd() { return end; }
    public int getAttempts() { return attempts; }
    public SnapshotStore getSnapshots() { return snapshots; }
    public DeathStacks getDeathStacks() { return deathStacks; }
    public KeepSelection getSelection() { return selection; }

    public boolean isManaged(World w) {
        return w != null && (w.equals(over) || w.equals(nether) || w.equals(end));
    }
}
