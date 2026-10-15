// SPDX-License-Identifier: GPL-3.0-or-later
package com.multiverse.programming.peripheral;

import com.multiverse.programming.MultiverseProgrammingPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;
import org.luaj.vm2.LuaBoolean;
import org.luaj.vm2.LuaInteger;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

import java.util.Collection;
import java.util.Locale;

/**
 * Peripheral that handles geographical scanning, biome inspection,
 * custom Map item generation, and topography projection onto adjacent monitors
 * or floating directly above the Cartographer table.
 */
public final class CartographerPeripheral implements Peripheral {

    public static final String SCOREBOARD_TAG = "multiverse_cartographer";
    private static final BlockFace[] ADJACENT_FACES = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
    };

    private final JavaPlugin plugin;
    private final Location location;
    private Entity hologramEntity;

    public CartographerPeripheral(JavaPlugin plugin, Location location) {
        this.plugin = plugin;
        this.location = location;
    }

    @Override
    public String getType() {
        return "cartographer";
    }

    @Override
    public Location getLocation() {
        return location;
    }

    public synchronized void updateHologram(String text) {
        clearHologram();
        World world = location.getWorld();
        if (world == null) return;

        Location holoLoc = location.clone().add(0.5, 1.25, 0.5);
        try {
            this.hologramEntity = world.spawn(holoLoc, TextDisplay.class, display -> {
                display.addScoreboardTag(SCOREBOARD_TAG);
                display.setText(text);
                display.setBillboard(Display.Billboard.CENTER);
                display.setDefaultBackground(true);
                display.setShadowed(true);
                display.setSeeThrough(true);
            });
        } catch (Throwable fallback) {
            try {
                this.hologramEntity = world.spawn(holoLoc.clone().subtract(0, 1.0, 0), ArmorStand.class, as -> {
                    as.addScoreboardTag(SCOREBOARD_TAG);
                    as.setCustomName(text);
                    as.setCustomNameVisible(true);
                    as.setVisible(false);
                    as.setGravity(false);
                    as.setMarker(true);
                });
            } catch (Throwable ignored) {}
        }
    }

    public synchronized void clearHologram() {
        if (hologramEntity != null && hologramEntity.isValid()) {
            try {
                hologramEntity.remove();
            } catch (Throwable ignored) {}
            hologramEntity = null;
        }
        cleanupAt(location);
    }

    public void clearMonitor(String sideStr) {
        if (location == null || location.getWorld() == null) return;
        Block block = location.getBlock();
        if (block == null) return;
        BlockFace face = TransposerPeripheral.parseFace(sideStr);
        Block rel = block.getRelative(face);
        if (rel != null) {
            MonitorPeripheral.removeDisplayAt(rel.getLocation());
        }
    }

    public void clearAllAdjacentMonitors() {
        if (location == null || location.getWorld() == null) return;
        Block block = location.getBlock();
        if (block == null) return;
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN}) {
            Block rel = block.getRelative(face);
            if (rel != null) {
                MonitorPeripheral.removeDisplayAt(rel.getLocation());
            }
        }
    }

    public static void cleanupAt(Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        Location center = loc.clone().add(0.5, 1.25, 0.5);
        try {
            Collection<Entity> nearby = loc.getWorld().getNearbyEntities(center, 1.5, 2.0, 1.5);
            for (Entity e : nearby) {
                if (e.getScoreboardTags().contains(SCOREBOARD_TAG)) {
                    e.remove();
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void cleanupAdjacent(Location loc) {
        cleanupAt(loc);
        if (loc == null || loc.getWorld() == null) return;
        Block block = loc.getBlock();
        if (block == null) return;
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN}) {
            Block rel = block.getRelative(face);
            if (rel != null) {
                cleanupAt(rel.getLocation());
                MonitorPeripheral.removeDisplayAt(rel.getLocation());
            }
        }
    }

    public static boolean hasDisplaysAround(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        Location holoLoc = loc.clone().add(0.5, 1.25, 0.5);
        Collection<Entity> nearby = loc.getWorld().getNearbyEntities(holoLoc, 1.5, 1.5, 1.5);
        for (Entity e : nearby) {
            if (e.getScoreboardTags().contains(SCOREBOARD_TAG)) {
                return true;
            }
        }
        Block block = loc.getBlock();
        if (block != null) {
            for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN}) {
                Block rel = block.getRelative(face);
                if (rel != null && MonitorPeripheral.hasDisplayAt(rel.getLocation())) {
                    return true;
                }
            }
        }
        return false;
    }

    private Material monitorMaterial() {
        if (plugin instanceof MultiverseProgrammingPlugin mp && mp.getConfigManager() != null) {
            return mp.getConfigManager().getMonitorBlock();
        }
        return Material.OCHRE_FROGLIGHT;
    }

    /**
     * Takes one MAP (or else one PAPER) from an adjacent container so createMap is not a free item source.
     */
    boolean consumeBlankMap() {
        Block block = location.getBlock();
        for (Material blank : new Material[]{Material.MAP, Material.PAPER}) {
            for (BlockFace face : ADJACENT_FACES) {
                Block rel = block.getRelative(face);
                if (rel != null && rel.getState() instanceof InventoryHolder holder) {
                    Inventory inv = holder.getInventory();
                    int slot = inv.first(blank);
                    if (slot == -1) continue;
                    ItemStack stack = inv.getItem(slot);
                    if (stack == null || stack.getAmount() <= 1) {
                        inv.clear(slot);
                    } else {
                        stack.setAmount(stack.getAmount() - 1);
                        inv.setItem(slot, stack);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    public String generateAsciiMap(int radius) {
        World world = location.getWorld();
        if (world == null) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("=== MAP SCAN (R:").append(radius).append(") ===\n");

        int originX = location.getBlockX();
        int originZ = location.getBlockZ();
        int selfY = location.getBlockY();

        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int x = originX + dx;
                int z = originZ + dz;
                int highY = world.getHighestBlockYAt(x, z);
                Block top = world.getBlockAt(x, highY, z);
                Material mat = top.getType();

                if (dx == 0 && dz == 0) {
                    sb.append("X"); // Self
                } else if (mat == Material.WATER) {
                    sb.append("~");
                } else if (mat == Material.LAVA) {
                    sb.append("!");
                } else if (highY > selfY + 2) {
                    sb.append("^"); // High terrain
                } else if (highY < selfY - 2) {
                    sb.append("v"); // Low terrain
                } else {
                    sb.append(".");
                }
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    @Override
    public LuaValue toLuaTable() {
        LuaTable table = new LuaTable();

        // cartographer.getBiome() -> biome name
        table.set("getBiome", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                World world = location.getWorld();
                if (world == null) return LuaValue.NIL;
                String biome = SyncDispatcher.sync(plugin, () -> world.getBiome(location).getKey().getKey().toUpperCase(Locale.ROOT));
                return LuaString.valueOf(biome);
            }
        });

        // cartographer.scanTopography([radius]) -> table of height points
        table.set("scanTopography", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                int radius = args.narg() >= 1 ? Math.min(32, Math.max(1, args.checkint(1))) : 8;

                return SyncDispatcher.sync(plugin, () -> {
                    LuaTable result = new LuaTable();
                    World world = location.getWorld();
                    if (world == null) return result;

                    int originX = location.getBlockX();
                    int originZ = location.getBlockZ();

                    LuaTable grid = new LuaTable();
                    int idx = 1;
                    for (int dx = -radius; dx <= radius; dx++) {
                        for (int dz = -radius; dz <= radius; dz++) {
                            int x = originX + dx;
                            int z = originZ + dz;
                            int highestY = world.getHighestBlockYAt(x, z);
                            Block topBlock = world.getBlockAt(x, highestY, z);

                            LuaTable pt = new LuaTable();
                            pt.set("x", LuaInteger.valueOf(x));
                            pt.set("y", LuaInteger.valueOf(highestY));
                            pt.set("z", LuaInteger.valueOf(z));
                            ScannerPeripheral.setOffset(pt, dx, highestY - location.getBlockY(), dz);
                            pt.set("material", LuaString.valueOf(topBlock.getType().name()));
                            grid.set(idx++, pt);
                        }
                    }
                    result.set("radius", LuaInteger.valueOf(radius));
                    result.set("points", grid);
                    return result;
                });
            }
        });

        // cartographer.createMap([scale]) -> boolean, mapId
        table.set("createMap", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                int scaleLevel = args.narg() >= 1 ? Math.max(0, Math.min(4, args.checkint(1))) : 1;

                return SyncDispatcher.sync(plugin, () -> {
                    World world = location.getWorld();
                    if (world == null) {
                        return varargsOf(LuaBoolean.FALSE, LuaString.valueOf("World not available"));
                    }
                    if (!consumeBlankMap()) {
                        return varargsOf(LuaBoolean.FALSE, LuaString.valueOf("Needs an empty map or paper in an adjacent container"));
                    }

                    MapView view = Bukkit.createMap(world);
                    view.setCenterX(location.getBlockX());
                    view.setCenterZ(location.getBlockZ());
                    view.setScale(MapView.Scale.valueOf("CLOSEST"));
                    if (scaleLevel == 1) view.setScale(MapView.Scale.CLOSE);
                    else if (scaleLevel == 2) view.setScale(MapView.Scale.NORMAL);
                    else if (scaleLevel == 3) view.setScale(MapView.Scale.FAR);
                    else if (scaleLevel == 4) view.setScale(MapView.Scale.FARTHEST);

                    ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
                    MapMeta meta = (MapMeta) mapItem.getItemMeta();
                    if (meta != null) {
                        meta.setMapView(view);
                        meta.setDisplayName("§6Cartographer Scan Map");
                        mapItem.setItemMeta(meta);
                    }

                    // Try to store map in adjacent container
                    boolean deposited = false;
                    for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN}) {
                        Block rel = location.getBlock().getRelative(face);
                        if (rel != null && rel.getState() instanceof InventoryHolder holder) {
                            Inventory inv = holder.getInventory();
                            if (inv.firstEmpty() != -1) {
                                inv.addItem(mapItem);
                                deposited = true;
                                break;
                            }
                        }
                    }

                    if (!deposited) {
                        world.dropItemNaturally(location.clone().add(0, 1, 0), mapItem);
                    }

                    return varargsOf(LuaBoolean.TRUE, LuaInteger.valueOf(view.getId()));
                });
            }
        });

        // cartographer.project([radius]) / cartographer.render([radius])
        table.set("project", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                int radius = args.narg() >= 1 ? Math.min(8, Math.max(1, args.checkint(1))) : 4;
                return SyncDispatcher.sync(plugin, () -> {
                    String mapText = generateAsciiMap(radius);
                    if (mapText == null) {
                        return varargsOf(LuaBoolean.FALSE, LuaString.valueOf("World not loaded"));
                    }
                    updateHologram(mapText);
                    return varargsOf(LuaBoolean.TRUE, LuaString.valueOf("Projected map above cartographer"));
                });
            }
        });
        table.set("render", table.get("project"));

        // cartographer.setHologram(text)
        table.set("setHologram", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                String text = arg.checkjstring();
                SyncDispatcher.sync(plugin, () -> {
                    updateHologram(text);
                    return null;
                });
                return LuaBoolean.TRUE;
            }
        });

        // cartographer.clear([side])
        table.set("clear", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String side = args.narg() >= 1 && !args.arg(1).isnil() ? args.checkjstring(1) : null;
                SyncDispatcher.sync(plugin, () -> {
                    if (side != null && !side.isBlank()) {
                        clearMonitor(side);
                    } else {
                        clearHologram();
                        clearAllAdjacentMonitors();
                    }
                    return null;
                });
                return LuaBoolean.TRUE;
            }
        });

        // cartographer.clearHologram() / cartographer.remove()
        table.set("clearHologram", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                SyncDispatcher.sync(plugin, () -> {
                    clearHologram();
                    return null;
                });
                return LuaBoolean.TRUE;
            }
        });
        table.set("remove", table.get("clearHologram"));
        table.set("despawn", table.get("clearHologram"));
        table.set("destroy", table.get("clearHologram"));

        // cartographer.clearMonitor([side])
        table.set("clearMonitor", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String side = args.narg() >= 1 && !args.arg(1).isnil() ? args.checkjstring(1) : null;
                SyncDispatcher.sync(plugin, () -> {
                    if (side != null && !side.isBlank()) {
                        clearMonitor(side);
                    } else {
                        clearAllAdjacentMonitors();
                    }
                    return null;
                });
                return LuaBoolean.TRUE;
            }
        });

        // cartographer.renderToMonitor(side, [radius])
        table.set("renderToMonitor", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String sideStr = args.checkjstring(1);
                int radius = args.narg() >= 2 ? Math.min(8, Math.max(1, args.checkint(2))) : 4;
                BlockFace face = TransposerPeripheral.parseFace(sideStr);

                return SyncDispatcher.sync(plugin, () -> {
                    World world = location.getWorld();
                    if (world == null) {
                        return varargsOf(LuaBoolean.FALSE, LuaString.valueOf("World not loaded"));
                    }
                    Block rel = location.getBlock().getRelative(face);
                    if (rel.getType() != monitorMaterial()) {
                        return varargsOf(LuaBoolean.FALSE, LuaString.valueOf("No monitor on side " + sideStr));
                    }

                    MonitorPeripheral monitor = new MonitorPeripheral(plugin, rel.getLocation());
                    String mapText = generateAsciiMap(radius);
                    if (mapText == null) {
                        return varargsOf(LuaBoolean.FALSE, LuaString.valueOf("World not loaded"));
                    }

                    monitor.setText(mapText);
                    return varargsOf(LuaBoolean.TRUE, LuaString.valueOf("Rendered to monitor"));
                });
            }
        });
        table.set("renderMap", table.get("renderToMonitor"));

        return table;
    }
}
