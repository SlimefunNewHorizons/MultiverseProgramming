// SPDX-License-Identifier: GPL-3.0-or-later
package com.multiverse.programming.peripheral;

import com.multiverse.programming.BukkitMockHelper;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FarmerPeripheralTest {

    private JavaPlugin plugin;
    private World world;
    private Location location;
    private FarmerPeripheral farmer;

    @BeforeEach
    void setUp() {
        BukkitMockHelper.setUpMockServer();
        plugin = mock(JavaPlugin.class);
        when(plugin.isEnabled()).thenReturn(true);

        world = mock(World.class);
        location = new Location(world, 0, 64, 0);
        farmer = new FarmerPeripheral(plugin, location);
    }

    @AfterEach
    void tearDown() {
        BukkitMockHelper.tearDownMockServer();
    }

    @Test
    @DisplayName("Farmer peripheral reports type and location")
    void testBasicProperties() {
        assertEquals("farmer", farmer.getType());
        assertEquals(location, farmer.getLocation());
    }

    @Test
    @DisplayName("inspectCrop inspects age and maturity of crops")
    void testInspectCrop() {
        Block cropBlock = mock(Block.class);
        when(cropBlock.getType()).thenReturn(Material.WHEAT);
        Ageable ageable = mock(Ageable.class);
        when(ageable.getAge()).thenReturn(7);
        when(ageable.getMaximumAge()).thenReturn(7);
        when(cropBlock.getBlockData()).thenReturn(ageable);

        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getBlockAt(0, 63, 0)).thenReturn(cropBlock);

        LuaTable table = farmer.toLuaTable().checktable();
        LuaValue res = table.get("inspectCrop").call(LuaValue.valueOf(0), LuaValue.valueOf(-1), LuaValue.valueOf(0));

        assertTrue(res.istable());
        LuaTable info = res.checktable();
        assertTrue(info.get("isCrop").toboolean());
        assertTrue(info.get("mature").toboolean());
        assertEquals(7, info.get("age").toint());
        assertEquals(7, info.get("maxAge").toint());
    }

    @Test
    @DisplayName("harvest and inspect refuse far or unloaded targets")
    void testRangeLimits() {
        LuaTable table = farmer.toLuaTable().checktable();
        assertThrows(org.luaj.vm2.LuaError.class, () -> table.get("harvest").invoke(LuaValue.varargsOf(new LuaValue[]{
                LuaValue.valueOf(5000), LuaValue.valueOf(64), LuaValue.valueOf(0)})));
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
        assertThrows(org.luaj.vm2.LuaError.class, () -> table.get("inspectCrop").invoke(LuaValue.varargsOf(new LuaValue[]{
                LuaValue.valueOf(1), LuaValue.valueOf(0), LuaValue.valueOf(1)})));
        verify(world, never()).getBlockAt(1, 64, 1);
    }

    @Test
    @DisplayName("harvest skips stems and sugar cane bases")
    void testHarvestOnlyRealCrops() {
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        LuaTable table = farmer.toLuaTable().checktable();

        Block stem = mock(Block.class);
        when(stem.getType()).thenReturn(Material.MELON_STEM);
        Ageable stemAge = mock(Ageable.class);
        when(stemAge.getAge()).thenReturn(7);
        when(stemAge.getMaximumAge()).thenReturn(7);
        when(stem.getBlockData()).thenReturn(stemAge);
        when(world.getBlockAt(1, 64, 0)).thenReturn(stem);
        assertFalse(table.get("harvest").invoke(LuaValue.varargsOf(new LuaValue[]{
                LuaValue.valueOf(1), LuaValue.valueOf(0), LuaValue.valueOf(0)})).arg1().toboolean());

        Block caneBase = mock(Block.class);
        Block dirt = mock(Block.class);
        when(dirt.getType()).thenReturn(Material.DIRT);
        when(caneBase.getType()).thenReturn(Material.SUGAR_CANE);
        when(caneBase.getRelative(org.bukkit.block.BlockFace.DOWN)).thenReturn(dirt);
        when(world.getBlockAt(2, 64, 0)).thenReturn(caneBase);
        assertFalse(table.get("harvest").invoke(LuaValue.varargsOf(new LuaValue[]{
                LuaValue.valueOf(2), LuaValue.valueOf(0), LuaValue.valueOf(0)})).arg1().toboolean());

        verify(stem, never()).setType(any());
        verify(caneBase, never()).setType(any());
    }
}
