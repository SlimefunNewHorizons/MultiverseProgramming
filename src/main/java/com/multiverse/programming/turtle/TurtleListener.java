// SPDX-License-Identifier: GPL-3.0-or-later
package com.multiverse.programming.turtle;

import com.multiverse.programming.DiskManager;
import com.multiverse.programming.LuaRunner;
import com.multiverse.programming.MultiverseProgrammingPlugin;
import com.multiverse.programming.peripheral.PeripheralManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Handles Bukkit events for Turtle placement, interaction, inventory GUI, and breaking.
 */
public final class TurtleListener implements Listener {

    /**
     * Permission required to open and operate a Turtle's control panel or supply terminal.
     * Must stay in sync with the permission declared in plugin.yml (default: true).
     */
    public static final String PERMISSION_TURTLE = "multiverseprogramming.turtle";

    /** Whether a player may bypass per-Turtle ownership/authorization checks. */
    public static final String PERMISSION_ADMIN = "multiverseprogramming.admin";

    private final MultiverseProgrammingPlugin plugin;

    public TurtleListener(MultiverseProgrammingPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
    }

    private Material getTurtleBlock() {
        return plugin.getConfigManager().getTurtleBlock();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTurtlePlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        ItemMeta meta = item.getItemMeta();
        Material turtleMat = getTurtleBlock();

        if (event.getBlockPlaced().getType() != turtleMat) {
            return;
        }

        if (com.multiverse.programming.ComputerListener.isSlimefunItem(item)
                || com.multiverse.programming.ComputerListener.isSlimefunBlock(event.getBlockPlaced())
                || com.multiverse.programming.ComputerListener.isMultiverseNetsBlock(event.getBlockPlaced())) {
            return;
        }

        boolean isTurtleItem = DiskManager.isMachine(item, DiskManager.ID_TURTLE)
                || (meta != null && meta.hasDisplayName()
                && meta.getDisplayName().contains(DiskManager.TURTLE_NAME));

        if (!isTurtleItem) {
            return;
        }

        Player player = event.getPlayer();
        if (!plugin.getConfigManager().isEnableTurtle()) {
            player.sendMessage(plugin.getPrefix() + " §cTurtles are currently disabled by the server administration.");
            event.setCancelled(true);
            return;
        }
        if (!player.hasPermission(PERMISSION_TURTLE)) {
            player.sendMessage(plugin.getPrefix() + " §cYou don't have permission to place Turtles.");
            event.setCancelled(true);
            return;
        }
        BlockFace facing = BlockFace.NORTH;
        float yaw = player.getLocation().getYaw();
        if (yaw < 0) yaw += 360;
        if (yaw >= 315 || yaw < 45) {
            facing = BlockFace.SOUTH;
        } else if (yaw >= 45 && yaw < 135) {
            facing = BlockFace.WEST;
        } else if (yaw >= 135 && yaw < 225) {
            facing = BlockFace.NORTH;
        } else {
            facing = BlockFace.EAST;
        }

        Turtle turtle = plugin.getTurtleManager().createTurtle(
                event.getBlockPlaced().getLocation(),
                facing,
                player.getUniqueId()
        );

        Block placedBlock = event.getBlockPlaced();
        if (placedBlock.getState() instanceof org.bukkit.block.TileState tileState) {
            try {
                var pdc = tileState.getPersistentDataContainer();
                pdc.set(DiskManager.KEY_ID, org.bukkit.persistence.PersistentDataType.STRING, DiskManager.ID_TURTLE);
                pdc.set(new org.bukkit.NamespacedKey(plugin, "turtle_id"), org.bukkit.persistence.PersistentDataType.STRING, turtle.getId());
                pdc.set(new org.bukkit.NamespacedKey(plugin, "turtle_owner"), org.bukkit.persistence.PersistentDataType.STRING, player.getUniqueId().toString());
                tileState.update();
            } catch (Throwable ignored) {}
        }

        player.sendMessage(plugin.getPrefix() + " §aTurtle §e" + turtle.getId() + " §aplaced! Right-click to open its control panel.");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onTurtleInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }

        // Never intercept MultiverseNets or Slimefun blocks (e.g. Controllers on Lodestone, Cargo Nodes, Electric machines)
        if (com.multiverse.programming.ComputerListener.isMultiverseNetsBlock(block)
                || com.multiverse.programming.ComputerListener.isSlimefunBlock(block)) {
            return;
        }

        if (block.getType() == Material.LODESTONE) {
            if (plugin.getTurtleManager() != null) {
                Turtle turtle = plugin.getTurtleManager().getTurtleByTerminal(block.getLocation());
                if (turtle != null) {
                    Player terminalPlayer = event.getPlayer();
                    event.setCancelled(true);
                    if (!terminalPlayer.hasPermission(PERMISSION_TURTLE)) {
                        terminalPlayer.sendMessage(plugin.getPrefix() + " §cYou don't have permission to use Turtles.");
                        return;
                    }
                    if (!canOperate(turtle, terminalPlayer)) {
                        terminalPlayer.sendMessage(plugin.getPrefix() + " §cOnly the owner or players authorized by the owner can operate this Turtle.");
                        return;
                    }
                    SupplyStationGUI.open(terminalPlayer, turtle);
                    return;
                }
            }
        }

        if (block.getType() != getTurtleBlock()) {
            return;
        }

        Turtle turtle = plugin.getTurtleManager().getTurtle(block.getLocation());
        if (turtle == null) {
            if (block.getState() instanceof org.bukkit.block.TileState tileState) {
                var pdc = tileState.getPersistentDataContainer();
                org.bukkit.NamespacedKey ownerKey = new org.bukkit.NamespacedKey(plugin, "turtle_owner");
                org.bukkit.NamespacedKey idKey = new org.bukkit.NamespacedKey(plugin, "turtle_id");
                if (pdc.has(ownerKey, org.bukkit.persistence.PersistentDataType.STRING) || pdc.has(idKey, org.bukkit.persistence.PersistentDataType.STRING)) {
                    String tid = pdc.get(idKey, org.bukkit.persistence.PersistentDataType.STRING);
                    String ownerStr = pdc.get(ownerKey, org.bukkit.persistence.PersistentDataType.STRING);
                    java.util.UUID ownerUuid = ownerStr != null ? java.util.UUID.fromString(ownerStr) : null;
                    BlockFace face = BlockFace.NORTH;
                    if (block.getBlockData() instanceof org.bukkit.block.data.Directional dir) {
                        face = dir.getFacing();
                    }
                    turtle = plugin.getTurtleManager().restoreTurtle(tid, block.getLocation(), face, ownerUuid);
                }
            }
        } else if (turtle.getOwner() == null) {
            if (block.getState() instanceof org.bukkit.block.TileState tileState) {
                var pdc = tileState.getPersistentDataContainer();
                org.bukkit.NamespacedKey ownerKey = new org.bukkit.NamespacedKey(plugin, "turtle_owner");
                if (pdc.has(ownerKey, org.bukkit.persistence.PersistentDataType.STRING)) {
                    try {
                        turtle.setOwner(java.util.UUID.fromString(pdc.get(ownerKey, org.bukkit.persistence.PersistentDataType.STRING)));
                    } catch (Throwable ignored) {}
                }
            }
        }

        if (turtle == null) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();

        if (!plugin.getConfigManager().isEnableTurtle()) {
            player.sendMessage(plugin.getPrefix() + " §cTurtles are currently disabled by the server administration.");
            return;
        }

        if (!player.hasPermission(PERMISSION_TURTLE)) {
            player.sendMessage(plugin.getPrefix() + " §cYou don't have permission to use Turtles.");
            return;
        }

        if (!canOperate(turtle, player)) {
            player.sendMessage(plugin.getPrefix() + " §cOnly the owner or players authorized by the owner can operate this Turtle.");
            return;
        }

        TurtleGUI gui = new TurtleGUI(turtle);
        player.openInventory(gui.getInventory());
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        boolean isSupplyHolder = top != null && top.getHolder() instanceof SupplyStationHolder;
        String title = event.getView().getTitle();

        if (isSupplyHolder || title.startsWith(SupplyStationGUI.TITLE_PREFIX)) {
            event.setCancelled(true);
            int rawSlot = event.getRawSlot();
            if (rawSlot < 0 || rawSlot >= SupplyStationGUI.SIZE) {
                return;
            }
            Turtle turtle;
            if (isSupplyHolder) {
                turtle = ((SupplyStationHolder) top.getHolder()).getTurtle();
            } else {
                String turtleId = title.substring(SupplyStationGUI.TITLE_PREFIX.length()).trim();
                turtle = (plugin.getTurtleManager() != null) ? plugin.getTurtleManager().getTurtleById(turtleId) : null;
            }
            if (turtle == null) return;
            Player player = (Player) event.getWhoClicked();
            if (!canOperate(turtle, player)) {
                player.closeInventory();
                player.sendMessage(plugin.getPrefix() + " §cOnly the owner or players authorized by the owner can operate this Turtle.");
                return;
            }

            if (rawSlot == SupplyStationGUI.SLOT_RESUME) {
                boolean resumed = turtle.revalidateAndResume();
                if (resumed) {
                    player.sendMessage(plugin.getPrefix() + " §aTurtle " + turtle.getId() + " re-validated inventories and resumed operation!");
                } else {
                    player.sendMessage(plugin.getPrefix() + " §eTurtle " + turtle.getId() + " cannot resume: " + turtle.getStatusMessage());
                }
                SupplyStationGUI.refresh(top, turtle);
            } else if (rawSlot == SupplyStationGUI.SLOT_PAUSE) {
                if (turtle.getStatus() == Turtle.Status.BUILDING) {
                    turtle.pauseBuild();
                    player.sendMessage(plugin.getPrefix() + " §eConstruction paused.");
                } else if (turtle.getStatus() == Turtle.Status.MINING) {
                    turtle.pauseQuarry();
                    player.sendMessage(plugin.getPrefix() + " §eQuarry excavation paused.");
                } else {
                    player.sendMessage(plugin.getPrefix() + " §eTurtle is not currently active.");
                }
                SupplyStationGUI.refresh(top, turtle);
            } else if (rawSlot == SupplyStationGUI.SLOT_STOP) {
                boolean stopped = turtle.stopAnyWork();
                if (stopped) {
                    player.sendMessage(plugin.getPrefix() + " §cStopped active task for Turtle " + turtle.getId() + ".");
                } else {
                    player.sendMessage(plugin.getPrefix() + " §eTurtle is already idle.");
                }
                SupplyStationGUI.refresh(top, turtle);
            }
            return;
        }

        if (top != null && top.getHolder() instanceof TurtleAccessHolder access) {
            handleAccessClick(event, top, access.getTurtle());
            return;
        }

        if (!(top.getHolder() instanceof TurtleGUI gui)) {
            return;
        }

        int rawSlot = event.getRawSlot();
        if (rawSlot < 0) {
            return;
        }

        // If clicked inside the Turtle GUI
        if (rawSlot < TurtleGUI.INVENTORY_SIZE) {
            Turtle turtle = gui.getTurtle();
            Player player = (Player) event.getWhoClicked();

            // Defense-in-depth: re-validate that the clicker may operate this Turtle, even though
            // the panel can normally only be opened through the already-gated interaction path.
            if (!canOperate(turtle, player)) {
                event.setCancelled(true);
                player.closeInventory();
                player.sendMessage(plugin.getPrefix() + " §cOnly the owner or players authorized by the owner can operate this Turtle.");
                return;
            }

            // Run Button
            if (rawSlot == TurtleGUI.RUN_BUTTON_SLOT) {
                event.setCancelled(true);
                handleRunButton(player, gui, turtle);
                return;
            }

            // Web Dashboard Button
            if (rawSlot == TurtleGUI.WEB_BUTTON_SLOT) {
                event.setCancelled(true);
                handleWebButton(player, turtle);
                return;
            }

            // Access Control Button (owner only)
            if (rawSlot == TurtleGUI.ACCESS_BUTTON_SLOT) {
                event.setCancelled(true);
                handleAccessButton(player, turtle);
                return;
            }

            // Task Control Button (Build / Mining)
            if (rawSlot == TurtleGUI.BUILD_BUTTON_SLOT) {
                event.setCancelled(true);
                if (event.isRightClick()) {
                    boolean stopped = turtle.stopAnyWork();
                    if (stopped) {
                        player.sendMessage(plugin.getPrefix() + " §cActive task stopped.");
                    } else {
                        player.sendMessage(plugin.getPrefix() + " §eTurtle is already idle.");
                    }
                    gui.setupGUI();
                    return;
                }
                if (turtle.getStatus() == Turtle.Status.BUILDING) {
                    turtle.pauseBuild();
                    player.sendMessage(plugin.getPrefix() + " §eConstruction paused. §7(Right-click to stop)");
                } else if (turtle.getStatus() == Turtle.Status.MINING) {
                    turtle.pauseQuarry();
                    player.sendMessage(plugin.getPrefix() + " §eQuarry excavation paused. §7(Right-click to stop)");
                } else if (turtle.getStatus() == Turtle.Status.PAUSED) {
                    if (turtle.isQuarryPaused()) {
                        turtle.resumeQuarry();
                        player.sendMessage(plugin.getPrefix() + " §aQuarry excavation resumed.");
                    } else {
                        turtle.resumeBuild();
                        player.sendMessage(plugin.getPrefix() + " §aConstruction resumed.");
                    }
                }
                gui.setupGUI();
                return;
            }

            // Fuel Slot
            if (rawSlot == TurtleGUI.FUEL_SLOT) {
                event.setCancelled(true);
                ItemStack cursor = event.getCursor();
                if (cursor != null && !cursor.getType().isAir()) {
                    if (Turtle.getFuelValue(cursor.getType()) > 0) {
                        int oldFuel = turtle.getFuel();
                        Material fuelType = cursor.getType();
                        ItemStack remaining = turtle.refuelWithItem(cursor, cursor.getAmount());
                        event.getView().setCursor(remaining);
                        if (fuelType == Material.LAVA_BUCKET && remaining != null && remaining.getType() != Material.BUCKET) {
                            var overflow = player.getInventory().addItem(new ItemStack(Material.BUCKET));
                            for (ItemStack rest : overflow.values()) {
                                player.getWorld().dropItemNaturally(player.getLocation(), rest);
                            }
                        }
                        player.sendMessage(plugin.getPrefix() + " §aRefueled! Current fuel: " + turtle.getFuel()
                                + " §7(+" + (turtle.getFuel() - oldFuel) + ")");
                        gui.setupGUI();
                    } else {
                        player.sendMessage(plugin.getPrefix() + " §cItem on cursor is not a valid fuel source (coal, charcoal, blaze rod, lava bucket, coal block).");
                    }
                }
                return;
            }

            // Allowed interactive slots: DISK_SLOT and the 16 TURTLE_SLOTS
            if (rawSlot == TurtleGUI.DISK_SLOT || TurtleGUI.isTurtleSlot(rawSlot)) {
                // Allowed! Player can take/place items freely.
                return;
            }

            // All decorative glass slots
            event.setCancelled(true);
        }
    }

    private void handleRunButton(Player player, TurtleGUI gui, Turtle turtle) {
        gui.saveToTurtle();
        ItemStack disk = turtle.getDisk();

        if (disk == null || disk.getType().isAir()) {
            player.sendMessage(plugin.getPrefix() + " §cNo floppy disk inserted in Turtle slot 0.");
            return;
        }

        String code = DiskManager.readProgram(disk);
        String error = LuaRunner.validate(code);
        if (error != null) {
            player.sendMessage(plugin.getPrefix() + " §cSyntax error on Turtle disk:\n §4✘ " + error);
            return;
        }

        player.sendMessage(plugin.getPrefix() + " §7Executing Turtle script…");

        turtle.cancelScript();

        Consumer<String> onLine = line -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.sendMessage("§f" + org.bukkit.ChatColor.translateAlternateColorCodes('&', line));
            }
        });

        int maxLines = plugin.getConfigManager().getMaxStreamLines();
        LuaRunner.LuaProgram program = LuaRunner.runStreaming(
                plugin,
                turtle.getLocation(),
                true,
                code,
                plugin.getAdvancedTimeoutMs(),
                maxLines,
                onLine,
                g -> {
                    TurtlePeripheral tp = new TurtlePeripheral(plugin, turtle);
                    g.set("turtle", tp.toLuaTable());
                }
        );
        turtle.setRunningScript(program);
    }

    /**
     * Handles clicks inside the owner-only access control GUI: clicking an authorized
     * player revokes access, clicking an online player grants it.
     */
    private void handleAccessClick(InventoryClickEvent event, Inventory top, Turtle turtle) {
        event.setCancelled(true);
        if (turtle == null || top == null) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getRawSlot() < 0) return;

        if (!player.hasPermission(PERMISSION_ADMIN) && !turtle.isOwner(player.getUniqueId())) {
            player.sendMessage(plugin.getPrefix() + " §cOnly the owner can manage who may operate this Turtle.");
            return;
        }

        UUID target = TurtleAccessGUI.readTargetUuid(event.getCurrentItem());
        if (target == null) return;

        int raw = event.getRawSlot();
        if (raw >= TurtleAccessGUI.FIRST_AUTHORIZED_SLOT
                && raw < TurtleAccessGUI.FIRST_AUTHORIZED_SLOT + TurtleAccessGUI.AUTHORIZED_SLOTS) {
            if (turtle.removeAuthorized(target)) {
                markTurtleDirty();
                player.sendMessage(plugin.getPrefix() + " §e" + TurtleAccessGUI.nameOf(target)
                        + " §7can no longer operate this Turtle.");
                TurtleAccessGUI.refresh(top, turtle);
            }
            return;
        }

        if (raw >= TurtleAccessGUI.FIRST_ONLINE_SLOT && raw < TurtleAccessGUI.SIZE) {
            if (turtle.addAuthorized(target)) {
                markTurtleDirty();
                player.sendMessage(plugin.getPrefix() + " §a" + TurtleAccessGUI.nameOf(target)
                        + " §7can now open and operate this Turtle.");
                Player online = Bukkit.getPlayer(target);
                if (online != null && online.isOnline()) {
                    online.sendMessage(plugin.getPrefix() + " §aYou were authorized to operate Turtle §e"
                            + turtle.getId() + "§a by §f" + player.getName() + "§a.");
                }
                TurtleAccessGUI.refresh(top, turtle);
            } else {
                player.sendMessage(plugin.getPrefix() + " §eThat player is already authorized.");
            }
        }
    }

    private void handleAccessButton(Player player, Turtle turtle) {
        if (!player.hasPermission(PERMISSION_ADMIN) && !turtle.isOwner(player.getUniqueId())) {
            player.sendMessage(plugin.getPrefix() + " §cOnly the owner can manage who may operate this Turtle.");
            return;
        }
        if (turtle.getOwner() == null) {
            player.sendMessage(plugin.getPrefix() + " §eThis Turtle has no registered owner, so access is open to everyone with permission.");
            return;
        }
        TurtleAccessGUI.open(player, turtle);
    }

    /**
     * Whether the given player may open and operate the Turtle (owner, an authorized
     * player, or an administrator).
     */
    private boolean canOperate(Turtle turtle, Player player) {
        if (player.hasPermission(PERMISSION_ADMIN)) return true;
        return turtle.isAccessAllowed(player.getUniqueId());
    }

    /**
     * Whether the given player may break/disassemble the Turtle: its owner or an administrator.
     * Unclaimed turtles (no owner) can only be removed by administrators.
     */
    private boolean canBreakTurtle(Turtle turtle, Player player) {
        if (player.hasPermission(PERMISSION_ADMIN)) return true;
        return turtle.getOwner() != null && turtle.getOwner().equals(player.getUniqueId());
    }

    private void markTurtleDirty() {
        if (plugin.getTurtleManager() != null) {
            plugin.getTurtleManager().markDirty();
        }
    }

    private void handleWebButton(Player player, Turtle turtle) {
        String webUrl = plugin.getConfigManager().getWebPortalPublicUrl();
        if (webUrl == null || webUrl.isBlank()) {
            webUrl = "http://localhost:" + plugin.getConfigManager().getWebPortalPort();
        }
        player.sendMessage(plugin.getPrefix() + " §b=== Web Dashboard ===");
        player.sendMessage(" §7Portal URL: §f" + webUrl);
        player.sendMessage(" §7Target Turtle ID: §e" + turtle.getId());
        Location loc = turtle.getLocation();
        player.sendMessage(String.format(" §7Current Coords: §a[%d, %d, %d] §7Facing: §e%s",
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), turtle.getFacing().name()));
        player.sendMessage(" §7Upload your §b.litematic §7or §b.nbt §7blueprint on the web portal to build!");
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory dragTop = event.getView().getTopInventory();
        if (dragTop != null && dragTop.getHolder() instanceof TurtleAccessHolder) {
            event.setCancelled(true);
            return;
        }
        if (event.getView().getTitle().startsWith(SupplyStationGUI.TITLE_PREFIX)) {
            for (int slot : event.getRawSlots()) {
                if (slot < SupplyStationGUI.SIZE) {
                    event.setCancelled(true);
                    return;
                }
            }
            return;
        }
        if (event.getView().getTopInventory().getHolder() instanceof TurtleGUI) {
            for (int slot : event.getRawSlots()) {
                if (slot < TurtleGUI.INVENTORY_SIZE) {
                    if (slot != TurtleGUI.DISK_SLOT && !TurtleGUI.isTurtleSlot(slot)) {
                        event.setCancelled(true);
                        return;
                    }
                }
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof TurtleGUI gui) {
            gui.saveToTurtle();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();

        // Check if block is protected by an active turtle task (building, mining, active chests, or working turtle)
        Turtle.BlockProtectionCheck protCheck = (plugin.getTurtleManager() != null)
                ? plugin.getTurtleManager().checkBlockProtection(block.getLocation())
                : null;
        if (protCheck != null && protCheck.isProtected()) {
            Player player = event.getPlayer();
            if (!player.isSneaking()) {
                event.setCancelled(true);
                player.sendMessage(plugin.getPrefix() + " §cYou cannot break this block because " + protCheck.reason()
                        + " §eTo force break it, you must sneak (crouch) while breaking.");
                try {
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.5f);
                } catch (Throwable ignored) {}
                return;
            }
        }

        if (com.multiverse.programming.ComputerListener.isMultiverseNetsBlock(block)
                || com.multiverse.programming.ComputerListener.isSlimefunBlock(block)) {
            return;
        }

        if (block.getType() != getTurtleBlock()) {
            return;
        }

        Turtle turtle = plugin.getTurtleManager().getTurtle(block.getLocation());
        if (turtle == null) {
            if (block.getState() instanceof org.bukkit.block.TileState tileState) {
                var pdc = tileState.getPersistentDataContainer();
                org.bukkit.NamespacedKey idKey = new org.bukkit.NamespacedKey(plugin, "turtle_id");
                if (pdc.has(idKey, org.bukkit.persistence.PersistentDataType.STRING)) {
                    turtle = plugin.getTurtleManager().getTurtleById(pdc.get(idKey, org.bukkit.persistence.PersistentDataType.STRING));
                }
            }
        }

        if (turtle == null) {
            return;
        }

        Player player = event.getPlayer();
        if (!canBreakTurtle(turtle, player)) {
            player.sendMessage(plugin.getPrefix() + " §cYou cannot break this Turtle: only its owner or an administrator may do so.");
            event.setCancelled(true);
            return;
        }

        plugin.getTurtleManager().removeTurtle(turtle.getLocation());

        event.setDropItems(false);
        World world = block.getWorld();
        Location loc = block.getLocation();

        // 1. Drop Turtle Item
        world.dropItemNaturally(loc, DiskManager.createTurtle(getTurtleBlock()));

        // 2. Drop Disk
        ItemStack disk = turtle.getDisk();
        if (disk != null && !disk.getType().isAir()) {
            world.dropItemNaturally(loc, disk);
        }

        // 3. Drop all 16 inventory items
        for (ItemStack item : turtle.getInventory()) {
            if (item != null && !item.getType().isAir()) {
                world.dropItemNaturally(loc, item);
            }
        }

        event.getPlayer().sendMessage(plugin.getPrefix() + " §eTurtle " + turtle.getId() + " disassembled.");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (plugin.getTurtleManager() == null) return;
        event.blockList().removeIf(b -> {
            Turtle.BlockProtectionCheck check = plugin.getTurtleManager().checkBlockProtection(b.getLocation());
            return check != null && check.isProtected();
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (plugin.getTurtleManager() == null) return;
        event.blockList().removeIf(b -> {
            Turtle.BlockProtectionCheck check = plugin.getTurtleManager().checkBlockProtection(b.getLocation());
            return check != null && check.isProtected();
        });
    }
}
