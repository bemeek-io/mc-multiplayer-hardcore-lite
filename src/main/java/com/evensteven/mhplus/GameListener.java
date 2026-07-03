package com.evensteven.mhplus;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

public final class GameListener implements Listener {

    private final MHPlus plugin;

    public GameListener(MHPlus plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        if (!plugin.isManaged(dead.getWorld()) || plugin.isResetting()) {
            return;
        }
        plugin.recordDeath(dead);
    }

    @EventHandler
    public void onSpawn(CreatureSpawnEvent event) {
        LivingEntity mob = event.getEntity();
        if (mob instanceof Enemy && plugin.isManaged(mob.getWorld())) {
            plugin.strengthenMob(mob, true);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Routes new arrivals, applies the shared max HP, wipes anyone who was
        // offline through a reset, and reopens any pending keep-item picks.
        plugin.handleArrival(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        // Freeze their inventory as the "last played" snapshot — unless they
        // still owe picks from an earlier reset, in which case that older
        // snapshot must survive.
        if (plugin.getSnapshots().hasPending(p.getUniqueId())) {
            return;
        }
        if (plugin.isManaged(p.getWorld())) {
            plugin.getSnapshots().capture(p, plugin.getAttempts());
        } else if (plugin.isResetting()) {
            // Quitting from limbo mid-reset: keep whatever was frozen when the
            // reset began; only capture if somehow nothing was.
            plugin.getSnapshots().captureIfNotGeneration(p, plugin.getAttempts());
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (plugin.isResetting()) {
            event.setRespawnLocation(plugin.getLimbo().getSpawnLocation());
            return;
        }
        World w = event.getRespawnLocation().getWorld();
        if (w == null || !plugin.isManaged(w)) {
            event.setRespawnLocation(plugin.getOver().getSpawnLocation());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        World from = event.getFrom().getWorld();
        if (!plugin.isManaged(from)) {
            return;
        }
        TeleportCause cause = event.getCause();
        Location target = null;

        if (cause == TeleportCause.NETHER_PORTAL && plugin.getNether() != null) {
            Location f = event.getFrom();
            if (from.equals(plugin.getOver())) {
                target = new Location(plugin.getNether(), f.getX() / 8.0, f.getY(), f.getZ() / 8.0);
            } else if (from.equals(plugin.getNether())) {
                target = new Location(plugin.getOver(), f.getX() * 8.0, f.getY(), f.getZ() * 8.0);
            }
        } else if (cause == TeleportCause.END_PORTAL && plugin.getEnd() != null) {
            if (from.equals(plugin.getOver())) {
                buildEndPlatform(plugin.getEnd(), 100, 49, 0);
                target = new Location(plugin.getEnd(), 100.5, 50, 0.5);
            } else if (from.equals(plugin.getEnd())) {
                target = plugin.getOver().getSpawnLocation();
            }
        }

        if (target != null) {
            event.setTo(target);
            event.setCanCreatePortal(true);
            event.setSearchRadius(128);
        }
    }

    private void buildEndPlatform(World end, int cx, int cy, int cz) {
        for (int x = cx - 2; x <= cx + 2; x++) {
            for (int z = cz - 2; z <= cz + 2; z++) {
                end.getBlockAt(x, cy, z).setType(Material.OBSIDIAN);
                for (int y = cy + 1; y <= cy + 3; y++) {
                    end.getBlockAt(x, y, z).setType(Material.AIR);
                }
            }
        }
    }
}
