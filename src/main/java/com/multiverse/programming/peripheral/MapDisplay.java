// SPDX-License-Identifier: GPL-3.0-or-later
package com.multiverse.programming.peripheral;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Display;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * High-resolution live terrain map (up to 128x128 cells) floating above a block.
 * <p>
 * The map is split into horizontal bands of {@link #ROWS_PER_BAND} rows, each its own TextDisplay,
 * so a moving player only re-sends the one or two bands they cross instead of the whole picture.
 * Terrain uses vanilla map colors blended with an elevation ramp, hill shading lit from the
 * north-west and contour lines every 4 blocks. Players are drawn on top and refreshed twice per second.
 * <p>
 * Radar mode (cartographer + scanner + monitor) dims the terrain, adds range rings at a quarter of the
 * range, tracks hostile mobs and animals too, and sweeps a scan beam when the radius is at most
 * {@link #SWEEP_MAX_RADIUS} (larger maps would re-send every band on each step).
 * <p>
 * Displays are not saved to disk; the map lives until it is cleared, one of its blocks is removed or
 * the plugin stops.
 */
public final class MapDisplay {

    public static final String TAG = "multiverse_holo_map";
    public static final int MIN_RADIUS = 8;
    public static final int MAX_RADIUS = 64;
    static final int ROWS_PER_BAND = 8;
    // TextDisplay metrics at scale 1: one font pixel = 0.025 blocks, lines are 10px, the block glyph advances 9px.
    static final double PIXEL = 0.025;
    static final int LINE_PX = 10;
    static final int GLYPH_PX = 9;
    static final double MAP_HEIGHT_BLOCKS = 3.2;
    static final float LABEL_SCALE = 0.45f;
    static final long UPDATE_TICKS = 10L;
    static final int TERRAIN_REFRESH_RUNS = 40; // every 20 s
    static final String CELL = "█";

    static final int UNKNOWN = 0x16181C;
    static final int SELF = 0xFF3B3B;
    static final int PLAYER = 0xFFFFFF;
    static final int HOSTILE = 0xFF4D4D;
    static final int PASSIVE = 0xFFB3D9;
    static final int RADAR_TINT = 0x0B1F12;
    static final int RADAR_GREEN = 0x6CFF8A;
    static final int SWEEP_MAX_RADIUS = 32;
    static final int SWEEP_STEP_DEG = 30;   // one step per update: a full turn every 6 s
    static final int SWEEP_WIDTH_DEG = 75;
    private static final Color BACKGROUND = Color.fromARGB(255, 12, 14, 18);
    // Elevation ramp, in blocks above sea level
    static final int[][] LAND_RAMP = {{0, 0x4F8F3A}, {8, 0x8FBF4F}, {16, 0xD6C46A}, {26, 0xB08A5A}, {38, 0x8B7D72}, {52, 0xF4F4F4}};
    static final int[][] WATER_RAMP = {{0, 0x5D8CFF}, {4, 0x3355D6}, {12, 0x1B2A80}};

    private static final Map<String, MapDisplay> ACTIVE = new ConcurrentHashMap<>();

    private final JavaPlugin plugin;
    private final Location cartographer;
    private final Location anchor;
    private final BooleanSupplier stillValid;
    private final boolean radar;
    private final int radius;
    private final int size;
    private final float scale;
    private final int bandCount;
    private final List<TextDisplay> bands = new ArrayList<>();
    private TextDisplay header;
    private TextDisplay legend;
    private int[] terrain;
    private int[] base;
    private int[] shown;
    private int[] counts = {-1, -1, -1};
    private int sweepStep;
    private int runs;
    private boolean unloaded;
    private BukkitTask task;

    private MapDisplay(JavaPlugin plugin, Location cartographer, Location anchor, int radius, boolean radar, BooleanSupplier stillValid) {
        this.plugin = plugin;
        this.radar = radar;
        this.cartographer = cartographer.clone();
        this.anchor = anchor.clone();
        this.radius = radius;
        this.size = radius * 2;
        this.scale = scaleFor(size);
        this.bandCount = (size + ROWS_PER_BAND - 1) / ROWS_PER_BAND;
        this.stillValid = stillValid;
    }

    // ------------------------------------------------------------------ registry

    /**
     * Shows a live map centered on {@code cartographer}, floating above {@code anchor}. Replaces any map
     * already on that anchor. Must run on the main thread.
     *
     * @param radar      radar mode: rings, mobs and sweep (the caller checks that a scanner is connected)
     * @param stillValid checked every update; when false the map removes itself (block broken, etc.)
     */
    public static MapDisplay show(JavaPlugin plugin, Location cartographer, Location anchor, int radius, boolean radar,
                                  BooleanSupplier stillValid) {
        removeAt(anchor);
        int r = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
        MapDisplay map = new MapDisplay(plugin, cartographer, anchor, r, radar, stillValid);
        ACTIVE.put(key(anchor), map);
        map.spawnAll();
        map.setTerrain(map.computeTerrain());
        map.redraw(true);
        if (plugin != null && Bukkit.getScheduler() != null) {
            map.task = Bukkit.getScheduler().runTaskTimer(plugin, map::tick, UPDATE_TICKS, UPDATE_TICKS);
        }
        return map;
    }

    public static boolean hasMapAt(Location anchor) {
        return anchor != null && ACTIVE.containsKey(key(anchor));
    }

    public static boolean hasMapFor(Location cartographer) {
        String k = key(cartographer);
        return ACTIVE.values().stream().anyMatch(m -> key(m.cartographer).equals(k));
    }

    /** Removes the map above {@code anchor}, including leftover tagged displays from an earlier session. */
    public static void removeAt(Location anchor) {
        if (anchor == null) return;
        MapDisplay map = ACTIVE.remove(key(anchor));
        if (map != null) map.despawn();
        World world = NpcPeripheral.worldOf(anchor);
        if (world == null) return;
        Location column = new Location(world, anchor.getBlockX() + 0.5, anchor.getBlockY() + 1.5 + MAP_HEIGHT_BLOCKS / 2, anchor.getBlockZ() + 0.5);
        try {
            for (Entity e : world.getNearbyEntities(column, 1.5, MAP_HEIGHT_BLOCKS / 2 + 1.5, 1.5)) {
                if (e.getScoreboardTags().contains(TAG)) e.remove();
            }
        } catch (Throwable ignored) {
        }
    }

    /** Removes every map drawn by the cartographer at {@code cartographer}. */
    public static void removeForCartographer(Location cartographer) {
        String k = key(cartographer);
        for (MapDisplay map : new ArrayList<>(ACTIVE.values())) {
            if (key(map.cartographer).equals(k)) removeAt(map.anchor);
        }
    }

    public static void removeAll() {
        for (MapDisplay map : new ArrayList<>(ACTIVE.values())) removeAt(map.anchor);
    }

    private static String key(Location loc) {
        World w = NpcPeripheral.worldOf(loc);
        return (w != null ? w.getUID() : "?") + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    // ------------------------------------------------------------------ geometry & entities

    /** Text scale so the whole map is {@link #MAP_HEIGHT_BLOCKS} tall whatever its resolution. */
    static float scaleFor(int rows) {
        return (float) (MAP_HEIGHT_BLOCKS / (rows * LINE_PX * PIXEL));
    }

    private double bandHeight() {
        return ROWS_PER_BAND * LINE_PX * PIXEL * scale;
    }

    private void spawnAll() {
        World world = NpcPeripheral.worldOf(anchor);
        if (world == null) return;
        bands.clear();
        double x = anchor.getBlockX() + 0.5, z = anchor.getBlockZ() + 0.5;
        double legendY = anchor.getBlockY() + 1.05;
        double mapBottom = anchor.getBlockY() + 1.25;
        int lineWidth = size * GLYPH_PX + 16;
        for (int b = 0; b < bandCount; b++) {
            // band 0 is the top (north) band
            double y = mapBottom + (bandCount - 1 - b) * bandHeight();
            bands.add(spawn(world, new Location(world, x, y, z), scale, lineWidth));
        }
        legend = spawn(world, new Location(world, x, legendY, z), LABEL_SCALE, 400);
        header = spawn(world, new Location(world, x, mapBottom + bandCount * bandHeight() + 0.05, z), LABEL_SCALE, 400);
        unloaded = false;
    }

    private static TextDisplay spawn(World world, Location loc, float scale, int lineWidth) {
        TextDisplay display = world.spawn(loc, TextDisplay.class, d -> {
            d.addScoreboardTag(TAG);
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setDefaultBackground(false);
            d.setBackgroundColor(BACKGROUND);
            d.setShadowed(false);
            d.setSeeThrough(false);
            d.setLineWidth(lineWidth);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                    new Vector3f(scale, scale, scale), new AxisAngle4f()));
        });
        return display;
    }

    private void despawn() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (TextDisplay d : bands) if (d != null && d.isValid()) d.remove();
        bands.clear();
        if (header != null && header.isValid()) header.remove();
        if (legend != null && legend.isValid()) legend.remove();
        header = legend = null;
    }

    // ------------------------------------------------------------------ live updates

    private void tick() {
        World world = NpcPeripheral.worldOf(anchor);
        if (world == null || !stillValid.getAsBoolean()) {
            removeAt(anchor);
            return;
        }
        if (!world.isChunkLoaded(anchor.getBlockX() >> 4, anchor.getBlockZ() >> 4)) {
            unloaded = true; // non-persistent displays went away with the chunk
            return;
        }
        if (unloaded) {
            spawnAll();
            setTerrain(computeTerrain());
            redraw(true);
            return;
        }
        if (bands.stream().anyMatch(d -> d == null || !d.isValid())) {
            removeAt(anchor); // removed from outside (purge command, /kill): do not fight it
            return;
        }
        if (++runs % TERRAIN_REFRESH_RUNS == 0) setTerrain(computeTerrain());
        sweepStep++;
        redraw(false);
    }

    private void setTerrain(int[] colors) {
        terrain = colors;
        base = radar ? radarBase(colors, size) : colors;
    }

    private void redraw(boolean full) {
        if (base == null) return;
        int[] frame = base.clone();
        if (radar && radius <= SWEEP_MAX_RADIUS) applySweep(frame, size, sweepStep * SWEEP_STEP_DEG);
        int[] now = drawEntities(frame);
        for (int b = 0; b < bands.size(); b++) {
            int from = b * ROWS_PER_BAND * size, to = Math.min(frame.length, from + ROWS_PER_BAND * size);
            if (full || shown == null || !Arrays.equals(frame, from, to, shown, from, to)) {
                TextDisplay d = bands.get(b);
                if (d != null) d.text(bandText(frame, size, b * ROWS_PER_BAND, Math.min(ROWS_PER_BAND, size - b * ROWS_PER_BAND)));
            }
        }
        shown = frame;
        if (full || !Arrays.equals(now, counts)) {
            counts = now;
            if (header != null) header.text(headerText(radius, now[0], now[1], now[2], radar));
            if (legend != null && full) legend.text(legendText(radar));
        }
    }

    /**
     * Paints the cartographer and players (plus hostile mobs and animals in radar mode) onto {@code frame}.
     *
     * @return {players, hostiles, animals}
     */
    private int[] drawEntities(int[] frame) {
        World world = NpcPeripheral.worldOf(cartographer);
        int cx = cartographer.getBlockX(), cz = cartographer.getBlockZ();
        int players = 0, hostiles = 0, animals = 0;
        if (world != null) {
            Location center = new Location(world, cx + 0.5, cartographer.getBlockY(), cz + 0.5);
            for (Entity e : world.getNearbyEntities(center, radius, 64, radius)) {
                int col = e.getLocation().getBlockX() - cx + radius;
                int row = e.getLocation().getBlockZ() - cz + radius;
                if (e instanceof Player p) {
                    if (p.getGameMode() == GameMode.SPECTATOR || p.hasPotionEffect(PotionEffectType.INVISIBILITY)) continue;
                    players++;
                    plus(frame, col, row, PLAYER);
                } else if (radar && e instanceof Enemy) {
                    hostiles++;
                    plus(frame, col, row, HOSTILE);
                } else if (radar && e instanceof Animals) {
                    animals++;
                    dot(frame, col, row, PASSIVE);
                }
            }
        }
        plus(frame, radius, radius, SELF);
        return new int[]{players, hostiles, animals};
    }

    /** Radar background: terrain dimmed towards dark green with range rings every quarter of the range. */
    static int[] radarBase(int[] terrain, int size) {
        int[] out = new int[terrain.length];
        double r = size / 2.0;
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                int i = row * size + col;
                int rgb = mix(terrain[i], RADAR_TINT, 0.45);
                double dist = Math.hypot(col - r, row - r);
                for (int k = 1; k <= 4; k++) {
                    if (Math.abs(dist - r * k / 4.0) < 0.5) {
                        rgb = mix(rgb, RADAR_GREEN, 0.5);
                        break;
                    }
                }
                out[i] = quantize(rgb);
            }
        }
        return out;
    }

    /** Brightens a wedge trailing behind the beam at {@code angleDeg} (0 = north, clockwise). */
    static void applySweep(int[] frame, int size, int angleDeg) {
        double r = size / 2.0;
        double beam = Math.floorMod(angleDeg, 360);
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                double dx = col - r, dz = row - r;
                if (Math.hypot(dx, dz) > r) continue;
                double bearing = Math.toDegrees(Math.atan2(dx, -dz));
                double behind = ((beam - bearing) % 360 + 360) % 360;
                if (behind < SWEEP_WIDTH_DEG) {
                    int i = row * size + col;
                    frame[i] = quantize(mix(frame[i], RADAR_GREEN, 0.55 * (1 - behind / SWEEP_WIDTH_DEG)));
                }
            }
        }
    }

    private void dot(int[] frame, int col, int row, int rgb) {
        if (col >= 0 && col < size && row >= 0 && row < size) frame[row * size + col] = rgb;
    }

    // Players and the cartographer use a 5-cell plus so they stay visible at 128x128.
    private void plus(int[] frame, int col, int row, int rgb) {
        dot(frame, col, row, rgb);
        dot(frame, col + 1, row, rgb);
        dot(frame, col - 1, row, rgb);
        dot(frame, col, row + 1, rgb);
        dot(frame, col, row - 1, rgb);
    }

    // ------------------------------------------------------------------ terrain

    private int[] computeTerrain() {
        World world = NpcPeripheral.worldOf(cartographer);
        int[] colors = new int[size * size];
        Arrays.fill(colors, UNKNOWN);
        if (world == null) return colors;
        int n = size + 2; // one-cell border for shading neighbours
        int x0 = cartographer.getBlockX() - radius - 1, z0 = cartographer.getBlockZ() - radius - 1;
        int[] heights = new int[n * n];
        for (int gz = 0; gz < n; gz++) {
            for (int gx = 0; gx < n; gx++) {
                int x = x0 + gx, z = z0 + gz;
                // Never load or generate chunks for the map: unloaded areas stay dark.
                heights[gz * n + gx] = world.isChunkLoaded(x >> 4, z >> 4) ? world.getHighestBlockYAt(x, z) : Integer.MIN_VALUE;
            }
        }
        int sea = world.getSeaLevel();
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                int gx = col + 1, gz = row + 1;
                int h = heights[gz * n + gx];
                if (h == Integer.MIN_VALUE) continue;
                int x = x0 + gx, z = z0 + gz;
                BlockData data = world.getBlockAt(x, h, z).getBlockData();
                boolean water = data != null && data.getMaterial() == Material.WATER;
                int depth = water ? Math.max(0, h - world.getHighestBlockYAt(x, z, HeightMap.OCEAN_FLOOR)) : 0;
                Color mapColor = data != null ? data.getMapColor() : null;
                colors[row * size + col] = cellColor(mapColor != null ? mapColor.asRGB() : 0x7F7F7F, water, depth, h - sea,
                        neighbour(heights, n, gx, gz - 1, h), neighbour(heights, n, gx, gz + 1, h),
                        neighbour(heights, n, gx + 1, gz, h), neighbour(heights, n, gx - 1, gz, h), h);
            }
        }
        return colors;
    }

    private static int neighbour(int[] heights, int n, int gx, int gz, int fallback) {
        int h = heights[gz * n + gx];
        return h == Integer.MIN_VALUE ? fallback : h;
    }

    /**
     * Color of one terrain cell: map color blended 50/50 with the elevation ramp, hill shading lit from the
     * north-west, darker contour lines every 4 blocks (index lines every 20), water tinted by depth. The
     * result is quantized to 16 levels per channel so neighbouring cells share colors and merge into fewer runs.
     */
    static int cellColor(int mapRgb, boolean water, int depth, int aboveSea, int hN, int hS, int hE, int hW, int h) {
        int rgb;
        if (water) {
            rgb = ramp(WATER_RAMP, depth);
        } else {
            rgb = mix(mapRgb, ramp(LAND_RAMP, aboveSea), 0.5);
            double ddx = (hE - hW) / 2.0, ddz = (hS - hN) / 2.0;
            double nx = -ddx * 1.6, ny = 1, nz = -ddz * 1.6, nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
            double lx = -0.6, ly = 0.75, lz = -0.6, ll = Math.sqrt(lx * lx + ly * ly + lz * lz);
            double dot = (nx * lx + ny * ly + nz * lz) / (nl * ll);
            rgb = shade(rgb, Math.max(0.4, Math.min(1.18, 0.35 + dot * 0.85)));
            int band = Math.floorDiv(h, 4), east = Math.floorDiv(hE, 4), south = Math.floorDiv(hS, 4);
            if (band != east || band != south) {
                boolean index = Math.floorMod(Math.max(band, Math.max(east, south)) * 4, 20) == 0;
                rgb = shade(rgb, index ? 0.37 : 0.6);
            }
        }
        return quantize(rgb);
    }

    static int ramp(int[][] stops, int v) {
        if (v <= stops[0][0]) return stops[0][1];
        for (int i = 1; i < stops.length; i++) {
            if (v <= stops[i][0]) {
                double t = (v - stops[i - 1][0]) / (double) (stops[i][0] - stops[i - 1][0]);
                return mix(stops[i - 1][1], stops[i][1], t);
            }
        }
        return stops[stops.length - 1][1];
    }

    static int mix(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int g = (int) Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = (int) Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
        return (r << 16) | (g << 8) | bl;
    }

    static int shade(int rgb, double k) {
        int r = (int) Math.min(255, ((rgb >> 16) & 255) * k);
        int g = (int) Math.min(255, ((rgb >> 8) & 255) * k);
        int b = (int) Math.min(255, (rgb & 255) * k);
        return (r << 16) | (g << 8) | b;
    }

    static int quantize(int rgb) {
        return (rgb & 0xF0F0F0) | 0x080808;
    }

    // ------------------------------------------------------------------ text

    /** Rows {@code [firstRow, firstRow + rows)} of {@code frame} as text, merging equal neighbours into one run. */
    static Component bandText(int[] frame, int size, int firstRow, int rows) {
        TextComponent.Builder text = Component.text();
        for (int r = 0; r < rows; r++) {
            int base = (firstRow + r) * size;
            int runColor = frame[base];
            StringBuilder run = new StringBuilder();
            for (int c = 0; c < size; c++) {
                int rgb = frame[base + c];
                if (rgb != runColor) {
                    text.append(Component.text(run.toString(), TextColor.color(runColor)));
                    run.setLength(0);
                    runColor = rgb;
                }
                run.append(CELL);
            }
            text.append(Component.text(run.toString(), TextColor.color(runColor)));
            if (r < rows - 1) text.append(Component.newline());
        }
        return text.build();
    }

    static Component headerText(int radius, int players, int hostiles, int animals, boolean radar) {
        TextComponent.Builder text = Component.text()
                .append(Component.text(radar ? "◉ RADAR " : "◆ CARTOGRAPHER ", radar ? TextColor.color(RADAR_GREEN) : NamedTextColor.GOLD))
                .append(Component.text((radius * 2) + "×" + (radius * 2) + "  ", NamedTextColor.GRAY))
                .append(Component.text("N ↑  ", NamedTextColor.WHITE))
                .append(Component.text(CELL + " " + players + (players == 1 ? " player" : " players"), TextColor.color(PLAYER)));
        if (radar) {
            text.append(Component.text("  " + CELL + " " + hostiles + " hostile", TextColor.color(HOSTILE)));
            text.append(Component.text("  " + CELL + " " + animals + (animals == 1 ? " animal" : " animals"), TextColor.color(PASSIVE)));
        }
        return text.build();
    }

    static Component legendText(boolean radar) {
        TextComponent.Builder text = Component.text();
        if (radar) {
            text.append(Component.text(CELL, TextColor.color(SELF))).append(Component.text(" You  ", NamedTextColor.GRAY));
            text.append(Component.text(CELL, TextColor.color(PLAYER))).append(Component.text(" Player  ", NamedTextColor.GRAY));
            text.append(Component.text(CELL, TextColor.color(HOSTILE))).append(Component.text(" Hostile  ", NamedTextColor.GRAY));
            text.append(Component.text(CELL, TextColor.color(PASSIVE))).append(Component.text(" Animal  ", NamedTextColor.GRAY));
            text.append(Component.text(CELL, TextColor.color(RADAR_GREEN))).append(Component.text(" ring = 1/4 range", NamedTextColor.GRAY));
            return text.build();
        }
        text.append(Component.text("deep ", NamedTextColor.GRAY));
        for (int d = 12; d >= 0; d -= 4) text.append(Component.text(CELL, TextColor.color(quantize(ramp(WATER_RAMP, d)))));
        for (int v = 0; v <= 52; v += 6) text.append(Component.text(CELL, TextColor.color(quantize(ramp(LAND_RAMP, v)))));
        text.append(Component.text(" peaks   ", NamedTextColor.GRAY));
        text.append(Component.text(CELL, TextColor.color(SELF))).append(Component.text(" You  ", NamedTextColor.GRAY));
        text.append(Component.text(CELL, TextColor.color(PLAYER))).append(Component.text(" Player", NamedTextColor.GRAY));
        return text.build();
    }
}
