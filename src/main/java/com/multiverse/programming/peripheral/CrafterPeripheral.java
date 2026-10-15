// SPDX-License-Identifier: GPL-3.0-or-later
package com.multiverse.programming.peripheral;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Crafter;
import org.bukkit.block.data.Directional;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemCraftResult;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import org.luaj.vm2.LuaBoolean;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

/**
 * Peripheral that interfaces with the Minecraft 1.21 Crafter block.
 * Allows programmatic querying of crafting slots, disabling slots, and automated crafting.
 */
public final class CrafterPeripheral implements Peripheral {

    private final JavaPlugin plugin;
    private final Location location;

    public CrafterPeripheral(JavaPlugin plugin, Location location) {
        this.plugin = plugin;
        this.location = location;
    }

    @Override
    public String getType() {
        return "crafter";
    }

    @Override
    public Location getLocation() {
        return location;
    }

    private Crafter getCrafterBlock() {
        Block block = location.getBlock();
        BlockState state = block.getState();
        if (state instanceof Crafter crafter) {
            return crafter;
        }
        throw new LuaError("Crafter block is no longer present at " + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ());
    }

    @Override
    public LuaValue toLuaTable() {
        LuaTable table = new LuaTable();

        // crafter.craft() -> executes a craft attempt, returns boolean success
        table.set("craft", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return SyncDispatcher.sync(plugin, () -> {
                    Crafter crafter = getCrafterBlock();
                    Inventory inv = crafter.getInventory();
                    ItemStack[] matrix = new ItemStack[9];
                    for (int i = 0; i < 9; i++) {
                        ItemStack item = inv.getItem(i);
                        matrix[i] = (item != null && !item.getType().isAir()) ? item.clone() : null;
                    }

                    // craftItemResult runs the real recipe: special recipes keep their NBT (dyes, shulker
                    // recolor, book copies) and remainders such as empty buckets stay in the matrix.
                    ItemCraftResult craft = Bukkit.craftItemResult(matrix, crafter.getWorld());
                    ItemStack crafted = craft.getResult();
                    if (crafted != null && !crafted.getType().isAir()) {
                        ItemStack result = crafted.clone();
                        ItemStack[] remaining = craft.getResultingMatrix();
                        for (int i = 0; i < 9; i++) {
                            ItemStack left = i < remaining.length ? remaining[i] : null;
                            inv.setItem(i, left == null || left.getType().isAir() ? null : left);
                        }

                        // Determine output location and target container: facing direction or above
                        Location dropLoc = crafter.getLocation().clone().add(0.5, 0.5, 0.5);
                        Block targetContainerBlock;
                        if (crafter.getBlockData() instanceof Directional dir) {
                            BlockFace face = dir.getFacing();
                            dropLoc.add(face.getModX() * 0.7, face.getModY() * 0.7, face.getModZ() * 0.7);
                            targetContainerBlock = crafter.getBlock().getRelative(face);
                        } else {
                            dropLoc.add(0, 0.7, 0);
                            targetContainerBlock = crafter.getBlock().getRelative(BlockFace.UP);
                        }

                        // Try direct insertion into adjacent container if present
                        if (targetContainerBlock != null && targetContainerBlock.getState() instanceof InventoryHolder holder) {
                            Map<Integer, ItemStack> overflow = holder.getInventory().addItem(result);
                            if (overflow.isEmpty()) {
                                result = null;
                            } else {
                                result = overflow.values().iterator().next();
                            }
                        }

                        if (result != null && !result.getType().isAir() && result.getAmount() > 0) {
                            crafter.getWorld().dropItemNaturally(dropLoc, result);
                        }
                        for (ItemStack overflow : craft.getOverflowItems()) {
                            if (overflow != null && !overflow.getType().isAir()) {
                                crafter.getWorld().dropItemNaturally(dropLoc, overflow);
                            }
                        }
                        // No crafter.update() here: it would write back the pre-craft snapshot and restore the ingredients.
                        return LuaBoolean.TRUE;
                    }

                    // Fallback to triggering crafter block logic
                    crafter.setTriggered(true);
                    crafter.update(true);
                    return LuaBoolean.FALSE;
                });
            }
        });

        // crafter.isCrafting() -> boolean
        table.set("isCrafting", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return SyncDispatcher.sync(plugin, () -> {
                    Crafter crafter = getCrafterBlock();
                    return LuaBoolean.valueOf(crafter.getCraftingTicks() > 0);
                });
            }
        });

        // crafter.isSlotDisabled(slot) -> 1-based index (1-9)
        table.set("isSlotDisabled", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                int slot = arg.checkint();
                if (slot < 1 || slot > 9) {
                    throw new LuaError("Crafter slot index must be between 1 and 9");
                }
                return SyncDispatcher.sync(plugin, () -> {
                    Crafter crafter = getCrafterBlock();
                    return LuaBoolean.valueOf(crafter.isSlotDisabled(slot - 1));
                });
            }
        });

        // crafter.setSlotDisabled(slot, disabled)
        table.set("setSlotDisabled", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue arg1, LuaValue arg2) {
                int slot = arg1.checkint();
                boolean disabled = arg2.checkboolean();
                if (slot < 1 || slot > 9) {
                    throw new LuaError("Crafter slot index must be between 1 and 9");
                }
                SyncDispatcher.syncVoid(plugin, () -> {
                    Crafter crafter = getCrafterBlock();
                    crafter.setSlotDisabled(slot - 1, disabled);
                    crafter.update(true);
                });
                return LuaValue.NONE;
            }
        });

        // crafter.getItems() -> table of 9 slots with {name="ITEM_NAME", count=X}
        table.set("getItems", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return SyncDispatcher.sync(plugin, () -> {
                    Crafter crafter = getCrafterBlock();
                    Inventory inv = crafter.getInventory();
                    LuaTable result = new LuaTable();
                    for (int i = 0; i < 9; i++) {
                        ItemStack item = inv.getItem(i);
                        if (item != null && !item.getType().isAir()) {
                            LuaTable slotData = new LuaTable();
                            slotData.set("name", item.getType().name());
                            slotData.set("count", item.getAmount());
                            slotData.set("maxStack", item.getMaxStackSize());
                            result.set(i + 1, slotData);
                        } else {
                            result.set(i + 1, LuaValue.NIL);
                        }
                    }
                    return result;
                });
            }
        });

        return table;
    }
}
