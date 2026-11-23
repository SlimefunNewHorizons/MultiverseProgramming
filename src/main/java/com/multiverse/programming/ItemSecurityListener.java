// SPDX-License-Identifier: GPL-3.0-or-later
package com.multiverse.programming;

import org.bukkit.Material;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Prevents players from placing writable books, written books, or storage containers
 * (shulkers, chests, barrels, hoppers, dispensers, droppers) into Item Frames,
 * Chiseled Bookshelves, or Decorated Pots.
 * Protects against item duplication, chunk overloading, and NBT crash exploits without
 * restricting normal survival chest storage.
 */
public final class ItemSecurityListener implements Listener {

    private final MultiverseProgrammingPlugin plugin;

    public ItemSecurityListener(MultiverseProgrammingPlugin plugin) {
        this.plugin = plugin;
    }

    public static boolean isStorageBlock(Material mat) {
        if (mat == null) return false;
        String name = mat.name();
        return name.endsWith("SHULKER_BOX") ||
                name.equals("CHEST") ||
                name.equals("TRAPPED_CHEST") ||
                name.equals("BARREL") ||
                name.equals("DISPENSER") ||
                name.equals("DROPPER") ||
                name.equals("HOPPER") ||
                name.endsWith("BUNDLE") ||
                name.equals("DECORATED_POT");
    }

    public static boolean isForbiddenBook(Material mat) {
        if (mat == null) return false;
        return mat == Material.WRITABLE_BOOK || mat == Material.WRITTEN_BOOK;
    }

    public static boolean isForbiddenInItemFrame(Material mat) {
        return isStorageBlock(mat) || isForbiddenBook(mat);
    }

    /**
     * Allows a completely blank, unsigned Book and Quill (no pages, no custom PDC)
     * in Item Frames so Slimefun addons like FlowerPower (Magic Basin -> Experience Book)
     * work without opening book-ban / chunk-overload / NBT dupe vectors.
     */
    public static boolean isEmptyWritableBook(ItemStack item) {
        if (item == null || item.getType() != Material.WRITABLE_BOOK) return false;
        if (!item.hasItemMeta()) return true;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return true;
        if (!meta.getPersistentDataContainer().isEmpty()) return false;
        if (meta instanceof BookMeta bookMeta) {
            if (bookMeta.getPageCount() == 0) return true;
            for (String page : bookMeta.getPages()) {
                if (page != null && !page.isBlank()) return false;
            }
            return true;
        }
        return false;
    }

    public static boolean isForbiddenInBookshelf(Material mat) {
        return isForbiddenBook(mat) || isStorageBlock(mat);
    }

    public static boolean isForbiddenItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        return isForbiddenInBookshelf(item.getType());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof ItemFrame) {
            Player player = event.getPlayer();
            if (player.hasPermission("multiverseprogramming.admin")) {
                return;
            }
            EquipmentSlot hand = event.getHand();
            ItemStack item = (hand == EquipmentSlot.OFF_HAND)
                    ? player.getInventory().getItemInOffHand()
                    : player.getInventory().getItemInMainHand();

            if (item != null && isForbiddenInItemFrame(item.getType()) && !isEmptyWritableBook(item)) {
                event.setCancelled(true);
                player.sendMessage(plugin.getPrefix() + " §cPlacing books or storage containers in item frames is prohibited to prevent duplication exploits.");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteractBlock(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            Material type = event.getClickedBlock().getType();
            if (type == Material.CHISELED_BOOKSHELF || type == Material.DECORATED_POT) {
                Player player = event.getPlayer();
                if (player.hasPermission("multiverseprogramming.admin")) {
                    return;
                }
                ItemStack item = event.getItem();
                if (item != null && isForbiddenItem(item)) {
                    event.setCancelled(true);
                    player.sendMessage(plugin.getPrefix() + " §cPlacing books or storage items in this block is prohibited.");
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        String destType = event.getDestination().getType().name();
        if (destType.equals("CHISELED_BOOKSHELF") || destType.contains("POT")) {
            if (isForbiddenItem(event.getItem())) {
                event.setCancelled(true);
            }
        }
    }
}
