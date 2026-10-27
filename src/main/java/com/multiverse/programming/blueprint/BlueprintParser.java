// SPDX-License-Identifier: GPL-3.0-or-later
package com.multiverse.programming.blueprint;

import com.multiverse.programming.blueprint.nbt.NbtReader;
import com.multiverse.programming.blueprint.nbt.NbtTag;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parser capable of reading both Litematica (.litematic) and Vanilla Structure (.nbt) files.
 */
public final class BlueprintParser {

    private BlueprintParser() {
    }

    public static Blueprint parse(String id, String fileName, InputStream in) throws IOException {
        NbtTag.CompoundTag root = NbtReader.read(in);
        return parseCompound(id, fileName, root);
    }

    public static Blueprint parse(String id, File file) throws IOException {
        NbtTag.CompoundTag root = NbtReader.read(file);
        return parseCompound(id, file.getName(), root);
    }

    public static Blueprint parseCompound(String id, String fileName, NbtTag.CompoundTag root) throws IOException {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".litematic") || (root.contains("Metadata") && root.contains("Regions"))) {
            return parseLitematic(id, fileName, root);
        } else if (lower.endsWith(".schem") || (root.contains("Width") && root.contains("Palette") && root.contains("BlockData"))) {
            return parseSpongeSchematic(id, fileName, root);
        } else {
            return parseVanillaStructure(id, fileName, root);
        }
    }

    private static Blueprint parseSpongeSchematic(String id, String fileName, NbtTag.CompoundTag root) throws IOException {
        String name = resolveCleanName(null, fileName);
        String author = "Unknown";
        NbtTag.CompoundTag metadata = root.getCompound("Metadata");
        if (metadata != null) {
            author = metadata.getString("Author", author);
            String metaName = metadata.getString("Name", null);
            if (metaName != null && !metaName.isBlank()) {
                name = resolveCleanName(metaName, fileName);
            }
        }

        int sizeX = Math.max(1, root.getInt("Width", 1));
        int sizeY = Math.max(1, root.getInt("Height", 1));
        int sizeZ = Math.max(1, root.getInt("Length", 1));

        NbtTag.CompoundTag paletteTag = root.getCompound("Palette");
        Map<Integer, String> idToBlock = new java.util.HashMap<>();
        Map<Integer, String> idToItem = new java.util.HashMap<>();

        if (paletteTag != null) {
            for (Map.Entry<String, NbtTag> entry : paletteTag.value().entrySet()) {
                if (entry.getValue() instanceof NbtTag.IntTag it) {
                    String blockState = entry.getKey();
                    if (!blockState.contains(":")) {
                        blockState = "minecraft:" + blockState;
                    }
                    idToBlock.put(it.value(), blockState);
                    idToItem.put(it.value(), resolveItemName(cleanMaterial(blockState)));
                }
            }
        }

        byte[] blockData = root.getByteArray("BlockData");
        List<Blueprint.PlacementBlock> blocks = new ArrayList<>();
        Map<String, Integer> materialCounts = new LinkedHashMap<>();

        if (blockData != null && !idToBlock.isEmpty()) {
            int index = 0;
            int totalExpected = sizeX * sizeY * sizeZ;
            for (int i = 0; i < totalExpected && index < blockData.length; i++) {
                int value = 0;
                int shift = 0;
                while (index < blockData.length) {
                    byte b = blockData[index++];
                    value |= (b & 0x7F) << shift;
                    if ((b & 0x80) == 0) {
                        break;
                    }
                    shift += 7;
                }

                String mat = idToBlock.getOrDefault(value, "minecraft:air");
                if (!isAir(mat)) {
                    int x = i % sizeX;
                    int z = (i / sizeX) % sizeZ;
                    int y = i / (sizeX * sizeZ);

                    blocks.add(new Blueprint.PlacementBlock(x, y, z, mat));
                    String item = idToItem.getOrDefault(value, cleanMaterial(mat));
                    boolean isUpperHalf = mat.contains("half=upper") || mat.contains("part=head");
                    if (!isUpperHalf) {
                        materialCounts.put(item, materialCounts.getOrDefault(item, 0) + 1);
                    }
                }
            }
        }

        // Sort blocks: layer by layer (y), placement priority, then z and x
        blocks.sort((a, b) -> {
            if (a.y() != b.y()) return Integer.compare(a.y(), b.y());
            int pA = getPlacementPriority(a.material());
            int pB = getPlacementPriority(b.material());
            if (pA != pB) return Integer.compare(pA, pB);
            if (a.z() != b.z()) return Integer.compare(a.z(), b.z());
            return Integer.compare(a.x(), b.x());
        });

        return new Blueprint(
                id,
                name,
                author,
                "schem",
                sizeX,
                sizeY,
                sizeZ,
                blocks.size(),
                materialCounts,
                blocks,
                System.currentTimeMillis()
        );
    }

    private static Blueprint parseVanillaStructure(String id, String fileName, NbtTag.CompoundTag root) throws IOException {
        String name = resolveCleanName(root.getString("name", null), fileName);
        String author = root.getString("author", "Unknown");

        int sizeX = 1, sizeY = 1, sizeZ = 1;
        NbtTag.ListTag sizeList = root.getList("size");
        if (sizeList != null && sizeList.value().size() >= 3) {
            sizeX = Math.max(1, ((NbtTag.IntTag) sizeList.value().get(0)).value());
            sizeY = Math.max(1, ((NbtTag.IntTag) sizeList.value().get(1)).value());
            sizeZ = Math.max(1, ((NbtTag.IntTag) sizeList.value().get(2)).value());
        }

        List<String> palette = new ArrayList<>();
        List<String> itemPalette = new ArrayList<>();
        NbtTag.ListTag paletteList = root.getList("palette");
        if (paletteList == null) {
            NbtTag.ListTag palettes = root.getList("palettes");
            if (palettes != null && !palettes.value().isEmpty() && palettes.value().get(0) instanceof NbtTag.ListTag inner) {
                paletteList = inner;
            }
        }
        if (paletteList != null) {
            for (NbtTag tag : paletteList.value()) {
                if (tag instanceof NbtTag.CompoundTag comp) {
                    palette.add(parseBlockStateString(comp));
                    itemPalette.add(resolveItemName(comp.getString("Name", "minecraft:air")));
                }
            }
        }

        List<Blueprint.PlacementBlock> blocks = new ArrayList<>();
        Map<String, Integer> materialCounts = new LinkedHashMap<>();

        NbtTag.ListTag blocksList = root.getList("blocks");
        if (blocksList != null) {
            for (NbtTag tag : blocksList.value()) {
                if (tag instanceof NbtTag.CompoundTag comp) {
                    int state = comp.getInt("state", 0);
                    String mat = (state >= 0 && state < palette.size()) ? palette.get(state) : "minecraft:air";
                    if (isAir(mat)) {
                        continue;
                    }

                    NbtTag.ListTag posList = comp.getList("pos");
                    if (posList != null && posList.value().size() >= 3) {
                        int x = ((NbtTag.IntTag) posList.value().get(0)).value();
                        int y = ((NbtTag.IntTag) posList.value().get(1)).value();
                        int z = ((NbtTag.IntTag) posList.value().get(2)).value();

                        blocks.add(new Blueprint.PlacementBlock(x, y, z, mat));
                        String item = (state >= 0 && state < itemPalette.size()) ? itemPalette.get(state) : cleanMaterial(mat);
                        boolean isUpperHalf = mat.contains("half=upper") || mat.contains("part=head");
                        if (!isUpperHalf) {
                            materialCounts.put(item, materialCounts.getOrDefault(item, 0) + 1);
                        }
                    }
                }
            }
        }

        // Sort blocks: layer by layer (y), placement priority, then z and x
        blocks.sort((a, b) -> {
            if (a.y() != b.y()) return Integer.compare(a.y(), b.y());
            int pA = getPlacementPriority(a.material());
            int pB = getPlacementPriority(b.material());
            if (pA != pB) return Integer.compare(pA, pB);
            if (a.z() != b.z()) return Integer.compare(a.z(), b.z());
            return Integer.compare(a.x(), b.x());
        });

        return new Blueprint(
                id,
                name,
                author,
                "nbt",
                sizeX,
                sizeY,
                sizeZ,
                blocks.size(),
                materialCounts,
                blocks,
                System.currentTimeMillis()
        );
    }

    private static Blueprint parseLitematic(String id, String fileName, NbtTag.CompoundTag root) throws IOException {
        NbtTag.CompoundTag metadata = root.getCompound("Metadata");
        String metaName = metadata != null ? metadata.getString("Name", null) : null;
        String name = resolveCleanName(metaName, fileName);
        String author = metadata != null ? metadata.getString("Author", "Unknown") : "Unknown";

        int sizeX = 1, sizeY = 1, sizeZ = 1;
        if (metadata != null) {
            NbtTag.CompoundTag encSize = metadata.getCompound("EnclosingSize");
            if (encSize != null) {
                sizeX = Math.abs(encSize.getInt("x", 1));
                sizeY = Math.abs(encSize.getInt("y", 1));
                sizeZ = Math.abs(encSize.getInt("z", 1));
            }
        }

        List<Blueprint.PlacementBlock> blocks = new ArrayList<>();
        Map<String, Integer> materialCounts = new LinkedHashMap<>();

        NbtTag.CompoundTag regions = root.getCompound("Regions");
        if (regions != null) {
            for (Map.Entry<String, NbtTag> entry : regions.value().entrySet()) {
                if (!(entry.getValue() instanceof NbtTag.CompoundTag region)) {
                    continue;
                }

                NbtTag.CompoundTag rSize = region.getCompound("Size");
                int rx = rSize != null ? Math.abs(rSize.getInt("x", 1)) : 1;
                int ry = rSize != null ? Math.abs(rSize.getInt("y", 1)) : 1;
                int rz = rSize != null ? Math.abs(rSize.getInt("z", 1)) : 1;

                List<String> palette = new ArrayList<>();
                List<String> itemPalette = new ArrayList<>();
                NbtTag.ListTag paletteTag = region.getList("BlockStatePalette");
                if (paletteTag != null) {
                    for (NbtTag pt : paletteTag.value()) {
                        if (pt instanceof NbtTag.CompoundTag comp) {
                            palette.add(parseBlockStateString(comp));
                            itemPalette.add(resolveItemName(comp.getString("Name", "minecraft:air")));
                        }
                    }
                }

                long[] blockStates = region.getLongArray("BlockStates");
                if (palette.isEmpty()) {
                    continue;
                }

                if (palette.size() == 1 || blockStates == null || blockStates.length == 0) {
                    String singleMat = palette.get(0);
                    if (!isAir(singleMat)) {
                        String item = !itemPalette.isEmpty() ? itemPalette.get(0) : cleanMaterial(singleMat);
                        boolean isUpperHalf = singleMat.contains("half=upper") || singleMat.contains("part=head");
                        for (int y = 0; y < ry; y++) {
                            for (int z = 0; z < rz; z++) {
                                for (int x = 0; x < rx; x++) {
                                    blocks.add(new Blueprint.PlacementBlock(x, y, z, singleMat));
                                    if (!isUpperHalf) {
                                        materialCounts.put(item, materialCounts.getOrDefault(item, 0) + 1);
                                    }
                                }
                            }
                        }
                    }
                    continue;
                }

                // Bit-unpacking of Litematica BlockStates array
                int bitsPerEntry = Math.max(2, (int) Math.ceil(Math.log(palette.size()) / Math.log(2)));
                long mask = (1L << bitsPerEntry) - 1L;
                int totalBlocksInRegion = rx * ry * rz;

                for (int i = 0; i < totalBlocksInRegion; i++) {
                    long bitIndex = (long) i * bitsPerEntry;
                    int startLong = (int) (bitIndex / 64);
                    int startBit = (int) (bitIndex % 64);

                    if (startLong >= blockStates.length) {
                        break;
                    }

                    long value = blockStates[startLong] >>> startBit;
                    if (startBit + bitsPerEntry > 64 && startLong + 1 < blockStates.length) {
                        value |= blockStates[startLong + 1] << (64 - startBit);
                    }
                    int paletteIdx = (int) (value & mask);

                    if (paletteIdx >= 0 && paletteIdx < palette.size()) {
                        String mat = palette.get(paletteIdx);
                        if (!isAir(mat)) {
                            int y = (i / (rx * rz)) % ry;
                            int z = (i / rx) % rz;
                            int x = i % rx;

                            blocks.add(new Blueprint.PlacementBlock(x, y, z, mat));
                            String item = (paletteIdx < itemPalette.size()) ? itemPalette.get(paletteIdx) : cleanMaterial(mat);
                            boolean isUpperHalf = mat.contains("half=upper") || mat.contains("part=head");
                            if (!isUpperHalf) {
                                materialCounts.put(item, materialCounts.getOrDefault(item, 0) + 1);
                            }
                        }
                    }
                }
            }
        }

        // Sort blocks: bottom-to-top (y), placement priority, then z and x
        blocks.sort((a, b) -> {
            if (a.y() != b.y()) return Integer.compare(a.y(), b.y());
            int pA = getPlacementPriority(a.material());
            int pB = getPlacementPriority(b.material());
            if (pA != pB) return Integer.compare(pA, pB);
            if (a.z() != b.z()) return Integer.compare(a.z(), b.z());
            return Integer.compare(a.x(), b.x());
        });

        return new Blueprint(
                id,
                name,
                author,
                "litematic",
                sizeX,
                sizeY,
                sizeZ,
                blocks.size(),
                materialCounts,
                blocks,
                System.currentTimeMillis()
        );
    }

    public static String parseBlockStateString(NbtTag.CompoundTag comp) {
        if (comp == null) return "minecraft:air";
        String name = comp.getString("Name", "minecraft:air");
        if (!name.contains(":")) {
            name = "minecraft:" + name.toLowerCase(Locale.ROOT);
        }
        NbtTag.CompoundTag properties = comp.getCompound("Properties");
        if (properties == null || properties.value().isEmpty()) {
            return name;
        }

        List<String> sortedKeys = new ArrayList<>(properties.value().keySet());
        Collections.sort(sortedKeys);
        StringBuilder sb = new StringBuilder(name).append("[");
        boolean first = true;
        for (String k : sortedKeys) {
            NbtTag valTag = properties.value().get(k);
            if (valTag == null) continue;
            String val = "";
            if (valTag instanceof NbtTag.StringTag st) {
                val = st.value();
            } else if (valTag instanceof NbtTag.ByteTag bt) {
                val = String.valueOf(bt.value());
            } else if (valTag instanceof NbtTag.IntTag it) {
                val = String.valueOf(it.value());
            } else if (valTag instanceof NbtTag.ShortTag sht) {
                val = String.valueOf(sht.value());
            } else if (valTag instanceof NbtTag.LongTag lt) {
                val = String.valueOf(lt.value());
            } else {
                val = valTag.toString();
            }
            if (!first) sb.append(",");
            sb.append(k).append("=").append(val);
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    public static String resolveItemName(String blockName) {
        String clean = cleanMaterial(blockName);
        if (clean.equals("WALL_TORCH")) {
            return "TORCH";
        }
        if (clean.equals("SOUL_WALL_TORCH")) {
            return "SOUL_TORCH";
        }
        if (clean.equals("REDSTONE_WALL_TORCH")) {
            return "REDSTONE_TORCH";
        }
        if (clean.endsWith("_WALL_HANGING_SIGN")) {
            return clean.replace("_WALL_HANGING_SIGN", "_HANGING_SIGN");
        }
        if (clean.endsWith("_WALL_SIGN")) {
            return clean.replace("_WALL_SIGN", "_SIGN");
        }
        if (clean.endsWith("_WALL_TORCH")) {
            return clean.replace("_WALL_TORCH", "_TORCH");
        }
        if (clean.endsWith("_WALL_BANNER")) {
            return clean.replace("_WALL_BANNER", "_BANNER");
        }
        if (clean.endsWith("_WALL_FAN")) {
            return clean.replace("_WALL_FAN", "_FAN");
        }
        if (clean.startsWith("POTTED_")) {
            return "FLOWER_POT";
        }
        return clean;
    }

    public static int getPlacementPriority(String state) {
        if (state == null) return 0;
        String s = state.toLowerCase(Locale.ROOT);

        // Priority 5: Liquids (Placed last so containment walls/floors exist)
        if (s.contains(":water") || s.contains(":flowing_water") || s.contains(":lava") || s.contains(":flowing_lava")) {
            return 5;
        }

        // Priority 4: Delicate attachments & wall-mounted blocks (Need solid base or wall first)
        if (s.contains("wall_torch") || s.contains("torch") || s.contains("lantern") || s.contains("sea_pickle")
                || s.contains("wall_sign") || s.contains("hanging_sign") || s.contains("sign")
                || s.contains("ladder") || s.contains("lever") || s.contains("button")
                || s.contains("redstone_wire") || s.contains("repeater") || s.contains("comparator")
                || s.contains("rail") || s.contains("carpet") || s.contains("flower")
                || s.contains("sapling") || s.contains("banner") || s.contains("bell")
                || s.contains("vines") || s.contains("glow_lichen")) {
            return 4;
        }

        // Priority 3: Double blocks (Upper halves)
        if (s.contains("half=upper") || s.contains("part=head")) {
            return 3;
        }

        // Priority 2: Double blocks (Lower halves)
        if (s.contains("half=lower") || s.contains("door") || s.contains("bed") || s.contains("part=foot")) {
            return 2;
        }

        // Priority 2: Gravity-affected blocks (Must be placed AFTER solid foundation, before attachments)
        if (s.contains("sand") || s.contains("gravel") || s.contains("concrete_powder") || s.contains("anvil") || s.contains("pointed_dripstone")) {
            return 2;
        }

        // Priority 1: Structural attachments (Stairs, slabs, walls, fences, trapdoors)
        if (s.contains("stairs") || s.contains("slab") || s.contains("wall")
                || s.contains("fence") || s.contains("trapdoor") || s.contains("bars")
                || s.contains("chain")) {
            return 1;
        }

        // Priority 0: Solid structural foundation/walls (Stone, planks, bricks, dirt, etc.)
        return 0;
    }

    public static String cleanMaterial(String name) {
        if (name == null) {
            return "AIR";
        }
        if (name.startsWith("minecraft:")) {
            name = name.substring("minecraft:".length());
        }
        if (name.contains("[")) {
            name = name.substring(0, name.indexOf('['));
        }
        return name.toUpperCase(Locale.ROOT);
    }

    public static boolean isAir(String mat) {
        if (mat == null) return true;
        String s = mat.toLowerCase(Locale.ROOT);
        return s.equals("air") || s.equals("minecraft:air")
                || s.equals("cave_air") || s.equals("minecraft:cave_air")
                || s.equals("void_air") || s.equals("minecraft:void_air")
                || s.startsWith("minecraft:air[") || s.startsWith("air[");
    }

    public static String resolveCleanName(String metadataName, String fileName) {
        String cleanFile = fileName != null ? fileName.replaceFirst("\\.(?i)(litematic|nbt|schem)$", "").trim() : "";
        if (metadataName == null || metadataName.isBlank()) {
            return cleanFile.isEmpty() ? "Unnamed Blueprint" : cleanFile;
        }
        String trimmedMeta = metadataName.trim();
        boolean isRepeated = trimmedMeta.matches("^(.)\\1+$");
        boolean isPlaceholder = trimmedMeta.matches("(?i)^(test|temp|schematic|untitled|new|sample|litematic|nbt|aaaa+.*)$");
        if ((isRepeated || isPlaceholder || trimmedMeta.length() <= 2) && !cleanFile.isEmpty() && cleanFile.length() > 2) {
            return cleanFile;
        }
        return trimmedMeta;
    }
}
