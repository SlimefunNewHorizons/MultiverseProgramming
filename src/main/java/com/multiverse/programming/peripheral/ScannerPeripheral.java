// SPDX-License-Identifier: GPL-3.0-or-later
package com.multiverse.programming.peripheral;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.luaj.vm2.LuaBoolean;
import org.luaj.vm2.LuaDouble;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaInteger;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.ThreeArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.Locale;

/**
 * Peripheral that scans nearby entities, players, and blocks in a configurable radius.
 */
public final class ScannerPeripheral implements Peripheral {

    static final int INSPECT_RANGE = 16;

    private final JavaPlugin plugin;
    private final Location location;

    public ScannerPeripheral(JavaPlugin plugin, Location location) {
        this.plugin = plugin;
        this.location = location;
    }

    @Override
    public String getType() {
        return "scanner";
    }

    @Override
    public Location getLocation() {
        return location;
    }

    // Results carry absolute x/y/z plus dx/dy/dz relative to the scanner block, ready for inspect(dx, dy, dz).
    static void setOffset(LuaTable table, int dx, int dy, int dz) {
        table.set("dx", LuaInteger.valueOf(dx));
        table.set("dy", LuaInteger.valueOf(dy));
        table.set("dz", LuaInteger.valueOf(dz));
    }

    private void setOffset(LuaTable table, Location entityLoc) {
        setOffset(table, entityLoc.getBlockX() - location.getBlockX(),
                entityLoc.getBlockY() - location.getBlockY(), entityLoc.getBlockZ() - location.getBlockZ());
    }

    @Override
    public LuaValue toLuaTable() {
        LuaTable table = new LuaTable();

        // scanner.scanEntities([radius]) -> table list of entities
        table.set("scanEntities", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                int radius = args.narg() >= 1 ? Math.min(32, Math.max(1, args.checkint(1))) : 16;
                return SyncDispatcher.sync(plugin, () -> {
                    LuaTable result = new LuaTable();
                    World world = location.getWorld();
                    if (world == null) return result;

                    int idx = 1;
                    for (Entity e : world.getNearbyEntities(location, radius, radius, radius)) {
                        LuaTable entTable = new LuaTable();
                        entTable.set("type", LuaString.valueOf(e.getType().name()));
                        entTable.set("name", LuaString.valueOf(e.getName()));
                        entTable.set("uniqueId", LuaString.valueOf(e.getUniqueId().toString()));
                        entTable.set("x", LuaDouble.valueOf(e.getLocation().getX()));
                        entTable.set("y", LuaDouble.valueOf(e.getLocation().getY()));
                        entTable.set("z", LuaDouble.valueOf(e.getLocation().getZ()));
                        setOffset(entTable, e.getLocation());
                        entTable.set("distance", LuaDouble.valueOf(e.getLocation().distance(location)));
                        entTable.set("isPlayer", LuaBoolean.valueOf(e instanceof Player));
                        if (e instanceof LivingEntity living) {
                            entTable.set("health", LuaDouble.valueOf(living.getHealth()));
                            entTable.set("maxHealth", LuaDouble.valueOf(living.getMaxHealth()));
                        }
                        result.set(idx++, entTable);
                    }
                    return result;
                });
            }
        });

        // scanner.scanPlayers([radius]) -> table list of players
        table.set("scanPlayers", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                int radius = args.narg() >= 1 ? Math.min(64, Math.max(1, args.checkint(1))) : 16;
                return SyncDispatcher.sync(plugin, () -> {
                    LuaTable result = new LuaTable();
                    World world = location.getWorld();
                    if (world == null) return result;

                    int idx = 1;
                    for (Entity e : world.getNearbyEntities(location, radius, radius, radius)) {
                        if (e instanceof Player p) {
                            LuaTable pTable = new LuaTable();
                            pTable.set("name", LuaString.valueOf(p.getName()));
                            pTable.set("uniqueId", LuaString.valueOf(p.getUniqueId().toString()));
                            pTable.set("x", LuaDouble.valueOf(p.getLocation().getX()));
                            pTable.set("y", LuaDouble.valueOf(p.getLocation().getY()));
                            pTable.set("z", LuaDouble.valueOf(p.getLocation().getZ()));
                            setOffset(pTable, p.getLocation());
                            pTable.set("distance", LuaDouble.valueOf(p.getLocation().distance(location)));
                            pTable.set("health", LuaDouble.valueOf(p.getHealth()));
                            pTable.set("foodLevel", LuaInteger.valueOf(p.getFoodLevel()));
                            result.set(idx++, pTable);
                        }
                    }
                    return result;
                });
            }
        });

        // scanner.scanBlocks([radius], [materialFilter]) -> list of matching blocks
        table.set("scanBlocks", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                int radius = args.narg() >= 1 ? Math.min(16, Math.max(1, args.checkint(1))) : 8;
                String filter = args.narg() >= 2 && !args.arg(2).isnil() ? args.checkjstring(2).toUpperCase(Locale.ROOT) : null;

                return SyncDispatcher.sync(plugin, () -> {
                    LuaTable result = new LuaTable();
                    World world = location.getWorld();
                    if (world == null) return result;

                    int originX = location.getBlockX();
                    int originY = location.getBlockY();
                    int originZ = location.getBlockZ();

                    int idx = 1;
                    for (int dx = -radius; dx <= radius; dx++) {
                        for (int dy = -radius; dy <= radius; dy++) {
                            for (int dz = -radius; dz <= radius; dz++) {
                                int bx = originX + dx;
                                int by = originY + dy;
                                int bz = originZ + dz;

                                if (by < world.getMinHeight() || by >= world.getMaxHeight()) continue;

                                Block b = world.getBlockAt(bx, by, bz);
                                Material mat = b.getType();
                                if (mat.isAir()) continue;

                                String matName = mat.name();
                                if (filter != null && !matName.contains(filter)) {
                                    continue;
                                }

                                LuaTable bTable = new LuaTable();
                                bTable.set("x", LuaInteger.valueOf(bx));
                                bTable.set("y", LuaInteger.valueOf(by));
                                bTable.set("z", LuaInteger.valueOf(bz));
                                setOffset(bTable, dx, dy, dz);
                                bTable.set("material", LuaString.valueOf(matName));
                                result.set(idx++, bTable);

                                // Safety cap to avoid gigantic Lua tables
                                if (idx > 1000) {
                                    return result;
                                }
                            }
                        }
                    }
                    return result;
                });
            }
        });

        // scanner.inspect(dx, dy, dz) -> inspect the block at an offset from the scanner (max INSPECT_RANGE)
        table.set("inspect", new ThreeArgFunction() {
            @Override
            public LuaValue call(LuaValue arg1, LuaValue arg2, LuaValue arg3) {
                int dx = arg1.checkint();
                int dy = arg2.checkint();
                int dz = arg3.checkint();

                // Unbounded targets = remote x-ray and sync chunk generation anywhere.
                if (Math.abs(dx) > INSPECT_RANGE || Math.abs(dy) > INSPECT_RANGE || Math.abs(dz) > INSPECT_RANGE) {
                    throw new LuaError("target out of range (max " + INSPECT_RANGE + " blocks from scanner)");
                }
                int bx = location.getBlockX() + dx;
                int by = location.getBlockY() + dy;
                int bz = location.getBlockZ() + dz;

                return SyncDispatcher.sync(plugin, () -> {
                    World world = location.getWorld();
                    if (world == null || by < world.getMinHeight() || by >= world.getMaxHeight()
                            || !world.isChunkLoaded(bx >> 4, bz >> 4)) {
                        return LuaValue.NIL;
                    }
                    Block b = world.getBlockAt(bx, by, bz);
                    LuaTable bTable = new LuaTable();
                    bTable.set("x", LuaInteger.valueOf(bx));
                    bTable.set("y", LuaInteger.valueOf(by));
                    bTable.set("z", LuaInteger.valueOf(bz));
                    setOffset(bTable, dx, dy, dz);
                    bTable.set("material", LuaString.valueOf(b.getType().name()));
                    bTable.set("blockData", LuaString.valueOf(b.getBlockData().getAsString()));
                    return bTable;
                });
            }
        });

        return table;
    }
}
