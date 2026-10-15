// SPDX-License-Identifier: GPL-3.0-or-later
package com.multiverse.programming.peripheral;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BrewingStand;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionType;
import org.luaj.vm2.LuaBoolean;
import org.luaj.vm2.LuaInteger;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Peripheral that manages automated alchemical synthesis, recipe querying,
 * and programmatic potion crafting.
 */
public final class AlchemistPeripheral implements Peripheral {

    private final JavaPlugin plugin;
    private final Location location;

    public AlchemistPeripheral(JavaPlugin plugin, Location location) {
        this.plugin = plugin;
        this.location = location;
    }

    @Override
    public String getType() {
        return "alchemist";
    }

    @Override
    public Location getLocation() {
        return location;
    }

    private List<Inventory> getConnectedInventories() {
        List<Inventory> list = new ArrayList<>();
        if (location == null) return list;
        Block block = location.getBlock();
        if (block == null) return list;
        if (block.getState() instanceof BrewingStand stand) {
            list.add(stand.getInventory());
        }
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN}) {
            Block rel = block.getRelative(face);
            if (rel != null && rel.getState() instanceof InventoryHolder holder && !(holder instanceof BrewingStand)) {
                list.add(holder.getInventory());
            }
        }
        return list;
    }

    // Only plain water bottles are a valid base; any other POTION (e.g. the previous output) must not be consumed.
    static boolean isWaterBottle(ItemStack item) {
        return item != null && item.getType() == Material.POTION
                && item.getItemMeta() instanceof PotionMeta meta && meta.getBasePotionType() == PotionType.WATER;
    }

    private boolean consumeItem(Material mat, int amount) {
        return consumeItem(item -> item.getType() == mat, amount);
    }

    private boolean consumeItem(Predicate<ItemStack> matches, int amount) {
        int needed = amount;
        for (Inventory inv : getConnectedInventories()) {
            for (ItemStack item : inv.getContents()) {
                if (item != null && matches.test(item) && item.getAmount() > 0) {
                    int take = Math.min(needed, item.getAmount());
                    item.setAmount(item.getAmount() - take);
                    needed -= take;
                    if (needed <= 0) return true;
                }
            }
        }
        return false;
    }

    private boolean hasItem(Material mat, int amount) {
        return hasItem(item -> item.getType() == mat, amount);
    }

    private boolean hasItem(Predicate<ItemStack> matches, int amount) {
        int count = 0;
        for (Inventory inv : getConnectedInventories()) {
            for (ItemStack item : inv.getContents()) {
                if (item != null && matches.test(item)) {
                    count += item.getAmount();
                    if (count >= amount) return true;
                }
            }
        }
        return false;
    }

    private boolean depositItem(ItemStack item) {
        for (Inventory inv : getConnectedInventories()) {
            // addItem ignores brewing stand slot rules (would fill ingredient/fuel slots)
            if (inv instanceof BrewerInventory) continue;
            if (inv.firstEmpty() != -1) {
                inv.addItem(item);
                return true;
            }
        }
        World world = location.getWorld();
        if (world != null) {
            world.dropItemNaturally(location.clone().add(0.5, 1.0, 0.5), item);
            return true;
        }
        return false;
    }

    private Material getIngredientForType(String type) {
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "SPEED", "SWIFTNESS" -> Material.SUGAR;
            case "SLOWNESS" -> Material.FERMENTED_SPIDER_EYE;
            case "STRENGTH" -> Material.BLAZE_POWDER;
            case "HEALING", "INSTANT_HEALTH" -> Material.GLISTERING_MELON_SLICE;
            case "HARMING", "INSTANT_DAMAGE" -> Material.FERMENTED_SPIDER_EYE;
            case "JUMP_BOOST", "LEAPING" -> Material.RABBIT_FOOT;
            case "REGENERATION" -> Material.GHAST_TEAR;
            case "FIRE_RESISTANCE" -> Material.MAGMA_CREAM;
            case "WATER_BREATHING" -> Material.PUFFERFISH;
            case "INVISIBILITY" -> Material.FERMENTED_SPIDER_EYE;
            case "NIGHT_VISION" -> Material.GOLDEN_CARROT;
            case "POISON" -> Material.SPIDER_EYE;
            case "WEAKNESS" -> Material.FERMENTED_SPIDER_EYE;
            case "SLOW_FALLING" -> Material.PHANTOM_MEMBRANE;
            default -> null;
        };
    }

    /** LONG_/STRONG_ variant of a base potion type, or null when vanilla has none (e.g. STRONG_FIRE_RESISTANCE). */
    static PotionType variantOf(PotionType base, String prefix) {
        try {
            return PotionType.valueOf(prefix + base.name());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private PotionType parsePotionType(String name) {
        if (name == null) return null;
        String s = name.trim().toUpperCase(Locale.ROOT);
        try {
            return PotionType.valueOf(s);
        } catch (IllegalArgumentException e) {
            return switch (s) {
                case "SPEED", "SWIFTNESS" -> PotionType.SWIFTNESS;
                case "HEALING", "INSTANT_HEALTH", "HEALTH" -> PotionType.HEALING;
                case "HARMING", "INSTANT_DAMAGE", "DAMAGE" -> PotionType.HARMING;
                case "LEAPING", "JUMP", "JUMP_BOOST" -> PotionType.LEAPING;
                case "REGEN", "REGENERATION" -> PotionType.REGENERATION;
                case "FIRE", "FIRE_RESISTANCE" -> PotionType.FIRE_RESISTANCE;
                case "WATER", "WATER_BREATHING" -> PotionType.WATER_BREATHING;
                case "INVIS", "INVISIBILITY" -> PotionType.INVISIBILITY;
                case "NIGHT", "NIGHT_VISION" -> PotionType.NIGHT_VISION;
                case "SLOW", "SLOWNESS" -> PotionType.SLOWNESS;
                default -> null;
            };
        }
    }

    @Override
    public LuaValue toLuaTable() {
        LuaTable table = new LuaTable();

        // alchemist.getRecipes() -> table of brewable potion names
        table.set("getRecipes", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                LuaTable list = new LuaTable();
                String[] recipes = {
                        "SWIFTNESS", "SLOWNESS", "STRENGTH", "HEALING", "HARMING",
                        "LEAPING", "REGENERATION", "FIRE_RESISTANCE", "WATER_BREATHING",
                        "INVISIBILITY", "NIGHT_VISION", "POISON", "WEAKNESS", "SLOW_FALLING"
                };
                for (int i = 0; i < recipes.length; i++) {
                    list.set(i + 1, recipes[i]);
                }
                return list;
            }
        });

        // alchemist.inspectStand() -> table with brewing stand info
        table.set("inspectStand", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return SyncDispatcher.sync(plugin, () -> {
                    LuaTable info = new LuaTable();
                    Block b = location.getBlock();
                    if (b.getState() instanceof BrewingStand stand) {
                        info.set("fuelLevel", LuaInteger.valueOf(stand.getFuelLevel()));
                        info.set("brewingTime", LuaInteger.valueOf(stand.getBrewingTime()));
                        BrewerInventory inv = stand.getInventory();
                        ItemStack fuel = inv.getFuel();
                        info.set("hasFuelItem", LuaBoolean.valueOf(fuel != null && fuel.getType() == Material.BLAZE_POWDER));
                    } else {
                        info.set("fuelLevel", LuaInteger.valueOf(0));
                        info.set("isStand", LuaBoolean.FALSE);
                    }
                    return info;
                });
            }
        });

        // alchemist.brew(potionType, [extended/upgraded], [isSplash])
        table.set("brew", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String potionName = args.checkjstring(1);
                String modifier = args.narg() >= 2 && !args.arg(2).isnil() ? args.checkjstring(2).toLowerCase(Locale.ROOT) : "";
                boolean splash = args.narg() >= 3 && args.checkboolean(3);

                PotionType pType = parsePotionType(potionName);
                if (pType == null) {
                    return varargsOf(LuaBoolean.FALSE, LuaString.valueOf("Unknown potion type: " + potionName));
                }

                // Ingredient lookup by the canonical type so aliases (REGEN, FIRE, INVIS...) work too
                Material ingredient = getIngredientForType(pType.name());
                if (ingredient == null) {
                    return varargsOf(LuaBoolean.FALSE, LuaString.valueOf("No recipe ingredient known for: " + potionName));
                }

                // Modifier (Redstone for long, Glowstone for strong) must map to a real potion variant
                boolean longMod = modifier.contains("long") || modifier.contains("extend");
                boolean strongMod = modifier.contains("strong") || modifier.contains("ii") || modifier.contains("2");
                if (longMod && strongMod) {
                    return varargsOf(LuaBoolean.FALSE, LuaString.valueOf("A potion cannot be both extended and upgraded"));
                }
                PotionType resultType = pType;
                if (longMod || strongMod) {
                    resultType = variantOf(pType, longMod ? "LONG_" : "STRONG_");
                    if (resultType == null) {
                        return varargsOf(LuaBoolean.FALSE, LuaString.valueOf(pType.name() + " has no " + (longMod ? "extended" : "upgraded") + " variant"));
                    }
                }
                final PotionType finalType = resultType;

                return SyncDispatcher.sync(plugin, () -> {
                    // Check base ingredient: Water Bottle
                    if (!hasItem(AlchemistPeripheral::isWaterBottle, 1)) {
                        return varargsOf(LuaBoolean.FALSE, LuaString.valueOf("Missing Water Bottle in adjacent containers"));
                    }

                    // Check Nether wart for non-weakness potions
                    boolean needsNetherWart = pType != PotionType.WEAKNESS;
                    if (needsNetherWart && !hasItem(Material.NETHER_WART, 1)) {
                        return varargsOf(LuaBoolean.FALSE, LuaString.valueOf("Missing Nether Wart in adjacent containers"));
                    }

                    // Check primary ingredient
                    if (!hasItem(ingredient, 1)) {
                        return varargsOf(LuaBoolean.FALSE, LuaString.valueOf("Missing ingredient " + ingredient.name() + " in adjacent containers"));
                    }

                    if (longMod && !hasItem(Material.REDSTONE, 1)) {
                        return varargsOf(LuaBoolean.FALSE, LuaString.valueOf("Missing Redstone for extended potion"));
                    }
                    if (strongMod && !hasItem(Material.GLOWSTONE_DUST, 1)) {
                        return varargsOf(LuaBoolean.FALSE, LuaString.valueOf("Missing Glowstone Dust for upgraded potion"));
                    }
                    if (splash && !hasItem(Material.GUNPOWDER, 1)) {
                        return varargsOf(LuaBoolean.FALSE, LuaString.valueOf("Missing Gunpowder for splash potion"));
                    }

                    // Consume items
                    consumeItem(AlchemistPeripheral::isWaterBottle, 1);
                    if (needsNetherWart) consumeItem(Material.NETHER_WART, 1);
                    consumeItem(ingredient, 1);
                    if (longMod) consumeItem(Material.REDSTONE, 1);
                    if (strongMod) consumeItem(Material.GLOWSTONE_DUST, 1);
                    if (splash) consumeItem(Material.GUNPOWDER, 1);

                    // Create resulting potion
                    Material finalMat = splash ? Material.SPLASH_POTION : Material.POTION;
                    ItemStack potionItem = new ItemStack(finalMat);
                    PotionMeta meta = (PotionMeta) potionItem.getItemMeta();
                    if (meta != null) {
                        meta.setBasePotionType(finalType);
                        potionItem.setItemMeta(meta);
                    }

                    depositItem(potionItem);
                    return varargsOf(LuaBoolean.TRUE, LuaString.valueOf("Successfully synthesized " + potionName + (splash ? " (Splash)" : "")));
                });
            }
        });

        return table;
    }
}
