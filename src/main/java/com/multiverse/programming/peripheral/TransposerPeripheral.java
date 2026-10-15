// SPDX-License-Identifier: GPL-3.0-or-later
package com.multiverse.programming.peripheral;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.luaj.vm2.LuaBoolean;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaInteger;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Peripheral that interfaces with adjacent containers (chests, barrels, hoppers, etc.)
 * allowing automated slot querying, stack inspection, and high-speed item transfers.
 */
public final class TransposerPeripheral implements Peripheral {

    private final JavaPlugin plugin;
    private final Location location;

    public TransposerPeripheral(JavaPlugin plugin, Location location) {
        this.plugin = plugin;
        this.location = location;
    }

    @Override
    public String getType() {
        return "transposer";
    }

    @Override
    public Location getLocation() {
        return location;
    }

    public static BlockFace parseFace(String dir) {
        if (dir == null) {
            throw new LuaError("Direction cannot be nil");
        }
        return switch (dir.trim().toLowerCase(Locale.ROOT)) {
            case "north", "n" -> BlockFace.NORTH;
            case "south", "s" -> BlockFace.SOUTH;
            case "east", "e" -> BlockFace.EAST;
            case "west", "w" -> BlockFace.WEST;
            case "up", "top", "u", "t" -> BlockFace.UP;
            case "down", "bottom", "d", "b" -> BlockFace.DOWN;
            default -> throw new LuaError("Unknown direction '" + dir + "'. Expected: north, south, east, west, up, down");
        };
    }

    private Inventory getAdjacentInventory(BlockFace face) {
        Block relative = location.getBlock().getRelative(face);
        if (relative.getState() instanceof InventoryHolder holder) {
            return holder.getInventory();
        }
        return null;
    }

    /**
     * Blocks shulker-in-shulker nesting (vanilla forbids it) and fires InventoryMoveItemEvent so
     * container-lock and claim plugins can veto the transfer just as they do for hoppers.
     */
    static boolean isTransferAllowed(Inventory from, ItemStack moving, Inventory to) {
        if (to.getType() == InventoryType.SHULKER_BOX && moving.getType().name().endsWith("SHULKER_BOX")) {
            return false;
        }
        PluginManager pm = Bukkit.getPluginManager();
        if (pm == null) {
            return true;
        }
        InventoryMoveItemEvent event = new InventoryMoveItemEvent(from, moving.clone(), to, true);
        pm.callEvent(event);
        return !event.isCancelled();
    }

    @Override
    public LuaValue toLuaTable() {
        LuaTable table = new LuaTable();

        // transposer.getDirections() -> list of sides with containers
        table.set("getDirections", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return SyncDispatcher.sync(plugin, () -> {
                    LuaTable result = new LuaTable();
                    int index = 1;
                    BlockFace[] faces = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST};
                    for (BlockFace face : faces) {
                        if (getAdjacentInventory(face) != null) {
                            result.set(index++, face.name().toLowerCase(Locale.ROOT));
                        }
                    }
                    return result;
                });
            }
        });

        // transposer.getSlotCount(direction) -> int
        table.set("getSlotCount", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                BlockFace face = parseFace(arg.tojstring());
                return SyncDispatcher.sync(plugin, () -> {
                    Inventory inv = getAdjacentInventory(face);
                    if (inv == null) {
                        return LuaInteger.valueOf(0);
                    }
                    return LuaInteger.valueOf(inv.getSize());
                });
            }
        });

        // transposer.getItem(direction, slot) -> {name, count, maxStack}
        table.set("getItem", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue arg1, LuaValue arg2) {
                BlockFace face = parseFace(arg1.tojstring());
                int slot = arg2.checkint();
                return SyncDispatcher.sync(plugin, () -> {
                    Inventory inv = getAdjacentInventory(face);
                    if (inv == null || slot < 1 || slot > inv.getSize()) {
                        return LuaValue.NIL;
                    }
                    ItemStack item = inv.getItem(slot - 1);
                    if (item == null || item.getType().isAir()) {
                        return LuaValue.NIL;
                    }
                    LuaTable itemData = new LuaTable();
                    itemData.set("name", item.getType().name());
                    itemData.set("count", item.getAmount());
                    itemData.set("maxStack", item.getMaxStackSize());
                    return itemData;
                });
            }
        });

        // transposer.getAllItems(direction) -> table of all slots
        table.set("getAllItems", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                BlockFace face = parseFace(arg.tojstring());
                return SyncDispatcher.sync(plugin, () -> {
                    Inventory inv = getAdjacentInventory(face);
                    if (inv == null) {
                        return new LuaTable();
                    }
                    LuaTable result = new LuaTable();
                    for (int i = 0; i < inv.getSize(); i++) {
                        ItemStack item = inv.getItem(i);
                        if (item != null && !item.getType().isAir()) {
                            LuaTable itemData = new LuaTable();
                            itemData.set("name", item.getType().name());
                            itemData.set("count", item.getAmount());
                            itemData.set("maxStack", item.getMaxStackSize());
                            result.set(i + 1, itemData);
                        }
                    }
                    return result;
                });
            }
        });

        // transposer.transferItem(fromDir, toDir, fromSlot, [count], [toSlot]) -> items transferred
        table.set("transferItem", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                BlockFace fromFace = parseFace(args.checkjstring(1));
                BlockFace toFace = parseFace(args.checkjstring(2));
                int fromSlot = args.checkint(3);
                int count = args.narg() >= 4 ? args.optint(4, -1) : -1;
                int toSlot = args.narg() >= 5 ? args.optint(5, -1) : -1;

                return SyncDispatcher.sync(plugin, () -> {
                    Inventory fromInv = getAdjacentInventory(fromFace);
                    Inventory toInv = getAdjacentInventory(toFace);

                    if (fromInv == null || toInv == null) {
                        return LuaInteger.valueOf(0);
                    }
                    if (fromSlot < 1 || fromSlot > fromInv.getSize()) {
                        return LuaInteger.valueOf(0);
                    }

                    ItemStack source = fromInv.getItem(fromSlot - 1);
                    if (source == null || source.getType().isAir() || source.getAmount() <= 0) {
                        return LuaInteger.valueOf(0);
                    }

                    int toMove = (count <= 0) ? source.getAmount() : Math.min(count, source.getAmount());
                    if (toMove <= 0) {
                        return LuaInteger.valueOf(0);
                    }

                    ItemStack moving = source.clone();
                    moving.setAmount(toMove);

                    if (!isTransferAllowed(fromInv, moving, toInv)) {
                        return LuaInteger.valueOf(0);
                    }

                    int transferred;
                    if (toSlot >= 1 && toSlot <= toInv.getSize()) {
                        // Targeted slot transfer
                        int targetIdx = toSlot - 1;
                        ItemStack targetItem = toInv.getItem(targetIdx);
                        if (targetItem == null || targetItem.getType().isAir()) {
                            toInv.setItem(targetIdx, moving);
                            transferred = toMove;
                        } else if (targetItem.isSimilar(moving)) {
                            int space = targetItem.getMaxStackSize() - targetItem.getAmount();
                            int canMove = Math.min(toMove, space);
                            if (canMove > 0) {
                                targetItem.setAmount(targetItem.getAmount() + canMove);
                                toInv.setItem(targetIdx, targetItem);
                                transferred = canMove;
                            } else {
                                transferred = 0;
                            }
                        } else {
                            transferred = 0;
                        }
                    } else {
                        // Automatic slot allocation using addItem
                        var leftovers = toInv.addItem(moving);
                        if (leftovers.isEmpty()) {
                            transferred = toMove;
                        } else {
                            int remaining = leftovers.values().iterator().next().getAmount();
                            transferred = toMove - remaining;
                        }
                    }

                    if (transferred > 0) {
                        int newSourceAmount = source.getAmount() - transferred;
                        if (newSourceAmount <= 0) {
                            fromInv.setItem(fromSlot - 1, null);
                        } else {
                            source.setAmount(newSourceAmount);
                            fromInv.setItem(fromSlot - 1, source);
                        }
                    }

                    return LuaInteger.valueOf(transferred);
                });
            }
        });

        // transposer.compareItems(fromDir, fromSlot, toDir, toSlot) -> boolean
        table.set("compareItems", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                BlockFace face1 = parseFace(args.checkjstring(1));
                int slot1 = args.checkint(2);
                BlockFace face2 = parseFace(args.checkjstring(3));
                int slot2 = args.checkint(4);

                return SyncDispatcher.sync(plugin, () -> {
                    Inventory inv1 = getAdjacentInventory(face1);
                    Inventory inv2 = getAdjacentInventory(face2);
                    if (inv1 == null || inv2 == null) {
                        return LuaBoolean.FALSE;
                    }
                    if (slot1 < 1 || slot1 > inv1.getSize() || slot2 < 1 || slot2 > inv2.getSize()) {
                        return LuaBoolean.FALSE;
                    }
                    ItemStack item1 = inv1.getItem(slot1 - 1);
                    ItemStack item2 = inv2.getItem(slot2 - 1);
                    if (item1 == null || item2 == null) {
                        return LuaBoolean.valueOf(item1 == item2);
                    }
                    return LuaBoolean.valueOf(item1.isSimilar(item2));
                });
            }
        });

        return table;
    }
}
