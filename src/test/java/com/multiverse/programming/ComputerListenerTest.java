// SPDX-License-Identifier: GPL-3.0-or-later
package com.multiverse.programming;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

class ComputerListenerTest {

    private MultiverseProgrammingPlugin plugin;
    private ConfigManager configManager;
    private ComputerListener listener;

    @BeforeEach
    void setUp() {
        BukkitMockHelper.setUpMockServer();
        plugin = mock(MultiverseProgrammingPlugin.class);
        configManager = mock(ConfigManager.class);
        when(plugin.getConfigManager()).thenReturn(configManager);
        when(plugin.getPrefix()).thenReturn("§8[§bComputer§8]");
        when(configManager.getComputerBlock()).thenReturn(Material.LECTERN);
        when(configManager.getAdvancedComputerBlock()).thenReturn(Material.ENCHANTING_TABLE);
        when(configManager.isDropDisksOnBreak()).thenReturn(true);
        when(configManager.getPreventSpamDelayMs()).thenReturn(500L);
        when(configManager.isEnableComputer()).thenReturn(true);
        when(configManager.isEnableAdvancedComputer()).thenReturn(true);

        listener = new ComputerListener(plugin, Material.LECTERN, Material.ENCHANTING_TABLE);
    }

    @AfterEach
    void tearDown() {
        ComputerListener.cancelAll();
        com.multiverse.programming.peripheral.MapDisplay.removeAll();
        BukkitMockHelper.tearDownMockServer();
    }

    /** Advanced computer block at {@code loc} with a cartographer touching it on the east side. */
    private Block advancedComputer(World world, Location loc, Location cartographerLoc) {
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.ENCHANTING_TABLE);
        when(block.getLocation()).thenReturn(loc);
        when(block.getWorld()).thenReturn(world);
        org.bukkit.block.TileState state = mock(org.bukkit.block.TileState.class);
        org.bukkit.persistence.PersistentDataContainer pdc = BukkitMockHelper.createMockPDC();
        pdc.set(DiskManager.KEY_ID, org.bukkit.persistence.PersistentDataType.STRING, DiskManager.ID_ADVANCED_COMPUTER);
        when(state.getPersistentDataContainer()).thenReturn(pdc);
        when(block.getState()).thenReturn(state);
        Block other = mock(Block.class);
        when(other.getLocation()).thenReturn(new Location(world, 50, 50, 50));
        Block cartographer = mock(Block.class);
        when(cartographer.getLocation()).thenReturn(cartographerLoc);
        when(block.getRelative(any(org.bukkit.block.BlockFace.class))).thenReturn(other);
        when(block.getRelative(org.bukkit.block.BlockFace.EAST)).thenReturn(cartographer);
        when(world.getBlockAt(any(Location.class))).thenReturn(block);
        return block;
    }

    /** Inventory mock that remembers its items, returned by Bukkit.createInventory. */
    private Inventory capturingInventory() {
        Inventory inv = mock(Inventory.class);
        ItemStack[] slots = new ItemStack[9];
        doAnswer(i -> {
            slots[(int) i.getArgument(0)] = i.getArgument(1);
            return null;
        }).when(inv).setItem(anyInt(), any());
        when(inv.getItem(anyInt())).thenAnswer(i -> slots[(int) i.getArgument(0)]);
        when(org.bukkit.Bukkit.getServer().createInventory(any(), anyInt(), anyString())).thenReturn(inv);
        return inv;
    }

    private PlayerInteractEvent rightClick(Player player, Block block) {
        return new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null, block, org.bukkit.block.BlockFace.UP, EquipmentSlot.HAND);
    }

    @Test
    @DisplayName("Advanced computer GUI shows Run when idle and Stop while a live radar is active")
    void testAdvancedGuiStopButtonForLiveRadar() {
        World world = mock(World.class);
        Location loc = new Location(world, 0, 64, 0);
        Location cartographerLoc = new Location(world, 1, 64, 0);
        Block computer = advancedComputer(world, loc, cartographerLoc);
        Inventory inv = capturingInventory();
        Player player = mock(Player.class);
        when(player.hasPermission("multiverseprogramming.use")).thenReturn(true);
        when(player.getUniqueId()).thenReturn(java.util.UUID.randomUUID());

        listener.onBlockUse(rightClick(player, computer));
        assertEquals(Material.EMERALD, inv.getItem(ComputerGUI.BUTTON_SLOT).getType(), "idle: Run button");

        com.multiverse.programming.peripheral.MapDisplay.show(plugin, cartographerLoc, cartographerLoc, 32, true, () -> true);
        listener.onBlockUse(rightClick(player, computer));
        verify(player, times(2)).openInventory(inv);
        assertEquals(Material.REDSTONE_BLOCK, inv.getItem(ComputerGUI.BUTTON_SLOT).getType(), "radar active: Stop button");

        // Clicking Stop removes the radar even though no Lua program is running
        InventoryView view = mock(InventoryView.class);
        when(view.getPlayer()).thenReturn(player);
        when(view.getTitle()).thenReturn(ComputerGUI.ADVANCED_TITLE);
        when(view.getTopInventory()).thenReturn(inv);
        InventoryClickEvent click = new InventoryClickEvent(view, org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,
                ComputerGUI.BUTTON_SLOT, ClickType.LEFT, org.bukkit.event.inventory.InventoryAction.PICKUP_ALL);
        listener.onInventoryClick(click);
        verify(player).sendMessage(contains("radar stopped"));
        assertFalse(ComputerListener.hasLiveMaps(loc));
    }

    @Test
    @DisplayName("onBlockUse checks multiverseprogramming.use permission")
    void testBlockUsePermissionDenied() {
        Player player = mock(Player.class);
        when(player.hasPermission("multiverseprogramming.use")).thenReturn(false);

        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.LECTERN);
        org.bukkit.block.TileState state = mock(org.bukkit.block.TileState.class);
        org.bukkit.persistence.PersistentDataContainer pdc = BukkitMockHelper.createMockPDC();
        pdc.set(DiskManager.KEY_ID, org.bukkit.persistence.PersistentDataType.STRING, DiskManager.ID_COMPUTER);
        when(state.getPersistentDataContainer()).thenReturn(pdc);
        when(block.getState()).thenReturn(state);

        PlayerInteractEvent event = new PlayerInteractEvent(
                player,
                Action.RIGHT_CLICK_BLOCK,
                null,
                block,
                org.bukkit.block.BlockFace.UP,
                EquipmentSlot.HAND
        );

        listener.onBlockUse(event);
        assertTrue(event.isCancelled());
        verify(player).sendMessage(contains("permission"));
        verify(player, never()).openInventory(any(Inventory.class));
    }

    @Test
    @DisplayName("Breaking advanced computer drops stored floppy disk naturally")
    void testBlockBreakDropsDisk() {
        Block block = mock(Block.class);
        World world = mock(World.class);
        Location loc = new Location(world, 10, 64, 10);
        when(block.getLocation()).thenReturn(loc);
        when(block.getWorld()).thenReturn(world);
        when(block.getType()).thenReturn(Material.ENCHANTING_TABLE);

        ItemStack disk = mock(ItemStack.class);
        when(disk.getType()).thenReturn(Material.WRITABLE_BOOK);

        ComputerListener.getAdvancedDisks().put(loc, disk);

        Player player = mock(Player.class);
        BlockBreakEvent event = new BlockBreakEvent(block, player);

        listener.onBlockBreak(event);

        // Verify disk was dropped in the world
        verify(world).dropItemNaturally(loc, disk);
        // Verify disk was removed from map to prevent memory leak
        assertFalse(ComputerListener.getAdvancedDisks().containsKey(loc));
    }

    @Test
    @DisplayName("Piston extending into computer block is cancelled")
    void testPistonExtendCancelled() {
        Block computerBlock = mock(Block.class);
        when(computerBlock.getType()).thenReturn(Material.LECTERN);
        org.bukkit.block.TileState state = mock(org.bukkit.block.TileState.class);
        org.bukkit.persistence.PersistentDataContainer pdc = BukkitMockHelper.createMockPDC();
        pdc.set(DiskManager.KEY_ID, org.bukkit.persistence.PersistentDataType.STRING, DiskManager.ID_COMPUTER);
        when(state.getPersistentDataContainer()).thenReturn(pdc);
        when(computerBlock.getState()).thenReturn(state);

        Block piston = mock(Block.class);
        BlockPistonExtendEvent event = new BlockPistonExtendEvent(piston, List.of(computerBlock), org.bukkit.block.BlockFace.NORTH);

        listener.onPistonExtend(event);
        assertTrue(event.isCancelled());
    }

    @Test
    @DisplayName("WorldUnload cleans up world locations to prevent memory leaks")
    void testWorldUnloadCleanup() {
        World world = mock(World.class);
        Location loc = new Location(world, 0, 60, 0);

        ItemStack disk = mock(ItemStack.class);
        when(disk.getType()).thenReturn(Material.WRITABLE_BOOK);
        ComputerListener.getAdvancedDisks().put(loc, disk);

        WorldUnloadEvent event = new WorldUnloadEvent(world);
        listener.onWorldUnload(event);

        assertFalse(ComputerListener.getAdvancedDisks().containsKey(loc));
    }

    @Test
    @DisplayName("SWAP_OFFHAND click is always cancelled in Computer GUI")
    void testSwapOffhandCancelled() {
        Player player = mock(Player.class);
        InventoryView view = mock(InventoryView.class);
        when(view.getPlayer()).thenReturn(player);
        when(view.getTitle()).thenReturn(ComputerGUI.TITLE);

        InventoryClickEvent event = new InventoryClickEvent(
                view,
                org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,
                0,
                ClickType.SWAP_OFFHAND,
                org.bukkit.event.inventory.InventoryAction.HOTBAR_SWAP
        );

        listener.onInventoryClick(event);
        assertTrue(event.isCancelled());
    }

    @Test
    @DisplayName("Vanilla interactions with NPC entity are cancelled")
    void testNpcVanillaInteractionsCancelled() {
        org.bukkit.entity.Entity npcEntity = mock(org.bukkit.entity.Entity.class);
        when(npcEntity.getScoreboardTags()).thenReturn(java.util.Set.of(com.multiverse.programming.peripheral.NpcPeripheral.NPC_TAG));

        org.bukkit.event.player.PlayerInteractEntityEvent interactEvent = mock(org.bukkit.event.player.PlayerInteractEntityEvent.class);
        when(interactEvent.getRightClicked()).thenReturn(npcEntity);
        listener.onPlayerInteractNpc(interactEvent);
        verify(interactEvent).setCancelled(true);

        org.bukkit.event.player.PlayerInteractAtEntityEvent interactAtEvent = mock(org.bukkit.event.player.PlayerInteractAtEntityEvent.class);
        when(interactAtEvent.getRightClicked()).thenReturn(npcEntity);
        listener.onPlayerInteractAtNpc(interactAtEvent);
        verify(interactAtEvent).setCancelled(true);

        org.bukkit.event.entity.EntityDamageEvent dmgEvent = mock(org.bukkit.event.entity.EntityDamageEvent.class);
        when(dmgEvent.getEntity()).thenReturn(npcEntity);
        listener.onNpcDamage(dmgEvent);
        verify(dmgEvent).setCancelled(true);

        org.bukkit.event.entity.EntityTransformEvent transEvent = mock(org.bukkit.event.entity.EntityTransformEvent.class);
        when(transEvent.getEntity()).thenReturn(npcEntity);
        listener.onNpcTransform(transEvent);
        verify(transEvent).setCancelled(true);
    }

    @Test
    @DisplayName("Sculk bloom is cancelled on NPC block adjacent to computer")
    void testSculkBloomCancelledOnNpcBlock() {
        when(configManager.getNpcBlock()).thenReturn(Material.SCULK_CATALYST);

        Block catalystBlock = mock(Block.class);
        when(catalystBlock.getType()).thenReturn(Material.SCULK_CATALYST);

        Block computer = mock(Block.class);
        when(computer.getType()).thenReturn(Material.LECTERN);
        org.bukkit.block.TileState cState = mock(org.bukkit.block.TileState.class);
        org.bukkit.persistence.PersistentDataContainer cPdc = BukkitMockHelper.createMockPDC();
        cPdc.set(DiskManager.KEY_ID, org.bukkit.persistence.PersistentDataType.STRING, DiskManager.ID_COMPUTER);
        when(cState.getPersistentDataContainer()).thenReturn(cPdc);
        when(computer.getState()).thenReturn(cState);
        when(catalystBlock.getRelative(org.bukkit.block.BlockFace.NORTH)).thenReturn(computer);

        org.bukkit.event.block.SculkBloomEvent bloomEvent = mock(org.bukkit.event.block.SculkBloomEvent.class);
        when(bloomEvent.getBlock()).thenReturn(catalystBlock);
        listener.onSculkBloom(bloomEvent);
        verify(bloomEvent).setCancelled(true);
    }

    @Test
    @DisplayName("onBlockUse does not intercept vanilla lectern without PDC")
    void testVanillaLecternNotIntercepted() {
        Player player = mock(Player.class);
        when(player.hasPermission("multiverseprogramming.use")).thenReturn(true);

        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.LECTERN);
        org.bukkit.World world = mock(org.bukkit.World.class);
        when(block.getLocation()).thenReturn(new Location(world, 10, 64, 10));
        org.bukkit.block.TileState state = mock(org.bukkit.block.TileState.class);
        org.bukkit.persistence.PersistentDataContainer pdc = BukkitMockHelper.createMockPDC();
        when(state.getPersistentDataContainer()).thenReturn(pdc);
        when(block.getState()).thenReturn(state);

        PlayerInteractEvent event = new PlayerInteractEvent(
                player,
                Action.RIGHT_CLICK_BLOCK,
                null,
                block,
                org.bukkit.block.BlockFace.UP,
                EquipmentSlot.HAND
        );

        listener.onBlockUse(event);

        assertFalse(event.isCancelled(), "Vanilla lectern must NOT be cancelled");
        verify(player, never()).openInventory(any(Inventory.class));
    }

    @Test
    @DisplayName("onBlockUse does not intercept vanilla enchanting table without PDC")
    void testVanillaEnchantingTableNotIntercepted() {
        Player player = mock(Player.class);
        when(player.hasPermission("multiverseprogramming.use")).thenReturn(true);

        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.ENCHANTING_TABLE);
        org.bukkit.World world = mock(org.bukkit.World.class);
        when(block.getLocation()).thenReturn(new Location(world, 20, 64, 20));
        org.bukkit.block.TileState state = mock(org.bukkit.block.TileState.class);
        org.bukkit.persistence.PersistentDataContainer pdc = BukkitMockHelper.createMockPDC();
        when(state.getPersistentDataContainer()).thenReturn(pdc);
        when(block.getState()).thenReturn(state);

        PlayerInteractEvent event = new PlayerInteractEvent(
                player,
                Action.RIGHT_CLICK_BLOCK,
                null,
                block,
                org.bukkit.block.BlockFace.UP,
                EquipmentSlot.HAND
        );

        listener.onBlockUse(event);

        assertFalse(event.isCancelled(), "Vanilla enchanting table must NOT be cancelled");
        verify(player, never()).openInventory(any(Inventory.class));
    }

    @Test
    @DisplayName("onBlockUse does not intercept Slimefun blocks")
    void testSlimefunBlockNotIntercepted() {
        Player player = mock(Player.class);
        when(player.hasPermission("multiverseprogramming.use")).thenReturn(true);

        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.ENCHANTING_TABLE);
        org.bukkit.block.TileState state = mock(org.bukkit.block.TileState.class);
        org.bukkit.persistence.PersistentDataContainer pdc = BukkitMockHelper.createMockPDC();
        pdc.set(new org.bukkit.NamespacedKey("slimefun", "slimefun_item"), org.bukkit.persistence.PersistentDataType.STRING, "AUTO_ENCHANTER");
        when(state.getPersistentDataContainer()).thenReturn(pdc);
        when(block.getState()).thenReturn(state);

        PlayerInteractEvent event = new PlayerInteractEvent(
                player,
                Action.RIGHT_CLICK_BLOCK,
                null,
                block,
                org.bukkit.block.BlockFace.UP,
                EquipmentSlot.HAND
        );

        listener.onBlockUse(event);

        assertFalse(event.isCancelled(), "Slimefun block must NOT be cancelled");
        verify(player, never()).openInventory(any(Inventory.class));
    }

    @Test
    @DisplayName("onBlockUse does not intercept MultiverseNets blocks")
    void testDoesNotInterceptMultiverseNetsBlocks() {
        Player player = mock(Player.class);
        when(player.hasPermission("multiverseprogramming.use")).thenReturn(true);

        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.ENCHANTING_TABLE);
        when(block.getX()).thenReturn(10);
        when(block.getY()).thenReturn(64);
        when(block.getZ()).thenReturn(10);

        org.bukkit.Chunk chunk = mock(org.bukkit.Chunk.class);
        when(chunk.isLoaded()).thenReturn(true);
        org.bukkit.persistence.PersistentDataContainer pdc = BukkitMockHelper.createMockPDC();
        pdc.set(new org.bukkit.NamespacedKey("multiversenets", "t10_64_10"), org.bukkit.persistence.PersistentDataType.STRING, "MVN_SF_ENCODER");
        when(chunk.getPersistentDataContainer()).thenReturn(pdc);
        when(block.getChunk()).thenReturn(chunk);

        PlayerInteractEvent event = new PlayerInteractEvent(
                player,
                Action.RIGHT_CLICK_BLOCK,
                null,
                block,
                org.bukkit.block.BlockFace.UP,
                EquipmentSlot.HAND
        );

        listener.onBlockUse(event);

        assertFalse(event.isCancelled());
        verify(player, never()).openInventory(any(Inventory.class));
    }

    @Test
    @DisplayName("onInventoryClick ignores non-computer holder inventories")
    void testIgnoresNonComputerHolderInventories() {
        Player player = mock(Player.class);
        Inventory top = mock(Inventory.class);
        when(top.getHolder()).thenReturn(mock(org.bukkit.inventory.InventoryHolder.class));

        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(top);
        when(view.getTitle()).thenReturn("Some Other Plugin GUI");

        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getView()).thenReturn(view);

        listener.onInventoryClick(event);
        verify(event, never()).setCancelled(true);
    }
}
