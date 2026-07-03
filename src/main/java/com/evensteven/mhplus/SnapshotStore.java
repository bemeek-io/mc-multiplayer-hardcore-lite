package com.evensteven.mhplus;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Persists each player's "last played" inventory across world resets, plus how
 * many keep-picks they still owe. Items are stored as base64-encoded Paper
 * item bytes so they survive server restarts and (usually) version bumps.
 *
 * A snapshot is only overwritten while its owner is actually playing (quit or
 * reset capture). While a player still has pending picks their snapshot is
 * frozen, so someone offline through several resets always chooses from the
 * last inventory they really played on.
 */
public final class SnapshotStore {

    private final MHPlus plugin;
    private final File file;
    private final YamlConfiguration data;

    SnapshotStore(MHPlus plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "snapshots.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    // ------------------------------------------------------------- capture

    /** Stores the player's full inventory (main, armor, offhand) as their snapshot. */
    public void capture(Player p, int generation) {
        List<String> encoded = new ArrayList<>();
        for (ItemStack item : p.getInventory().getContents()) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            encoded.add(Base64.getEncoder().encodeToString(item.serializeAsBytes()));
        }
        ConfigurationSection s = section(p.getUniqueId(), true);
        s.set("name", p.getName());
        s.set("generation", generation);
        s.set("items", encoded);
        save();
    }

    /** Captures only if the stored snapshot isn't already from this generation. */
    public void captureIfNotGeneration(Player p, int generation) {
        ConfigurationSection s = section(p.getUniqueId(), false);
        if (s != null && s.getInt("generation", -1) == generation) {
            return;
        }
        capture(p, generation);
    }

    // ------------------------------------------------------------- pending

    public boolean hasPending(UUID id) {
        return getPending(id) > 0;
    }

    public int getPending(UUID id) {
        ConfigurationSection s = section(id, false);
        return s == null ? 0 : s.getInt("pending-picks", 0);
    }

    public void setPending(UUID id, int picks) {
        ConfigurationSection s = section(id, false);
        if (s == null) {
            return;
        }
        s.set("pending-picks", Math.max(0, picks));
        save();
    }

    /** After a reset: everyone with a snapshot and no outstanding picks gets a fresh set. */
    public void offerPicksToAll(int picks) {
        ConfigurationSection players = data.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String key : players.getKeys(false)) {
            ConfigurationSection s = players.getConfigurationSection(key);
            if (s == null) {
                continue;
            }
            if (s.getInt("pending-picks", 0) <= 0 && !s.getStringList("items").isEmpty()) {
                s.set("pending-picks", picks);
            }
        }
        save();
    }

    // --------------------------------------------------------------- items

    public List<ItemStack> getItems(UUID id) {
        List<ItemStack> items = new ArrayList<>();
        ConfigurationSection s = section(id, false);
        if (s == null) {
            return items;
        }
        for (String encoded : s.getStringList("items")) {
            try {
                items.add(ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded)));
            } catch (Exception ex) {
                plugin.getLogger().warning("Dropping unreadable snapshot item for " + id + ": " + ex.getMessage());
            }
        }
        return items;
    }

    public void setItems(UUID id, List<ItemStack> items) {
        ConfigurationSection s = section(id, false);
        if (s == null) {
            return;
        }
        List<String> encoded = new ArrayList<>();
        for (ItemStack item : items) {
            encoded.add(Base64.getEncoder().encodeToString(item.serializeAsBytes()));
        }
        s.set("items", encoded);
        save();
    }

    // ------------------------------------------------------------- plumbing

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
            plugin.getLogger().severe("Could not save snapshots.yml: " + e.getMessage());
        }
    }
}
