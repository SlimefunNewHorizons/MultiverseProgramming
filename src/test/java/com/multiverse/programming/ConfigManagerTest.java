// SPDX-License-Identifier: GPL-3.0-or-later
package com.multiverse.programming;

import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfigManagerTest {

    @BeforeEach
    void setUp() {
        BukkitMockHelper.setUpMockServer();
    }

    @AfterEach
    void tearDown() {
        BukkitMockHelper.tearDownMockServer();
    }

    @Test
    @DisplayName("parseBlockMaterial correctly parses valid block materials")
    void testParseValidBlockMaterial() {
        Material lectern = ConfigManager.parseBlockMaterial("LECTERN", Material.LECTERN, "computer-block", null);
        assertEquals(Material.LECTERN, lectern);

        Material enchantingTable = ConfigManager.parseBlockMaterial("ENCHANTING_TABLE", Material.ENCHANTING_TABLE, "advanced-computer-block", null);
        assertEquals(Material.ENCHANTING_TABLE, enchantingTable);

        Material noteBlock = ConfigManager.parseBlockMaterial("NOTE_BLOCK", Material.LECTERN, "computer-block", null);
        assertEquals(Material.NOTE_BLOCK, noteBlock);
    }

    @Test
    @DisplayName("parseBlockMaterial falls back gracefully on null or empty input")
    void testParseNullOrEmpty() {
        Material resultNull = ConfigManager.parseBlockMaterial(null, Material.LECTERN, "computer-block", null);
        assertEquals(Material.LECTERN, resultNull);

        Material resultEmpty = ConfigManager.parseBlockMaterial("   ", Material.LECTERN, "computer-block", null);
        assertEquals(Material.LECTERN, resultEmpty);
    }

    @Test
    @DisplayName("parseBlockMaterial falls back on unknown material name")
    void testParseUnknownMaterial() {
        Material result = ConfigManager.parseBlockMaterial("SUPER_COMPUTER_9000", Material.LECTERN, "computer-block", null);
        assertEquals(Material.LECTERN, result);
    }

    @Test
    @DisplayName("parseBlockMaterial rejects non-block materials like DIAMOND")
    void testParseNonBlockMaterial() {
        Material result = ConfigManager.parseBlockMaterial("DIAMOND", Material.LECTERN, "computer-block", null);
        assertEquals(Material.LECTERN, result, "Should fallback to LECTERN because DIAMOND is not a block");
    }

    @Test
    @DisplayName("parseBlockMaterial rejects blocks that cannot exist as inventory items (AIR, WATER)")
    void testParseNonItemBlocks() {
        Material resultAir = ConfigManager.parseBlockMaterial("AIR", Material.LECTERN, "computer-block", null);
        assertEquals(Material.LECTERN, resultAir);

        Material resultWater = ConfigManager.parseBlockMaterial("WATER", Material.LECTERN, "computer-block", null);
        assertEquals(Material.LECTERN, resultWater);
    }

    @Test
    @DisplayName("web-portal-public-url migrates the pre-move DrakesCraft-Labs URL and keeps custom ones")
    void testResolveWebPortalPublicUrl() {
        String current = ConfigManager.DEFAULT_WEB_PORTAL_PUBLIC_URL;
        assertEquals(current, ConfigManager.resolveWebPortalPublicUrl(null));
        assertEquals(current, ConfigManager.resolveWebPortalPublicUrl("   "));
        assertEquals(current, ConfigManager.resolveWebPortalPublicUrl("https://drakescraft-labs.github.io/MultiverseProgramming/"));
        assertEquals(current, ConfigManager.resolveWebPortalPublicUrl(" https://DrakesCraft-Labs.github.io/MultiverseProgramming "));
        assertEquals("https://portal.example.net/", ConfigManager.resolveWebPortalPublicUrl(" https://portal.example.net/ "));
    }
}
