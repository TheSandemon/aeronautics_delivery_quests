package com.ladderstar.adq;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class ADQSchematicManager {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Map<String, StructureTemplate> SCHEMATICS = new HashMap<>();
    private static Path schematicsDir;

    public static void init() {
        try {
            schematicsDir = FMLPaths.CONFIGDIR.get().resolve("aeronautics_delivery_quests").resolve("schematics");
            if (!Files.exists(schematicsDir)) {
                Files.createDirectories(schematicsDir);
            }
            generateDefaultSchematicsIfEmpty();
        } catch (Exception e) {
            LOGGER.error("[TNM Quests] Failed to initialize ADQSchematicManager", e);
        }
    }

    public static void loadSchematics(ServerLevel level) {
        SCHEMATICS.clear();
        if (schematicsDir == null || !Files.exists(schematicsDir)) {
            return;
        }

        File[] files = schematicsDir.toFile().listFiles((dir, name) -> name.endsWith(".nbt"));
        if (files == null) return;

        for (File file : files) {
            try (InputStream is = new FileInputStream(file)) {
                CompoundTag nbt = NbtIo.readCompressed(is, NbtAccounter.unlimitedHeap());
                StructureTemplate template = new StructureTemplate();
                template.load(level.holderLookup(Registries.BLOCK), nbt);
                String name = file.getName().replace(".nbt", "");
                SCHEMATICS.put(name, template);
                LOGGER.info("[TNM Quests] Loaded local schematic '{}' from config folder", name);
            } catch (Exception e) {
                LOGGER.error("[TNM Quests] Failed to load schematic: " + file.getName(), e);
            }
        }
    }

    public static StructureTemplate getSchematic(ServerLevel level, String schematicName) {
        // 1. If it's a datapack ResourceLocation (contains :)
        if (schematicName.contains(":")) {
            try {
                ResourceLocation loc = ResourceLocation.parse(schematicName);
                Optional<StructureTemplate> opt = level.getServer().getStructureManager().get(loc);
                if (opt.isPresent()) {
                    return opt.get();
                }
                LOGGER.warn("[TNM Quests] Datapack schematic not found in ServerStructureManager: {}", schematicName);
            } catch (Exception e) {
                LOGGER.error("[TNM Quests] Failed to load datapack schematic: " + schematicName, e);
            }
        }

        // 2. Fall back to local registry
        if (SCHEMATICS.containsKey(schematicName)) {
            return SCHEMATICS.get(schematicName);
        }

        // 3. If registry is empty, reload just in case
        if (SCHEMATICS.isEmpty()) {
            loadSchematics(level);
        }

        return SCHEMATICS.get(schematicName);
    }

    public static String getRandomSchematicName(net.minecraft.util.RandomSource rand) {
        if (SCHEMATICS.isEmpty()) {
            return "light_cargo_crate"; // fallback default
        }
        List<String> keys = new ArrayList<>(SCHEMATICS.keySet());
        return keys.get(rand.nextInt(keys.size()));
    }

    private static boolean needsRegeneration(String filename) {
        File file = schematicsDir.resolve(filename).toFile();
        if (!file.exists()) {
            return true;
        }
        try (InputStream is = new FileInputStream(file)) {
            CompoundTag nbt = NbtIo.readCompressed(is, NbtAccounter.unlimitedHeap());
            ListTag palette = nbt.getList("palette", 10);
            boolean hasIronBlock = false;
            for (int i = 0; i < palette.size(); i++) {
                String name = palette.getCompound(i).getString("Name");
                if (name.equals("simulated:docking_connector") || 
                    name.equals("minecraft:chain") || 
                    name.equals("minecraft:netherite_block")) {
                    return true;
                }
                if (name.equals("minecraft:iron_block")) {
                    hasIronBlock = true;
                }
            }
            if (filename.equals("medium_machinery_pallet.nbt") && hasIronBlock) {
                return true;
            }
            if (filename.equals("heavy_secure_container.nbt") && !hasIronBlock) {
                return true;
            }
            if (filename.equals("heavy_industrial_boiler.nbt") && !hasIronBlock) {
                return true;
            }
        } catch (Exception e) {
            return true;
        }
        return false;
    }

    private static void generateDefaultSchematicsIfEmpty() {
        try {
            LOGGER.info("[TNM Quests] Verifying and generating default cargo schematics programmatically...");

            // 1. Light Cargo Crate (using simulated:rope_connector, has exactly 2)
            if (needsRegeneration("light_cargo_crate.nbt")) {
                writeNbtFile("light_cargo_crate.nbt", createCrateNbt(3, 3, 3,
                    "minecraft:waxed_copper_block",
                    "minecraft:waxed_exposed_cut_copper",
                    "minecraft:acacia_fence_gate",
                    "minecraft:trapped_chest",
                    "simulated:rope_connector"));
            }

            // 2. Medium Machinery Pallet (using simulated:rope_connector, has exactly 8)
            if (needsRegeneration("medium_machinery_pallet.nbt")) {
                writeNbtFile("medium_machinery_pallet.nbt", createPalletNbt(5, 3, 5,
                    "minecraft:polished_andesite",
                    "minecraft:stone",
                    "simulated:rope_connector",
                    "minecraft:spruce_fence_gate",
                    "minecraft:trapped_chest",
                    "minecraft:polished_andesite"));
            }

            // 3. Heavy Secure Container (using simulated:rope_connector, has exactly 2)
            if (needsRegeneration("heavy_secure_container.nbt")) {
                writeNbtFile("heavy_secure_container.nbt", createCrateNbt(5, 4, 5,
                    "minecraft:iron_block",
                    "minecraft:obsidian",
                    "minecraft:dark_oak_fence_gate",
                    "minecraft:trapped_chest",
                    "simulated:rope_connector"));
            }

            // 4. Light Food Crate (using simulated:rope_connector, has exactly 2)
            if (needsRegeneration("light_food_crate.nbt")) {
                writeNbtFile("light_food_crate.nbt", createCrateNbt(3, 3, 3,
                    "minecraft:oak_planks",
                    "minecraft:hay_block",
                    "minecraft:oak_fence_gate",
                    "minecraft:trapped_chest",
                    "simulated:rope_connector"));
            }

            // 5. Medium Ore Crate (using simulated:rope_connector, has exactly 2)
            if (needsRegeneration("medium_ore_crate.nbt")) {
                writeNbtFile("medium_ore_crate.nbt", createCrateNbt(3, 3, 3,
                    "minecraft:raw_copper_block",
                    "minecraft:copper_ore",
                    "minecraft:spruce_fence_gate",
                    "minecraft:trapped_chest",
                    "simulated:rope_connector"));
            }

            // 6. Heavy Industrial Boiler (using simulated:rope_connector, has exactly 2)
            if (needsRegeneration("heavy_industrial_boiler.nbt")) {
                writeNbtFile("heavy_industrial_boiler.nbt", createCrateNbt(5, 4, 5,
                    "minecraft:iron_block",
                    "minecraft:blast_furnace",
                    "minecraft:dark_oak_fence_gate",
                    "minecraft:trapped_chest",
                    "simulated:rope_connector"));
            }

        } catch (Exception e) {
            LOGGER.error("[TNM Quests] Failed to generate default schematics", e);
        }
    }

    private static void writeNbtFile(String filename, CompoundTag nbt) {
        File file = schematicsDir.resolve(filename).toFile();
        try (OutputStream os = new FileOutputStream(file)) {
            NbtIo.writeCompressed(nbt, os);
            LOGGER.info("[TNM Quests] Wrote default schematic file: {}", filename);
        } catch (Exception e) {
            LOGGER.error("[TNM Quests] Failed to write default schematic file: " + filename, e);
        }
    }

    // Helper to generate a procedural 3D crate structure NBT
    private static CompoundTag createCrateNbt(int w, int h, int l, String baseMat, String wallMat, String handleMat, String chestMat, String attachmentMat) {
        CompoundTag root = new CompoundTag();

        // size: [w, h, l]
        ListTag sizeList = new ListTag();
        sizeList.add(IntTag.valueOf(w));
        sizeList.add(IntTag.valueOf(h));
        sizeList.add(IntTag.valueOf(l));
        root.put("size", sizeList);

        // palette
        ListTag paletteList = new ListTag();
        Map<String, Integer> matIndices = new HashMap<>();

        String[] materials = {baseMat, wallMat, handleMat, chestMat, attachmentMat, "minecraft:air"};
        int idx = 0;
        for (String mat : materials) {
            if (!matIndices.containsKey(mat)) {
                matIndices.put(mat, idx++);
                CompoundTag state = new CompoundTag();
                state.putString("Name", mat);
                paletteList.add(state);
            }
        }
        root.put("palette", paletteList);

        // blocks
        ListTag blocksList = new ListTag();
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                for (int z = 0; z < l; z++) {
                    String blockType = "minecraft:air";

                    // Simple rules for a crate structure
                    if (y == 0) {
                        // Base plate: solid base material, but center is open (center hole)
                        if (x == w / 2 && z == l / 2) {
                            blockType = attachmentMat; // center attachment block
                        } else {
                            blockType = baseMat;
                        }
                    } else if (y == h - 1) {
                        // Roof: wall material cover, chest on corner, attachment in center
                        if (x == w / 2 && z == l / 2) {
                            blockType = attachmentMat;
                        } else if (x == w - 1 && z == l - 1) {
                            blockType = chestMat;
                        } else {
                            blockType = wallMat;
                        }
                    } else {
                        // Middle layers: hollow walls
                        boolean borderX = (x == 0 || x == w - 1);
                        boolean borderZ = (z == 0 || z == l - 1);
                        if (borderX && borderZ) {
                            blockType = wallMat; // Corners are solid walls
                        } else if (borderX || borderZ) {
                            blockType = handleMat; // Sides have handles
                        }
                    }

                    if (!blockType.equals("minecraft:air")) {
                        CompoundTag block = new CompoundTag();
                        ListTag posList = new ListTag();
                        posList.add(IntTag.valueOf(x));
                        posList.add(IntTag.valueOf(y));
                        posList.add(IntTag.valueOf(z));
                        block.put("pos", posList);
                        block.putInt("state", matIndices.get(blockType));
                        blocksList.add(block);
                    }
                }
            }
        }
        root.put("blocks", blocksList);
        root.put("entities", new ListTag());

        return root;
    }

    private static CompoundTag createCrateNbt(int w, int h, int l, String baseMat, String wallMat, String handleMat, String chestMat) {
        return createCrateNbt(w, h, l, baseMat, wallMat, handleMat, chestMat, "minecraft:chain");
    }

    private static CompoundTag createPalletNbt(int w, int h, int l, String baseMat, String wallMat, String attachMat, String handleMat, String chestMat, String sideMat) {
        CompoundTag root = new CompoundTag();

        ListTag sizeList = new ListTag();
        sizeList.add(IntTag.valueOf(w));
        sizeList.add(IntTag.valueOf(h));
        sizeList.add(IntTag.valueOf(l));
        root.put("size", sizeList);

        ListTag paletteList = new ListTag();
        Map<String, Integer> matIndices = new HashMap<>();

        String[] materials = {baseMat, wallMat, attachMat, handleMat, chestMat, sideMat, "minecraft:air"};
        int idx = 0;
        for (String mat : materials) {
            if (!matIndices.containsKey(mat)) {
                matIndices.put(mat, idx++);
                CompoundTag state = new CompoundTag();
                state.putString("Name", mat);
                paletteList.add(state);
            }
        }
        root.put("palette", paletteList);

        ListTag blocksList = new ListTag();
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                for (int z = 0; z < l; z++) {
                    String blockType = "minecraft:air";

                    if (y == 0) {
                        // Pallet base frame
                        if (x == 0 || x == w - 1 || z == 0 || z == l - 1) {
                            blockType = baseMat;
                        } else if (x == w / 2 || z == l / 2) {
                            blockType = sideMat;
                        }
                    } else if (y == 1) {
                        // Fences at corners, chests in center
                        boolean cornerX = (x == 0 || x == w - 1);
                        boolean cornerZ = (z == 0 || z == l - 1);
                        if (cornerX && cornerZ) {
                            blockType = attachMat; // rope attachment fences
                        } else if (x == w / 2 && z == l / 2) {
                            blockType = chestMat; // Trapped chest
                        } else if (x == 0 || x == w - 1 || z == 0 || z == l - 1) {
                            blockType = handleMat; // handles on sides
                        }
                    } else if (y == 2) {
                        // Top level frame
                        boolean cornerX = (x == 0 || x == w - 1);
                        boolean cornerZ = (z == 0 || z == l - 1);
                        if (cornerX && cornerZ) {
                            blockType = attachMat;
                        } else if (x == 0 || x == w - 1 || z == 0 || z == l - 1) {
                            blockType = wallMat;
                        }
                    }

                    if (!blockType.equals("minecraft:air")) {
                        CompoundTag block = new CompoundTag();
                        ListTag posList = new ListTag();
                        posList.add(IntTag.valueOf(x));
                        posList.add(IntTag.valueOf(y));
                        posList.add(IntTag.valueOf(z));
                        block.put("pos", posList);
                        block.putInt("state", matIndices.get(blockType));
                        blocksList.add(block);
                    }
                }
            }
        }
        root.put("blocks", blocksList);
        root.put("entities", new ListTag());

        return root;
    }

    public static Set<BlockPos> getNonAirRelativePositions(ServerLevel level, String schematicName) {
        StructureTemplate template = getSchematic(level, schematicName);
        if (template == null) return Collections.emptySet();

        Set<BlockPos> nonAirPos = new HashSet<>();
        CompoundTag nbt = template.save(new CompoundTag());
        if (nbt.contains("blocks", 9)) {
            ListTag blocksList = nbt.getList("blocks", 10);
            for (int i = 0; i < blocksList.size(); i++) {
                CompoundTag blockTag = blocksList.getCompound(i);
                if (blockTag.contains("pos", 9)) {
                    ListTag posList = blockTag.getList("pos", 3);
                    if (posList.size() == 3) {
                        int x = posList.getInt(0);
                        int y = posList.getInt(1);
                        int z = posList.getInt(2);
                        nonAirPos.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        return nonAirPos;
    }
}
