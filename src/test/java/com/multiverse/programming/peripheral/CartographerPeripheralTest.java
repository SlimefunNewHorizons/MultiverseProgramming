// SPDX-License-Identifier: GPL-3.0-or-later
package com.multiverse.programming.peripheral;

import com.multiverse.programming.BukkitMockHelper;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class CartographerPeripheralTest {

    private JavaPlugin plugin;
    private World world;
    private Location location;
    private CartographerPeripheral cartographer;

    @BeforeEach
    void setUp() {
        BukkitMockHelper.setUpMockServer();
        plugin = mock(JavaPlugin.class);
        when(plugin.isEnabled()).thenReturn(true);

        world = mock(World.class);
        location = new Location(world, 0, 64, 0);
        cartographer = new CartographerPeripheral(plugin, location);
    }

    @AfterEach
    void tearDown() {
        BukkitMockHelper.tearDownMockServer();
    }

    @Test
    @DisplayName("Cartographer peripheral reports type and location")
    void testBasicProperties() {
        assertEquals("cartographer", cartographer.getType());
        assertEquals(location, cartographer.getLocation());
    }

    @Test
    @DisplayName("getBiome returns nil when world is null")
    void testGetBiomeNullWorld() {
        Location noWorldLoc = new Location(null, 0, 0, 0);
        CartographerPeripheral p = new CartographerPeripheral(plugin, noWorldLoc);
        LuaTable table = p.toLuaTable().checktable();
        assertTrue(table.get("getBiome").call().isnil());
    }

    @Test
    @DisplayName("scanTopography returns radius and height points")
    void testScanTopography() {
        when(world.getHighestBlockYAt(anyInt(), anyInt())).thenReturn(64);
        Block topBlock = mock(Block.class);
        when(topBlock.getType()).thenReturn(Material.GRASS_BLOCK);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(topBlock);

        LuaTable table = cartographer.toLuaTable().checktable();
        LuaValue res = table.get("scanTopography").call(LuaValue.valueOf(2));

        assertTrue(res.istable());
        LuaTable resTable = res.checktable();
        assertEquals(2, resTable.get("radius").toint());
        assertTrue(resTable.get("points").istable());
        assertTrue(resTable.get("points").checktable().length() > 0);
    }

    @Test
    @DisplayName("map radius defaults to the full 128x128 map and is clamped")
    void testMapRadius() {
        assertEquals(MapDisplay.MAX_RADIUS, CartographerPeripheral.mapRadius(LuaValue.NONE, 1));
        assertEquals(MapDisplay.MIN_RADIUS, CartographerPeripheral.mapRadius(LuaValue.valueOf(2), 1));
        assertEquals(MapDisplay.MAX_RADIUS, CartographerPeripheral.mapRadius(LuaValue.valueOf(500), 1));
        assertEquals(20, CartographerPeripheral.mapRadius(LuaValue.varargsOf(LuaValue.valueOf("north"), LuaValue.valueOf(20)), 2));
    }

    @Test
    @DisplayName("radar needs a scanner connected to the same computer")
    void testRadarNeedsScanner() {
        LuaTable table = cartographer.toLuaTable().checktable();
        org.luaj.vm2.Varargs res = table.get("radar").invoke(LuaValue.NONE);
        assertFalse(res.arg1().toboolean());
        assertTrue(res.arg(2).tojstring().contains("scanner"));

        cartographer.setScanner(new ScannerPeripheral(plugin, new Location(world, 1, 64, 0)));
        assertTrue(table.get("radar").invoke(LuaValue.valueOf(32)).arg1().toboolean());
        assertTrue(table.get("clear").call().toboolean());
    }

    @Test
    @DisplayName("createMap fails without an empty map or paper nearby")
    void testCreateMapNeedsBlank() {
        Block self = mock(Block.class);
        when(world.getBlockAt(any(Location.class))).thenReturn(self);

        LuaTable table = cartographer.toLuaTable().checktable();
        org.luaj.vm2.Varargs res = table.get("createMap").invoke(LuaValue.NONE);
        assertFalse(res.arg1().toboolean());
    }

    @Test
    @DisplayName("consumeBlankMap takes one paper from an adjacent container")
    void testConsumeBlankMap() {
        Block self = mock(Block.class);
        when(world.getBlockAt(any(Location.class))).thenReturn(self);
        Block chestBlock = mock(Block.class);
        when(self.getRelative(org.bukkit.block.BlockFace.NORTH)).thenReturn(chestBlock);
        org.bukkit.block.Chest chest = mock(org.bukkit.block.Chest.class);
        when(chestBlock.getState()).thenReturn(chest);
        org.bukkit.inventory.Inventory inv = mock(org.bukkit.inventory.Inventory.class);
        when(chest.getInventory()).thenReturn(inv);
        when(inv.first(Material.MAP)).thenReturn(-1);
        when(inv.first(Material.PAPER)).thenReturn(3);
        org.bukkit.inventory.ItemStack paper = mock(org.bukkit.inventory.ItemStack.class);
        when(paper.getAmount()).thenReturn(5);
        when(inv.getItem(3)).thenReturn(paper);

        assertTrue(cartographer.consumeBlankMap());
        verify(paper).setAmount(4);
        verify(inv).setItem(3, paper);
    }

    @Test
    @DisplayName("renderToMonitor refuses a side without a monitor block")
    void testRenderToMonitorNeedsMonitor() {
        Block self = mock(Block.class);
        when(world.getBlockAt(any(Location.class))).thenReturn(self);
        Block stone = mock(Block.class);
        when(stone.getType()).thenReturn(Material.STONE);
        when(self.getRelative(org.bukkit.block.BlockFace.NORTH)).thenReturn(stone);

        LuaTable table = cartographer.toLuaTable().checktable();
        assertFalse(table.get("renderToMonitor").call(LuaValue.valueOf("north")).toboolean());
    }

    @Test
    @DisplayName("setHologram, project, and clear functions succeed")
    void testHologramAndClear() {
        when(world.getHighestBlockYAt(anyInt(), anyInt())).thenReturn(64);
        Block topBlock = mock(Block.class);
        when(topBlock.getType()).thenReturn(Material.GRASS_BLOCK);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(topBlock);

        LuaTable table = cartographer.toLuaTable().checktable();
        assertTrue(table.get("setHologram").call(LuaValue.valueOf("Test Carto")).toboolean());
        assertTrue(table.get("project").call(LuaValue.valueOf(2)).arg1().toboolean());
        assertTrue(table.get("render").call(LuaValue.valueOf(2)).arg1().toboolean());
        assertTrue(table.get("clear").call().toboolean());
        assertTrue(table.get("clearHologram").call().toboolean());
        assertTrue(table.get("remove").call().toboolean());
    }
}
