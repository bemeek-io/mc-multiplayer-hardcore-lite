package com.evensteven.mhplus;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Random;
import java.util.stream.Stream;

/**
 * If one player dies, everyone dies and the gameplay world set is wiped and
 * regenerated in-process (no server restart).
 *
 * Command: /mhreset [seed]  - force a reset now, optionally with a seed.
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

    private long currentSeed;
    private Long pendingSeed; // if set, the next regeneration uses this exact seed
    private int attempts;
    private volatile boolean resetting = false;

    private NamespacedKey genKey;
    private final Random random = new Random();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        baseName = getConfig().getString("gameplay-world", "hardcore");
        enableNether = getConfig().getBoolean("enable-nether", true);
        enableEnd = getConfig().getBoolean("enable-end", true);
        countdownSeconds = Math.max(1, getConfig().getInt("countdown-seconds", 5));
        borderRadius = getConfig().getDouble("world-border-radius", 0.0);
        clearInventory = getConfig().getBoolean("clear-inventory-on-reset", true);
        attempts = getConfig().getInt("attempts", 0);
        currentSeed = getConfig().contains("seed") ? getConfig().getLong("seed") : random.nextLong();

        genKey = new NamespacedKey(this, "seen_generation");
        limbo = Bukkit.getWorlds().get(0);

        if (baseName.equalsIgnoreCase(limbo.getName())) {
            getLogger().severe("gameplay-world must NOT equal your server level-name ("
                    + limbo.getName() + "). Change it in config.yml. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        loadOrCreateWorlds(currentSeed);
        getServer().getPluginManager().registerEvents(new GameListener(this), this);
        if (getCommand("mhreset") != null) {
            getCommand("mhreset").setExecutor(this);
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            handleArrival(p);
        }
        getLogger().info("Enabled. Current run: attempt #" + attempts + ".");
    }

    @Override
    public void onDisable() {
        getConfig().set("attempts", attempts);
        getConfig().set("seed", currentSeed);
        saveConfig();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (resetting) {
            sender.sendMessage("\u00A7cA reset is already in progress.");
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
        sender.sendMessage("\u00A7aForcing world reset"
                + (seed != null ? " (seed " + seed + ")" : " (random seed)") + "...");
        forceReset(seed);
        return true;
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
        w.setGameRule(GameRule.KEEP_INVENTORY, false);
        w.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
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
        announce("\u00A74\u2620 \u00A7c" + causeMsg + " \u00A77Everyone dies.");

        new BukkitRunnable() {
            int remaining = countdownSeconds;

            @Override
            public void run() {
                if (remaining <= 0) {
                    cancel();
                    holdThenRegenerate();
                    return;
                }
                announce("\u00A7e\u00A7lWorld resets in \u00A7c\u00A7l" + remaining + "\u00A7e\u00A7l...");
                remaining--;
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void holdThenRegenerate() {
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
        getConfig().set("attempts", attempts);
        getConfig().set("seed", currentSeed);
        saveConfig();

        over.getChunkAt(over.getSpawnLocation()).load();
        for (Player p : Bukkit.getOnlinePlayers()) {
            wipePlayer(p);
        }
        announce("\u00A7aA fresh world has been generated. Attempt #" + attempts
                + " (seed " + currentSeed + "). Good luck.");
        resetting = false;
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
        p.setHealth(20.0);
        for (PotionEffect eff : p.getActivePotionEffects()) {
            p.removePotionEffect(eff.getType());
        }
        p.getPersistentDataContainer().set(genKey, PersistentDataType.INTEGER, attempts);
    }

    public void handleArrival(Player p) {
        if (resetting) {
            p.setGameMode(GameMode.SPECTATOR);
            p.teleport(limbo.getSpawnLocation());
            return;
        }
        Integer seen = p.getPersistentDataContainer().get(genKey, PersistentDataType.INTEGER);
        if (seen == null || seen < attempts) {
            wipePlayer(p);
        } else if (!isManaged(p.getWorld())) {
            p.teleport(over.getSpawnLocation());
        }
    }

    // -------------------------------------------------------------- helpers

    public boolean isResetting() { return resetting; }
    public World getLimbo() { return limbo; }
    public World getOver() { return over; }
    public World getNether() { return nether; }
    public World getEnd() { return end; }

    public boolean isManaged(World w) {
        return w != null && (w.equals(over) || w.equals(nether) || w.equals(end));
    }
}
