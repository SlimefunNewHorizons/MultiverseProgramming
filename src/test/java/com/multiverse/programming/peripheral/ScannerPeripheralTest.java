// SPDX-License-Identifier: GPL-3.0-or-later
package com.multiverse.programming.peripheral;

import com.multiverse.programming.BukkitMockHelper;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.*;

class ScannerPeripheralTest {

    private JavaPlugin plugin;
    private World world;
    private Location location;
    private ScannerPeripheral scanner;

    @BeforeEach
    void setUp() {
        BukkitMockHelper.setUpMockServer();
        plugin = mock(JavaPlugin.class);
        when(plugin.isEnabled()).thenReturn(true);

        world = mock(World.class);
        location = new Location(world, 10, 64, 10);
        scanner = new ScannerPeripheral(plugin, location);
    }

    @AfterEach
    void tearDown() {
        BukkitMockHelper.tearDownMockServer();
    }

    @Test
    @DisplayName("Scanner peripheral reports type and location")
    void testBasicProperties() {
        assertEquals("scanner", scanner.getType());
        assertEquals(location, scanner.getLocation());
    }

    @Test
    @DisplayName("scanEntities returns formatted entity list")
    void testScanEntities() {
        Player mockPlayer = mock(Player.class);
        when(mockPlayer.getType()).thenReturn(org.bukkit.entity.EntityType.PLAYER);
        when(mockPlayer.getName()).thenReturn("Steve");
        when(mockPlayer.getUniqueId()).thenReturn(UUID.randomUUID());
        when(mockPlayer.getLocation()).thenReturn(new Location(world, 12, 64, 10));
        when(mockPlayer.getHealth()).thenReturn(20.0);
        when(mockPlayer.getMaxHealth()).thenReturn(20.0);

        when(world.getNearbyEntities(eq(location), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of(mockPlayer));

        LuaTable table = scanner.toLuaTable().checktable();
        LuaValue scanEntitiesFunc = table.get("scanEntities");
        LuaValue res = scanEntitiesFunc.call(LuaValue.valueOf(16));

        assertTrue(res.istable());
        LuaTable resTable = res.checktable();
        assertEquals(1, resTable.length());

        LuaTable first = resTable.get(1).checktable();
        assertEquals("Steve", first.get("name").tojstring());
        assertTrue(first.get("isPlayer").toboolean());
        assertEquals(20.0, first.get("health").todouble());
    }

    @Test
    @DisplayName("inspect rejects far targets and skips unloaded chunks")
    void testInspectLimits() {
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        LuaTable table = scanner.toLuaTable().checktable();
        LuaValue inspect = table.get("inspect");

        assertThrows(org.luaj.vm2.LuaError.class,
                () -> inspect.call(LuaValue.valueOf(10_000_000), LuaValue.valueOf(64), LuaValue.valueOf(10)));

        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
        assertTrue(inspect.call(LuaValue.valueOf(2), LuaValue.valueOf(0), LuaValue.valueOf(0)).isnil());
        verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());

        Block stone = mock(Block.class);
        when(stone.getType()).thenReturn(Material.STONE);
        org.bukkit.block.data.BlockData data = mock(org.bukkit.block.data.BlockData.class);
        when(data.getAsString()).thenReturn("minecraft:stone");
        when(stone.getBlockData()).thenReturn(data);
        when(world.getBlockAt(12, 64, 10)).thenReturn(stone);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        LuaValue info = inspect.call(LuaValue.valueOf(2), LuaValue.valueOf(0), LuaValue.valueOf(0));
        assertEquals("STONE", info.get("material").tojstring());
        assertEquals(12, info.get("x").toint());
        assertEquals(2, info.get("dx").toint());
    }

    @Test
    @DisplayName("scanBlocks filters by material")
    void testScanBlocks() {
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);

        Block diamondBlock = mock(Block.class);
        when(diamondBlock.getType()).thenReturn(Material.DIAMOND_ORE);

        Block stoneBlock = mock(Block.class);
        when(stoneBlock.getType()).thenReturn(Material.STONE);

        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(stoneBlock);
        when(world.getBlockAt(10, 64, 11)).thenReturn(diamondBlock);

        LuaTable table = scanner.toLuaTable().checktable();
        LuaValue scanBlocksFunc = table.get("scanBlocks");
        LuaValue res = scanBlocksFunc.call(LuaValue.valueOf(2), LuaValue.valueOf("DIAMOND"));

        assertTrue(res.istable());
        LuaTable resTable = res.checktable();
        assertTrue(resTable.length() >= 1);
        assertEquals("DIAMOND_ORE", resTable.get(1).get("material").tojstring());
    }
}
