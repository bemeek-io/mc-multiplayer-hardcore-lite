package com.evensteven.mhplus;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class GameListener implements Listener {

    private final MHPlus plugin;

    public GameListener(MHPlus plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        if (!plugin.isManaged(dead.getWorld()) || plugin.isResetting()) {
            return;
        }
        // Destroy inventory entirely — nothing drops on the ground.
        event.getDrops().clear();
        event.setDroppedExp(0);
        dead.getInventory().clear();
        dead.getInventory().setArmorContents(null);
        dead.getInventory().setItemInOffHand(null);
        plugin.recordDeath(dead);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Routes new arrivals, applies personal max HP from Death stacks, wipes
        // anyone who was offline through a reset, and reopens keep-item picks.
        plugin.handleArrival(event.getPlayer());
    }

    /**
     * Death's Poison HUD buff must not actually deal poison damage.
     * Stacks expire on wall-clock immediately, but the infinite HUD effect is
     * only cleared on the next prune pass — cancel through that gap too.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPoisonDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.POISON) {
            return;
        }
        if (!(event.getEntity() instanceof Player p)) {
            return;
        }
        if (plugin.getDeathStacks().hasActive(p.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        PotionEffect effect = p.getPotionEffect(PotionEffectType.POISON);
        if (effect != null && effect.getDuration() == PotionEffect.INFINITE_DURATION) {
            event.setCancelled(true);
            // Clear leftover Death HUD poison and restore max HP now.
            plugin.applyMaxHealth(p);
        }
    }

    /** Milk (etc.) clearing Poison: put the Death HUD buff back if stacks remain. */
    @EventHandler
    public void onPotionChange(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player p)) {
            return;
        }
        if (!plugin.getDeathStacks().hasActive(p.getUniqueId())) {
            return;
        }
        if (event.getAction() != EntityPotionEffectEvent.Action.REMOVED) {
            return;
        }
        if (event.getOldEffect() == null || event.getOldEffect().getType() != PotionEffectType.POISON) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) {
                plugin.syncDeathEffect(p);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        plugin.clearDeathBar(p.getUniqueId());
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

    @EventHandler
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        // With hardcore=true in server.properties, the server forces every
        // dead player into spectator right AFTER the respawn events have run
        // (it reads server.properties, so the per-world hardcore flag doesn't
        // stop it). Cancel that switch so Death stacks can apply on respawn.
        if (event.getCause() != PlayerGameModeChangeEvent.Cause.HARDCORE_DEATH) {
            return;
        }
        if (plugin.isResetting()) {
            return;
        }
        event.setCancelled(true);
        plugin.ensureSurvivalNextTick(event.getPlayer());
    }

    @EventHandler
    public void onPostRespawn(PlayerPostRespawnEvent event) {
        // Undo spectator mode from any hardcore world flag and reapply the
        // player's Death-reduced max health to the fresh player entity.
        plugin.handleRespawned(event.getPlayer());
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
            // Overworld and Nether use different scales: a 128-block overworld
            // search window is only 16 blocks in the Nether (÷8).
            if (target.getWorld() != null
                    && target.getWorld().getEnvironment() == World.Environment.NETHER) {
                event.setSearchRadius(16);
            } else {
                event.setSearchRadius(128);
            }
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
