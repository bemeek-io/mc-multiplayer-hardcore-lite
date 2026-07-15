package com.evensteven.mhplus;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persists per-player timed "Death" stacks. Each stack is an independent
 * wall-clock expiry timestamp (epoch ms — real-world time, not Minecraft
 * day/night); once expired it no longer reduces max health.
 */
public final class DeathStacks {

    private final MHPlus plugin;
    private final File file;
    private final YamlConfiguration data;
    private final long durationMs;

    DeathStacks(MHPlus plugin, long durationMs) {
        this.plugin = plugin;
        this.durationMs = durationMs;
        this.file = new File(plugin.getDataFolder(), "deaths.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    /** Adds one Death stack that expires after the configured duration. */
    public void add(UUID id) {
        pruneExpired(id);
        List<Long> expires = expires(id);
        expires.add(System.currentTimeMillis() + durationMs);
        setExpires(id, expires);
    }

    /** Drops every expired stack across all players. Returns true if anything changed. */
    public boolean pruneExpired() {
        ConfigurationSection players = data.getConfigurationSection("players");
        if (players == null) {
            return false;
        }
        boolean changed = false;
        long now = System.currentTimeMillis();
        for (String key : players.getKeys(false)) {
            ConfigurationSection s = players.getConfigurationSection(key);
            if (s == null) {
                continue;
            }
            List<Long> kept = new ArrayList<>();
            for (long exp : s.getLongList("expires")) {
                if (exp > now) {
                    kept.add(exp);
                } else {
                    changed = true;
                }
            }
            if (kept.size() != s.getLongList("expires").size()) {
                s.set("expires", kept);
                changed = true;
            }
        }
        if (changed) {
            save();
        }
        return changed;
    }

    public void pruneExpired(UUID id) {
        List<Long> expires = expires(id);
        long now = System.currentTimeMillis();
        List<Long> kept = new ArrayList<>();
        for (long exp : expires) {
            if (exp > now) {
                kept.add(exp);
            }
        }
        if (kept.size() != expires.size()) {
            setExpires(id, kept);
        }
    }

    public int activeCount(UUID id) {
        pruneExpired(id);
        return expires(id).size();
    }

    public boolean hasActive(UUID id) {
        return activeCount(id) > 0;
    }

    /** Soonest expiry among active stacks, or null if none. */
    public Long nextExpiry(UUID id) {
        pruneExpired(id);
        Long soonest = null;
        for (long exp : expires(id)) {
            if (soonest == null || exp < soonest) {
                soonest = exp;
            }
        }
        return soonest;
    }

    public void clearAll() {
        data.set("players", null);
        save();
    }

    private List<Long> expires(UUID id) {
        ConfigurationSection s = section(id, false);
        if (s == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(s.getLongList("expires"));
    }

    private void setExpires(UUID id, List<Long> expires) {
        ConfigurationSection s = section(id, true);
        s.set("expires", expires);
        save();
    }

    private ConfigurationSection section(UUID id, boolean create) {
        String path = "players." + id;
        ConfigurationSection s = data.getConfigurationSection(path);
        if (s == null && create) {
            s = data.createSection(path);
        }
        return s;
    }

    private void save() {
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save deaths.yml: " + e.getMessage());
        }
    }
}
