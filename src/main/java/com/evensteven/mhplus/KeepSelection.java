package com.evensteven.mhplus;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The "choose what you carry into the next world" chest GUI. Shows the
 * player's snapshot inventory; each click on an item hands it over and burns
 * one pick. A barrier button forfeits whatever picks remain. Closing early is
 * fine — picks stay pending and /mhkeep (or the next login) reopens the menu.
 */
public final class KeepSelection implements Listener {

    private static final int ITEM_SLOTS = 45;   // 5 rows of snapshot items
    private static final int FORFEIT_SLOT = 49; // middle of the bottom row

    private final MHPlus plugin;
    private final SnapshotStore store;
    private final Map<UUID, Inventory> open = new HashMap<>();

    public KeepSelection(MHPlus plugin, SnapshotStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    /** Opens the selection menu if the player has picks left; no-op otherwise. */
    public void openIfPending(Player p) {
        UUID id = p.getUniqueId();
        int picks = store.getPending(id);
        if (picks <= 0) {
            return;
        }
        List<ItemStack> items = store.getItems(id);
        if (items.isEmpty()) {
            store.setPending(id, 0);
            return;
        }
        Inventory inv = Bukkit.createInventory(null, 54,
                Component.text("Choose " + picks + " to keep — click items"));
        paint(inv, items);
        open.put(id, inv);
        p.openInventory(inv);
        p.sendMessage("§6Your old life ended. §eClick up to §c" + picks
                + "§e item" + (picks == 1 ? "" : "s") + "/stack" + (picks == 1 ? "" : "s")
                + " to carry into this world. Close and §a/mhkeep§e to resume later.");
    }

    /** Schedules openIfPending a moment later, once the player has settled in. */
    public void openIfPendingLater(Player p, long delayTicks) {
        UUID id = p.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player online = Bukkit.getPlayer(id);
            if (online != null && online.isOnline() && !plugin.isResetting()) {
                openIfPending(online);
            }
        }, delayTicks);
    }

    private void paint(Inventory inv, List<ItemStack> items) {
        inv.clear();
        for (int i = 0; i < items.size() && i < ITEM_SLOTS; i++) {
            inv.setItem(i, items.get(i));
        }
        ItemStack forfeit = new ItemStack(Material.BARRIER);
        forfeit.editMeta(meta -> meta.displayName(Component.text("Forfeit remaining picks")));
        inv.setItem(FORFEIT_SLOT, forfeit);
    }

    // ---------------------------------------------------------------- events

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) {
            return;
        }
        UUID id = p.getUniqueId();
        Inventory menu = open.get(id);
        if (menu == null || !menu.equals(event.getView().getTopInventory())) {
            return;
        }
        event.setCancelled(true); // the menu is modal; nothing moves by hand
        if (!menu.equals(event.getClickedInventory())) {
            return;
        }
        int slot = event.getSlot();
        if (slot == FORFEIT_SLOT) {
            store.setPending(id, 0);
            open.remove(id);
            Bukkit.getScheduler().runTask(plugin, (Runnable) p::closeInventory);
            p.sendMessage("§7Remaining picks forfeited.");
            return;
        }
        if (slot >= ITEM_SLOTS) {
            return;
        }
        List<ItemStack> items = store.getItems(id);
        if (slot >= items.size()) {
            return;
        }

        ItemStack chosen = items.remove(slot);
        store.setItems(id, items);
        give(p, chosen);

        int left = store.getPending(id) - 1;
        store.setPending(id, left);
        if (left <= 0 || items.isEmpty()) {
            store.setPending(id, 0);
            open.remove(id);
            Bukkit.getScheduler().runTask(plugin, (Runnable) p::closeInventory);
            p.sendMessage("§aAll picks used. Good luck out there.");
        } else {
            paint(menu, items);
            p.sendMessage("§e" + left + " pick" + (left == 1 ? "" : "s") + " left.");
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        Inventory menu = open.get(event.getWhoClicked().getUniqueId());
        if (menu != null && menu.equals(event.getView().getTopInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Inventory menu = open.get(id);
        if (menu == null || !menu.equals(event.getInventory())) {
            return;
        }
        open.remove(id);
        int left = store.getPending(id);
        if (left > 0) {
            event.getPlayer().sendMessage("§eYou still have §c" + left
                    + "§e pick" + (left == 1 ? "" : "s") + " — run §a/mhkeep§e to finish choosing.");
        }
    }

    private void give(Player p, ItemStack item) {
        Map<Integer, ItemStack> leftover = p.getInventory().addItem(item);
        for (ItemStack rest : leftover.values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }
    }
}
