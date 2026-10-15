// SPDX-License-Identifier: GPL-3.0-or-later
package com.multiverse.programming.peripheral;

import com.multiverse.programming.BukkitMockHelper;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NpcPeripheralTest {

    private JavaPlugin plugin;
    private World world;
    private Location location;
    private NpcPeripheral npc;

    @BeforeEach
    void setUp() {
        BukkitMockHelper.setUpMockServer();
        plugin = mock(JavaPlugin.class);
        when(plugin.isEnabled()).thenReturn(true);

        world = mock(World.class);
        location = new Location(world, 0, 64, 0);
        npc = new NpcPeripheral(plugin, location);
    }

    @AfterEach
    void tearDown() {
        BukkitMockHelper.tearDownMockServer();
    }

    @Test
    @DisplayName("Npc peripheral reports type and location")
    void testBasicProperties() {
        assertEquals("npc", npc.getType());
        assertEquals(location, npc.getLocation());
    }

    @Test
    @DisplayName("Npc type resolution rejects non-living, unspawnable and boss entities")
    void testResolveNpcType() {
        assertEquals(org.bukkit.entity.EntityType.ZOMBIE, NpcPeripheral.resolveNpcType("zombie"));
        assertEquals(org.bukkit.entity.EntityType.VILLAGER, NpcPeripheral.resolveNpcType("TNT"));
        assertEquals(org.bukkit.entity.EntityType.VILLAGER, NpcPeripheral.resolveNpcType("END_CRYSTAL"));
        assertEquals(org.bukkit.entity.EntityType.VILLAGER, NpcPeripheral.resolveNpcType("LIGHTNING_BOLT"));
        assertEquals(org.bukkit.entity.EntityType.VILLAGER, NpcPeripheral.resolveNpcType("ENDER_DRAGON"));
        assertEquals(org.bukkit.entity.EntityType.VILLAGER, NpcPeripheral.resolveNpcType("WITHER"));
        assertEquals(org.bukkit.entity.EntityType.VILLAGER, NpcPeripheral.resolveNpcType("not_a_mob"));
        assertEquals(org.bukkit.entity.EntityType.VILLAGER, NpcPeripheral.resolveNpcType(null));
    }

    @Test
    @DisplayName("Npc registry keeps one instance per block and cleanupAt unregisters it")
    void testRegistryDoesNotLeak() {
        new NpcPeripheral(plugin, location.clone());
        new NpcPeripheral(plugin, location.clone());
        long atBlock = NpcPeripheral.getActiveNpcs().stream()
                .filter(n -> NpcPeripheral.worldOf(n.getLocation()) == world && n.getLocation().getBlockY() == 64
                        && n.getLocation().getBlockX() == 0 && n.getLocation().getBlockZ() == 0)
                .count();
        assertEquals(1, atBlock);

        NpcPeripheral.cleanupAt(location);
        assertFalse(NpcPeripheral.hasNpcAt(location));
    }

    @Test
    @DisplayName("Npc registry survives and prunes entries whose world was unloaded")
    void testRegistryToleratesUnloadedWorld() throws Exception {
        Location staleLoc = new Location(mock(World.class), 5, 64, 5);
        NpcPeripheral stale = new NpcPeripheral(plugin, staleLoc);
        // Simulate the world being unloaded/collected: Location keeps it as a weak reference.
        java.lang.reflect.Field worldRef = Location.class.getDeclaredField("world");
        worldRef.setAccessible(true);
        worldRef.set(staleLoc, new java.lang.ref.WeakReference<World>(null));
        assertThrows(IllegalArgumentException.class, staleLoc::getWorld);

        assertDoesNotThrow(() -> NpcPeripheral.hasNpcAt(location));
        assertDoesNotThrow(() -> new NpcPeripheral(plugin, location.clone()));
        assertFalse(NpcPeripheral.getActiveNpcs().contains(stale));
    }

    @Test
    @DisplayName("Npc setName and getName update dialogue identity")
    void testName() {
        LuaTable table = npc.toLuaTable().checktable();
        table.get("setName").call(LuaValue.valueOf("Guard"));
        assertEquals("Guard", table.get("getName").call().tojstring());
    }

    @Test
    @DisplayName("Npc records and retrieves player response")
    void testPlayerResponses() {
        LuaTable table = npc.toLuaTable().checktable();
        npc.recordPlayerResponse("Steve", "Yes, I accept the quest!");

        LuaValue response = table.get("getLastResponse").call(LuaValue.valueOf("Steve"));
        assertEquals("Yes, I accept the quest!", response.tojstring());

        table.get("clearResponse").call(LuaValue.valueOf("Steve"));
        assertTrue(table.get("getLastResponse").call(LuaValue.valueOf("Steve")).isnil());
    }

    @Test
    @DisplayName("Npc spawn, create, despawn, clear and remove work cleanly")
    void testSpawnDespawnAndHologram() {
        LuaTable table = npc.toLuaTable().checktable();
        assertFalse(table.get("isSpawned").call().toboolean());
        assertFalse(table.get("hasSpawned").call().toboolean());

        // spawn and create
        table.get("spawn").call(LuaValue.valueOf("VILLAGER"));
        table.get("create").call(LuaValue.valueOf("Bob"), LuaValue.valueOf("VILLAGER"));
        assertEquals("Bob", table.get("getName").call().tojstring());

        // setHologram and clear
        assertTrue(table.get("setHologram").call(LuaValue.valueOf("Hello world")).toboolean());
        assertTrue(table.get("clearHologram").call().toboolean());
        assertTrue(table.get("clear").call().toboolean());

        // despawn and remove
        assertTrue(table.get("despawn").call().toboolean());
        assertTrue(table.get("remove").call().toboolean());
        assertTrue(table.get("destroy").call().toboolean());
    }

    @Test
    @DisplayName("Npc say, ask, and getLastResponse support flexible arguments and colorize")
    void testFlexibleSayAskAndResponses() {
        LuaTable table = npc.toLuaTable().checktable();

        // Colorize helper
        assertEquals("§6Title", NpcPeripheral.colorize("&6Title"));

        // say with 1 arg (implicit nearest player)
        assertTrue(table.get("say").call(LuaValue.valueOf("&aHello adventurer!")).toboolean());

        // ask with 2 args (question + options)
        LuaTable opts = new LuaTable();
        opts.set(1, LuaValue.valueOf("Option A"));
        opts.set(2, LuaValue.valueOf("Option B"));
        assertTrue(table.get("ask").call(LuaValue.valueOf("&eChoose wisely:"), opts).toboolean());

        // record and get without playerName
        npc.recordPlayerResponse("Steve", "1");
        assertEquals("1", table.get("getLastResponse").call().tojstring());

        // clear all
        table.get("clearResponse").call();
        assertTrue(table.get("getLastResponse").call().isnil());
    }
}
