package com.evensteven.mhplus;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Vanilla compasses only point at spawn in {@code minecraft:overworld}. Our
 * gameplay world is a custom dimension, so the client spins them. Binding
 * them as untracked lodestone compasses aimed at gameplay spawn makes the
 * needle work without a real lodestone block (or a resource pack).
 */
final class SpawnCompasses {

    private final MHPlus plugin;
    private final NamespacedKey marker;

    SpawnCompasses(MHPlus plugin) {
        this.plugin = plugin;
        this.marker = new NamespacedKey(plugin, "spawn_compass");
    }

    void bindInventory(Player player) {
        PlayerInventory inv = player.getInventory();
        for (ItemStack stack : inv.getContents()) {
            bind(stack);
        }
        for (ItemStack stack : inv.getExtraContents()) {
            bind(stack);
        }
    }

    /**
     * @return true if the stack was modified
     */
    boolean bind(ItemStack stack) {
        if (stack == null || stack.getType() != Material.COMPASS) {
            return false;
        }
        if (!(stack.getItemMeta() instanceof CompassMeta meta)) {
            return false;
        }
        // Real lodestone pairing — leave it alone.
        if (meta.hasLodestone() && meta.isLodestoneTracked() && !isMarked(meta)) {
            return false;
        }
        Location spawn = plugin.getOver().getSpawnLocation();
        if (meta.hasLodestone()
                && isMarked(meta)
                && sameBlock(meta.getLodestone(), spawn)) {
            return false;
        }
        meta.setLodestone(spawn);
        meta.setLodestoneTracked(false);
        meta.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
        return stack.setItemMeta(meta);
    }

    /** Player just linked a compass to a real lodestone — drop our marker. */
    void clearMarkerIfPlayerLodestone(ItemStack stack) {
        if (stack == null || stack.getType() != Material.COMPASS) {
            return;
        }
        if (!(stack.getItemMeta() instanceof CompassMeta meta)) {
            return;
        }
        if (!isMarked(meta)) {
            return;
        }
        if (!meta.hasLodestone() || !meta.isLodestoneTracked()) {
            return;
        }
        meta.getPersistentDataContainer().remove(marker);
        stack.setItemMeta(meta);
    }

    private boolean isMarked(CompassMeta meta) {
        return meta.getPersistentDataContainer().has(marker, PersistentDataType.BYTE);
    }

    private static boolean sameBlock(Location a, Location b) {
        if (a == null || b == null || a.getWorld() == null || b.getWorld() == null) {
            return false;
        }
        return a.getWorld().equals(b.getWorld())
                && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }
}
