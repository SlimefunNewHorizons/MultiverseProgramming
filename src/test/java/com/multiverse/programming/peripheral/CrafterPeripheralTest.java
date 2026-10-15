// SPDX-License-Identifier: GPL-3.0-or-later
package com.multiverse.programming.peripheral;

import com.multiverse.programming.BukkitMockHelper;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Crafter;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.LuaValue;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CrafterPeripheralTest {

    private JavaPlugin plugin;
    private Location location;
    private Block block;
    private Crafter crafterBlock;
    private Inventory inventory;
    private CrafterPeripheral crafterPeripheral;

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

        crafterBlock = mock(Crafter.class);
        when(block.getState()).thenReturn(crafterBlock);
        when(crafterBlock.getWorld()).thenReturn(world);
        when(crafterBlock.getLocation()).thenReturn(location);

        inventory = mock(Inventory.class);
        when(crafterBlock.getInventory()).thenReturn(inventory);

        crafterPeripheral = new CrafterPeripheral(plugin, location);
    }

    @AfterEach
    void tearDown() {
        BukkitMockHelper.tearDownMockServer();
    }

    @Test
    @DisplayName("Crafter reports type as crafter")
    void testType() {
        assertEquals("crafter", crafterPeripheral.getType());
    }

    @Test
    @DisplayName("Crafter Lua methods query and set slot disabled status")
    void testSlots() {
        when(crafterBlock.isSlotDisabled(0)).thenReturn(false);
        when(crafterBlock.isSlotDisabled(1)).thenReturn(true);

        LuaValue table = crafterPeripheral.toLuaTable();

        // 1-based indexing in Lua
        assertFalse(table.get("isSlotDisabled").call(LuaValue.valueOf(1)).toboolean());
        assertTrue(table.get("isSlotDisabled").call(LuaValue.valueOf(2)).toboolean());

        // setSlotDisabled
        table.get("setSlotDisabled").call(LuaValue.valueOf(1), LuaValue.valueOf(true));
        verify(crafterBlock).setSlotDisabled(0, true);
    }

    @Test
    @DisplayName("Crafter Lua method isCrafting queries crafting ticks")
    void testIsCrafting() {
        when(crafterBlock.getCraftingTicks()).thenReturn(0).thenReturn(5);
        LuaValue table = crafterPeripheral.toLuaTable();

        assertFalse(table.get("isCrafting").call().toboolean());
        assertTrue(table.get("isCrafting").call().toboolean());
    }

    @Test
    @DisplayName("craft keeps remainders and never writes back the pre-craft snapshot")
    void testCraftUsesResultingMatrix() {
        org.bukkit.inventory.ItemStack milk = mock(org.bukkit.inventory.ItemStack.class);
        when(milk.getType()).thenReturn(org.bukkit.Material.MILK_BUCKET);
        when(inventory.getItem(0)).thenReturn(milk);

        org.bukkit.inventory.ItemStack cake = mock(org.bukkit.inventory.ItemStack.class);
        when(cake.getType()).thenReturn(org.bukkit.Material.CAKE);
        when(cake.getAmount()).thenReturn(1);
        when(cake.clone()).thenReturn(cake);
        org.bukkit.inventory.ItemStack bucket = mock(org.bukkit.inventory.ItemStack.class);
        when(bucket.getType()).thenReturn(org.bukkit.Material.BUCKET);
        org.bukkit.inventory.ItemStack[] remaining = new org.bukkit.inventory.ItemStack[9];
        remaining[0] = bucket;

        org.bukkit.inventory.ItemCraftResult craftResult = mock(org.bukkit.inventory.ItemCraftResult.class);
        when(craftResult.getResult()).thenReturn(cake);
        when(craftResult.getResultingMatrix()).thenReturn(remaining);
        when(org.bukkit.Bukkit.getServer().craftItemResult(any(org.bukkit.inventory.ItemStack[].class), any(World.class)))
                .thenReturn(craftResult);

        when(crafterBlock.getBlock()).thenReturn(block);
        when(location.clone()).thenReturn(location);
        when(location.add(anyDouble(), anyDouble(), anyDouble())).thenReturn(location);

        assertTrue(crafterPeripheral.toLuaTable().get("craft").call().toboolean());
        verify(inventory).setItem(0, bucket);
        verify(inventory).setItem(1, null);
        verify(crafterBlock, never()).update(anyBoolean());
    }
}
