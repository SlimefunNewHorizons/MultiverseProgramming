// SPDX-License-Identifier: GPL-3.0-or-later
package com.multiverse.programming;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class ComputerGUI {

    public static final String TITLE = "Computer";
    public static final String ADVANCED_TITLE = "Advanced Computer";
    public static final int DISK_SLOT = 0;
    public static final int BUTTON_SLOT = 8;

    private ComputerGUI() {
    }

    public static Inventory open() {
        return open((Location) null);
    }

    public static Inventory open(Location location) {
        return open(location, TITLE, "§a✔ Validate & Run");
    }

    public static Inventory openAdvanced() {
        return openAdvanced(false, null);
    }

    public static Inventory openAdvanced(boolean isRunning) {
        return openAdvanced(isRunning, null);
    }

    public static Inventory openAdvanced(boolean isRunning, Location location) {
        ComputerHolder holder = new ComputerHolder(true, location);
        Inventory inv = Bukkit.createInventory(holder, 9, ADVANCED_TITLE);
        holder.setInventory(inv);

        ItemStack frame = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta frameMeta = frame.getItemMeta();
        if (frameMeta != null) {
            frameMeta.setDisplayName(" ");
            frame.setItemMeta(frameMeta);
        }

        for (int i = 1; i < BUTTON_SLOT; i++) {
            inv.setItem(i, frame.clone());
        }

        ItemStack button;
        if (isRunning) {
            button = new ItemStack(Material.REDSTONE_BLOCK);
            ItemMeta buttonMeta = button.getItemMeta();
            if (buttonMeta != null) {
                buttonMeta.setDisplayName("§c⏹ Stop Program");
                buttonMeta.setLore(List.of("§7A program or live map/radar is running.", "§7Click to stop it."));
                button.setItemMeta(buttonMeta);
            }
        } else {
            button = new ItemStack(Material.EMERALD);
            ItemMeta buttonMeta = button.getItemMeta();
            if (buttonMeta != null) {
                buttonMeta.setDisplayName("§a▶ Run Program");
                buttonMeta.setLore(List.of("§7Checks and runs the program on the disk."));
                button.setItemMeta(buttonMeta);
            }
        }
        inv.setItem(BUTTON_SLOT, button);

        return inv;
    }

    private static Inventory open(Location location, String title, String buttonLabel) {
        ComputerHolder holder = new ComputerHolder(false, location);
        Inventory inv = Bukkit.createInventory(holder, 9, title);
        holder.setInventory(inv);

        ItemStack frame = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta frameMeta = frame.getItemMeta();
        if (frameMeta != null) {
            frameMeta.setDisplayName(" ");
            frame.setItemMeta(frameMeta);
        }

        for (int i = 1; i < BUTTON_SLOT; i++) {
            inv.setItem(i, frame.clone());
        }

        ItemStack button = new ItemStack(Material.EMERALD);
        ItemMeta buttonMeta = button.getItemMeta();
        if (buttonMeta != null) {
            buttonMeta.setDisplayName(buttonLabel);
            buttonMeta.setLore(List.of("§7Checks the disk's code for errors."));
            button.setItemMeta(buttonMeta);
        }
        inv.setItem(BUTTON_SLOT, button);

        return inv;
    }

    public static void pressButton(JavaPlugin plugin, Player player, Inventory inv, String prefix, long timeoutMs) {
        pressButton(plugin, player, inv, null, prefix, timeoutMs);
    }

    public static void pressButton(JavaPlugin plugin, Player player, Inventory inv, Location computerLoc, String prefix, long timeoutMs) {
        ItemStack disk = inv.getItem(DISK_SLOT);
        if (disk == null || disk.getType().isAir()) {
            player.sendMessage(prefix + " §cThere is no floppy disk in the slot.");
            return;
        }

        String code = DiskManager.readProgram(disk);
        String error = LuaRunner.validate(code);
        player.closeInventory();

        if (error != null) {
            player.sendMessage(prefix + " §cError in the code:");
            for (String line : error.split("\n")) {
                player.sendMessage(" §4✘ " + line);
            }
            return;
        }

        player.sendMessage(prefix + " §7Running program…");

        // Run asynchronously so the Minecraft server main thread NEVER freezes!
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            MultiverseProgrammingPlugin mvPlugin = (plugin instanceof MultiverseProgrammingPlugin mp) ? mp : null;
            LuaRunner.Result result = (mvPlugin != null)
                    ? LuaRunner.execute(mvPlugin, computerLoc, code, timeoutMs)
                    : LuaRunner.execute(code, timeoutMs);

            // Report results back to the player
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (!result.output().isEmpty()) {
                    for (String line : result.output().split("\n")) {
                        player.sendMessage("§f" + org.bukkit.ChatColor.translateAlternateColorCodes('&', line));
                    }
                } else if (result.ok()) {
                    player.sendMessage(prefix + " §7(no output)");
                }
            });
        });
    }

    public static void pressButton(Player player, Inventory inv, String prefix, long timeoutMs) {
        JavaPlugin plugin = JavaPlugin.getPlugin(MultiverseProgrammingPlugin.class);
        pressButton(plugin, player, inv, null, prefix, timeoutMs);
    }
}