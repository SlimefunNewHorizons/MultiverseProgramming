// SPDX-License-Identifier: GPL-3.0-or-later
package com.multiverse.programming.peripheral;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.java.JavaPlugin;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Peripheral that allows writing and managing text displayed on a monitor block in the world.
 * Backed by a Minecraft TextDisplay entity floating over the block.
 */
public final class MonitorPeripheral implements Peripheral {

    public static final String SCOREBOARD_TAG = "multiverse_monitor";
    // Caps keep the TextDisplay packet small and stop setLine(huge) from exhausting memory.
    public static final int MAX_LINES = 20;
    public static final int MAX_LINE_LENGTH = 256;
    private final JavaPlugin plugin;
    private final Location location;
    private final List<String> lines = new ArrayList<>();

    public MonitorPeripheral(JavaPlugin plugin, Location location) {
        this.plugin = plugin;
        this.location = location;
    }

    @Override
    public String getType() {
        return "monitor";
    }

    @Override
    public Location getLocation() {
        return location;
    }

    private void updateWorldDisplay() {
        SyncDispatcher.syncVoid(plugin, () -> {
            if (location.getWorld() == null || !location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                return;
            }
            Location spawnLoc = location.clone().add(0.5, 1.25, 0.5);
            TextDisplay display = findExistingDisplay(spawnLoc);
            String fullText = String.join("\n", lines);

            if (lines.isEmpty() || fullText.isBlank()) {
                if (display != null) {
                    display.remove();
                }
                return;
            }

            if (display == null) {
                display = location.getWorld().spawn(spawnLoc, TextDisplay.class, d -> {
                    d.addScoreboardTag(SCOREBOARD_TAG);
                    d.setBillboard(Display.Billboard.CENTER);
                    d.setDefaultBackground(true);
                    d.setShadowed(true);
                });
            }
            display.setText(ChatColor.translateAlternateColorCodes('&', fullText));
        });
    }

    private TextDisplay findExistingDisplay(Location targetLoc) {
        if (targetLoc.getWorld() == null) {
            return null;
        }
        Collection<Entity> nearby = targetLoc.getWorld().getNearbyEntities(targetLoc, 1.0, 1.0, 1.0);
        for (Entity e : nearby) {
            if (e instanceof TextDisplay td && td.getScoreboardTags().contains(SCOREBOARD_TAG)) {
                return td;
            }
        }
        return null;
    }

    public static void removeDisplayAt(Location loc) {
        if (loc.getWorld() == null) {
            return;
        }
        Location targetLoc = loc.clone().add(0.5, 1.25, 0.5);
        Collection<Entity> nearby = targetLoc.getWorld().getNearbyEntities(targetLoc, 1.0, 1.0, 1.0);
        for (Entity e : nearby) {
            if (e instanceof TextDisplay td && td.getScoreboardTags().contains(SCOREBOARD_TAG)) {
                td.remove();
            }
        }
    }

    public static boolean hasDisplayAt(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        Location targetLoc = loc.clone().add(0.5, 1.25, 0.5);
        Collection<Entity> nearby = targetLoc.getWorld().getNearbyEntities(targetLoc, 1.0, 1.0, 1.0);
        for (Entity e : nearby) {
            if (e instanceof TextDisplay td && td.getScoreboardTags().contains(SCOREBOARD_TAG)) {
                return true;
            }
        }
        return false;
    }

    private static String clip(String line) {
        return line.length() > MAX_LINE_LENGTH ? line.substring(0, MAX_LINE_LENGTH) : line;
    }

    public void setText(String text) {
        lines.clear();
        if (text != null && !text.isEmpty()) {
            for (String l : text.split("\n")) {
                if (lines.size() >= MAX_LINES) break;
                lines.add(clip(l));
            }
        }
        updateWorldDisplay();
    }

    public void clear() {
        lines.clear();
        updateWorldDisplay();
    }

    @Override
    public LuaValue toLuaTable() {
        LuaTable table = new LuaTable();

        // monitor.write(text) -> appends text or adds new line
        table.set("write", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                String str = arg.tojstring();
                // Scroll: drop the oldest line once the screen is full.
                if (lines.size() >= MAX_LINES) {
                    lines.remove(0);
                }
                lines.add(clip(str));
                updateWorldDisplay();
                return LuaValue.NONE;
            }
        });

        // monitor.setText(text) -> replaces entire content (multiline supported)
        table.set("setText", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                setText(arg.tojstring());
                return LuaValue.NONE;
            }
        });

        // monitor.getText() -> returns entire content
        table.set("getText", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return LuaValue.valueOf(String.join("\n", lines));
            }
        });

        // monitor.setLine(lineNum, text) -> 1-based line index
        table.set("setLine", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue arg1, LuaValue arg2) {
                int lineNum = arg1.checkint();
                String text = arg2.tojstring();
                if (lineNum <= 0) {
                    return LuaValue.NONE;
                }
                if (lineNum > MAX_LINES) {
                    throw new LuaError("line " + lineNum + " out of range (monitor has " + MAX_LINES + " lines)");
                }
                while (lines.size() < lineNum) {
                    lines.add("");
                }
                lines.set(lineNum - 1, clip(text));
                updateWorldDisplay();
                return LuaValue.NONE;
            }
        });

        // monitor.getLine(lineNum) -> gets text at 1-based index
        table.set("getLine", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                int lineNum = arg.checkint();
                if (lineNum >= 1 && lineNum <= lines.size()) {
                    return LuaValue.valueOf(lines.get(lineNum - 1));
                }
                return LuaValue.NIL;
            }
        });

        // monitor.clear() -> clears all lines and removes display
        table.set("clear", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                lines.clear();
                updateWorldDisplay();
                return LuaValue.NONE;
            }
        });

        // monitor.getLineCount() -> returns number of lines
        table.set("getLineCount", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return LuaValue.valueOf(lines.size());
            }
        });

        return table;
    }
}
