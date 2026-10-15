// SPDX-License-Identifier: GPL-3.0-or-later
package com.multiverse.programming.turtle;

import com.multiverse.programming.blueprint.Blueprint;
import com.multiverse.programming.blueprint.Blueprint.PlacementBlock;
import com.multiverse.programming.peripheral.SyncDispatcher;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import com.multiverse.programming.MultiverseProgrammingPlugin;
import com.multiverse.programming.protection.CoreProtectBridge;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Represents a programmable Turtle block in the world.
 * Can navigate the world, mine, place blocks, and execute blueprint builds.
 */
public final class Turtle {

    public enum Status {
        IDLE,
        MOVING,
        BUILDING,
        MINING,
        PAUSED,
        ERROR
    }

    public enum LateralSide {
        LEFT,
        RIGHT
    }

    public static final String TURTLE_HOLOGRAM_TAG = "multiverse_turtle_hologram";

    private final String id;
    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private UUID owner;
    private Location location;
    private BlockFace facing;
    private int fuel;
    private int selectedSlot; // 0 to 15
    private final ItemStack[] inventory = new ItemStack[16];
    private ItemStack disk;
    private final Set<UUID> authorizedPlayers = new LinkedHashSet<>();
    private Status status = Status.IDLE;
    private String statusMessage = "Idle";

    // Active Build Task fields
    private String activeBlueprintId;
    private Location buildOrigin;
    private Blueprint activeBlueprint;
    private final AtomicInteger currentBlockIndex = new AtomicInteger(0);
    private int totalBlocks = 0;
    private BukkitTask buildTask;

    public record BlockProtectionCheck(boolean isProtected, String reason) {}
    public record BlockPos(int x, int y, int z) {}

    private Location constructionChestLoc;
    private Location constructionChestLoc2;
    private org.bukkit.entity.Entity constructionHologram;
    private Location fuelChestLoc;
    private Location fuelChestLoc2;
    private org.bukkit.entity.Entity fuelHologram;
    private Location terminalBlockLoc;
    private org.bukkit.entity.Entity terminalHologram;
    private org.bukkit.entity.Entity quarryTerminalHologram;
    private com.multiverse.programming.LuaRunner.LuaProgram runningScript;
    private final Set<BlockPos> activeBuildPositions = new HashSet<>();

    // Active Quarry Engine Upgrade fields
    private BukkitTask quarryTask;
    private LateralSide quarryLateralSide;
    private Location quarryStartLocation;
    private Location quarryStorageChestLoc;
    private Location quarryStorageChestLoc2;
    private org.bukkit.entity.Entity quarryStorageHologram;
    private Location quarryFuelChestLoc;
    private Location quarryFuelChestLoc2;
    private org.bukkit.entity.Entity quarryFuelHologram;
    private int quarryBlocksMined = 0;
    private int quarryTotalBlocks = 0;
    private int quarryWidth = 0;
    private int quarryLength = 0;
    private int quarryTargetY = 0;
    private int quarryCurY = 0;
    private int quarryStepX = 0;
    private int quarryStepZ = 0;
    private boolean quarryHandleLiquids = true;
    private int quarryFuelPoints = 0;
    private World quarryWorld;
    private int quarryMinX = 0;
    private int quarryMaxX = 0;
    private int quarryMinY = 0;
    private int quarryMaxY = 0;
    private int quarryMinZ = 0;
    private int quarryMaxZ = 0;

    public Turtle(org.bukkit.plugin.java.JavaPlugin plugin, String id, Location location, BlockFace facing, UUID owner) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.id = Objects.requireNonNull(id, "id cannot be null");
        this.location = Objects.requireNonNull(location, "location cannot be null");
        this.facing = (facing != null) ? sanitizeHorizontalFace(facing) : BlockFace.NORTH;
        this.owner = owner;
        this.fuel = 1000;
        this.selectedSlot = 0;
    }

    private static BlockFace sanitizeHorizontalFace(BlockFace face) {
        return switch (face) {
            case SOUTH -> BlockFace.SOUTH;
            case EAST -> BlockFace.EAST;
            case WEST -> BlockFace.WEST;
            default -> BlockFace.NORTH;
        };
    }

    public String getId() {
        return id;
    }

    public UUID getOwner() {
        return owner;
    }

    public void setOwner(UUID owner) {
        this.owner = owner;
    }

    public synchronized boolean isOwner(UUID uuid) {
        return uuid != null && uuid.equals(owner);
    }

    /**
     * Players explicitly authorized by the owner to open and operate this Turtle.
     */
    public synchronized List<UUID> getAuthorizedPlayers() {
        return new ArrayList<>(authorizedPlayers);
    }

    /**
     * Whether the given player may open and operate this Turtle.
     * An unclaimed Turtle (no owner) stays open to anyone holding the base permission,
     * preserving the previous behaviour for turtles restored without an owner.
     */
    public synchronized boolean isAccessAllowed(UUID uuid) {
        if (owner == null) return true;
        if (uuid == null) return false;
        return owner.equals(uuid) || authorizedPlayers.contains(uuid);
    }

    /**
     * Authorizes a player to operate this Turtle. The owner is always allowed and
     * cannot be added again.
     *
     * @return true if the player was newly authorized, false if already authorized or invalid
     */
    public synchronized boolean addAuthorized(UUID uuid) {
        if (uuid == null || uuid.equals(owner)) return false;
        return authorizedPlayers.add(uuid);
    }

    public synchronized boolean removeAuthorized(UUID uuid) {
        return uuid != null && authorizedPlayers.remove(uuid);
    }

    public String getOwnerName() {
        if (owner == null) return "None";
        try {
            org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(owner);
            if (op.getName() != null) return op.getName();
        } catch (Throwable ignored) {}
        return owner.toString();
    }

    public synchronized Location getLocation() {
        return location.clone();
    }

    public synchronized void setLocation(Location location) {
        this.location = location.clone();
    }

    public synchronized BlockFace getFacing() {
        return facing;
    }

    public synchronized int getFuel() {
        return fuel;
    }

    public synchronized void setFuel(int fuel) {
        this.fuel = Math.max(0, fuel);
    }

    public synchronized int getSelectedSlot() {
        return selectedSlot;
    }

    public synchronized void setSelectedSlot(int slot) {
        if (slot >= 0 && slot < 16) {
            this.selectedSlot = slot;
        }
    }

    public synchronized ItemStack getItem(int slot) {
        if (slot >= 0 && slot < 16) {
            ItemStack stack = inventory[slot];
            if (stack == null) return null;
            ItemStack cloned = null;
            try {
                cloned = stack.clone();
            } catch (Throwable ignored) {
            }
            return (cloned != null) ? cloned : stack;
        }
        return null;
    }

    public synchronized void setItem(int slot, ItemStack item) {
        if (slot >= 0 && slot < 16) {
            if (item == null || item.getType().isAir()) {
                inventory[slot] = null;
            } else {
                ItemStack cloned = null;
                try {
                    cloned = item.clone();
                } catch (Throwable ignored) {
                }
                inventory[slot] = (cloned != null) ? cloned : item;
            }
        }
    }

    public synchronized ItemStack[] getInventory() {
        ItemStack[] copy = new ItemStack[16];
        for (int i = 0; i < 16; i++) {
            copy[i] = inventory[i] != null ? inventory[i].clone() : null;
        }
        return copy;
    }

    public synchronized ItemStack getDisk() {
        return disk != null ? disk.clone() : null;
    }

    public synchronized void setDisk(ItemStack disk) {
        this.disk = (disk != null && !disk.getType().isAir()) ? disk.clone() : null;
    }

    public synchronized Status getStatus() {
        return status;
    }

    public synchronized String getStatusMessage() {
        return statusMessage;
    }

    public synchronized String getActiveBlueprintId() {
        return activeBlueprintId;
    }

    public synchronized Blueprint getActiveBlueprint() {
        return activeBlueprint;
    }

    public synchronized Location getBuildOrigin() {
        return buildOrigin != null ? buildOrigin.clone() : null;
    }

    public int getCurrentBlockIndex() {
        return currentBlockIndex.get();
    }

    public int getTotalBlocks() {
        return totalBlocks;
    }

    public double getProgressPercentage() {
        if (totalBlocks <= 0) return 0.0;
        return Math.min(100.0, ((double) currentBlockIndex.get() / totalBlocks) * 100.0);
    }

    public synchronized Location getConstructionChestLoc() {
        return constructionChestLoc != null ? constructionChestLoc.clone() : null;
    }

    public synchronized Location getConstructionChestLoc2() {
        return constructionChestLoc2 != null ? constructionChestLoc2.clone() : null;
    }

    public synchronized Location getFuelChestLoc() {
        return fuelChestLoc != null ? fuelChestLoc.clone() : null;
    }

    public synchronized Location getFuelChestLoc2() {
        return fuelChestLoc2 != null ? fuelChestLoc2.clone() : null;
    }

    public synchronized Location getTerminalBlockLoc() {
        return terminalBlockLoc != null ? terminalBlockLoc.clone() : null;
    }

    public synchronized boolean isTerminalBlock(Location loc) {
        if (terminalBlockLoc == null || loc == null || loc.getWorld() == null) return false;
        return terminalBlockLoc.getWorld().equals(loc.getWorld())
                && terminalBlockLoc.getBlockX() == loc.getBlockX()
                && terminalBlockLoc.getBlockY() == loc.getBlockY()
                && terminalBlockLoc.getBlockZ() == loc.getBlockZ();
    }

    public void notifyOwner(String message) {
        if (this.owner == null || message == null || message.isBlank()) return;
        try {
            Player p = Bukkit.getPlayer(this.owner);
            if (p != null && p.isOnline()) {
                String prefix = (plugin instanceof MultiverseProgrammingPlugin mvp) ? mvp.getPrefix() : "§8[§bTurtle§8]§r";
                p.sendMessage(prefix + " " + message);
            }
        } catch (Throwable ignored) {}
    }

    public synchronized void setRunningScript(com.multiverse.programming.LuaRunner.LuaProgram program) {
        if (this.runningScript != null && this.runningScript.isRunning()) {
            this.runningScript.cancel();
        }
        this.runningScript = program;
    }

    public synchronized void cancelScript() {
        if (this.runningScript != null) {
            this.runningScript.cancel();
            this.runningScript = null;
        }
    }

    public boolean isSelfChestOrTerminal(World world, int bx, int by, int bz) {
        if (world == null) return false;
        if (isAtLocation(constructionChestLoc, world, bx, by, bz)) return true;
        if (isAtLocation(constructionChestLoc2, world, bx, by, bz)) return true;
        if (isAtLocation(fuelChestLoc, world, bx, by, bz)) return true;
        if (isAtLocation(fuelChestLoc2, world, bx, by, bz)) return true;
        if (isAtLocation(quarryStorageChestLoc, world, bx, by, bz)) return true;
        if (isAtLocation(quarryStorageChestLoc2, world, bx, by, bz)) return true;
        if (isAtLocation(quarryFuelChestLoc, world, bx, by, bz)) return true;
        if (isAtLocation(quarryFuelChestLoc2, world, bx, by, bz)) return true;
        if (isAtLocation(terminalBlockLoc, world, bx, by, bz)) return true;
        return false;
    }

    public boolean isSelfOrAnyChestOrTerminal(World world, int bx, int by, int bz) {
        if (isSelfChestOrTerminal(world, bx, by, bz)) return true;
        if (plugin instanceof MultiverseProgrammingPlugin mvp && mvp.getTurtleManager() != null) {
            for (Turtle t : mvp.getTurtleManager().getAllTurtles()) {
                if (t.isSelfChestOrTerminal(world, bx, by, bz)) {
                    return true;
                }
            }
        }
        return false;
    }

    // =========================================================================
    // Movement
    // =========================================================================

    public boolean forward() {
        return moveInDirection(facing);
    }

    public boolean back() {
        return moveInDirection(facing.getOppositeFace());
    }

    public boolean up() {
        return moveInDirection(BlockFace.UP);
    }

    public boolean down() {
        return moveInDirection(BlockFace.DOWN);
    }

    public synchronized boolean turnLeft() {
        BlockFace oldFacing = this.facing;
        this.facing = switch (facing) {
            case NORTH -> BlockFace.WEST;
            case WEST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.EAST;
            case EAST -> BlockFace.NORTH;
            default -> BlockFace.NORTH;
        };

        if (quarryLateralSide != null && location != null && location.getWorld() != null) {
            World world = location.getWorld();
            BlockFace lateralFace = getLateralFace(this.facing, quarryLateralSide);
            Block newEngineBlock = location.getBlock().getRelative(lateralFace);
            BlockFace oldLateralFace = getLateralFace(oldFacing, quarryLateralSide);
            Block oldEngineBlock = location.getBlock().getRelative(oldLateralFace);
            if (newEngineBlock != null && !canSafelyOccupy(newEngineBlock)
                    && oldEngineBlock != null && !newEngineBlock.getLocation().equals(oldEngineBlock.getLocation())) {
                this.facing = oldFacing;
                return false;
            }
            SyncDispatcher.sync(plugin, () -> {
                relocateTurtleWithEngine(world, this.location, this.facing);
                return null;
            });
            return true;
        }

        updateBlockFacing();
        return true;
    }

    public synchronized boolean turnRight() {
        BlockFace oldFacing = this.facing;
        this.facing = switch (facing) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            case WEST -> BlockFace.NORTH;
            default -> BlockFace.NORTH;
        };

        if (quarryLateralSide != null && location != null && location.getWorld() != null) {
            World world = location.getWorld();
            BlockFace lateralFace = getLateralFace(this.facing, quarryLateralSide);
            Block newEngineBlock = location.getBlock().getRelative(lateralFace);
            BlockFace oldLateralFace = getLateralFace(oldFacing, quarryLateralSide);
            Block oldEngineBlock = location.getBlock().getRelative(oldLateralFace);
            if (newEngineBlock != null && !canSafelyOccupy(newEngineBlock)
                    && oldEngineBlock != null && !newEngineBlock.getLocation().equals(oldEngineBlock.getLocation())) {
                this.facing = oldFacing;
                return false;
            }
            SyncDispatcher.sync(plugin, () -> {
                relocateTurtleWithEngine(world, this.location, this.facing);
                return null;
            });
            return true;
        }

        updateBlockFacing();
        return true;
    }

    private void updateBlockFacing() {
        if (location == null || location.getWorld() == null) {
            return;
        }
        SyncDispatcher.sync(plugin, () -> {
            try {
                Block block = location.getBlock();
                if (block != null && block.getBlockData() instanceof Directional dir) {
                    dir.setFacing(facing);
                    block.setBlockData(dir, false);
                }
            } catch (Throwable ignored) {
            }
            return null;
        });
    }

    private boolean moveInDirection(BlockFace dir) {
        return SyncDispatcher.sync(plugin, () -> {
            World world = location.getWorld();
            if (world == null) return false;

            Block currentBlock = location.getBlock();
            Block targetBlock = currentBlock.getRelative(dir);

            if (!canSafelyOccupy(targetBlock)) {
                return false; // Obstacle or delicate block (redstone, crops, torch, etc.)
            }
            if (!canOwnerModify(targetBlock)) {
                return false;
            }

            if (quarryLateralSide != null) {
                BlockFace lateralFace = getLateralFace(facing, quarryLateralSide);
                Block targetEngineBlock = targetBlock.getRelative(lateralFace);
                Location currEngineLoc = currentBlock.getRelative(lateralFace).getLocation();
                if (targetEngineBlock != null && !canSafelyOccupy(targetEngineBlock)
                        && !targetEngineBlock.getLocation().equals(location)
                        && !targetEngineBlock.getLocation().equals(currEngineLoc)) {
                    return false; // Engine obstructed or delicate block
                }

                if (!consumeQuarryFuel()) {
                    return false;
                }

                relocateTurtleWithEngine(world, targetBlock.getLocation(), facing);
                return true;
            }

            if (isFuelRequired() && getFuel() <= 0) {
                return false; // Out of fuel: the turtle cannot move
            }

            Material mat = currentBlock.getType();
            currentBlock.setType(Material.AIR, false);

            targetBlock.setType(mat, false);
            if (targetBlock.getBlockData() instanceof Directional d) {
                try {
                    d.setFacing(facing);
                    targetBlock.setBlockData(d, false);
                } catch (IllegalArgumentException ignored) {
                }
            }
            updateBlockPdc(targetBlock);

            Location oldLoc = this.location.clone();
            this.location = targetBlock.getLocation();
            TurtleManager tm = getTurtleManager();
            if (tm != null) {
                tm.updateTurtleLocation(this, oldLoc, this.location);
            }
            world.playSound(this.location, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.4f, 1.8f);
            if (isFuelRequired()) {
                setFuel(getFuel() - 1);
            }
            return true;
        });
    }

    public void updateBlockPdc(Block block) {
        if (block != null && block.getState() instanceof org.bukkit.block.TileState tileState) {
            try {
                var pdc = tileState.getPersistentDataContainer();
                if (this.id != null) {
                    pdc.set(new org.bukkit.NamespacedKey(plugin, "turtle_id"), org.bukkit.persistence.PersistentDataType.STRING, this.id);
                }
                if (this.owner != null) {
                    pdc.set(new org.bukkit.NamespacedKey(plugin, "turtle_owner"), org.bukkit.persistence.PersistentDataType.STRING, this.owner.toString());
                }
                tileState.update();
            } catch (Throwable ignored) {}
        }
    }

    // =========================================================================
    // Digging and Placing
    // =========================================================================

    public boolean dig() {
        return digFace(facing);
    }

    public boolean digUp() {
        return digFace(BlockFace.UP);
    }

    public boolean digDown() {
        return digFace(BlockFace.DOWN);
    }

    private boolean digFace(BlockFace face) {
        return SyncDispatcher.sync(plugin, () -> {
            Block target = location.getBlock().getRelative(face);
            if (target.isEmpty() || isIllegalBlock(target.getType())) {
                return false;
            }
            if (isSelfOrAnyChestOrTerminal(target.getWorld(), target.getX(), target.getY(), target.getZ())) {
                notifyOwner("§c[Turtle " + id + "] Cannot dig block: target is a Turtle supply chest or terminal.");
                return false;
            }
            if (isFuelRequired() && getFuel() <= 0) {
                notifyOwner("§c[Turtle " + id + "] Out of fuel: cannot dig.");
                return false;
            }
            if (!canOwnerModify(target)) {
                return false;
            }

            List<ItemStack> drops = new ArrayList<>(target.getDrops());
            // setType(AIR) deletes container contents without dropping them; shulker drops already carry theirs.
            if (target.getState() instanceof org.bukkit.block.Container container
                    && !(container instanceof org.bukkit.block.ShulkerBox)) {
                for (ItemStack content : container.getSnapshotInventory().getContents()) {
                    if (content != null && !content.getType().isAir()) {
                        drops.add(content);
                    }
                }
            }
            Material oldMat = target.getType();
            BlockData oldData = target.getBlockData();
            Location targetLoc = target.getLocation();

            target.setType(Material.AIR, true);
            CoreProtectBridge.logRemoval(owner, id, targetLoc, oldMat, oldData);

            for (ItemStack drop : drops) {
                if (drop == null || drop.getType().isAir()) continue;
                ItemStack remaining = addToInventory(drop);
                if (remaining != null && remaining.getAmount() > 0) {
                    target.getWorld().dropItemNaturally(target.getLocation(), remaining);
                }
            }
            if (isFuelRequired()) {
                setFuel(getFuel() - 1);
            }
            return true;
        });
    }

    public boolean place() {
        return placeFace(facing);
    }

    public boolean placeUp() {
        return placeFace(BlockFace.UP);
    }

    public boolean placeDown() {
        return placeFace(BlockFace.DOWN);
    }

    private boolean placeFace(BlockFace face) {
        return SyncDispatcher.sync(plugin, () -> {
            Block target = location.getBlock().getRelative(face);
            if (!target.isEmpty() && !target.isPassable()) {
                return false;
            }

            ItemStack stack;
            synchronized (this) {
                stack = inventory[selectedSlot];
            }
            if (stack == null || stack.getAmount() <= 0 || !stack.getType().isBlock() || isIllegalBlock(stack.getType())) {
                return false;
            }
            // setType places an empty block: a filled shulker box (or any item carrying block-entity data) would lose its contents.
            if (stack.hasItemMeta() && stack.getItemMeta() instanceof org.bukkit.inventory.meta.BlockStateMeta bsm && bsm.hasBlockState()) {
                notifyOwner("§c[Turtle " + id + "] Cannot place " + stack.getType().name() + ": it holds stored contents.");
                return false;
            }
            if (!canOwnerModify(target)) {
                return false;
            }

            target.setType(stack.getType(), true);
            CoreProtectBridge.logPlacement(owner, id, target.getLocation(), stack.getType(), target.getBlockData());

            synchronized (this) {
                stack.setAmount(stack.getAmount() - 1);
                if (stack.getAmount() <= 0) {
                    inventory[selectedSlot] = null;
                }
            }
            return true;
        });
    }

    /**
     * Claim check (WorldGuard / ProtectionStones / BentoBox) for single-block dig, place and move,
     * evaluated as the turtle's owner.
     */
    private boolean canOwnerModify(Block target) {
        if (plugin instanceof MultiverseProgrammingPlugin mvp && mvp.getProtectionManager() != null
                && !mvp.getProtectionManager().canBuildAt(owner, target.getLocation())) {
            notifyOwner("§c[Turtle " + id + "] Blocked: target is inside a protected area.");
            return false;
        }
        return true;
    }

    public synchronized ItemStack addToInventory(ItemStack item) {
        if (item == null || item.getAmount() <= 0) return null;
        ItemStack toAdd = item.clone();

        // 1. Try stacking into existing non-full slots
        for (int i = 0; i < 16; i++) {
            ItemStack existing = inventory[i];
            if (existing != null && existing.isSimilar(toAdd)) {
                int space = existing.getMaxStackSize() - existing.getAmount();
                if (space > 0) {
                    int add = Math.min(space, toAdd.getAmount());
                    existing.setAmount(existing.getAmount() + add);
                    toAdd.setAmount(toAdd.getAmount() - add);
                    if (toAdd.getAmount() <= 0) return null;
                }
            }
        }

        // 2. Try empty slots
        for (int i = 0; i < 16; i++) {
            if (inventory[i] == null || inventory[i].getType().isAir()) {
                inventory[i] = toAdd.clone();
                return null;
            }
        }
        return toAdd;
    }

    // =========================================================================
    // Refueling
    // =========================================================================

    public synchronized boolean refuel(int count) {
        ItemStack stack = inventory[selectedSlot];
        int fuelPerItem = getFuelValue(stack != null ? stack.getType() : null);

        if (fuelPerItem > 0 && stack != null && stack.getAmount() > 0) {
            int consume = (count <= 0) ? stack.getAmount() : Math.min(count, stack.getAmount());
            this.fuel += consume * fuelPerItem;

            if (stack.getType() == Material.LAVA_BUCKET) {
                inventory[selectedSlot] = new ItemStack(Material.BUCKET, consume);
            } else {
                stack.setAmount(stack.getAmount() - consume);
                if (stack.getAmount() <= 0) {
                    inventory[selectedSlot] = null;
                }
            }
            return true;
        }

        // Fallback: check fuel chests
        if (refuelFromChest(fuelChestLoc, count)) {
            return true;
        }
        if (refuelFromChest(fuelChestLoc2, count)) {
            return true;
        }

        return false;
    }

    private boolean refuelFromChest(Location chestLoc, int count) {
        if (chestLoc == null || chestLoc.getWorld() == null) return false;
        Block block = chestLoc.getBlock();
        if (block.getState() instanceof org.bukkit.block.Container container) {
            Inventory chestInv = container.getInventory();
            for (int slot = 0; slot < chestInv.getSize(); slot++) {
                ItemStack cItem = chestInv.getItem(slot);
                int fVal = getFuelValue(cItem != null ? cItem.getType() : null);
                if (fVal > 0 && cItem != null && cItem.getAmount() > 0) {
                    int consume = (count <= 0) ? cItem.getAmount() : Math.min(count, cItem.getAmount());
                    this.fuel += consume * fVal;
                    if (cItem.getType() == Material.LAVA_BUCKET) {
                        chestInv.setItem(slot, new ItemStack(Material.BUCKET, consume));
                    } else {
                        cItem.setAmount(cItem.getAmount() - consume);
                        if (cItem.getAmount() <= 0) {
                            chestInv.setItem(slot, null);
                        }
                    }
                    return true;
                }
            }
        }
        return false;
    }

    public static int getFuelValue(Material mat) {
        if (mat == null) return 0;
        return switch (mat) {
            case COAL, CHARCOAL -> 80;
            case BLAZE_ROD -> 120;
            case LAVA_BUCKET -> 1000;
            case COAL_BLOCK -> 800;
            default -> 0;
        };
    }

    /**
     * Consumes fuel directly from an external ItemStack (e.g. from the player's cursor)
     * without touching or overwriting the turtle's internal inventory slot.
     *
     * @param stack The fuel item stack to consume from.
     * @param count The number of items to consume (0 or less consumes the entire stack).
     * @return The remaining item stack (e.g. reduced count or empty BUCKET), or null if fully consumed.
     */
    public synchronized ItemStack refuelWithItem(ItemStack stack, int count) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
            return stack;
        }
        int fuelPerItem = getFuelValue(stack.getType());
        if (fuelPerItem <= 0) {
            return stack;
        }
        int consume = (count <= 0) ? stack.getAmount() : Math.min(count, stack.getAmount());
        this.fuel += consume * fuelPerItem;

        if (stack.getType() == Material.LAVA_BUCKET) {
            if (stack.getAmount() == 1) {
                return new ItemStack(Material.BUCKET);
            } else {
                stack.setAmount(stack.getAmount() - 1);
                return stack;
            }
        } else {
            stack.setAmount(stack.getAmount() - consume);
            if (stack.getAmount() <= 0) {
                return null;
            }
            return stack;
        }
    }

    // =========================================================================
    // Blueprint Construction
    // =========================================================================

    public synchronized boolean startBuild(
            Blueprint blueprint,
            Location origin,
            int delayTicks,
            boolean requireMaterials,
            boolean clearBlocks,
            int rotationDegrees,
            Runnable onDone,
            Consumer<String> onError
    ) {
        cancelBuild();

        if (blueprint == null) {
            failBuild("Blueprint cannot be null", onError);
            return false;
        }
        if (origin == null || origin.getWorld() == null) {
            failBuild("Target coordinates (origin) are mandatory. The turtle will not build without explicit coordinates.", onError);
            return false;
        }

        if (rotationDegrees != 0) {
            blueprint = blueprint.rotate(rotationDegrees);
        }

        // Validate area obstruction, region protection claims, and clear if permitted
        if (!checkAndPrepareArea(blueprint, origin, clearBlocks, onError)) {
            return false;
        }

        if (requireMaterials) {
            setupConstructionChests(origin);
        }

        this.activeBuildPositions.clear();
        if (blueprint != null && blueprint.blocks() != null) {
            for (PlacementBlock pb : blueprint.blocks()) {
                Material targetMat = parseMaterialFromBlockState(pb.material());
                if (targetMat != null && !targetMat.isAir()) {
                    this.activeBuildPositions.add(new BlockPos(pb.x(), pb.y(), pb.z()));
                }
            }
        }

        this.activeBlueprint = blueprint;
        this.activeBlueprintId = blueprint.id();
        this.buildOrigin = origin.clone();
        this.totalBlocks = blueprint.totalBlocks();
        this.currentBlockIndex.set(0);
        this.status = Status.BUILDING;
        this.statusMessage = "Building " + blueprint.name() + " (0%)";

        int safeDelay = Math.max(1, delayTicks);

        if (Bukkit.getScheduler() != null && plugin != null) {
            this.buildTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (status == Status.PAUSED) {
                return;
            }

            int index = currentBlockIndex.get();
            if (index >= activeBlueprint.blocks().size()) {
                finishBuild(onDone);
                return;
            }

            PlacementBlock pb = activeBlueprint.blocks().get(index);
            BlockData blockData = parseBlockData(pb.material());
            Material blockMat = (blockData != null) ? blockData.getMaterial() : parseMaterialFromBlockState(pb.material());

            if (blockMat == null || blockMat.isAir() || isIllegalBlock(blockMat)) {
                currentBlockIndex.incrementAndGet();
                return;
            }

            World world = buildOrigin.getWorld();
            if (world == null) {
                failBuild("Build origin world is unloaded.", onError);
                return;
            }

            int targetX = buildOrigin.getBlockX() + pb.x();
            int targetY = buildOrigin.getBlockY() + pb.y();
            int targetZ = buildOrigin.getBlockZ() + pb.z();

            if (targetY < world.getMinHeight() || targetY >= world.getMaxHeight()) {
                currentBlockIndex.incrementAndGet();
                return;
            }

            int chunkX = targetX >> 4;
            int chunkZ = targetZ >> 4;
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                try {
                    world.getChunkAtAsync(chunkX, chunkZ);
                } catch (Throwable ignored) {
                    world.loadChunk(chunkX, chunkZ, false);
                }
                return;
            }

            // Material Check if required. This MUST come after the out-of-bounds and chunk-loaded
            // guards above: those paths skip or retry the current block, and consuming before them
            // would drop an item on a skipped block or consume one on every retry tick until the
            // chunk loads. Here the block is guaranteed to be placed this tick.
            // Upper halves of doors/tall plants or head parts of beds are formed with the lower/foot
            // part, so they should only consume 1 item for the pair.
            if (requireMaterials) {
                Material itemMat = getItemMaterialForBlock(blockMat);
                boolean isUpper = pb.material().contains("half=upper") || pb.material().contains("part=head");
                if (!isUpper && itemMat.isItem()) {
                    if (!consumeMaterial(itemMat)) {
                        this.status = Status.PAUSED;
                        this.statusMessage = "Paused: Missing material " + itemMat.name();
                        if (onError != null) {
                            onError.accept("Turtle is missing required material: " + itemMat.name());
                        }
                        updateConstructionHologramMissing(itemMat);
                        return;
                    }
                }
            }

            if (true) {
                // Physically relocate the turtle to an adjacent free spot facing the block
                moveTurtleAdjacentTo(world, targetX, targetY, targetZ);

                Block targetBlock = world.getBlockAt(targetX, targetY, targetZ);
                boolean updated = false;
                if (blockData != null) {
                    if (targetBlock.getBlockData() == null || !targetBlock.getBlockData().matches(blockData)) {
                        targetBlock.setBlockData(blockData, false);
                        updated = true;
                        CoreProtectBridge.logPlacement(owner, id, targetBlock.getLocation(), blockData.getMaterial(), blockData);
                    }
                } else if (targetBlock.getType() != blockMat) {
                    targetBlock.setType(blockMat, false);
                    updated = true;
                    CoreProtectBridge.logPlacement(owner, id, targetBlock.getLocation(), blockMat, targetBlock.getBlockData());
                }

                if (updated) {
                    try {
                        world.spawnParticle(Particle.HAPPY_VILLAGER, targetX + 0.5, targetY + 0.5, targetZ + 0.5, 2, 0.1, 0.1, 0.1, 0.02);
                        world.playSound(targetBlock.getLocation(), Sound.BLOCK_STONE_PLACE, 0.5f, 1.0f);
                    } catch (Throwable ignored) {}
                }
            }

            int next = currentBlockIndex.incrementAndGet();
            this.statusMessage = String.format(Locale.ROOT, "Building %s (%d/%d - %.1f%%)",
                    activeBlueprint.name(), next, totalBlocks, getProgressPercentage());

            if (next >= totalBlocks) {
                finishBuild(onDone);
            } else if (requireMaterials) {
                PlacementBlock nextPb = activeBlueprint.blocks().get(next);
                BlockData nextData = parseBlockData(nextPb.material());
                Material nextMat = (nextData != null) ? nextData.getMaterial() : parseMaterialFromBlockState(nextPb.material());
                if (nextMat != null && !nextMat.isAir()) {
                    updateConstructionHologramNeeded(getItemMaterialForBlock(nextMat));
                }
            }
        }, 1L, safeDelay);
        }

        return true;
    }

    public synchronized boolean startBuild(
            Blueprint blueprint,
            Location origin,
            int delayTicks,
            boolean requireMaterials,
            boolean clearBlocks,
            Runnable onDone,
            Consumer<String> onError
    ) {
        return startBuild(blueprint, origin, delayTicks, requireMaterials, clearBlocks, 0, onDone, onError);
    }

    public synchronized boolean startBuild(
            Blueprint blueprint,
            Location origin,
            int delayTicks,
            boolean requireMaterials,
            Runnable onDone,
            Consumer<String> onError
    ) {
        return startBuild(blueprint, origin, delayTicks, requireMaterials, false, 0, onDone, onError);
    }

    private boolean checkAndPrepareArea(Blueprint bp, Location origin, boolean clearBlocks, Consumer<String> onError) {
        World world = origin.getWorld();
        if (world == null) {
            failBuild("World is not loaded.", onError);
            return false;
        }

        // Region claims protection check (WorldGuard / ProtectionStones)
        if (plugin instanceof MultiverseProgrammingPlugin mvp && mvp.getProtectionManager() != null) {
            String protectionError = mvp.getProtectionManager().checkBuildArea(this.owner, origin, bp);
            if (protectionError != null) {
                failBuild(protectionError, onError);
                return false;
            }
        }

        List<Block> obstructed = new ArrayList<>();
        for (PlacementBlock pb : bp.blocks()) {
            Material targetMat = parseMaterialFromBlockState(pb.material());
            if (targetMat == null || targetMat.isAir()) continue;

            int bx = origin.getBlockX() + pb.x();
            int by = origin.getBlockY() + pb.y();
            int bz = origin.getBlockZ() + pb.z();

            if (isSelfOrAnyChestOrTerminal(world, bx, by, bz)) {
                String error = String.format("Cannot build or destroy blocks here: target position [%d, %d, %d] overlaps with Turtle supply chests or terminal. Please reposition the build area.", bx, by, bz);
                failBuild(error, onError);
                return false;
            }

            if (by < world.getMinHeight() || by >= world.getMaxHeight()) continue;

            if (this.location != null
                    && this.location.getBlockX() == bx
                    && this.location.getBlockY() == by
                    && this.location.getBlockZ() == bz) {
                continue;
            }

            Block existing = world.getBlockAt(bx, by, bz);
            if (existing != null && !existing.isEmpty() && existing.getType() != targetMat) {
                obstructed.add(existing);
            }
        }

        if (!obstructed.isEmpty()) {
            if (!clearBlocks) {
                StringBuilder sample = new StringBuilder();
                int limit = Math.min(3, obstructed.size());
                for (int i = 0; i < limit; i++) {
                    Block b = obstructed.get(i);
                    if (i > 0) sample.append(", ");
                    sample.append(b.getType().name()).append(" at [").append(b.getX()).append(", ").append(b.getY()).append(", ").append(b.getZ()).append("]");
                }
                if (obstructed.size() > limit) {
                    sample.append(", and ").append(obstructed.size() - limit).append(" more");
                }
                String detail = String.format("Target area is obstructed by %d existing block(s) (%s). Clear the area first or specify 'clear' to destroy them without drops (e.g. turtle.build(id, x, y, z, true)).",
                        obstructed.size(), sample);
                failBuild(detail, onError);
                return false;
            }
            // Clear obstructed blocks without drops
            for (Block b : obstructed) {
                if (isSelfOrAnyChestOrTerminal(b.getWorld(), b.getX(), b.getY(), b.getZ())) {
                    continue;
                }
                if (!isIllegalBlock(b.getType())) {
                    Material oldMat = b.getType();
                    BlockData oldData = b.getBlockData();
                    Location bLoc = b.getLocation();
                    b.setType(Material.AIR, false);
                    CoreProtectBridge.logRemoval(owner, id, bLoc, oldMat, oldData);
                }
            }
        }

        return true;
    }

    private void moveTurtleAdjacentTo(World world, int targetX, int targetY, int targetZ) {
        if (world == null || this.location == null) return;

        int curX = this.location.getBlockX();
        int curY = this.location.getBlockY();
        int curZ = this.location.getBlockZ();

        int dx = curX - targetX;
        int dy = curY - targetY;
        int dz = curZ - targetZ;

        // If turtle is already adjacent (Manhattan distance == 1)
        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) == 1) {
            BlockFace faceTowards = determineFacing(dx, dy, dz);
            if (faceTowards != null) {
                setTurtleFacing(faceTowards);
            }
            try {
                world.playSound(this.location, Sound.BLOCK_DISPENSER_DISPENSE, 0.3f, 1.8f);
            } catch (Throwable ignored) {}
            return;
        }

        // Potential adjacent offsets and the facing needed to look at the target block
        int[][] candidateOffsets = new int[][] {
                {0, 0, -1},  // NORTH of target -> turtle faces SOUTH
                {1, 0, 0},   // EAST of target -> turtle faces WEST
                {0, 0, 1},   // SOUTH of target -> turtle faces NORTH
                {-1, 0, 0},  // WEST of target -> turtle faces EAST
                {0, 1, 0},   // UP of target -> turtle faces DOWN
                {0, -1, 0}   // DOWN of target -> turtle faces UP
        };

        BlockFace[] candidateFacings = new BlockFace[] {
                BlockFace.SOUTH,
                BlockFace.WEST,
                BlockFace.NORTH,
                BlockFace.EAST,
                BlockFace.DOWN,
                BlockFace.UP
        };

        int bestIndex = -1;
        double minDistanceSq = Double.MAX_VALUE;

        for (int i = 0; i < candidateOffsets.length; i++) {
            int cx = targetX + candidateOffsets[i][0];
            int cy = targetY + candidateOffsets[i][1];
            int cz = targetZ + candidateOffsets[i][2];

            if (cy < world.getMinHeight() || cy >= world.getMaxHeight()) {
                continue;
            }

            Block candidateBlock = world.getBlockAt(cx, cy, cz);
            boolean isSelf = (cx == curX && cy == curY && cz == curZ);
            if (isSelf || canSafelyOccupy(candidateBlock)) {
                double distSq = (cx - curX) * (cx - curX) + (cy - curY) * (cy - curY) + (cz - curZ) * (cz - curZ);
                if (i >= 4) {
                    distSq += 0.5; // slight preference for ground/horizontal
                }
                if (distSq < minDistanceSq) {
                    minDistanceSq = distSq;
                    bestIndex = i;
                }
            }
        }

        if (bestIndex != -1) {
            int bx = targetX + candidateOffsets[bestIndex][0];
            int by = targetY + candidateOffsets[bestIndex][1];
            int bz = targetZ + candidateOffsets[bestIndex][2];
            BlockFace bestFacing = candidateFacings[bestIndex];

            Location newLoc = new Location(world, bx, by, bz);
            if (!newLoc.equals(this.location)) {
                relocateTurtleTo(world, newLoc, bestFacing);
            } else {
                setTurtleFacing(bestFacing);
            }
        }
    }

    /**
     * Determines whether the turtle can safely relocate to a block location without destroying
     * valuable components like redstone dust, crops, torches, rails, or delicate flora.
     */
    public static boolean canSafelyOccupy(Block block) {
        if (block == null) return false;
        Material mat = block.getType();
        if (mat.isAir()) return true;

        // Disallow delicate blocks, redstone components, crops, plants, rails, etc.
        // Even though Bukkit considers them isPassable(), stepping on them destroys or pops them off.
        String name = mat.name();
        if (name.contains("REDSTONE") || name.contains("WIRE")
                || name.contains("REPEATER") || name.contains("COMPARATOR")
                || name.contains("RAIL") || name.contains("TORCH")
                || name.contains("CROP") || name.contains("STEM")
                || name.contains("SAPLING") || name.contains("CARROT")
                || name.contains("POTATO") || name.contains("BEETROOT")
                || name.contains("WHEAT") || name.contains("FLOWER")
                || name.contains("TULIP") || name.contains("ORCHID")
                || name.contains("ROSE") || name.contains("DAISY")
                || name.contains("POPPY") || name.contains("BLUET")
                || name.contains("ALLIUM") || name.contains("LILY")
                || name.contains("BERRY") || name.contains("MUSHROOM")
                || name.contains("FUNGUS") || name.contains("VINE")
                || name.contains("LICHEN") || name.contains("HANGING")
                || name.contains("LEVER") || name.contains("BUTTON")
                || name.contains("PRESSURE_PLATE") || name.contains("TRIPWIRE")
                || name.contains("CARPET") || name.contains("STRING")
                || name.contains("AMETHYST_CLUSTER") || name.contains("BUD")
                || name.contains("SPORE_BLOSSOM") || name.contains("DRIPSTONE")
                || name.contains("SCULK_VEIN") || name.contains("KELP")
                || name.contains("SEAGRASS") || name.contains("CORAL")
                || name.contains("LANTERN") || name.contains("BELL")
                || name.contains("BANNER") || name.contains("SIGN")) {
            return false;
        }

        // Only allow empty or non-solid passable blocks (like pure light air, water/air voids)
        return block.isEmpty();
    }

    private BlockFace determineFacing(int dx, int dy, int dz) {
        if (dx == 1 && dy == 0 && dz == 0) return BlockFace.WEST;
        if (dx == -1 && dy == 0 && dz == 0) return BlockFace.EAST;
        if (dz == 1 && dx == 0 && dy == 0) return BlockFace.NORTH;
        if (dz == -1 && dx == 0 && dy == 0) return BlockFace.SOUTH;
        if (dy == 1 && dx == 0 && dz == 0) return BlockFace.DOWN;
        if (dy == -1 && dx == 0 && dz == 0) return BlockFace.UP;
        return BlockFace.NORTH;
    }

    private void setTurtleFacing(BlockFace newFacing) {
        this.facing = newFacing;
        if (location == null || location.getWorld() == null) return;
        try {
            Block block = location.getBlock();
            if (block != null && block.getBlockData() instanceof Directional dir) {
                dir.setFacing(newFacing);
                block.setBlockData(dir, false);
            }
        } catch (Throwable ignored) {}
    }

    private void relocateTurtleTo(World world, Location newLoc, BlockFace newFacing) {
        Block oldBlock = this.location.getBlock();
        Material turtleMat = (oldBlock != null) ? oldBlock.getType() : Material.DISPENSER;
        if (!turtleMat.isBlock() || turtleMat.isAir()) {
            turtleMat = Material.DISPENSER;
        }

        if (oldBlock != null) {
            oldBlock.setType(Material.AIR, false);
        }

        Block newBlock = newLoc.getBlock();
        if (newBlock != null) {
            newBlock.setType(turtleMat, false);
            if (newBlock.getBlockData() instanceof Directional dir) {
                try {
                    dir.setFacing(newFacing);
                    newBlock.setBlockData(dir, false);
                } catch (Throwable ignored) {}
            }
            updateBlockPdc(newBlock);
        }

        Location oldLoc = this.location.clone();
        this.location = newLoc.clone();
        this.facing = newFacing;

        TurtleManager tm = getTurtleManager();
        if (tm != null) {
            tm.updateTurtleLocation(this, oldLoc, this.location);
        }

        try {
            world.playSound(newLoc, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.45f, 1.6f);
            world.spawnParticle(Particle.SMOKE, oldLoc.getX() + 0.5, oldLoc.getY() + 0.5, oldLoc.getZ() + 0.5, 3, 0.08, 0.08, 0.08, 0.01);
        } catch (Throwable ignored) {}
    }

    /** @return true if manual turtle movement/digging should consume fuel (per {@code turtle-fuel-required}). */
    private boolean isFuelRequired() {
        if (plugin instanceof MultiverseProgrammingPlugin mvp && mvp.getConfigManager() != null) {
            return mvp.getConfigManager().isTurtleFuelRequired();
        }
        return false;
    }

    private TurtleManager getTurtleManager() {
        if (plugin instanceof MultiverseProgrammingPlugin mvp) {
            return mvp.getTurtleManager();
        }
        return null;
    }

    private synchronized boolean consumeMaterial(Material mat) {
        // 1. Check internal turtle inventory
        for (int i = 0; i < 16; i++) {
            ItemStack stack = inventory[i];
            if (stack != null && stack.getAmount() > 0) {
                if (stack.getType() == mat ||
                        (mat == Material.WATER && stack.getType() == Material.WATER_BUCKET) ||
                        (mat == Material.LAVA && stack.getType() == Material.LAVA_BUCKET)) {
                    stack.setAmount(stack.getAmount() - 1);
                    if (stack.getAmount() <= 0) {
                        inventory[i] = null;
                    }
                    return true;
                }
            }
        }

        // 2. Check construction supply chests (both halves)
        if (consumeFromChest(constructionChestLoc, mat)) {
            return true;
        }
        if (consumeFromChest(constructionChestLoc2, mat)) {
            return true;
        }

        return false;
    }

    private boolean consumeFromChest(Location chestLoc, Material mat) {
        if (chestLoc == null || chestLoc.getWorld() == null) return false;
        Block chestBlock = chestLoc.getBlock();
        if (chestBlock.getState() instanceof org.bukkit.block.Container container) {
            Inventory chestInv = container.getInventory();
            for (int slot = 0; slot < chestInv.getSize(); slot++) {
                ItemStack item = chestInv.getItem(slot);
                if (item != null && item.getAmount() > 0) {
                    if (item.getType() == mat ||
                            (mat == Material.WATER && item.getType() == Material.WATER_BUCKET) ||
                            (mat == Material.LAVA && item.getType() == Material.LAVA_BUCKET)) {
                        item.setAmount(item.getAmount() - 1);
                        if (item.getAmount() <= 0) {
                            chestInv.setItem(slot, null);
                        }
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private record DoubleChestPair(Block s1, Block s2, Block f1, Block f2, Block terminal) {}

    private static boolean isPlaceableOrChest(Block b) {
        if (b == null) return false;
        Material mat = b.getType();
        return mat.isAir() || b.isEmpty() || b.isPassable() || mat == Material.CHEST || mat == Material.TRAPPED_CHEST || mat == Material.BARREL;
    }

    private static boolean isPlaceableOrTerminal(Block b) {
        return isPlaceableOrChest(b) || (b != null && b.getType() == Material.LODESTONE);
    }

    public static void placeDoubleChest(Block b1, Block b2, BlockFace chestFacing) {
        if (b1 == null || b2 == null) return;
        b1.setType(Material.CHEST, false);
        b2.setType(Material.CHEST, false);

        int dx = b2.getX() - b1.getX();
        int dz = b2.getZ() - b1.getZ();

        // Facing must be perpendicular to the axis connecting b1 and b2
        if (dx != 0 && (chestFacing == BlockFace.EAST || chestFacing == BlockFace.WEST)) {
            chestFacing = BlockFace.NORTH;
        } else if (dz != 0 && (chestFacing == BlockFace.NORTH || chestFacing == BlockFace.SOUTH)) {
            chestFacing = BlockFace.EAST;
        }

        BlockFace clockwise = getRightFace(chestFacing);
        boolean b2IsClockwise = (dx == clockwise.getModX() && dz == clockwise.getModZ());

        Block bRight = b2IsClockwise ? b2 : b1;
        Block bLeft = b2IsClockwise ? b1 : b2;

        try {
            if (bLeft.getBlockData() instanceof org.bukkit.block.data.type.Chest chestLeft) {
                chestLeft.setFacing(chestFacing);
                chestLeft.setType(org.bukkit.block.data.type.Chest.Type.LEFT);
                bLeft.setBlockData(chestLeft, false);
            }
            if (bRight.getBlockData() instanceof org.bukkit.block.data.type.Chest chestRight) {
                chestRight.setFacing(chestFacing);
                chestRight.setType(org.bukkit.block.data.type.Chest.Type.RIGHT);
                bRight.setBlockData(chestRight, false);
            }
        } catch (Throwable ignored) {}
    }

    private DoubleChestPair setupSeparatedDoubleChests(Location startLoc) {
        World world = startLoc.getWorld();
        if (world == null) return null;

        Block startBlock = world.getBlockAt(startLoc.getBlockX(), startLoc.getBlockY(), startLoc.getBlockZ());
        if (startBlock == null) return null;
        BlockFace back = this.facing.getOppositeFace();
        BlockFace left = getLeftFace(this.facing);
        BlockFace right = getRightFace(this.facing);

        // Terminal monitor placed directly behind the turtle
        Block term = startBlock.getRelative(back, 1);

        // Candidate 1: Standard lateral 5-block separation (Construction 5 blocks to right, Fuel 5 blocks to left)
        Block c1_s1 = term.getRelative(right, 5);
        Block c1_s2 = term.getRelative(right, 6);
        Block c1_f1 = term.getRelative(left, 5);
        Block c1_f2 = term.getRelative(left, 6);
        if (isPlaceableOrChest(c1_s1) && isPlaceableOrChest(c1_s2) && isPlaceableOrChest(c1_f1) && isPlaceableOrChest(c1_f2) && isPlaceableOrTerminal(term)) {
            return new DoubleChestPair(c1_s1, c1_s2, c1_f1, c1_f2, term);
        }

        // Candidate 2: Inverted lateral 5-block separation (Fuel 5 blocks to right, Construction 5 blocks to left)
        Block c2_s1 = term.getRelative(left, 5);
        Block c2_s2 = term.getRelative(left, 6);
        Block c2_f1 = term.getRelative(right, 5);
        Block c2_f2 = term.getRelative(right, 6);
        if (isPlaceableOrChest(c2_s1) && isPlaceableOrChest(c2_s2) && isPlaceableOrChest(c2_f1) && isPlaceableOrChest(c2_f2) && isPlaceableOrTerminal(term)) {
            return new DoubleChestPair(c2_s1, c2_s2, c2_f1, c2_f2, term);
        }

        // Candidate 3: Backward layout (Chests placed 5 blocks behind the terminal monitor)
        Block back5 = term.getRelative(back, 5);
        Block c3_s1 = back5.getRelative(right, 2);
        Block c3_s2 = back5.getRelative(right, 3);
        Block c3_f1 = back5.getRelative(left, 2);
        Block c3_f2 = back5.getRelative(left, 3);
        if (isPlaceableOrChest(c3_s1) && isPlaceableOrChest(c3_s2) && isPlaceableOrChest(c3_f1) && isPlaceableOrChest(c3_f2) && isPlaceableOrTerminal(term)) {
            return new DoubleChestPair(c3_s1, c3_s2, c3_f1, c3_f2, term);
        }

        // Candidate 4: Linear corridor layout (Storage 5 blocks behind terminal, Fuel 10 blocks behind terminal)
        Block c4_s1 = term.getRelative(back, 5);
        Block c4_s2 = term.getRelative(back, 6);
        Block c4_f1 = term.getRelative(back, 10);
        Block c4_f2 = term.getRelative(back, 11);
        if (isPlaceableOrChest(c4_s1) && isPlaceableOrChest(c4_s2) && isPlaceableOrChest(c4_f1) && isPlaceableOrChest(c4_f2) && isPlaceableOrTerminal(term)) {
            return new DoubleChestPair(c4_s1, c4_s2, c4_f1, c4_f2, term);
        }

        // Default fallback: Candidate 1 positions
        return new DoubleChestPair(c1_s1, c1_s2, c1_f1, c1_f2, term);
    }

    private org.bukkit.entity.Entity spawnHologram(World world, Location loc, String text) {
        if (world == null || loc == null) return null;
        try {
            return world.spawn(loc, org.bukkit.entity.TextDisplay.class, display -> {
                display.addScoreboardTag(TURTLE_HOLOGRAM_TAG);
                display.setText(text);
                display.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
                display.setDefaultBackground(false);
                display.setSeeThrough(true);
            });
        } catch (Throwable fallback) {
            try {
                return world.spawn(loc.clone().subtract(0, 1.0, 0), org.bukkit.entity.ArmorStand.class, as -> {
                    as.addScoreboardTag(TURTLE_HOLOGRAM_TAG);
                    as.setCustomName(text);
                    as.setCustomNameVisible(true);
                    as.setVisible(false);
                    as.setGravity(false);
                    as.setMarker(true);
                });
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    public static void cleanupHologramsAt(Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        try {
            java.util.Collection<org.bukkit.entity.Entity> nearby = loc.getWorld().getNearbyEntities(loc, 3.0, 3.0, 3.0);
            for (org.bukkit.entity.Entity e : nearby) {
                if (e.getScoreboardTags().contains(TURTLE_HOLOGRAM_TAG)) {
                    e.remove();
                }
            }
        } catch (Throwable ignored) {}
    }

    private void updateHologram(org.bukkit.entity.Entity holo, String text) {
        if (holo == null || !holo.isValid()) return;
        try {
            if (holo instanceof org.bukkit.entity.TextDisplay td) {
                td.setText(text);
            } else if (holo instanceof org.bukkit.entity.ArmorStand as) {
                as.setCustomName(text);
            }
        } catch (Throwable ignored) {}
    }

    private void updateConstructionHologramNeeded(Material neededMat) {
        if (constructionHologram == null || !constructionHologram.isValid()) return;
        int next = currentBlockIndex.get() + 1;
        String blockName = (neededMat != null) ? neededMat.name() : "None";
        String text = "§e📦 Place construction blocks here\n§7Needed now: §f" + blockName + " §8(" + Math.min(next, totalBlocks) + "/" + totalBlocks + ")";
        updateHologram(constructionHologram, text);
    }

    private void updateConstructionHologramMissing(Material missingMat) {
        if (constructionHologram == null || !constructionHologram.isValid()) return;
        int next = currentBlockIndex.get() + 1;
        String blockName = (missingMat != null) ? missingMat.name() : "Unknown";
        String text = "§e📦 Place construction blocks here\n§c⚠️ Missing material: §e" + blockName + " §8(" + Math.min(next, totalBlocks) + "/" + totalBlocks + ")\n§7Add items to chest & use Terminal to resume";
        updateHologram(constructionHologram, text);
    }

    private void setupConstructionChests(Location origin) {
        World world = (this.location != null && this.location.getWorld() != null) ? this.location.getWorld() : origin.getWorld();
        if (world == null) return;

        Location startLoc = (this.location != null) ? this.location : origin;
        DoubleChestPair pair = setupSeparatedDoubleChests(startLoc);
        if (pair == null) return;

        placeDoubleChest(pair.s1(), pair.s2(), this.facing);
        this.constructionChestLoc = pair.s1().getLocation();
        this.constructionChestLoc2 = pair.s2().getLocation();

        placeDoubleChest(pair.f1(), pair.f2(), this.facing);
        this.fuelChestLoc = pair.f1().getLocation();
        this.fuelChestLoc2 = pair.f2().getLocation();

        if (pair.terminal() != null) {
            pair.terminal().setType(Material.LODESTONE, false);
            this.terminalBlockLoc = pair.terminal().getLocation();
        }

        cleanupHolograms();

        Location sHoloLoc = new Location(
                world,
                (pair.s1().getX() + pair.s2().getX()) / 2.0 + 0.5,
                Math.max(pair.s1().getY(), pair.s2().getY()) + 1.35,
                (pair.s1().getZ() + pair.s2().getZ()) / 2.0 + 0.5
        );
        Material firstNeeded = null;
        if (activeBlueprint != null && activeBlueprint.blocks() != null) {
            for (PlacementBlock pb : activeBlueprint.blocks()) {
                Material m = parseMaterialFromBlockState(pb.material());
                if (m != null && !m.isAir()) {
                    firstNeeded = getItemMaterialForBlock(m);
                    break;
                }
            }
        }
        String initialStorageHolo = (firstNeeded != null)
                ? "§e📦 Place construction blocks here\n§7Needed now: §f" + firstNeeded.name() + " §8(1/" + totalBlocks + ")"
                : "§e📦 Place construction blocks here";
        this.constructionHologram = spawnHologram(world, sHoloLoc, initialStorageHolo);

        Location fHoloLoc = new Location(
                world,
                (pair.f1().getX() + pair.f2().getX()) / 2.0 + 0.5,
                Math.max(pair.f1().getY(), pair.f2().getY()) + 1.35,
                (pair.f1().getZ() + pair.f2().getZ()) / 2.0 + 0.5
        );
        this.fuelHologram = spawnHologram(world, fHoloLoc, "§6⚡ Place fuel here");

        if (this.terminalBlockLoc != null) {
            Location termHoloLoc = this.terminalBlockLoc.clone().add(0.5, 1.35, 0.5);
            this.terminalHologram = spawnHologram(world, termHoloLoc, "§b🖥️ Supply Station Terminal\n§7Right-click to control Turtle");
        }
    }

    private void cleanupHolograms() {
        if (constructionHologram != null && constructionHologram.isValid()) {
            try {
                constructionHologram.remove();
            } catch (Throwable ignored) {}
            constructionHologram = null;
        }
        if (fuelHologram != null && fuelHologram.isValid()) {
            try {
                fuelHologram.remove();
            } catch (Throwable ignored) {}
            fuelHologram = null;
        }
        if (terminalHologram != null && terminalHologram.isValid()) {
            try {
                terminalHologram.remove();
            } catch (Throwable ignored) {}
            terminalHologram = null;
        }
        if (constructionChestLoc != null) cleanupHologramsAt(constructionChestLoc);
        if (constructionChestLoc2 != null) cleanupHologramsAt(constructionChestLoc2);
        if (fuelChestLoc != null) cleanupHologramsAt(fuelChestLoc);
        if (fuelChestLoc2 != null) cleanupHologramsAt(fuelChestLoc2);
        if (terminalBlockLoc != null) cleanupHologramsAt(terminalBlockLoc);
    }

    private void finishBuild(Runnable onDone) {
        cleanupHolograms();
        cancelTask();
        activeBuildPositions.clear();
        this.constructionChestLoc = null;
        this.constructionChestLoc2 = null;
        this.fuelChestLoc = null;
        this.fuelChestLoc2 = null;
        this.terminalBlockLoc = null;
        this.status = Status.IDLE;
        this.statusMessage = "Build completed (" + totalBlocks + " blocks)";
        if (buildOrigin != null && buildOrigin.getWorld() != null) {
            buildOrigin.getWorld().playSound(buildOrigin, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
        }
        if (onDone != null) {
            onDone.run();
        }
    }

    private void failBuild(String message, Consumer<String> onError) {
        cleanupHolograms();
        cancelTask();
        activeBuildPositions.clear();
        this.constructionChestLoc = null;
        this.constructionChestLoc2 = null;
        this.fuelChestLoc = null;
        this.fuelChestLoc2 = null;
        this.terminalBlockLoc = null;
        this.status = Status.ERROR;
        this.statusMessage = "Error: " + message;
        notifyOwner("§c[Turtle " + id + " Build Error] §e" + message);
        if (onError != null) {
            onError.accept(message);
        }
    }

    public synchronized void pauseBuild() {
        if (status == Status.BUILDING) {
            this.status = Status.PAUSED;
            this.statusMessage = "Build paused at " + currentBlockIndex.get() + "/" + totalBlocks;
        }
    }

    public synchronized void resumeBuild() {
        if (status == Status.PAUSED) {
            this.status = Status.BUILDING;
            this.statusMessage = "Resuming build...";
        }
    }

    public synchronized void cancelBuild() {
        cleanupHolograms();
        cancelTask();
        activeBuildPositions.clear();
        this.constructionChestLoc = null;
        this.constructionChestLoc2 = null;
        this.fuelChestLoc = null;
        this.fuelChestLoc2 = null;
        this.terminalBlockLoc = null;
        this.status = Status.IDLE;
        this.statusMessage = "Idle";
        this.activeBlueprint = null;
        this.activeBlueprintId = null;
        this.currentBlockIndex.set(0);
        this.totalBlocks = 0;
    }

    public synchronized boolean revalidateAndResume() {
        if (isBuilding()) {
            if (this.status == Status.PAUSED) {
                int index = currentBlockIndex.get();
                if (activeBlueprint != null && index < activeBlueprint.blocks().size()) {
                    PlacementBlock pb = activeBlueprint.blocks().get(index);
                    BlockData bData = parseBlockData(pb.material());
                    Material blockMat = (bData != null) ? bData.getMaterial() : parseMaterialFromBlockState(pb.material());
                    if (blockMat != null && !blockMat.isAir()) {
                        Material itemMat = getItemMaterialForBlock(blockMat);
                        boolean isUpper = pb.material().contains("half=upper") || pb.material().contains("part=head");
                        if (!isUpper && itemMat.isItem()) {
                            if (!hasMaterial(itemMat)) {
                                this.statusMessage = "Paused: Missing material " + itemMat.name();
                                updateConstructionHologramMissing(itemMat);
                                return false;
                            }
                        }
                    }
                }
                this.status = Status.BUILDING;
                this.statusMessage = "Resuming build...";
                if (activeBlueprint != null && index < activeBlueprint.blocks().size()) {
                    PlacementBlock pb = activeBlueprint.blocks().get(index);
                    BlockData bData = parseBlockData(pb.material());
                    Material blockMat = (bData != null) ? bData.getMaterial() : parseMaterialFromBlockState(pb.material());
                    if (blockMat != null && !blockMat.isAir()) {
                        updateConstructionHologramNeeded(getItemMaterialForBlock(blockMat));
                    }
                }
                return true;
            }
            return true;
        } else if (isQuarryActive()) {
            if (this.status == Status.PAUSED) {
                if (isQuarryStorageFull()) {
                    this.statusMessage = "Paused: Mined blocks storage chest is full";
                    return false;
                }
                if (fuel <= 0 && !hasQuarryFuel()) {
                    this.statusMessage = "Paused: Out of fuel (place fuel in Fuel Chest)";
                    return false;
                }
                this.status = Status.MINING;
                this.statusMessage = "Resuming quarry at Y=" + quarryCurY;
                return true;
            }
            return true;
        }
        return false;
    }

    public synchronized boolean hasMaterial(Material mat) {
        if (mat == null) return false;
        for (int i = 0; i < 16; i++) {
            ItemStack stack = inventory[i];
            if (stack != null && stack.getAmount() > 0) {
                if (stack.getType() == mat
                        || (mat == Material.WATER && stack.getType() == Material.WATER_BUCKET)
                        || (mat == Material.LAVA && stack.getType() == Material.LAVA_BUCKET)) {
                    return true;
                }
            }
        }
        return hasInChest(constructionChestLoc, mat) || hasInChest(constructionChestLoc2, mat);
    }

    private boolean hasInChest(Location loc, Material mat) {
        if (loc == null || loc.getWorld() == null || mat == null) return false;
        Block b = loc.getBlock();
        if (b != null && b.getState() instanceof org.bukkit.block.Container container) {
            Inventory inv = container.getInventory();
            for (ItemStack item : inv.getContents()) {
                if (item != null && item.getAmount() > 0) {
                    if (item.getType() == mat
                            || (mat == Material.WATER && item.getType() == Material.WATER_BUCKET)
                            || (mat == Material.LAVA && item.getType() == Material.LAVA_BUCKET)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean hasQuarryFuel() {
        return hasFuelInChest(quarryFuelChestLoc) || hasFuelInChest(quarryFuelChestLoc2) || hasFuelInChest(fuelChestLoc) || hasFuelInChest(fuelChestLoc2);
    }

    private boolean hasFuelInChest(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        Block b = loc.getBlock();
        if (b != null && b.getState() instanceof org.bukkit.block.Container container) {
            Inventory inv = container.getInventory();
            for (ItemStack item : inv.getContents()) {
                if (item != null && item.getAmount() > 0 && getFuelValue(item.getType()) > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    public synchronized Material getCurrentNeededMaterial() {
        if (!isBuilding() || activeBlueprint == null) return null;
        int index = currentBlockIndex.get();
        if (index < activeBlueprint.blocks().size()) {
            PlacementBlock pb = activeBlueprint.blocks().get(index);
            BlockData bData = parseBlockData(pb.material());
            Material blockMat = (bData != null) ? bData.getMaterial() : parseMaterialFromBlockState(pb.material());
            if (blockMat != null && !blockMat.isAir()) {
                return getItemMaterialForBlock(blockMat);
            }
        }
        return null;
    }

    private void cancelTask() {
        if (buildTask != null) {
            buildTask.cancel();
            buildTask = null;
        }
    }

    public synchronized boolean isBuilding() {
        return status == Status.BUILDING || (status == Status.PAUSED && buildTask != null);
    }

    public synchronized boolean isWorking() {
        return isBuilding() || isQuarryActive() || status == Status.MOVING;
    }

    /**
     * Stops and cancels any active work, blueprint construction, or quarry excavation on this turtle.
     * Resets status to IDLE and cleans up all holograms and scheduled tasks.
     * @return true if an active or paused task was cancelled, false if already idle.
     */
    public synchronized boolean stopAnyWork() {
        cancelScript();
        boolean wasBusy = (buildTask != null || quarryTask != null
                || status == Status.BUILDING || status == Status.MINING
                || status == Status.MOVING || status == Status.PAUSED);
        cancelBuild();
        cancelQuarry();
        this.status = Status.IDLE;
        this.statusMessage = "Idle";
        return wasBusy;
    }

    // =========================================================================
    // Quarry Engine Attachment & Autonomous Excavation
    // =========================================================================

    public static BlockFace getLeftFace(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.WEST;
            case WEST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.EAST;
            case EAST -> BlockFace.NORTH;
            default -> BlockFace.WEST;
        };
    }

    public static BlockFace getRightFace(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            case WEST -> BlockFace.NORTH;
            default -> BlockFace.EAST;
        };
    }

    public static BlockFace getLateralFace(BlockFace currentFacing, LateralSide side) {
        return (side == LateralSide.LEFT) ? getLeftFace(currentFacing) : getRightFace(currentFacing);
    }

    public Material getQuarryBlockMaterial() {
        if (plugin instanceof MultiverseProgrammingPlugin mvp) {
            if (mvp.getConfigManager() != null) {
                return mvp.getConfigManager().getQuarryBlock();
            }
        }
        return Material.BLAST_FURNACE;
    }

    public LateralSide findLateralQuarrySide() {
        if (location == null || location.getWorld() == null) return null;
        Material qMat = getQuarryBlockMaterial();
        Block b = null;
        try {
            b = location.getBlock();
        } catch (Throwable ignored) {}
        if (b == null) {
            b = location.getWorld().getBlockAt(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }
        if (b == null) return null;
        BlockFace left = getLeftFace(this.facing);
        Block leftBlock = b.getRelative(left);
        if (leftBlock != null && leftBlock.getType() == qMat) {
            return LateralSide.LEFT;
        }
        BlockFace right = getRightFace(this.facing);
        Block rightBlock = b.getRelative(right);
        if (rightBlock != null && rightBlock.getType() == qMat) {
            return LateralSide.RIGHT;
        }
        return null;
    }

    public boolean hasQuarryEngineAttached() {
        return findLateralQuarrySide() != null || quarryLateralSide != null;
    }

    public LateralSide getQuarryLateralSide() {
        return quarryLateralSide;
    }

    public void setQuarryLateralSide(LateralSide side) {
        this.quarryLateralSide = side;
    }

    public synchronized Location getQuarryStorageChestLoc() {
        return quarryStorageChestLoc != null ? quarryStorageChestLoc.clone() : null;
    }

    public synchronized Location getQuarryStorageChestLoc2() {
        return quarryStorageChestLoc2 != null ? quarryStorageChestLoc2.clone() : null;
    }

    public synchronized Location getQuarryFuelChestLoc() {
        return quarryFuelChestLoc != null ? quarryFuelChestLoc.clone() : null;
    }

    public synchronized Location getQuarryFuelChestLoc2() {
        return quarryFuelChestLoc2 != null ? quarryFuelChestLoc2.clone() : null;
    }

    private void setupQuarryChests(Location startLoc) {
        World world = startLoc.getWorld();
        if (world == null) return;

        DoubleChestPair pair = setupSeparatedDoubleChests(startLoc);
        if (pair == null) return;

        placeDoubleChest(pair.s1(), pair.s2(), this.facing);
        this.quarryStorageChestLoc = pair.s1().getLocation();
        this.quarryStorageChestLoc2 = pair.s2().getLocation();

        placeDoubleChest(pair.f1(), pair.f2(), this.facing);
        this.quarryFuelChestLoc = pair.f1().getLocation();
        this.quarryFuelChestLoc2 = pair.f2().getLocation();
        this.fuelChestLoc = pair.f1().getLocation();
        this.fuelChestLoc2 = pair.f2().getLocation();

        if (pair.terminal() != null) {
            pair.terminal().setType(Material.LODESTONE, false);
            this.terminalBlockLoc = pair.terminal().getLocation();
        }

        cleanupQuarryHolograms();

        Location sHoloLoc = new Location(
                world,
                (pair.s1().getX() + pair.s2().getX()) / 2.0 + 0.5,
                Math.max(pair.s1().getY(), pair.s2().getY()) + 1.35,
                (pair.s1().getZ() + pair.s2().getZ()) / 2.0 + 0.5
        );
        this.quarryStorageHologram = spawnHologram(world, sHoloLoc, "§e📦 Mined Blocks Storage");

        Location fHoloLoc = new Location(
                world,
                (pair.f1().getX() + pair.f2().getX()) / 2.0 + 0.5,
                Math.max(pair.f1().getY(), pair.f2().getY()) + 1.35,
                (pair.f1().getZ() + pair.f2().getZ()) / 2.0 + 0.5
        );
        this.quarryFuelHologram = spawnHologram(world, fHoloLoc, "§6⚡ Place fuel here");

        if (this.terminalBlockLoc != null) {
            Location termHoloLoc = this.terminalBlockLoc.clone().add(0.5, 1.35, 0.5);
            this.quarryTerminalHologram = spawnHologram(world, termHoloLoc, "§b🖥️ Supply Station Terminal\n§7Right-click to control Turtle");
        }
    }

    private void cleanupQuarryHolograms() {
        if (quarryStorageHologram != null && quarryStorageHologram.isValid()) {
            try { quarryStorageHologram.remove(); } catch (Throwable ignored) {}
            quarryStorageHologram = null;
        }
        if (quarryFuelHologram != null && quarryFuelHologram.isValid()) {
            try { quarryFuelHologram.remove(); } catch (Throwable ignored) {}
            quarryFuelHologram = null;
        }
        if (quarryTerminalHologram != null && quarryTerminalHologram.isValid()) {
            try { quarryTerminalHologram.remove(); } catch (Throwable ignored) {}
            quarryTerminalHologram = null;
        }
        if (quarryStorageChestLoc != null) cleanupHologramsAt(quarryStorageChestLoc);
        if (quarryStorageChestLoc2 != null) cleanupHologramsAt(quarryStorageChestLoc2);
        if (quarryFuelChestLoc != null) cleanupHologramsAt(quarryFuelChestLoc);
        if (quarryFuelChestLoc2 != null) cleanupHologramsAt(quarryFuelChestLoc2);
        if (terminalBlockLoc != null) cleanupHologramsAt(terminalBlockLoc);
    }

    public synchronized boolean isQuarryStorageFull() {
        boolean full1 = isChestFull(quarryStorageChestLoc);
        boolean full2 = (quarryStorageChestLoc2 != null) ? isChestFull(quarryStorageChestLoc2) : full1;
        return full1 && full2;
    }

    private boolean isChestFull(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        Block block = loc.getBlock();
        if (block != null && block.getState() instanceof org.bukkit.block.Container container) {
            Inventory inv = container.getInventory();
            if (inv.firstEmpty() != -1) {
                return false;
            }
            for (ItemStack item : inv.getContents()) {
                if (item != null && item.getAmount() < item.getMaxStackSize()) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    public synchronized boolean depositToQuarryStorage(ItemStack drop) {
        if (drop == null || drop.getType().isAir()) return true;

        ItemStack remaining = depositToChest(quarryStorageChestLoc, drop);
        if (remaining != null && remaining.getAmount() > 0 && quarryStorageChestLoc2 != null) {
            remaining = depositToChest(quarryStorageChestLoc2, remaining);
        }

        if (remaining != null && remaining.getAmount() > 0) {
            addToInventory(remaining);
            return false;
        }
        return true;
    }

    private ItemStack depositToChest(Location loc, ItemStack drop) {
        if (loc == null || loc.getWorld() == null || drop == null || drop.getAmount() <= 0) return drop;
        Block block = loc.getBlock();
        if (block != null && block.getState() instanceof org.bukkit.block.Container container) {
            Inventory inv = container.getInventory();
            java.util.HashMap<Integer, ItemStack> remaining = inv.addItem(drop);
            if (remaining.isEmpty()) {
                return null;
            }
            for (ItemStack rem : remaining.values()) {
                return rem;
            }
        }
        return drop;
    }

    public synchronized boolean consumeQuarryFuel() {
        quarryFuelPoints += 120;
        int toDeduct = quarryFuelPoints / 100;
        if (toDeduct <= 0) {
            return true;
        }
        quarryFuelPoints %= 100;

        boolean required = true;
        if (plugin instanceof MultiverseProgrammingPlugin mvp) {
            if (mvp.getConfigManager() != null) {
                required = mvp.getConfigManager().isTurtleFuelRequired();
            }
        }

        if (this.fuel < toDeduct) {
            refuel(0);
        }

        if (this.fuel >= toDeduct) {
            this.fuel -= toDeduct;
            return true;
        }

        return !required;
    }

    public synchronized void relocateTurtleWithEngine(World world, Location newLoc, BlockFace newFacing) {
        if (world == null || this.location == null) return;

        Material turtleMat = (plugin instanceof MultiverseProgrammingPlugin mvp && mvp.getConfigManager() != null)
                ? mvp.getConfigManager().getTurtleBlock()
                : Material.DISPENSER;
        Material quarryMat = getQuarryBlockMaterial();

        Location oldLoc = this.location.clone();
        BlockFace oldFacing = this.facing;

        Location oldEngineLoc = null;
        if (quarryLateralSide != null && oldLoc != null) {
            Block b = oldLoc.getBlock();
            if (b != null) {
                Block rel = b.getRelative(getLateralFace(oldFacing, quarryLateralSide));
                if (rel != null) oldEngineLoc = rel.getLocation();
            }
        }

        Location newEngineLoc = null;
        if (quarryLateralSide != null && newLoc != null) {
            Block b = newLoc.getBlock();
            if (b != null) {
                Block rel = b.getRelative(getLateralFace(newFacing, quarryLateralSide));
                if (rel != null) newEngineLoc = rel.getLocation();
            }
        }

        if (oldEngineLoc != null && !oldEngineLoc.equals(newLoc) && !oldEngineLoc.equals(newEngineLoc)) {
            Block b = oldEngineLoc.getBlock();
            if (b != null) b.setType(Material.AIR, false);
        }
        if (!oldLoc.equals(newLoc) && !oldLoc.equals(newEngineLoc)) {
            Block b = oldLoc.getBlock();
            if (b != null) b.setType(Material.AIR, false);
        }

        Block newTurtleBlock = newLoc.getBlock();
        if (newTurtleBlock != null) {
            newTurtleBlock.setType(turtleMat, false);
            if (newTurtleBlock.getBlockData() instanceof Directional dir) {
                try {
                    dir.setFacing(newFacing);
                    newTurtleBlock.setBlockData(dir, false);
                } catch (Throwable ignored) {}
            }
            updateBlockPdc(newTurtleBlock);
        }

        if (newEngineLoc != null) {
            Block newEngineBlock = newEngineLoc.getBlock();
            if (newEngineBlock != null) {
                newEngineBlock.setType(quarryMat, false);
            }
            try {
                world.spawnParticle(Particle.FLAME, newEngineLoc.clone().add(0.5, 0.5, 0.5), 3, 0.1, 0.1, 0.1, 0.01);
            } catch (Throwable ignored) {}
        }

        this.location = newLoc.clone();
        this.facing = newFacing;

        TurtleManager tm = getTurtleManager();
        if (tm != null) {
            tm.updateTurtleLocation(this, oldLoc, this.location);
        }

        try {
            world.playSound(newLoc, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.4f, 1.6f);
            world.spawnParticle(Particle.SMOKE, oldLoc.getX() + 0.5, oldLoc.getY() + 0.5, oldLoc.getZ() + 0.5, 2, 0.05, 0.05, 0.05, 0.01);
        } catch (Throwable ignored) {}
    }

    public synchronized boolean startQuarry(
            int width,
            int length,
            int targetYLevel,
            boolean handleLiquids,
            Runnable onDone,
            Consumer<String> onError
    ) {
        cancelQuarry();
        cancelBuild();

        if (location == null || location.getWorld() == null) {
            failQuarry("Turtle world is unloaded", onError);
            return false;
        }

        World world = location.getWorld();

        LateralSide side = findLateralQuarrySide();
        if (side == null) {
            failQuarry("No Quarry Engine attached to the lateral side of the turtle", onError);
            return false;
        }
        this.quarryLateralSide = side;

        width = Math.max(1, Math.min(64, width));
        length = Math.max(1, Math.min(64, length));
        int minY = Math.max(world.getMinHeight(), targetYLevel);

        this.quarryWidth = width;
        this.quarryLength = length;
        this.quarryTargetY = minY;
        this.quarryHandleLiquids = handleLiquids;
        this.quarryStartLocation = location.clone();

        int startY = location.getBlockY() - 1;
        if (startY < minY) {
            failQuarry("Target Y level (" + minY + ") is above excavation start level (" + startY + ")", onError);
            return false;
        }

        BlockFace forward = this.facing;
        BlockFace lateral = getRightFace(this.facing);

        int c1X = location.getBlockX() + (forward.getModX() * 1) + (lateral.getModX() * 0);
        int c1Z = location.getBlockZ() + (forward.getModZ() * 1) + (lateral.getModZ() * 0);

        int c2X = location.getBlockX() + (forward.getModX() * length) + (lateral.getModX() * (width - 1));
        int c2Z = location.getBlockZ() + (forward.getModZ() * length) + (lateral.getModZ() * (width - 1));

        this.quarryWorld = world;
        this.quarryMinX = Math.min(c1X, c2X);
        this.quarryMaxX = Math.max(c1X, c2X);
        this.quarryMinY = minY;
        this.quarryMaxY = startY;
        this.quarryMinZ = Math.min(c1Z, c2Z);
        this.quarryMaxZ = Math.max(c1Z, c2Z);

        if (plugin instanceof MultiverseProgrammingPlugin mvp) {
            com.multiverse.programming.protection.ProtectionManager pm = mvp.getProtectionManager();
            if (pm != null) {
                Location minLoc = new Location(world, quarryMinX, quarryMinY, quarryMinZ);
                Location maxLoc = new Location(world, quarryMaxX, quarryMaxY, quarryMaxZ);

                String protErr = pm.checkRegionArea(this.owner, minLoc, maxLoc);
                if (protErr != null) {
                    failQuarry(protErr, onError);
                    return false;
                }
            }
        }

        setupQuarryChests(this.location);

        Location[] ourChests = new Location[] {
                quarryStorageChestLoc, quarryStorageChestLoc2, quarryFuelChestLoc, quarryFuelChestLoc2, terminalBlockLoc
        };
        for (Location cloc : ourChests) {
            if (cloc != null && cloc.getWorld() != null && cloc.getWorld().equals(world)) {
                int cx = cloc.getBlockX();
                int cy = cloc.getBlockY();
                int cz = cloc.getBlockZ();
                if (cx >= quarryMinX && cx <= quarryMaxX && cy >= quarryMinY && cy <= quarryMaxY && cz >= quarryMinZ && cz <= quarryMaxZ) {
                    failQuarry(String.format("Cannot build or destroy blocks here: target position [%d, %d, %d] overlaps with Turtle supply chests or terminal. Please reposition the build area.", cx, cy, cz), onError);
                    return false;
                }
            }
        }
        if (plugin instanceof MultiverseProgrammingPlugin mvp && mvp.getTurtleManager() != null) {
            for (Turtle t : mvp.getTurtleManager().getAllTurtles()) {
                if (t == this) continue;
                Location[] otherChests = new Location[] {
                        t.constructionChestLoc, t.constructionChestLoc2, t.fuelChestLoc, t.fuelChestLoc2,
                        t.quarryStorageChestLoc, t.quarryStorageChestLoc2, t.quarryFuelChestLoc, t.quarryFuelChestLoc2,
                        t.terminalBlockLoc
                };
                for (Location cloc : otherChests) {
                    if (cloc != null && cloc.getWorld() != null && cloc.getWorld().equals(world)) {
                        int cx = cloc.getBlockX();
                        int cy = cloc.getBlockY();
                        int cz = cloc.getBlockZ();
                        if (cx >= quarryMinX && cx <= quarryMaxX && cy >= quarryMinY && cy <= quarryMaxY && cz >= quarryMinZ && cz <= quarryMaxZ) {
                            failQuarry(String.format("Cannot build or destroy blocks here: target position [%d, %d, %d] overlaps with Turtle supply chests or terminal. Please reposition the build area.", cx, cy, cz), onError);
                            return false;
                        }
                    }
                }
            }
        }

        this.quarryBlocksMined = 0;
        this.quarryTotalBlocks = width * length * (startY - minY + 1);
        this.quarryCurY = startY;
        this.quarryStepX = 0;
        this.quarryStepZ = 0;
        this.quarryFuelPoints = 0;
        this.status = Status.MINING;
        this.statusMessage = "Quarry excavation starting at Y=" + quarryCurY;

        if (Bukkit.getScheduler() != null && plugin != null) {
            this.quarryTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (status == Status.PAUSED) {
                    return;
                }

                if (quarryCurY < quarryTargetY) {
                    finishQuarry(onDone);
                    return;
                }

                BlockFace f = this.facing;
                BlockFace lat = getRightFace(this.facing);

                int d = quarryStepZ + 1;
                int w = quarryStepX;

                int bx = quarryStartLocation.getBlockX() + (f.getModX() * d) + (lat.getModX() * w);
                int bz = quarryStartLocation.getBlockZ() + (f.getModZ() * d) + (lat.getModZ() * w);
                int by = quarryCurY;

                if (isSelfOrAnyChestOrTerminal(world, bx, by, bz)) {
                    quarryStepX++;
                    if (quarryStepX >= quarryWidth) {
                        quarryStepX = 0;
                        quarryStepZ++;
                        if (quarryStepZ >= quarryLength) {
                            quarryStepZ = 0;
                            quarryCurY--;
                            int pct = (int) (((double) quarryBlocksMined / Math.max(1, quarryTotalBlocks)) * 100);
                            this.statusMessage = String.format(Locale.ROOT, "Quarry digging layer Y=%d (%d%% - %d blocks)",
                                    quarryCurY, pct, quarryBlocksMined);
                        }
                    }
                    return;
                }

                if (!consumeQuarryFuel()) {
                    this.status = Status.PAUSED;
                    this.statusMessage = "Paused: Out of fuel (place fuel in Fuel Chest)";
                    if (onError != null) onError.accept("Turtle is out of fuel for quarry operation");
                    return;
                }

                Location adjLoc = new Location(world, bx, Math.min(world.getMaxHeight() - 1, by + 1), bz);
                if (!adjLoc.equals(this.location)) {
                    relocateTurtleWithEngine(world, adjLoc, this.facing);
                }

                Block blockToMine = world.getBlockAt(bx, by, bz);
                Material mat = blockToMine.getType();

                if (isIllegalBlock(mat) || mat == Material.BEDROCK) {
                    // Skip unmineable blocks
                } else if (mat == Material.WATER || mat == Material.LAVA) {
                    if (quarryHandleLiquids) {
                        blockToMine.setType(Material.AIR, false);
                    }
                } else if (!mat.isAir()) {
                    if (isQuarryStorageFull()) {
                        this.status = Status.PAUSED;
                        this.statusMessage = "Paused: Mined blocks storage chest is full";
                        if (onError != null) onError.accept("Quarry storage chest is full");
                        return;
                    }

                    Collection<ItemStack> drops = blockToMine.getDrops();
                    Location bLoc = blockToMine.getLocation();
                    BlockData oldData = blockToMine.getBlockData();

                    blockToMine.setType(Material.AIR, false);
                    CoreProtectBridge.logRemoval(owner, id, bLoc, mat, oldData);

                    for (ItemStack drop : drops) {
                        if (drop == null || drop.getType().isAir()) continue;
                        boolean stored = depositToQuarryStorage(drop);
                        if (!stored) {
                            this.status = Status.PAUSED;
                            this.statusMessage = "Paused: Mined blocks storage chest is full";
                            if (onError != null) onError.accept("Quarry storage chest is full");
                            return;
                        }
                    }
                    quarryBlocksMined++;
                }

                quarryStepX++;
                if (quarryStepX >= quarryWidth) {
                    quarryStepX = 0;
                    quarryStepZ++;
                    if (quarryStepZ >= quarryLength) {
                        quarryStepZ = 0;
                        quarryCurY--;
                        int pct = (int) (((double) quarryBlocksMined / Math.max(1, quarryTotalBlocks)) * 100);
                        this.statusMessage = String.format(Locale.ROOT, "Quarry digging layer Y=%d (%d%% - %d blocks)",
                                quarryCurY, pct, quarryBlocksMined);
                    }
                }
            }, 1L, 1L);
        }

        return true;
    }

    private void finishQuarry(Runnable onDone) {
        cleanupQuarryHolograms();
        cancelQuarryTask();
        clearQuarryBounds();
        this.status = Status.IDLE;
        this.statusMessage = "Quarry completed (" + quarryBlocksMined + " blocks mined)";
        if (quarryStartLocation != null && quarryStartLocation.getWorld() != null) {
            relocateTurtleWithEngine(quarryStartLocation.getWorld(), quarryStartLocation, this.facing);
            quarryStartLocation.getWorld().playSound(quarryStartLocation, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
        }
        if (onDone != null) {
            onDone.run();
        }
    }

    private void failQuarry(String error, Consumer<String> onError) {
        cleanupQuarryHolograms();
        cancelQuarryTask();
        clearQuarryBounds();
        this.status = Status.ERROR;
        this.statusMessage = "Error: " + error;
        notifyOwner("§c[Turtle " + id + " Quarry Error] §e" + error);
        if (onError != null) {
            onError.accept(error);
        }
    }

    public synchronized void pauseQuarry() {
        if (status == Status.MINING) {
            this.status = Status.PAUSED;
            this.statusMessage = "Quarry paused at Y=" + quarryCurY;
        }
    }

    public synchronized void resumeQuarry() {
        if (status == Status.PAUSED && quarryTask != null) {
            if (quarryStorageChestLoc != null) {
                for (int i = 0; i < 16; i++) {
                    if (inventory[i] != null && !inventory[i].getType().isAir()) {
                        depositToQuarryStorage(inventory[i]);
                    }
                }
            }
            this.status = Status.MINING;
            this.statusMessage = "Resuming quarry at Y=" + quarryCurY;
        }
    }

    public synchronized void cancelQuarry() {
        cleanupQuarryHolograms();
        cancelQuarryTask();
        clearQuarryBounds();
        if (status == Status.MINING || status == Status.PAUSED) {
            this.status = Status.IDLE;
            this.statusMessage = "Idle";
        }
    }

    private void clearQuarryBounds() {
        this.quarryWorld = null;
        this.quarryMinX = 0;
        this.quarryMaxX = 0;
        this.quarryMinY = 0;
        this.quarryMaxY = 0;
        this.quarryMinZ = 0;
        this.quarryMaxZ = 0;
        this.quarryStorageChestLoc = null;
        this.quarryStorageChestLoc2 = null;
        this.quarryFuelChestLoc = null;
        this.quarryFuelChestLoc2 = null;
        this.terminalBlockLoc = null;
    }

    private void cancelQuarryTask() {
        if (quarryTask != null) {
            quarryTask.cancel();
            quarryTask = null;
        }
    }

    public synchronized boolean isQuarryActive() {
        return (status == Status.MINING || (status == Status.PAUSED && quarryTask != null));
    }

    public synchronized boolean isQuarryPaused() {
        return status == Status.PAUSED && quarryTask != null;
    }

    public synchronized int getQuarryBlocksMined() {
        return quarryBlocksMined;
    }

    public synchronized int getQuarryTotalBlocks() {
        return quarryTotalBlocks;
    }

    public synchronized int getQuarryCurrentY() {
        return quarryCurY;
    }

    public synchronized int getQuarryTargetY() {
        return quarryTargetY;
    }

    public synchronized double getQuarryProgressPercentage() {
        if (quarryTotalBlocks <= 0) return 0.0;
        return Math.min(100.0, Math.round(((double) quarryBlocksMined / quarryTotalBlocks) * 1000.0) / 10.0);
    }

    public static BlockData parseBlockData(String blockState) {
        if (blockState == null || blockState.isBlank()) {
            return null;
        }
        try {
            BlockData data = Bukkit.createBlockData(blockState);
            if (data instanceof Waterlogged wl) {
                if (!blockState.contains("waterlogged=true")) {
                    wl.setWaterlogged(false);
                }
            }
            return data;
        } catch (Throwable t) {
            try {
                Material mat = parseMaterialFromBlockState(blockState);
                if (mat != null && !mat.isAir()) {
                    BlockData data = Bukkit.createBlockData(mat);
                    if (data instanceof Waterlogged wl) {
                        if (!blockState.contains("waterlogged=true")) {
                            wl.setWaterlogged(false);
                        }
                    }
                    return data;
                }
            } catch (Throwable ignored) {}
            return null;
        }
    }

    public static Material getItemMaterialForBlock(Material blockMat) {
        if (blockMat == null) return Material.AIR;

        String name = blockMat.name();
        if (name.equals("WALL_TORCH")) return Material.TORCH;
        if (name.equals("SOUL_WALL_TORCH")) return Material.SOUL_TORCH;
        if (name.equals("REDSTONE_WALL_TORCH")) return Material.REDSTONE_TORCH;
        if (name.endsWith("_WALL_FAN")) {
            Material match = Material.matchMaterial(name.replace("_WALL_FAN", "_FAN"));
            if (match != null && match.isItem()) return match;
        }
        if (name.endsWith("_WALL_SIGN")) {
            Material match = Material.matchMaterial(name.replace("_WALL_SIGN", "_SIGN"));
            if (match != null && match.isItem()) return match;
        }
        if (name.endsWith("_WALL_HANGING_SIGN")) {
            Material match = Material.matchMaterial(name.replace("_WALL_HANGING_SIGN", "_HANGING_SIGN"));
            if (match != null && match.isItem()) return match;
        }
        if (name.startsWith("POTTED_")) {
            return Material.FLOWER_POT;
        }
        if (name.endsWith("_WALL_HEAD") || name.endsWith("_WALL_SKULL")) {
            String headName = name.replace("_WALL_HEAD", "_HEAD").replace("_WALL_SKULL", "_SKULL");
            Material match = Material.matchMaterial(headName);
            if (match != null && match.isItem()) return match;
        }
        if (name.equals("WATER_CAULDRON") || name.equals("LAVA_CAULDRON") || name.equals("POWDER_SNOW_CAULDRON")) {
            return Material.CAULDRON;
        }
        if (name.equals("REDSTONE_WIRE")) return Material.REDSTONE;
        if (name.equals("TRIPWIRE")) return Material.STRING;
        if (name.equals("SWEET_BERRY_BUSH")) return Material.SWEET_BERRIES;
        if (name.equals("CARROTS")) return Material.CARROT;
        if (name.equals("POTATOES")) return Material.POTATO;
        if (name.equals("BEETROOTS")) return Material.BEETROOT_SEEDS;
        if (name.equals("WHEAT")) return Material.WHEAT_SEEDS;
        if (name.equals("BAMBOO_SAPLING")) return Material.BAMBOO;
        if (name.equals("MELON_STEM") || name.equals("ATTACHED_MELON_STEM")) return Material.MELON_SEEDS;
        if (name.equals("PUMPKIN_STEM") || name.equals("ATTACHED_PUMPKIN_STEM")) return Material.PUMPKIN_SEEDS;
        if (name.equals("COCOA")) return Material.COCOA_BEANS;

        return blockMat;
    }

    public static Material parseMaterialFromBlockState(String blockState) {
        if (blockState == null || blockState.isBlank()) {
            return Material.AIR;
        }
        String clean = blockState;
        if (clean.contains("[")) {
            clean = clean.substring(0, clean.indexOf('['));
        }
        if (clean.startsWith("minecraft:")) {
            clean = clean.substring("minecraft:".length());
        }
        clean = clean.trim().toUpperCase(Locale.ROOT);
        Material mat = Material.matchMaterial(clean);
        return mat != null ? mat : Material.AIR;
    }

    public static boolean isIllegalBlock(Material mat) {
        if (mat == null) return false;
        return switch (mat) {
            case BEDROCK, BARRIER, STRUCTURE_BLOCK, STRUCTURE_VOID, JIGSAW,
                 COMMAND_BLOCK, CHAIN_COMMAND_BLOCK, REPEATING_COMMAND_BLOCK,
                 LIGHT, END_PORTAL, END_PORTAL_FRAME, END_GATEWAY,
                 NETHER_PORTAL, REINFORCED_DEEPSLATE,
                 BUDDING_AMETHYST, VAULT, DRAGON_EGG, TRIAL_SPAWNER, SPAWNER -> true;
            default -> false;
        };
    }

    public synchronized BlockProtectionCheck isBlockProtected(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return new BlockProtectionCheck(false, null);
        }

        World world = loc.getWorld();
        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();

        // 1. Turtle block itself while working
        if (this.location != null && this.location.getWorld() != null && this.location.getWorld().equals(world)) {
            if (this.location.getBlockX() == bx && this.location.getBlockY() == by && this.location.getBlockZ() == bz) {
                if (isWorking()) {
                    return new BlockProtectionCheck(true, "Turtle " + id + " is currently operating.");
                }
            }
        }

        // 2. Active Blueprint Construction
        if (isBuilding()) {
            if (isAtLocation(constructionChestLoc, world, bx, by, bz) || isAtLocation(constructionChestLoc2, world, bx, by, bz)) {
                return new BlockProtectionCheck(true, "it is the material chest of an active construction by Turtle " + id + ".");
            }
            if (isAtLocation(fuelChestLoc, world, bx, by, bz) || isAtLocation(fuelChestLoc2, world, bx, by, bz)) {
                return new BlockProtectionCheck(true, "it is the fuel chest of Turtle " + id + ".");
            }
            if (isAtLocation(terminalBlockLoc, world, bx, by, bz)) {
                return new BlockProtectionCheck(true, "it is the Supply Station Terminal of Turtle " + id + ".");
            }
            if (buildOrigin != null && buildOrigin.getWorld() != null && buildOrigin.getWorld().equals(world)) {
                int rx = bx - buildOrigin.getBlockX();
                int ry = by - buildOrigin.getBlockY();
                int rz = bz - buildOrigin.getBlockZ();
                if (activeBuildPositions.contains(new BlockPos(rx, ry, rz))) {
                    return new BlockProtectionCheck(true, "it is part of an active construction by Turtle " + id + ".");
                }
            }
        }

        // 3. Active Quarry Excavation
        if (isQuarryActive()) {
            if (isAtLocation(quarryStorageChestLoc, world, bx, by, bz) || isAtLocation(quarryStorageChestLoc2, world, bx, by, bz)) {
                return new BlockProtectionCheck(true, "it is the mined storage chest of an active quarry by Turtle " + id + ".");
            }
            if (isAtLocation(quarryFuelChestLoc, world, bx, by, bz) || isAtLocation(quarryFuelChestLoc2, world, bx, by, bz)) {
                return new BlockProtectionCheck(true, "it is the quarry fuel chest of Turtle " + id + ".");
            }
            if (isAtLocation(terminalBlockLoc, world, bx, by, bz)) {
                return new BlockProtectionCheck(true, "it is the Supply Station Terminal of Turtle " + id + ".");
            }
            if (quarryLateralSide != null && this.location != null && this.location.getWorld() != null && this.location.getWorld().equals(world)) {
                Block engBlock = this.location.getBlock().getRelative(getLateralFace(this.facing, quarryLateralSide));
                if (engBlock.getX() == bx && engBlock.getY() == by && engBlock.getZ() == bz) {
                    return new BlockProtectionCheck(true, "it is the Quarry Engine of Turtle " + id + ".");
                }
            }
            if (quarryWorld != null && quarryWorld.equals(world)) {
                if (bx >= quarryMinX && bx <= quarryMaxX
                        && by >= quarryMinY && by <= quarryMaxY
                        && bz >= quarryMinZ && bz <= quarryMaxZ) {
                    return new BlockProtectionCheck(true, "it is within the active quarry zone of Turtle " + id + ".");
                }
            }
        }

        return new BlockProtectionCheck(false, null);
    }

    private static boolean isAtLocation(Location target, World world, int bx, int by, int bz) {
        if (target == null || target.getWorld() == null) return false;
        return target.getWorld().equals(world) && target.getBlockX() == bx && target.getBlockY() == by && target.getBlockZ() == bz;
    }
}
