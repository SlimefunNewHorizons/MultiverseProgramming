// SPDX-License-Identifier: GPL-3.0-or-later
package com.multiverse.programming.peripheral;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class MapDisplayTest {

    private static final int GRASS = 0x7FB238;

    private static int luminance(int rgb) {
        return ((rgb >> 16) & 255) + ((rgb >> 8) & 255) + (rgb & 255);
    }

    @Test
    @DisplayName("Map is always about 3.2 blocks tall: 128 rows use scale 0.1")
    void testScale() {
        assertEquals(0.1f, MapDisplay.scaleFor(128), 0.0001f);
        assertEquals(0.8f, MapDisplay.scaleFor(16), 0.0001f);
    }

    @Test
    @DisplayName("Hill shading lights slopes facing the north-west")
    void testHillShading() {
        // Rising towards south-east = facing north-west (lit); same 4-block band so no contour line
        int lit = MapDisplay.cellColor(GRASS, false, 0, 10, 60, 62, 62, 60, 61);
        int dark = MapDisplay.cellColor(GRASS, false, 0, 10, 62, 60, 60, 62, 61);
        assertTrue(luminance(lit) > luminance(dark) + 60, "lit=" + Integer.toHexString(lit) + " dark=" + Integer.toHexString(dark));
    }

    @Test
    @DisplayName("Contour lines darken cells that cross a 4-block band")
    void testContours() {
        int flat = MapDisplay.cellColor(GRASS, false, 0, 10, 62, 62, 62, 62, 62);
        int contour = MapDisplay.cellColor(GRASS, false, 0, 10, 63, 63, 64, 63, 63);
        assertTrue(luminance(contour) < luminance(flat) * 0.8);
    }

    @Test
    @DisplayName("Deeper water is darker and colors are quantized to 16 levels")
    void testWaterAndQuantize() {
        int shallow = MapDisplay.cellColor(0x4040FF, true, 1, 0, 63, 63, 63, 63, 63);
        int deep = MapDisplay.cellColor(0x4040FF, true, 12, 0, 63, 63, 63, 63, 63);
        assertTrue(luminance(deep) < luminance(shallow));
        assertEquals(0x080808, shallow & 0x0F0F0F);
        assertEquals(0x080808, MapDisplay.cellColor(GRASS, false, 0, 30, 70, 74, 71, 69, 72) & 0x0F0F0F);
    }

    @Test
    @DisplayName("Band text merges equal neighbouring cells into one colored run per row")
    void testBandTextRuns() {
        int a = 0x112233, b = 0x445566, c = 0x778899;
        int[] frame = {a, a, b, c, c, c};
        List<Component> parts = MapDisplay.bandText(frame, 3, 0, 2).children();
        assertEquals(4, parts.size()); // "██"a, "█"b, newline, "███"c
        assertEquals("██", ((TextComponent) parts.get(0)).content());
        assertEquals(TextColor.color(a), parts.get(0).color());
        assertEquals("█", ((TextComponent) parts.get(1)).content());
        assertEquals("███", ((TextComponent) parts.get(3)).content());
        assertEquals(TextColor.color(c), parts.get(3).color());
    }

    private static String plain(Component c) {
        return c.children().stream().map(p -> ((TextComponent) p).content()).collect(Collectors.joining());
    }

    @Test
    @DisplayName("Header shows the resolution and live counts; radar adds mobs")
    void testHeader() {
        String map = plain(MapDisplay.headerText(64, 3, 0, 0, false));
        assertTrue(map.contains("128×128"));
        assertTrue(map.contains("3 players"));
        assertFalse(map.contains("hostile"));

        String radar = plain(MapDisplay.headerText(32, 1, 4, 2, true));
        assertTrue(radar.contains("RADAR"));
        assertTrue(radar.contains("1 player"));
        assertTrue(radar.contains("4 hostile"));
        assertTrue(radar.contains("2 animals"));
    }

    @Test
    @DisplayName("Radar background dims terrain and draws range rings")
    void testRadarRings() {
        int size = 16;
        int[] terrain = new int[size * size];
        java.util.Arrays.fill(terrain, GRASS);
        int[] base = MapDisplay.radarBase(terrain, size);
        int ring = base[8 * size]; // col 0, row 8: exactly at full range
        int plain = base[8 * size + 5]; // 3 cells from center: between the rings at 2 and 4
        assertTrue(luminance(plain) < luminance(GRASS), "terrain is dimmed");
        assertTrue(((ring >> 8) & 255) > ((plain >> 8) & 255), "ring cells are greener");
    }

    @Test
    @DisplayName("Advanced computer sees and stops the live map of a touching cartographer")
    void testComputerStopsLiveMap() {
        org.bukkit.World world = org.mockito.Mockito.mock(org.bukkit.World.class);
        org.bukkit.Location computer = new org.bukkit.Location(world, 0, 64, 0);
        org.bukkit.Location carto = new org.bukkit.Location(world, 1, 64, 0);
        org.bukkit.block.Block computerBlock = org.mockito.Mockito.mock(org.bukkit.block.Block.class);
        org.bukkit.block.Block cartoBlock = org.mockito.Mockito.mock(org.bukkit.block.Block.class);
        org.bukkit.block.Block other = org.mockito.Mockito.mock(org.bukkit.block.Block.class);
        org.mockito.Mockito.when(world.getBlockAt(org.mockito.ArgumentMatchers.any(org.bukkit.Location.class))).thenReturn(computerBlock);
        org.mockito.Mockito.when(computerBlock.getRelative(org.mockito.ArgumentMatchers.any(org.bukkit.block.BlockFace.class))).thenReturn(other);
        org.mockito.Mockito.when(computerBlock.getRelative(org.bukkit.block.BlockFace.EAST)).thenReturn(cartoBlock);
        org.mockito.Mockito.when(other.getLocation()).thenReturn(new org.bukkit.Location(world, 9, 9, 9));
        org.mockito.Mockito.when(cartoBlock.getLocation()).thenReturn(carto);

        MapDisplay.show(null, carto, carto, 16, true, () -> true);
        assertTrue(com.multiverse.programming.ComputerListener.hasLiveMaps(computer));
        com.multiverse.programming.ComputerListener.removeLiveMaps(computer);
        assertFalse(com.multiverse.programming.ComputerListener.hasLiveMaps(computer));
    }

    @Test
    @DisplayName("Sweep brightens the wedge trailing behind the beam only")
    void testSweep() {
        int size = 16;
        int[] frame = new int[size * size];
        java.util.Arrays.fill(frame, 0x101010);
        MapDisplay.applySweep(frame, size, 0); // beam pointing north
        assertNotEquals(0x101010, frame[2 * size + 7], "just west of the beam (already swept)");
        assertEquals(0x101010, frame[2 * size + 9], "just east of the beam (not swept yet)");
    }
}
