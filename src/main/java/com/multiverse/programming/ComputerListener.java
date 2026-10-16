// SPDX-License-Identifier: GPL-3.0-or-later
package com.multiverse.programming;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class ComputerListener implements Listener {

    private record BlockRef(boolean advanced, Location location) {
    }

    private record RunningEntry(UUID player, LuaRunner.LuaProgram program, BukkitTask cleanup) {
    }

    private final MultiverseProgrammingPlugin plugin;
    private Material computerBlock;
    private Material advancedComputerBlock;

    private static final Map<Location, ItemStack> advancedDisks = new ConcurrentHashMap<>();
    private static final Map<Location, RunningEntry> runningPrograms = new ConcurrentHashMap<>();
    private static final Map<UUID, BlockRef> openByPlayer = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> lastClickTime = new ConcurrentHashMap<>();

    public ComputerListener(MultiverseProgrammingPlugin plugin, Material computerBlock, Material advancedComputerBlock) {
        this.plugin = plugin;
        this.computerBlock = computerBlock;
        this.advancedComputerBlock = advancedComputerBlock;
    }

    public void updateMaterials(Material computerBlock, Material advancedComputerBlock) {
        this.computerBlock = computerBlock;
        this.advancedComputerBlock = advancedComputerBlock;
    }

    public void loadAdvancedDisks() {
        if (plugin.getDataFolder() == null) return;
        File file = new File(plugin.getDataFolder(), "advanced_disks.yml");
        if (!file.exists()) return;
        try {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection sec = config.getConfigurationSection("disks");
            if (sec == null) return;
            for (String key : sec.getKeys(false)) {
                ConfigurationSection itemSec = sec.getConfigurationSection(key);
                if (itemSec == null) continue;
                String wName = itemSec.getString("world");
                if (wName == null) continue;
                World world = Bukkit.getWorld(wName);
                if (world == null) continue;
                int x = itemSec.getInt("x");
                int y = itemSec.getInt("y");
                int z = itemSec.getInt("z");
                Location loc = new Location(world, x, y, z);
                ItemStack disk = itemSec.getItemStack("item");
                if (disk != null && !disk.getType().isAir()) {
                    advancedDisks.put(loc, disk);
                }
            }
        } catch (Throwable e) {
            plugin.getLogger().warning("Failed to load advanced_disks.yml: " + e.getMessage());
        }
    }

    public void saveAdvancedDisks() {
        if (plugin.getDataFolder() == null) return;
        File file = new File(plugin.getDataFolder(), "advanced_disks.yml");
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            YamlConfiguration config = new YamlConfiguration();
            ConfigurationSection sec = config.createSection("disks");
            int counter = 0;
            for (Map.Entry<Location, ItemStack> entry : advancedDisks.entrySet()) {
                Location loc = entry.getKey();
                ItemStack disk = entry.getValue();
                if (loc == null || loc.getWorld() == null || disk == null || disk.getType().isAir()) continue;
                ConfigurationSection itemSec = sec.createSection("disk_" + (counter++));
                itemSec.set("world", loc.getWorld().getName());
                itemSec.set("x", loc.getBlockX());
                itemSec.set("y", loc.getBlockY());
                itemSec.set("z", loc.getBlockZ());
                itemSec.set("item", disk);
            }
            config.save(file);
        } catch (Throwable e) {
            plugin.getLogger().warning("Failed to save advanced_disks.yml: " + e.getMessage());
        }
    }

    public static void cancelAll() {
        for (Map.Entry<Location, RunningEntry> e : runningPrograms.entrySet()) {
            e.getValue().program().cancel();
            e.getValue().cleanup().cancel();
            cleanupPeripheralsAround(e.getKey());
        }
        runningPrograms.clear();
        openByPlayer.clear();
        lastClickTime.clear();
    }

    public static boolean stopProgramAt(Location loc) {
        Location blockLoc = toBlockLocation(loc);
        RunningEntry entry = runningPrograms.remove(blockLoc);
        if (entry != null) {
            entry.program().cancel();
            entry.cleanup().cancel();
            cleanupPeripheralsAround(blockLoc);
            return true;
        }
        return false;
    }

    public static int stopProgramsByPlayer(UUID player) {
        if (player == null) return 0;
        List<Location> toStop = new ArrayList<>();
        for (Map.Entry<Location, RunningEntry> e : runningPrograms.entrySet()) {
            if (player.equals(e.getValue().player())) {
                toStop.add(e.getKey());
            }
        }
        int count = 0;
        for (Location loc : toStop) {
            RunningEntry entry = runningPrograms.remove(loc);
            if (entry != null) {
                entry.program().cancel();
                entry.cleanup().cancel();
                cleanupPeripheralsAround(loc);
                count++;
            }
        }
        return count;
    }

    public static int stopAllPrograms() {
        int count = runningPrograms.size();
        cancelAll();
        return count;
    }

    public static Map<Location, ItemStack> getAdvancedDisks() {
        return advancedDisks;
    }

    public boolean isComputerBlock(Material mat) {
        return mat != null && (mat == computerBlock || mat == advancedComputerBlock);
    }

    public static boolean isComputer(Block block) {
        if (block == null) return false;
        String id = getBlockMachineId(block);
        if (DiskManager.ID_COMPUTER.equalsIgnoreCase(id) || DiskManager.ID_ADVANCED_COMPUTER.equalsIgnoreCase(id)) {
            return true;
        }
        Location loc = toBlockLocation(block.getLocation());
        return (loc != null && (runningPrograms.containsKey(loc) || advancedDisks.containsKey(loc)));
    }

    public static boolean isMultiverseNetsBlock(Block block) {
        if (block == null) return false;
        try {
            org.bukkit.Chunk chunk = block.getChunk();
            if (chunk == null || !chunk.isLoaded()) return false;
            var pdc = chunk.getPersistentDataContainer();
            if (pdc == null) return false;
            String suffix = block.getX() + "_" + block.getY() + "_" + block.getZ();
            org.bukkit.NamespacedKey tKey = new org.bukkit.NamespacedKey("multiversenets", "t" + suffix);
            org.bukkit.NamespacedKey nKey = new org.bukkit.NamespacedKey("multiversenets", "n" + suffix);
            if (pdc.has(tKey, org.bukkit.persistence.PersistentDataType.STRING)
                    || pdc.has(nKey, org.bukkit.persistence.PersistentDataType.STRING)) {
                return true;
            }
            org.bukkit.NamespacedKey tKeyCap = new org.bukkit.NamespacedKey("MultiverseNets", "t" + suffix);
            org.bukkit.NamespacedKey nKeyCap = new org.bukkit.NamespacedKey("MultiverseNets", "n" + suffix);
            return pdc.has(tKeyCap, org.bukkit.persistence.PersistentDataType.STRING)
                    || pdc.has(nKeyCap, org.bukkit.persistence.PersistentDataType.STRING);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isSlimefunBlock(Block block) {
        if (block == null) return false;
        try {
            if (Bukkit.getPluginManager().isPluginEnabled("Slimefun")) {
                Class<?> blockStorage = Class.forName("me.mrCookieSlime.Slimefun.api.BlockStorage");
                java.lang.reflect.Method checkLoc = blockStorage.getMethod("check", Location.class);
                if (checkLoc.invoke(null, block.getLocation()) != null) {
                    return true;
                }
                java.lang.reflect.Method hasInfo = blockStorage.getMethod("hasBlockInfo", Location.class);
                if (Boolean.TRUE.equals(hasInfo.invoke(null, block.getLocation()))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}

        try {
            if (block.getState() instanceof org.bukkit.block.TileState tileState) {
                var pdc = tileState.getPersistentDataContainer();
                if (pdc != null) {
                    for (org.bukkit.NamespacedKey key : pdc.getKeys()) {
                        if ("slimefun".equalsIgnoreCase(key.getNamespace())) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        try {
            if (block.hasMetadata("slimefun_block") || block.hasMetadata("slimefun_item")) {
                return true;
            }
        } catch (Throwable ignored) {}

        return false;
    }

    public static boolean isSlimefunItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        try {
            if (Bukkit.getPluginManager().isPluginEnabled("Slimefun")) {
                Class<?> sfItem = Class.forName("io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem");
                java.lang.reflect.Method getByItem = sfItem.getMethod("getByItem", ItemStack.class);
                if (getByItem.invoke(null, item) != null) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}

        try {
            var pdc = item.getItemMeta().getPersistentDataContainer();
            if (pdc != null) {
                for (org.bukkit.NamespacedKey key : pdc.getKeys()) {
                    if ("slimefun".equalsIgnoreCase(key.getNamespace())) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}

        return false;
    }

    public static String getBlockMachineId(Block block) {
        if (block == null) return null;
        try {
            if (block.getState() instanceof org.bukkit.block.TileState tileState) {
                var pdc = tileState.getPersistentDataContainer();
                if (pdc != null && pdc.has(DiskManager.KEY_ID, org.bukkit.persistence.PersistentDataType.STRING)) {
                    return pdc.get(DiskManager.KEY_ID, org.bukkit.persistence.PersistentDataType.STRING);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(org.bukkit.event.block.BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (isSlimefunItem(item) || isSlimefunBlock(event.getBlockPlaced()) || isMultiverseNetsBlock(event.getBlockPlaced())) {
            return;
        }
        String machineId = DiskManager.getMachineId(item);
        if (machineId != null && event.getBlockPlaced().getState() instanceof org.bukkit.block.TileState tileState) {
            try {
                tileState.getPersistentDataContainer().set(DiskManager.KEY_ID, org.bukkit.persistence.PersistentDataType.STRING, machineId);
                tileState.update();
            } catch (Throwable ignored) {}
        }
    }

    @EventHandler
    public void onBlockUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }

        // Never intercept MultiverseNets or Slimefun blocks
        if (isMultiverseNetsBlock(block) || isSlimefunBlock(block)) {
            return;
        }

        Player player = event.getPlayer();
        Material bType = block.getType();

        if (player.isSneaking()) {
            if (bType == plugin.getConfigManager().getCartographerBlock()
                    && (com.multiverse.programming.peripheral.CartographerPeripheral.hasDisplaysAround(block.getLocation())
                    || com.multiverse.programming.peripheral.MapDisplay.hasMapFor(block.getLocation()))) {
                event.setCancelled(true);
                com.multiverse.programming.peripheral.CartographerPeripheral.cleanupAdjacent(block.getLocation());
                com.multiverse.programming.peripheral.MapDisplay.removeForCartographer(block.getLocation());
                player.sendMessage(plugin.getPrefix() + " §aCleared Cartographer hologram and adjacent displays.");
                return;
            }
            if (bType == plugin.getConfigManager().getNpcBlock() && isNpcBlock(block)) {
                event.setCancelled(true);
                com.multiverse.programming.peripheral.NpcPeripheral.cleanupAt(block.getLocation());
                player.sendMessage(plugin.getPrefix() + " §aCleared NPC entity and dialogue hologram.");
                return;
            }
            if (bType == plugin.getConfigManager().getMonitorBlock()
                    && (com.multiverse.programming.peripheral.MonitorPeripheral.hasDisplayAt(block.getLocation())
                    || com.multiverse.programming.peripheral.MapDisplay.hasMapAt(block.getLocation()))) {
                event.setCancelled(true);
                com.multiverse.programming.peripheral.MonitorPeripheral.removeDisplayAt(block.getLocation());
                com.multiverse.programming.peripheral.MapDisplay.removeAt(block.getLocation());
                player.sendMessage(plugin.getPrefix() + " §aCleared Monitor display.");
                return;
            }
        }

        Location blockLoc = toBlockLocation(block.getLocation());
        String machineId = getBlockMachineId(block);
        boolean advanced;
        if (DiskManager.ID_ADVANCED_COMPUTER.equalsIgnoreCase(machineId)) {
            advanced = true;
            if (!plugin.getConfigManager().isEnableAdvancedComputer()) {
                event.setCancelled(true);
                player.sendMessage(plugin.getPrefix() + " §cAdvanced computers are currently disabled by the server administration.");
                return;
            }
        } else if (DiskManager.ID_COMPUTER.equalsIgnoreCase(machineId)) {
            advanced = false;
            if (!plugin.getConfigManager().isEnableComputer()) {
                event.setCancelled(true);
                player.sendMessage(plugin.getPrefix() + " §cStandard computers are currently disabled by the server administration.");
                return;
            }
        } else if (blockLoc != null && advancedDisks.containsKey(blockLoc)) {
            advanced = true;
            if (!plugin.getConfigManager().isEnableAdvancedComputer()) {
                event.setCancelled(true);
                player.sendMessage(plugin.getPrefix() + " §cAdvanced computers are currently disabled by the server administration.");
                return;
            }
        } else if (blockLoc != null && runningPrograms.containsKey(blockLoc)) {
            advanced = false;
            if (!plugin.getConfigManager().isEnableComputer()) {
                event.setCancelled(true);
                player.sendMessage(plugin.getPrefix() + " §cStandard computers are currently disabled by the server administration.");
                return;
            }
        } else {
            return;
        }

        event.setCancelled(true);

        if (!player.hasPermission("multiverseprogramming.use")) {
            player.sendMessage(plugin.getPrefix() + " §cYou don't have permission to use computers.");
            return;
        }

        if (advanced && player.isSneaking() && (isProgramRunning(blockLoc) || hasLiveMaps(blockLoc))) {
            stopProgramAt(blockLoc);
            removeLiveMaps(blockLoc);
            player.sendMessage(plugin.getPrefix() + " §cForce-stopped running script on Advanced Computer at [" + blockLoc.getBlockX() + ", " + blockLoc.getBlockY() + ", " + blockLoc.getBlockZ() + "].");
            return;
        }

        openByPlayer.put(player.getUniqueId(), new BlockRef(advanced, blockLoc));

        if (!advanced) {
            player.openInventory(ComputerGUI.open(blockLoc));
            return;
        }

        // A live map/radar keeps running after its script ends, so it also counts as "running" for the Stop button.
        boolean isRunning = isProgramRunning(blockLoc) || hasLiveMaps(blockLoc);
        ItemStack savedDisk = advancedDisks.get(blockLoc);
        Inventory inv = ComputerGUI.openAdvanced(isRunning, blockLoc);
        if (savedDisk != null && !savedDisk.getType().isAir()) {
            inv.setItem(ComputerGUI.DISK_SLOT, savedDisk);
        }
        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        boolean isHolder = top != null && top.getHolder() instanceof ComputerHolder;
        String title = event.getView().getTitle();
        boolean advanced;
        if (isHolder) {
            advanced = ((ComputerHolder) top.getHolder()).isAdvanced();
        } else if (ComputerGUI.ADVANCED_TITLE.equals(title)) {
            advanced = true;
        } else if (ComputerGUI.TITLE.equals(title)) {
            advanced = false;
        } else {
            return;
        }

        ClickType click = event.getClick();
        if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT
                || click == ClickType.DOUBLE_CLICK || click == ClickType.NUMBER_KEY
                || click == ClickType.SWAP_OFFHAND) {
            event.setCancelled(true);
            return;
        }

        int raw = event.getRawSlot();
        if (raw < 0 || raw >= 9) {
            return;
        }

        event.setCancelled(true);

        if (raw == ComputerGUI.DISK_SLOT) {
            ItemStack cursor = event.getCursor();
            ItemStack current = event.getCurrentItem();
            boolean cursorIsDisk = DiskManager.isDisk(cursor);
            boolean slotIsDisk = current != null && DiskManager.isDisk(current);
            boolean cursorEmpty = cursor == null || cursor.getType().isAir();

            if (cursorIsDisk || (cursorEmpty && slotIsDisk)) {
                event.setCancelled(false);
                return;
            }

            if (!cursorEmpty) {
                player.sendMessage(plugin.getPrefix() + " §cOnly a " + DiskManager.NAME + " can be inserted here.");
            }
        } else if (raw == ComputerGUI.BUTTON_SLOT) {
            long now = System.currentTimeMillis();
            long delay = plugin.getConfigManager().getPreventSpamDelayMs();
            Long last = lastClickTime.get(player.getUniqueId());
            if (last != null && (now - last) < delay) {
                return;
            }
            lastClickTime.put(player.getUniqueId(), now);

            BlockRef ref = openByPlayer.get(player.getUniqueId());
            if (ref == null || ref.advanced() != advanced) {
                return;
            }
            if (advanced) {
                pressAdvanced(player, event.getInventory(), ref.location());
            } else {
                ComputerGUI.pressButton(plugin, player, event.getInventory(), ref.location(), plugin.getPrefix(), plugin.getTimeoutMs());
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        boolean isHolder = top != null && top.getHolder() instanceof ComputerHolder;
        String title = event.getView().getTitle();
        if (!isHolder && !ComputerGUI.TITLE.equals(title) && !ComputerGUI.ADVANCED_TITLE.equals(title)) {
            return;
        }
        for (int raw : event.getRawSlots()) {
            if (raw >= 0 && raw < 9) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        boolean isHolder = top != null && top.getHolder() instanceof ComputerHolder;
        String title = event.getView().getTitle();
        boolean advanced;
        if (isHolder) {
            advanced = ((ComputerHolder) top.getHolder()).isAdvanced();
        } else if (ComputerGUI.ADVANCED_TITLE.equals(title)) {
            advanced = true;
        } else if (ComputerGUI.TITLE.equals(title)) {
            advanced = false;
        } else {
            return;
        }

        BlockRef ref = openByPlayer.remove(player.getUniqueId());
        ItemStack disk = event.getInventory().getItem(ComputerGUI.DISK_SLOT);

        if (advanced) {
            if (ref != null) {
                if (disk == null || disk.getType().isAir()) {
                    advancedDisks.remove(ref.location());
                } else {
                    advancedDisks.put(ref.location(), disk.clone());
                }
            }
            return;
        }

        if (disk == null || disk.getType().isAir()) {
            return;
        }
        event.getInventory().setItem(ComputerGUI.DISK_SLOT, null);

        Map<Integer, ItemStack> overflow = player.getInventory().addItem(disk);
        for (ItemStack rest : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        openByPlayer.remove(uuid);
        lastClickTime.remove(uuid);

        List<Location> toStop = new ArrayList<>();
        for (Map.Entry<Location, RunningEntry> e : runningPrograms.entrySet()) {
            if (e.getValue().player().equals(uuid)) {
                toStop.add(e.getKey());
            }
        }
        for (Location loc : toStop) {
            RunningEntry entry = runningPrograms.get(loc);
            if (entry != null) {
                stopEntry(loc, entry);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        handleBlockRemoved(event.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        for (Block block : event.blockList()) {
            handleBlockRemoved(block);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (Block block : event.blockList()) {
            handleBlockRemoved(block);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        handleBlockRemoved(event.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (Block b : event.getBlocks()) {
            if (isComputer(b)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block b : event.getBlocks()) {
            if (isComputer(b)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        World world = event.getWorld();
        List<Location> toRemove = new ArrayList<>();
        for (Map.Entry<Location, RunningEntry> entry : runningPrograms.entrySet()) {
            if (world.equals(entry.getKey().getWorld())) {
                toRemove.add(entry.getKey());
            }
        }
        for (Location loc : toRemove) {
            RunningEntry entry = runningPrograms.remove(loc);
            if (entry != null) {
                entry.program().cancel();
                entry.cleanup().cancel();
            }
        }
        advancedDisks.keySet().removeIf(loc -> world.equals(loc.getWorld()));
    }

    public static Location toBlockLocation(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        return new Location(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public static boolean isProgramRunning(Location loc) {
        if (loc == null) return false;
        Location blockLoc = toBlockLocation(loc);
        RunningEntry entry = runningPrograms.get(blockLoc);
        return entry != null && entry.program().isRunning();
    }

    private void handleBlockRemoved(Block block) {
        if (block == null) {
            return;
        }
        Location loc = toBlockLocation(block.getLocation());
        RunningEntry running = runningPrograms.remove(loc);
        if (running != null) {
            running.program().cancel();
            running.cleanup().cancel();
            cleanupPeripheralsAround(loc);
        }

        ItemStack disk = advancedDisks.remove(loc);
        if (disk != null && !disk.getType().isAir() && plugin.getConfigManager().isDropDisksOnBreak()) {
            block.getWorld().dropItemNaturally(block.getLocation(), disk);
        }

        if (isComputer(block)) {
            cleanupPeripheralsAround(loc);
        }
        if (block.getType() == plugin.getConfigManager().getMonitorBlock()) {
            com.multiverse.programming.peripheral.MonitorPeripheral.removeDisplayAt(loc);
        }
        if (block.getType() == plugin.getConfigManager().getCartographerBlock()) {
            com.multiverse.programming.peripheral.CartographerPeripheral.cleanupAdjacent(loc);
        }
        if (block.getType() == plugin.getConfigManager().getNpcBlock()) {
            com.multiverse.programming.peripheral.NpcPeripheral.cleanupAt(loc);
        }
    }

    private void stopEntry(Location loc, RunningEntry entry) {
        entry.program().cancel();
        entry.cleanup().cancel();
        runningPrograms.remove(toBlockLocation(loc));
        cleanupPeripheralsAround(loc);
    }

    public static void cleanupPeripheralsAround(Location computerLoc) {
        if (computerLoc == null || computerLoc.getWorld() == null) return;
        Block computerBlock = computerLoc.getBlock();
        if (computerBlock == null) return;
        BlockFace[] faces = {
                BlockFace.SELF, BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
        };
        for (BlockFace face : faces) {
            Block b = computerBlock.getRelative(face);
            if (b == null) continue;
            Location loc = b.getLocation();
            com.multiverse.programming.peripheral.NpcPeripheral.cleanupAt(loc);
            com.multiverse.programming.peripheral.CartographerPeripheral.cleanupAdjacent(loc);
            com.multiverse.programming.peripheral.MonitorPeripheral.removeDisplayAt(loc);
        }
    }

    /** True when a cartographer touching this computer is showing a live map or radar. */
    public static boolean hasLiveMaps(Location computerLoc) {
        if (computerLoc == null || computerLoc.getWorld() == null) return false;
        Block computerBlock = computerLoc.getBlock();
        for (BlockFace face : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            if (com.multiverse.programming.peripheral.MapDisplay.hasMapFor(computerBlock.getRelative(face).getLocation())) {
                return true;
            }
        }
        return false;
    }

    /** Stops every live map or radar drawn by a cartographer touching this computer. */
    public static void removeLiveMaps(Location computerLoc) {
        if (computerLoc == null || computerLoc.getWorld() == null) return;
        Block computerBlock = computerLoc.getBlock();
        for (BlockFace face : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            com.multiverse.programming.peripheral.MapDisplay.removeForCartographer(computerBlock.getRelative(face).getLocation());
        }
    }

    private void pressAdvanced(Player player, Inventory inv, Location loc) {
        Location blockLoc = toBlockLocation(loc);
        RunningEntry running = runningPrograms.get(blockLoc);
        if (running != null && running.program().isRunning()) {
            stopEntry(blockLoc, running);
            removeLiveMaps(blockLoc);
            player.closeInventory();
            player.sendMessage(plugin.getPrefix() + " §7Program stopped.");
            return;
        }
        if (hasLiveMaps(blockLoc)) {
            removeLiveMaps(blockLoc);
            player.closeInventory();
            player.sendMessage(plugin.getPrefix() + " §7Live map/radar stopped.");
            return;
        }

        ItemStack disk = inv.getItem(ComputerGUI.DISK_SLOT);
        if (disk == null || disk.getType().isAir()) {
            player.sendMessage(plugin.getPrefix() + " §cThere is no floppy disk in the slot.");
            return;
        }

        String code = DiskManager.readProgram(disk);
        String error = LuaRunner.validate(code);
        if (error != null) {
            player.closeInventory();
            player.sendMessage(plugin.getPrefix() + " §cError in the code:");
            for (String line : error.split("\n")) {
                player.sendMessage(" §4✘ " + line);
            }
            return;
        }

        player.closeInventory();
        player.sendMessage(plugin.getPrefix() + " §7Program started.");

        UUID uuid = player.getUniqueId();
        Consumer<String> onLine = line -> Bukkit.getScheduler().runTask(plugin, () -> {
            Player target = Bukkit.getPlayer(uuid);
            if (target != null && target.isOnline()) {
                target.sendMessage("§f" + org.bukkit.ChatColor.translateAlternateColorCodes('&', line));
            }
        });

        int maxLines = plugin.getConfigManager().getMaxStreamLines();
        LuaRunner.LuaProgram program = LuaRunner.runStreaming(plugin, blockLoc, true, code, plugin.getAdvancedTimeoutMs(), maxLines, onLine);

        BukkitTask[] cleanup = new BukkitTask[1];
        cleanup[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!program.isRunning()) {
                RunningEntry entry = runningPrograms.get(blockLoc);
                if (entry != null && entry.program() == program) {
                    runningPrograms.remove(blockLoc);
                }
                cleanupPeripheralsAround(blockLoc);
                cleanup[0].cancel();
            }
        }, 1L, 20L);
        runningPrograms.put(blockLoc, new RunningEntry(uuid, program, cleanup[0]));
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChat(org.bukkit.event.player.AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();
        for (com.multiverse.programming.peripheral.NpcPeripheral npc : com.multiverse.programming.peripheral.NpcPeripheral.getActiveNpcs()) {
            Location loc = npc.getLocation();
            org.bukkit.World npcWorld = com.multiverse.programming.peripheral.NpcPeripheral.worldOf(loc);
            if (npcWorld != null && npcWorld.equals(player.getWorld())) {
                if (loc.distanceSquared(player.getLocation()) <= 256.0) {
                    npc.recordPlayerResponse(player.getName(), message);
                }
            }
        }
    }

    // =========================================================================
    // NPC Chatbot & Quest Interposer - Disable Vanilla Functionality
    // =========================================================================

    public boolean isNpcBlock(Block block) {
        if (block == null) return false;
        Material npcMat = plugin.getConfigManager() != null ? plugin.getConfigManager().getNpcBlock() : Material.SCULK_CATALYST;
        if (block.getType() != npcMat) return false;
        if (com.multiverse.programming.peripheral.NpcPeripheral.hasNpcAt(block.getLocation())) {
            return true;
        }
        for (BlockFace face : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST}) {
            Block adj = block.getRelative(face);
            if (adj != null && isComputer(adj)) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteractNpc(org.bukkit.event.player.PlayerInteractEntityEvent event) {
        org.bukkit.entity.Entity entity = event.getRightClicked();
        if (entity != null && entity.getScoreboardTags().contains(com.multiverse.programming.peripheral.NpcPeripheral.NPC_TAG)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteractAtNpc(org.bukkit.event.player.PlayerInteractAtEntityEvent event) {
        org.bukkit.entity.Entity entity = event.getRightClicked();
        if (entity != null && entity.getScoreboardTags().contains(com.multiverse.programming.peripheral.NpcPeripheral.NPC_TAG)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNpcDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        org.bukkit.entity.Entity entity = event.getEntity();
        if (entity != null && entity.getScoreboardTags().contains(com.multiverse.programming.peripheral.NpcPeripheral.NPC_TAG)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNpcTransform(org.bukkit.event.entity.EntityTransformEvent event) {
        org.bukkit.entity.Entity entity = event.getEntity();
        if (entity != null && entity.getScoreboardTags().contains(com.multiverse.programming.peripheral.NpcPeripheral.NPC_TAG)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNpcTarget(org.bukkit.event.entity.EntityTargetLivingEntityEvent event) {
        if (event.getTarget() != null && event.getTarget().getScoreboardTags().contains(com.multiverse.programming.peripheral.NpcPeripheral.NPC_TAG)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVillagerAcquireTrade(org.bukkit.event.entity.VillagerAcquireTradeEvent event) {
        org.bukkit.entity.Entity entity = event.getEntity();
        if (entity != null && entity.getScoreboardTags().contains(com.multiverse.programming.peripheral.NpcPeripheral.NPC_TAG)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVillagerCareerChange(org.bukkit.event.entity.VillagerCareerChangeEvent event) {
        org.bukkit.entity.Entity entity = event.getEntity();
        if (entity != null && entity.getScoreboardTags().contains(com.multiverse.programming.peripheral.NpcPeripheral.NPC_TAG)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSculkBloom(org.bukkit.event.block.SculkBloomEvent event) {
        Block block = event.getBlock();
        if (isNpcBlock(block)) {
            event.setCancelled(true);
        }
    }
}