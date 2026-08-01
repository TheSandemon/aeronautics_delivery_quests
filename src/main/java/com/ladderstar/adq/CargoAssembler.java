package com.ladderstar.adq;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class CargoAssembler {
    private static final Logger LOGGER = LogManager.getLogger();

    public static boolean spawnAndAssembleCargo(ServerPlayer player, QuestModel quest) {
        ServerLevel level = player.serverLevel();
        BlockPos originalStartPos = quest.getStartingPos();

        // 1. Force load the chunk
        level.getChunkAt(originalStartPos);

        // 2. Fetch schematic structure
        if (quest.getSchematicName() == null || quest.getSchematicName().isEmpty()) {
            quest.setSchematicName(ADQSchematicManager.getRandomSchematicName(level.getRandom()));
        }

        StructureTemplate template = ADQSchematicManager.getSchematic(level, quest.getSchematicName());
        if (template == null) {
            LOGGER.error("[TNM Quests] Failed to load cargo schematic: {}", quest.getSchematicName());
            return false;
        }

        Vec3i size = template.getSize();
        int W = size.getX();
        int H = size.getY();
        int L = size.getZ();

        LOGGER.info("[TNM Quests] Spawning physical cargo '{}' (Schematic: {}, Size: {}x{}x{})", 
                quest.getName(), quest.getSchematicName(), W, H, L);

        // 3. Proximity Spawning Scan (non-destructive search for a flat clear spot)
        BlockPos spawnPos = findClearSpawningSpot(level, originalStartPos, player.blockPosition(), W, H, L);
        if (spawnPos != null) {
            if (!spawnPos.equals(originalStartPos)) {
                LOGGER.info("[TNM Quests] Proximity scan relocated cargo from {} to clear spot at {}", 
                        originalStartPos.toShortString(), spawnPos.toShortString());
                quest.setStartingPos(spawnPos);
                QuestGenerator.saveQuests();
            }
        } else {
            LOGGER.error("[TNM Quests] No dry cargo footprint found around pickup location {}.", originalStartPos.toShortString());
            return false;
        }

        BlockPos placeOrigin = spawnPos.offset(-W / 2, 0, -L / 2);

        // 5. Place schematic structure in the level
        StructurePlaceSettings settings = new StructurePlaceSettings();
        try {
            template.placeInWorld(level, placeOrigin, placeOrigin, settings, level.getRandom(), 2);
        } catch (Exception e) {
            LOGGER.error("[TNM Quests] Failed to place cargo structure template in level", e);
            return false;
        }

        // 6. Track all placed block positions that are part of the cargo
        Set<BlockPos> blockSet = new HashSet<>();
        for (int x = 0; x < W; x++) {
            for (int y = 0; y < H; y++) {
                for (int z = 0; z < L; z++) {
                    BlockPos p = placeOrigin.offset(x, y, z);
                    if (!level.getBlockState(p).isAir()) {
                        blockSet.add(p);
                    }
                }
            }
        }

        quest.setOriginalBlockCount(blockSet.size());
        QuestGenerator.saveQuests();

        // 7. Compile into Sable Physics SubLevel (if loaded)
        boolean assembled = false;
        if (ModList.get().isLoaded("sable")) {
            assembled = assemblePhysicsContraption(level, spawnPos, placeOrigin, W, H, L, blockSet, quest);
        } else {
            LOGGER.warn("[TNM Quests] Sable Physics Engine is not loaded! Cannot compile physical contraption.");
        }

        return assembled;
    }

    public static void removeCargo(ServerLevel level, QuestModel quest) {
        BlockPos startPos = quest.getStartingPos();
        LOGGER.info("[TNM Quests] Cleaning up cargo blocks/entities for quest: {}", quest.getName());

        DeliveryTracker.releaseForcedChunk(level, quest);

        // 1. Remove physical Sable sublevel (main cargo body plus any split-off fragments)
        if (ModList.get().isLoaded("sable")) {
            if (quest.getCargoEntityId() != null) {
                removePhysicsEntity(level, quest.getCargoEntityId());
            }
            for (UUID fragmentId : new java.util.ArrayList<>(quest.getCargoFragmentIds())) {
                LOGGER.info("[TNM Quests] Removing split-off cargo fragment sublevel {} for quest '{}'", fragmentId, quest.getName());
                removePhysicsEntity(level, fragmentId);
            }
            quest.clearCargoFragments();
        }

        // 2. Clear structural blocks in the Overworld based on schematic size
        ServerLevel overworld = level.getServer().overworld();
        StructureTemplate template = ADQSchematicManager.getSchematic(overworld, quest.getSchematicName());
        int W = 3, H = 3, L = 3; // Fallback
        if (template != null) {
            Vec3i size = template.getSize();
            W = size.getX();
            H = size.getY();
            L = size.getZ();
        }

        BlockPos placeOrigin = startPos.offset(-W / 2, 0, -L / 2);
        for (int x = 0; x < W; x++) {
            for (int y = 0; y < H; y++) {
                for (int z = 0; z < L; z++) {
                    overworld.setBlockAndUpdate(placeOrigin.offset(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private static int getHighestSolidY(ServerLevel level, int x, int z, int centerY) {
        for (int y = centerY + 20; y >= centerY - 20; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (!state.isAir()
                    && state.getFluidState().isEmpty()
                    && state.isCollisionShapeFullBlock(level, pos)
                    && level.getFluidState(pos.above()).isEmpty()) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static BlockPos findClearSpawningSpot(ServerLevel level, BlockPos center, BlockPos playerPos, int W, int H, int L) {
        BlockPos bestPos = null;
        int bestFlatness = Integer.MAX_VALUE;

        // Proximity scan search: horizontally -16 to 16 around target start position
        for (int dx = -16; dx <= 16; dx++) {
            for (int dz = -16; dz <= 16; dz++) {
                int cx = center.getX() + dx;
                int cz = center.getZ() + dz;

                // 1. Calculate highest solid block Y for each block in footprint
                int maxY = Integer.MIN_VALUE;
                int minY = Integer.MAX_VALUE;
                boolean validGround = true;

                footprint:
                for (int x = cx - W / 2; x < cx - W / 2 + W; x++) {
                    for (int z = cz - L / 2; z < cz - L / 2 + L; z++) {
                        int hy = getHighestSolidY(level, x, z, center.getY());
                        if (hy == Integer.MIN_VALUE) {
                            validGround = false;
                            break footprint;
                        }
                        if (hy > maxY) maxY = hy;
                        if (hy < minY) minY = hy;

                        // Check if ground is a leaf block to try and avoid spawning on treetops
                        BlockState groundState = level.getBlockState(new BlockPos(x, hy, z));
                        if (groundState.is(net.minecraft.tags.BlockTags.LEAVES)) {
                            validGround = false;
                        }
                    }
                }

                if (!validGround) {
                    continue;
                }

                int flatness = maxY - minY;
                if (flatness > 2) {
                    continue; // Must be relatively flat terrain footprint (max 2 blocks height diff)
                }

                // 2. Check if the air column from maxY + 1 up to maxY + 3 + H is completely empty air
                boolean clearAir = true;
                for (int x = cx - W / 2; x < cx - W / 2 + W; x++) {
                    for (int y = maxY + 1; y < maxY + 3 + H; y++) {
                        for (int z = cz - L / 2; z < cz - L / 2 + L; z++) {
                            if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) {
                                clearAir = false;
                                break;
                            }
                        }
                        if (!clearAir) break;
                    }
                    if (!clearAir) break;
                }

                if (!clearAir) {
                    continue; // Air column is blocked
                }

                // Prioritize flatter ground that isn't tree leaves
                int score = flatness + (validGround ? 0 : 10);
                if (score < bestFlatness) {
                    bestFlatness = score;
                    bestPos = new BlockPos(cx, maxY + 3, cz); // Spawn 3 blocks in the air!
                }
            }
        }

        return bestPos;
    }

    private static boolean assemblePhysicsContraption(ServerLevel level, BlockPos spawnPos, BlockPos placeOrigin, int W, int H, int L, Set<BlockPos> blockSet, QuestModel quest) {
        try {
            LOGGER.info("[TNM Quests] Compiling Sable physics sublevel at {} relative to {}", spawnPos.toShortString(), placeOrigin.toShortString());

            dev.ryanhcode.sable.companion.math.BoundingBox3i bounds = new dev.ryanhcode.sable.companion.math.BoundingBox3i(
                placeOrigin.getX(), placeOrigin.getY(), placeOrigin.getZ(),
                placeOrigin.getX() + W - 1, placeOrigin.getY() + H - 1, placeOrigin.getZ() + L - 1
            );

            // Assemble the blocks using Sable API
            dev.ryanhcode.sable.sublevel.ServerSubLevel subLevel = dev.ryanhcode.sable.api.SubLevelAssemblyHelper.assembleBlocks(
                level,
                spawnPos,
                blockSet,
                bounds
            );

            if (subLevel != null) {
                quest.setCargoEntityId(subLevel.getUniqueId());
                LOGGER.info("[TNM Quests] Successfully compiled Sable physics sublevel: {}", subLevel.getUniqueId());
                return true;
            } else {
                LOGGER.error("[TNM Quests] Sable assembleBlocks returned null!");
            }
        } catch (Throwable t) {
            LOGGER.error("[TNM Quests] Failed to assemble Sable physics contraption", t);
        }
        return false;
    }

    private static void removePhysicsEntity(ServerLevel level, UUID subLevelId) {
        try {
            LOGGER.info("[TNM Quests] De-allocating Sable sublevel with UUID {}", subLevelId);
            for (ServerLevel sl : level.getServer().getAllLevels()) {
                dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container =
                    dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer.getContainer(sl);
                if (container != null) {
                    dev.ryanhcode.sable.sublevel.SubLevel sub = container.getSubLevel(subLevelId);
                    if (sub != null) {
                        container.removeSubLevel(sub, dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason.REMOVED);
                        LOGGER.info("[TNM Quests] Successfully removed Sable physics sublevel from dimension registry: {}", sl.dimension().location());
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.error("[TNM Quests] Failed to remove Sable physics sublevel", t);
        }
    }
}
