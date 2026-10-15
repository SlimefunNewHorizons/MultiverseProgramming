// SPDX-License-Identifier: GPL-3.0-or-later
package com.multiverse.programming.peripheral;

import com.multiverse.programming.BukkitMockHelper;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.LuaValue;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransposerPeripheralTest {

    private JavaPlugin plugin;
    private Location location;
    private Block block;
    private TransposerPeripheral transposer;

    @BeforeEach
    void setUp() {
        BukkitMockHelper.setUpMockServer();
        plugin = mock(JavaPlugin.class);
        when(plugin.isEnabled()).thenReturn(true);

        World world = mock(World.class);
        location = mock(Location.class);
        when(location.getWorld()).thenReturn(world);

        block = mock(Block.class);
        when(location.getBlock()).thenReturn(block);

        transposer = new TransposerPeripheral(plugin, location);
    }

    @AfterEach
    void tearDown() {
        BukkitMockHelper.tearDownMockServer();
    }

    @Test
    @DisplayName("parseFace parses valid direction strings and throws on invalid")
    void testParseFace() {
        assertEquals(BlockFace.NORTH, TransposerPeripheral.parseFace("north"));
        assertEquals(BlockFace.SOUTH, TransposerPeripheral.parseFace("south"));
        assertEquals(BlockFace.EAST, TransposerPeripheral.parseFace("east"));
        assertEquals(BlockFace.WEST, TransposerPeripheral.parseFace("west"));
        assertEquals(BlockFace.UP, TransposerPeripheral.parseFace("up"));
        assertEquals(BlockFace.UP, TransposerPeripheral.parseFace("top"));
        assertEquals(BlockFace.DOWN, TransposerPeripheral.parseFace("down"));
        assertEquals(BlockFace.DOWN, TransposerPeripheral.parseFace("bottom"));

        assertThrows(Throwable.class, () -> TransposerPeripheral.parseFace("invalid_dir"));
    }

    @Test
    @DisplayName("Transposer queries slot count and items in adjacent container")
    void testInventoryInspection() {
        Block northBlock = mock(Block.class);
        Chest chest = mock(Chest.class);
        Inventory chestInv = mock(Inventory.class);

        when(block.getRelative(BlockFace.NORTH)).thenReturn(northBlock);
        when(northBlock.getState()).thenReturn(chest);
        when(chest.getInventory()).thenReturn(chestInv);
        when(chestInv.getSize()).thenReturn(27);

        ItemStack diamondStack = mock(ItemStack.class);
        when(diamondStack.getType()).thenReturn(Material.DIAMOND);
        when(diamondStack.getAmount()).thenReturn(16);
        when(diamondStack.getMaxStackSize()).thenReturn(64);
        when(chestInv.getItem(0)).thenReturn(diamondStack);

        LuaValue table = transposer.toLuaTable();

        // Slot count
        assertEquals(27, table.get("getSlotCount").call(LuaValue.valueOf("north")).toint());

        // Item detail at slot 1 (1-based in Lua)
        LuaValue item = table.get("getItem").call(LuaValue.valueOf("north"), LuaValue.valueOf(1));
        assertFalse(item.isnil());
        assertEquals("DIAMOND", item.get("name").tojstring());
        assertEquals(16, item.get("count").toint());
        assertEquals(64, item.get("maxStack").toint());
    }

    @Test
    @DisplayName("Transposer refuses shulker boxes into shulker boxes")
    void testNoNestedShulkers() {
        org.bukkit.inventory.Inventory from = mock(org.bukkit.inventory.Inventory.class);
        org.bukkit.inventory.Inventory shulkerInv = mock(org.bukkit.inventory.Inventory.class);
        when(shulkerInv.getType()).thenReturn(org.bukkit.event.inventory.InventoryType.SHULKER_BOX);
        org.bukkit.inventory.ItemStack box = mock(org.bukkit.inventory.ItemStack.class);
        when(box.getType()).thenReturn(org.bukkit.Material.RED_SHULKER_BOX);
        assertFalse(TransposerPeripheral.isTransferAllowed(from, box, shulkerInv));

        org.bukkit.inventory.Inventory chest = mock(org.bukkit.inventory.Inventory.class);
        when(chest.getType()).thenReturn(org.bukkit.event.inventory.InventoryType.CHEST);
        assertTrue(TransposerPeripheral.isTransferAllowed(from, box, chest));
    }
}
