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
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Shared-life hardcore: every player death drains max health from EVERYONE
 * (present and future) and makes hostile mobs stronger. When the team's max
 * health is exhausted, the gameplay world set is wiped and regenerated
 * in-process (no server restart). After a reset each player chooses a few
 * items from the inventory they last played with to carry into the new world.
 *
 * Commands:
 *   /mhreset [seed]  - force a reset now, optionally with a seed.
 *   /mhstatus        - show the state of the current run.
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

    private double hpLossPerDeath;
    private double mobHealthBonusPerDeath;
    private double mobDamageBonusPerDeath;
    private int keepItemCount;

    private long currentSeed;
    private Long pendingSeed; // if set, the next regeneration uses this exact seed
    private int attempts;
    private int deaths; // deaths in the current run; drives max HP and mob strength
    private volatile boolean resetting = false;

    private NamespacedKey genKey;
    private NamespacedKey mobHealthKey;
    private NamespacedKey mobDamageKey;
    // Keys written by the plugin back when it was named MultiplayerHardcorePlus;
    // still read (and cleaned up) so a rename doesn't wipe players or stack mob buffs.
    private NamespacedKey legacyGenKey;
    private NamespacedKey legacyMobHealthKey;
    private NamespacedKey legacyMobDamageKey;
    private SnapshotStore snapshots;
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
        // Configured in hearts (1 heart = 2 HP); stored internally as HP.
        hpLossPerDeath = getConfig().getDouble("hearts-lost-per-death", 2.0) * 2.0;
        if (getConfig().contains("hp-loss-per-death")) {
            getConfig().set("hp-loss-per-death", null); // legacy HP-based key
            saveConfig();
        }
        mobHealthBonusPerDeath = getConfig().getDouble("mob-health-bonus-per-death", 0.15);
        mobDamageBonusPerDeath = getConfig().getDouble("mob-damage-bonus-per-death", 0.10);
        keepItemCount = Math.max(0, getConfig().getInt("keep-items-count", 3));
        attempts = getConfig().getInt("attempts", 0);
        deaths = getConfig().getInt("deaths", 0);
        currentSeed = getConfig().contains("seed") ? getConfig().getLong("seed") : random.nextLong();

        genKey = new NamespacedKey(this, "seen_generation");
        mobHealthKey = new NamespacedKey(this, "mob_health_bonus");
        mobDamageKey = new NamespacedKey(this, "mob_damage_bonus");
        legacyGenKey = NamespacedKey.fromString("multiplayerhardcoreplus:seen_generation");
        legacyMobHealthKey = NamespacedKey.fromString("multiplayerhardcoreplus:mob_health_bonus");
        legacyMobDamageKey = NamespacedKey.fromString("multiplayerhardcoreplus:mob_damage_bonus");
        limbo = Bukkit.getWorlds().get(0);

        if (baseName.equalsIgnoreCase(limbo.getName())) {
            getLogger().severe("gameplay-world must NOT equal your server level-name ("
                    + limbo.getName() + "). Change it in config.yml. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        snapshots = new SnapshotStore(this);
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

        if (Bukkit.isHardcore()) {
            getLogger().warning("hardcore=true is set in server.properties. The plugin cancels the"
                    + " forced spectator-on-death so the shared health pool works anyway, but"
                    + " consider setting hardcore=false to avoid fighting the server.");
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            handleArrival(p);
        }
        getLogger().info("Enabled. Current run: attempt #" + attempts + ", " + deaths
                + " death(s), team max HP " + currentMaxHealth() + ".");
    }

    @Override
    public void onDisable() {
        getConfig().set("attempts", attempts);
        getConfig().set("deaths", deaths);
        getConfig().set("seed", currentSeed);
        saveConfig();
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
        double max = currentMaxHealth();
        sender.sendMessage("§6Attempt #" + attempts + " §7| §c" + deaths
                + " death(s) §7| §aTeam max HP: " + fmt(max) + "/" + fmt(baseMaxHealth())
                + " (" + fmt(max / 2.0) + " hearts)");
        if (deaths > 0) {
            sender.sendMessage("§7Mobs: §c+" + Math.round(deaths * mobHealthBonusPerDeath * 100)
                    + "% health§7, §c+" + Math.round(deaths * mobDamageBonusPerDeath * 100)
                    + "% damage§7.");
        }
        if (hpLossPerDeath > 0) {
            int untilReset = (int) Math.ceil(max / hpLossPerDeath);
            sender.sendMessage("§7" + untilReset + " more death" + (untilReset == 1 ? "" : "s")
                    + " and the world resets.");
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
        // hardcore world forces dead players into spectator, which fights the
        // shared-health-pool design. Deaths must respawn normally.
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

    // ------------------------------------------------------------ health pool

    public double baseMaxHealth() {
        return Attribute.MAX_HEALTH.getDefaultValue(); // 20.0
    }

    public double currentMaxHealth() {
        return Math.max(0.0, baseMaxHealth() - deaths * hpLossPerDeath);
    }

    /** Sets the player's max-health attribute to the team's current pool. */
    public void applyMaxHealth(Player p) {
        AttributeInstance inst = p.getAttribute(Attribute.MAX_HEALTH);
        if (inst == null) {
            return;
        }
        double max = Math.max(1.0, currentMaxHealth());
        inst.setBaseValue(max);
        if (p.getHealth() > max) {
            p.setHealth(max);
        }
    }

    /**
     * Called for every player death in a managed world. Drains the shared
     * health pool; if it hits zero the world resets, otherwise everyone's max
     * HP drops and mobs get stronger.
     */
    public void recordDeath(Player dead) {
        deaths++;
        getConfig().set("deaths", deaths);
        saveConfig();

        double newMax = currentMaxHealth();
        if (newMax <= 0.0) {
            // The pool is spent. Freeze the dying player's inventory now (it is
            // about to drop) so they too get to pick items for the next world.
            snapshots.capture(dead, attempts);
            snapshots.setPending(dead.getUniqueId(), keepItemCount);
            triggerReset(dead.getName() + " has died, and the team's last hearts are spent.");
            return;
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            applyMaxHealth(p);
        }
        rescaleLoadedMobs();
        announce("§4☠ §c" + dead.getName() + " has died. §7Everyone loses §c"
                + fmt(hpLossPerDeath / 2.0) + " heart" + (hpLossPerDeath == 2.0 ? "" : "s")
                + "§7 of max health (§c" + fmt(newMax / 2.0)
                + "§7 hearts left) and mobs grow stronger.");
    }

    // ---------------------------------------------------------- mob scaling

    /** Buffs a hostile mob's health/damage to match the current death count. */
    public void strengthenMob(LivingEntity mob, boolean freshSpawn) {
        if (deaths <= 0) {
            return;
        }
        applyScalar(mob, Attribute.MAX_HEALTH, mobHealthKey, legacyMobHealthKey,
                deaths * mobHealthBonusPerDeath);
        applyScalar(mob, Attribute.ATTACK_DAMAGE, mobDamageKey, legacyMobDamageKey,
                deaths * mobDamageBonusPerDeath);
        if (freshSpawn) {
            AttributeInstance health = mob.getAttribute(Attribute.MAX_HEALTH);
            if (health != null) {
                mob.setHealth(health.getValue());
            }
        }
    }

    private void applyScalar(LivingEntity mob, Attribute attr, NamespacedKey key,
            NamespacedKey legacyKey, double amount) {
        AttributeInstance inst = mob.getAttribute(attr);
        if (inst == null) {
            return;
        }
        inst.removeModifier(key);
        if (legacyKey != null) {
            inst.removeModifier(legacyKey); // buff saved on the mob under the old plugin name
        }
        inst.addModifier(new AttributeModifier(key, amount, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
    }

    /** Re-buffs already-spawned hostiles after the death count changes. */
    private void rescaleLoadedMobs() {
        for (World w : new World[] {over, nether, end}) {
            if (w == null) {
                continue;
            }
            for (Enemy enemy : w.getEntitiesByClass(Enemy.class)) {
                strengthenMob(enemy, false);
            }
        }
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
        // Freeze what everyone is carrying BEFORE they leave the world. The
        // player whose death triggered the reset was captured at death time
        // (same generation), so captureIfNotGeneration skips them.
        for (Player p : Bukkit.getOnlinePlayers()) {
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
        deaths = 0; // the health pool refills for the new world
        getConfig().set("attempts", attempts);
        getConfig().set("deaths", deaths);
        getConfig().set("seed", currentSeed);
        saveConfig();

        // Everyone with a frozen snapshot (online or offline) gets their picks.
        snapshots.offerPicksToAll(keepItemCount);

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
            p.getEnderChest().clear();
            p.setExp(0f);
            p.setLevel(0);
            p.setTotalExperience(0);
        }
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        applyMaxHealth(p);
        p.setHealth(Math.max(1.0, currentMaxHealth()));
        for (PotionEffect eff : p.getActivePotionEffects()) {
            p.removePotionEffect(eff.getType());
        }
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
     * later, make sure the player really is in survival with the team's max
     * health, whatever the server did after our event handlers ran.
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
    public KeepSelection getSelection() { return selection; }

    public boolean isManaged(World w) {
        return w != null && (w.equals(over) || w.equals(nether) || w.equals(end));
    }
}
