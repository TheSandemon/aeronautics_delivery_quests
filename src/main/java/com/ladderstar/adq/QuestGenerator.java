package com.ladderstar.adq;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.neoforged.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.function.IntBinaryOperator;

/**
 * ==================================================================================
 *                       AERONAUTICS DELIVERY QUESTS - GENERATOR
 * ==================================================================================
 * This class orchestrates the lifecycle, loading, and generation of delivery quests.
 * 
 * 1. GENERATE vs FILL ACTIONS:
 *    - Generate Quest (Command: `/adq generate` or board GUI button):
 *      Queues a single new quest generation task in the background. It locates one suitable
 *      pickup and delivery endpoint and adds the resulting quest to the board.
 *    - Fill Quests (Board GUI button):
 *      Calculates how many slots are empty on the board (based on `maxActiveQuestsPerBoard` config)
 *      and triggers multiple asynchronous generation tasks sequentially to completely fill the board.
 *
 * 2. QUEST GENERATION MODES (ADQConfig.QUEST_GEN_MODE):
 *    - CUSTOM Mode:
 *      Directly spawns quests exactly as authored in 'config/aeronautics_delivery_quests/custom_quests.json'.
 *      Each quest is copied into the game with its exact defined name, description, weight, rewards, and schematic.
 *    - PROCEDURAL Mode:
 *      Mixes and matches components. It randomly draws names, descriptions, weights, schematics,
 *      and rewards from different templates loaded from 'custom_quests.json'.
 *    - Automatic Fallback:
 *      If 'custom_quests.json' is empty, missing, or corrupt, both modes automatically fall back
 *      to using built-in procedural defaults (featuring 6+ default schematics and 5+ default quest templates).
 *
 * 3. QUEST LOCATION MODES (ADQConfig.QUEST_LOCATION_MODE):
 *    - VILLAGE:
 *      Searches for structures matching the '#minecraft:village' tag in the Overworld.
 *    - ANY_STRUCTURE:
 *      Searches for any registered Overworld structure (surface/top-level only).
 *    - RANDOM:
 *      Searches dry surface positions inside the computed bounds. In sky-island mode it scans
 *      multiple positions per candidate chunk using a bounded, one-chunk-at-a-time search.
 *
 * 4. CONCURRENCY & THREAD SAFETY:
 *    - An AtomicBoolean lock ('isGenerating') guards the asynchronous execution.
 *    - While a quest is generating in the background, further generation commands/calls
 *      are discarded to prevent concurrent structure queries from lagging the server.
 *    - When generation starts/finishes, a sync packet is broadcast to all active players
 *      to update their client GUI button states (e.g. greying out the Generate button).
 * ==================================================================================
 */
public class QuestGenerator {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<QuestModel> availableQuests = new ArrayList<>();
    private static final Map<UUID, Long> playerCooldowns = new HashMap<>();
    private static final List<CustomQuestTemplate> customTemplates = new ArrayList<>();
    private static Path questFilePath;
    private static Path cooldownFilePath;
    private static final java.util.concurrent.atomic.AtomicBoolean isGenerating = new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.util.concurrent.atomic.AtomicLong randomSearchEpoch = new java.util.concurrent.atomic.AtomicLong();
    private static final TickWorkQueue<RandomSearchStep> randomSearchSteps = new TickWorkQueue<>();
    private static final TicketType<ChunkPos> RANDOM_SEARCH_TICKET = TicketType.create(
            "adq_random_search",
            Comparator.comparingLong(ChunkPos::toLong),
            600);
    private static final TicketType<ChunkPos> STRUCTURE_SEARCH_TICKET = TicketType.create(
            "adq_structure_search",
            Comparator.comparingLong(ChunkPos::toLong),
            600);
    private static final int NORMAL_RANDOM_ATTEMPTS = 32;
    private static final int[] NORMAL_CHUNK_SAMPLES = {8, 8};
    private static final int[] SKY_CHUNK_SAMPLES = {
        8, 8,
        2, 2,
        2, 8,
        2, 13,
        8, 2,
        8, 13,
        13, 2,
        13, 8,
        13, 13
    };

    private record RandomSearchStep(
            MinecraftServer server,
            long epoch,
            Runnable action) {
    }

    public static boolean isGenerating() {
        return isGenerating.get();
    }

    static void runRandomSearchStep(MinecraftServer server) {
        randomSearchSteps.runOne(step -> {
            if (step.server() != server || step.epoch() != randomSearchEpoch.get()) {
                return;
            }
            try {
                step.action().run();
            } catch (Exception error) {
                LOGGER.error("[ADQ] Unhandled error in tick-paced RANDOM search step", error);
                randomSearchEpoch.incrementAndGet();
                randomSearchSteps.clear();
                isGenerating.set(false);
                try {
                    QuestBoardMenuHandler.resyncToAllPlayers(server);
                } catch (Exception syncError) {
                    LOGGER.error("[ADQ] Failed to resync quest boards after RANDOM search failure", syncError);
                }
            }
        });
    }

    static void resetRandomSearchQueue() {
        randomSearchEpoch.incrementAndGet();
        randomSearchSteps.clear();
        isGenerating.set(false);
    }

    private static void enqueueRandomSearchStep(
            MinecraftServer server,
            long epoch,
            Runnable action) {
        if (epoch == randomSearchEpoch.get()) {
            randomSearchSteps.enqueue(new RandomSearchStep(server, epoch, action));
        }
    }

    public static class CustomQuestTemplate {
        public String name;
        public String description;
        public String schematicName;
        public String weightClass;
        public double actualWeight;
        public List<String> rewards;
        public String pickupPos = "";
        public String dropoffPos = "";

        public CustomQuestTemplate() {}

        public CustomQuestTemplate(String name, String description, String schematicName, String weightClass, double actualWeight, List<String> rewards) {
            this(name, description, schematicName, weightClass, actualWeight, rewards, "", "");
        }

        public CustomQuestTemplate(String name, String description, String schematicName, String weightClass, double actualWeight, List<String> rewards, String pickupPos, String dropoffPos) {
            this.name = name;
            this.description = description;
            this.schematicName = schematicName;
            this.weightClass = weightClass;
            this.actualWeight = actualWeight;
            this.rewards = rewards;
            this.pickupPos = pickupPos != null ? pickupPos : "";
            this.dropoffPos = dropoffPos != null ? dropoffPos : "";
        }
    }

    public static final List<CustomQuestTemplate> DEFAULT_TEMPLATES = List.of(
        new CustomQuestTemplate(
            "Secret Industrial Core",
            "A highly critical delivery containing sensitive mechanical components. Keep the shipment secure and intact!",
            "medium_machinery_pallet",
            "Medium",
            3500.0,
            List.of("minecraft:emerald:35", "create:mechanical_arm:1")
        ),
        new CustomQuestTemplate(
            "High-Value Vault Transport",
            "Transport a reinforced secure container containing corporate assets. Heavy and highly guarded!",
            "heavy_secure_container",
            "Heavy",
            9000.0,
            List.of("minecraft:emerald:50", "minecraft:gold_ingot:8")
        ),
        new CustomQuestTemplate(
            "Emergency Food Supplies",
            "Deliver urgent food rations to a starving village. Fast transport is requested.",
            "light_food_crate",
            "Light",
            1200.0,
            List.of("minecraft:emerald:15", "minecraft:bread:16")
        ),
        new CustomQuestTemplate(
            "Standard Copper Shipment",
            "A shipment of raw copper ores for industrial smelting.",
            "medium_ore_crate",
            "Medium",
            4000.0,
            List.of("minecraft:emerald:25", "create:copper_casing:4")
        ),
        new CustomQuestTemplate(
            "Industrial Boiler Delivery",
            "Transport a massive industrial boiler unit to the high-altitude power station.",
            "heavy_industrial_boiler",
            "Heavy",
            11000.0,
            List.of("minecraft:emerald:45", "create:fluid_tank:2")
        ),
        new CustomQuestTemplate(
            "Standard Cargo Haul",
            "A simple transport contract of standard copper components.",
            "light_cargo_crate",
            "Light",
            1500.0,
            List.of("minecraft:emerald:20", "create:cogwheel:4")
        )
    );

    /**
     * Loads custom quest templates from config/aeronautics_delivery_quests/custom_quests.json.
     * If the file is missing or outdated (e.g., legacy high emerald counts), it regenerates
     * the file with updated balanced example templates.
     */
    public static synchronized void loadCustomTemplates() {
        customTemplates.clear();
        try {
            Path configDir = FMLPaths.CONFIGDIR.get().resolve("aeronautics_delivery_quests");
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }
            
            Path customQuestsPath = configDir.resolve("custom_quests.json");
            boolean needsGen = !Files.exists(customQuestsPath);
            if (Files.exists(customQuestsPath)) {
                try {
                    String content = new String(Files.readAllBytes(customQuestsPath));
                    if (content.contains("minecraft:emerald:300") || content.contains("minecraft:emerald:640")) {
                        needsGen = true;
                        LOGGER.info("[ADQ] Outdated custom_quests.json detected. Overwriting with updated 1.0.2 economy values.");
                    }
                } catch (Exception e) {
                    needsGen = true;
                }
            }

            if (needsGen) {
                try (Writer writer = Files.newBufferedWriter(customQuestsPath)) {
                    GSON.toJson(DEFAULT_TEMPLATES, writer);
                }
                LOGGER.info("[ADQ] Generated example custom_quests.json template.");
            }
            
            try (Reader reader = Files.newBufferedReader(customQuestsPath)) {
                List<CustomQuestTemplate> loaded = GSON.fromJson(reader, new TypeToken<List<CustomQuestTemplate>>(){}.getType());
                if (loaded != null) {
                    customTemplates.addAll(loaded);
                }
                LOGGER.info("[ADQ] Loaded {} custom quest templates from custom_quests.json", customTemplates.size());
            }
        } catch (Exception e) {
            LOGGER.error("[ADQ] Failed to load custom quest templates", e);
        }
    }

    public static List<QuestModel> getAvailableQuests() {
        return availableQuests;
    }

    public static void init(ServerLevel level) {
        try {
            Path rootPath = level.getServer().getWorldPath(LevelResource.ROOT);
            questFilePath = rootPath.resolve("adq_quests.json");
            cooldownFilePath = rootPath.resolve("adq_cooldowns.json");
            loadQuests();
            loadCooldowns();
        } catch (Exception e) {
            LOGGER.error("[ADQ] Failed to initialize quest file path", e);
        }
    }

    public static synchronized void saveQuests() {
        if (questFilePath == null) return;
        try (Writer writer = Files.newBufferedWriter(questFilePath)) {
            GSON.toJson(availableQuests, writer);
            LOGGER.info("[ADQ] Successfully saved {} quests to {}", availableQuests.size(), questFilePath.getFileName());
        } catch (IOException e) {
            LOGGER.error("[ADQ] Failed to save quests to file", e);
        }
    }

    public static synchronized void loadQuests() {
        loadCustomTemplates();

        if (questFilePath == null || !Files.exists(questFilePath)) {
            availableQuests.clear();
            return;
        }
        try (Reader reader = Files.newBufferedReader(questFilePath)) {
            List<QuestModel> loaded = GSON.fromJson(reader, new TypeToken<List<QuestModel>>(){}.getType());
            availableQuests.clear();
            if (loaded != null) {
                availableQuests.addAll(loaded);
            }
            LOGGER.info("[ADQ] Loaded {} quests from {}", availableQuests.size(), questFilePath.getFileName());
        } catch (IOException e) {
            LOGGER.error("[ADQ] Failed to load quests from file", e);
        }
    }

    public static synchronized void saveCooldowns() {
        if (cooldownFilePath == null) return;
        try (Writer writer = Files.newBufferedWriter(cooldownFilePath)) {
            Map<String, Long> stringMap = new HashMap<>();
            for (Map.Entry<UUID, Long> entry : playerCooldowns.entrySet()) {
                stringMap.put(entry.getKey().toString(), entry.getValue());
            }
            GSON.toJson(stringMap, writer);
            LOGGER.info("[ADQ] Successfully saved {} cooldowns to {}", playerCooldowns.size(), cooldownFilePath.getFileName());
        } catch (IOException e) {
            LOGGER.error("[ADQ] Failed to save cooldowns to file", e);
        }
    }

    public static synchronized void loadCooldowns() {
        playerCooldowns.clear();
        if (cooldownFilePath == null || !Files.exists(cooldownFilePath)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(cooldownFilePath)) {
            Map<String, Long> stringMap = GSON.fromJson(reader, new TypeToken<Map<String, Long>>(){}.getType());
            if (stringMap != null) {
                for (Map.Entry<String, Long> entry : stringMap.entrySet()) {
                    try {
                        playerCooldowns.put(UUID.fromString(entry.getKey()), entry.getValue());
                    } catch (IllegalArgumentException e) {
                        LOGGER.error("[ADQ] Invalid UUID in cooldown file: " + entry.getKey(), e);
                    }
                }
            }
            LOGGER.info("[ADQ] Loaded {} cooldowns from {}", playerCooldowns.size(), cooldownFilePath.getFileName());
        } catch (IOException e) {
            LOGGER.error("[ADQ] Failed to load cooldowns from file", e);
        }
    }

    public static synchronized long getCooldown(UUID playerId) {
        return playerCooldowns.getOrDefault(playerId, 0L);
    }

    public static synchronized void setCooldown(UUID playerId, long timestamp) {
        playerCooldowns.put(playerId, timestamp);
        saveCooldowns();
    }

    /**
     * Triggers the asynchronous generation of a new quest.
     * 
     * Steps performed:
     * 1. Check if the active quest count has already reached the configured maximum capacity.
     * 2. Perform a thread-safe Compare-and-Set check on the 'isGenerating' lock to guarantee
     *    that only one quest generation thread runs at a time.
     * 3. Sync player UI screens immediately to grey out the generation buttons.
     * 4. Spawn a CompletableFuture task running on the background thread pool to locate suitable
     *    pickup and delivery coordinate pairs using structure search registries or random surface coordinates.
     * 5. Fallback/Finalize on the main Server thread: Load chunk data synchronously, calculate the 
     *    surface height, safe-guard coordinates against the world border, assign the rewards based on
     *    the quest weight class and generation mode, and sync the board state.
     */
    public static void generateNewQuestAsync(ServerLevel level) {
        generateNewQuestAsync(level, null);
    }

    public static void generateNewQuestAsync(ServerLevel level, UUID triggerPlayerUuid) {
        if (availableQuests.size() >= ADQConfig.MAX_ACTIVE_QUESTS.get()) {
            return;
        }
        // Acquire concurrency lock to prevent multiple simultaneous background generation threads
        if (!isGenerating.compareAndSet(false, true)) {
            LOGGER.info("[ADQ] Quest generation is already running. Skipping duplicate invocation.");
            return;
        }
 
        // Resync to all players to update the generator button state immediately (greys out generate buttons)
        level.getServer().execute(() -> QuestBoardMenuHandler.resyncToAllPlayers(level.getServer()));
 
        LOGGER.info("[ADQ] Triggering periodic quest generation asynchronously...");

        boolean useCustom = ADQConfig.QUEST_GEN_MODE.get() == ADQConfig.QuestGenerationMode.CUSTOM;
        List<CustomQuestTemplate> templatesSource = List.copyOf(
                customTemplates.isEmpty() ? DEFAULT_TEMPLATES : customTemplates);
        CustomQuestTemplate selectedTemplate = null;
        ParsedCoords customStart = null;
        ParsedCoords customEnd = null;

        if (useCustom && !templatesSource.isEmpty()) {
            net.minecraft.util.RandomSource rand = net.minecraft.util.RandomSource.create();
            selectedTemplate = templatesSource.get(rand.nextInt(templatesSource.size()));
            if (selectedTemplate != null) {
                customStart = parseCoordinates(selectedTemplate.pickupPos);
                customEnd = parseCoordinates(selectedTemplate.dropoffPos);
            }
        }

        if (selectedTemplate != null && customStart != null && customEnd != null) {
            final CustomQuestTemplate finalTemplate = selectedTemplate;
            final ParsedCoords finalCustomStart = customStart;
            final ParsedCoords finalCustomEnd = customEnd;

            level.getServer().execute(() -> {
                try {
                    BlockPos startingPos = resolvePosition(level, finalCustomStart);
                    BlockPos endingPos = resolvePosition(level, finalCustomEnd);

                    if (startingPos == null || endingPos == null
                            || !isWellWithinBorder(level, startingPos)
                            || !isWellWithinBorder(level, endingPos)) {
                        announceGenerationFailure(level);
                        return;
                    }

                    UUID questId = UUID.randomUUID();
                    String name = finalTemplate.name;
                    String description = finalTemplate.description;
                    String weightClass = finalTemplate.weightClass;
                    double actualWeight = finalTemplate.actualWeight;
                    List<String> rewards = new ArrayList<>(finalTemplate.rewards);
                    String schematicName = finalTemplate.schematicName;

                    QuestModel quest = new QuestModel(questId, name, description, startingPos, endingPos, weightClass, actualWeight, rewards);
                    quest.setCreationTime(System.currentTimeMillis());
                    quest.setSchematicName(schematicName);

                    synchronized (availableQuests) {
                        availableQuests.add(quest);
                    }
                    saveQuests();

                    LOGGER.info("[ADQ] Generated custom coordinates quest: '{}' [{} class, {}kpg, Schematic: {}] from {} to {}",
                            name, weightClass, (int)actualWeight, quest.getSchematicName(), startingPos.toShortString(), endingPos.toShortString());
                } catch (Exception e) {
                    LOGGER.error("[ADQ] Error finalising custom coords quest on server thread", e);
                } finally {
                    isGenerating.set(false);
                    if (triggerPlayerUuid != null) {
                        ServerPlayer triggerPlayer = level.getServer().getPlayerList().getPlayer(triggerPlayerUuid);
                        if (triggerPlayer != null) {
                            ADQEventHandler.clearActionCooldown(triggerPlayer, "generate");
                            ADQEventHandler.clearActionCooldown(triggerPlayer, "fill");
                        }
                    }
                    QuestBoardMenuHandler.resyncToAllPlayers(level.getServer());
                }
            });
            return;
        }

        final CustomQuestTemplate finalSelectedTemplate = selectedTemplate;
        final ADQConfig.QuestLocationMode locationMode = ADQConfig.QUEST_LOCATION_MODE.get();

        if (locationMode == ADQConfig.QuestLocationMode.RANDOM) {
            level.getServer().execute(() -> startRandomQuestSearch(
                    level,
                    triggerPlayerUuid,
                    useCustom,
                    finalSelectedTemplate,
                    templatesSource));
            return;
        }
 
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            boolean scheduledFinalization = false;
            try {
                Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
                Optional<HolderSet.Named<Structure>> villageHolderSet = registry.getTag(StructureTags.VILLAGE);

                HolderSet<Structure> targetHolderSet = null;

                if (locationMode == ADQConfig.QuestLocationMode.VILLAGE) {
                    if (villageHolderSet.isPresent()) {
                        targetHolderSet = villageHolderSet.get();
                    } else {
                        LOGGER.warn("[ADQ] Village structure tag not found in registry! Aborting quest generation.");
                        return;
                    }
                } else if (locationMode == ADQConfig.QuestLocationMode.ANY_STRUCTURE) {
                    List<net.minecraft.core.Holder<Structure>> allHolders = new ArrayList<>();
                    for (var ref : registry.holders().toList()) {
                        allHolders.add(ref);
                    }
                    if (allHolders.isEmpty()) {
                        LOGGER.warn("[ADQ] No structures found in registry! Aborting quest generation.");
                        return;
                    }
                    targetHolderSet = HolderSet.direct(allHolders);
                }

                net.minecraft.util.RandomSource rand = net.minecraft.util.RandomSource.create();
                net.minecraft.world.level.border.WorldBorder border = level.getWorldBorder();

                // 1. Calculate Search Center and Radius based on active players in the world
                BlockPos searchCenter = null;
                double R = ADQConfig.MIN_PLAYER_RADIUS.get();

                if (triggerPlayerUuid != null) {
                    ServerPlayer triggerPlayer = level.getServer().getPlayerList().getPlayer(triggerPlayerUuid);
                    if (triggerPlayer != null) {
                        searchCenter = triggerPlayer.blockPosition();
                    }
                }

                if (searchCenter == null) {
                    List<ServerPlayer> players = level.players();
                    if (!players.isEmpty()) {
                        // Pick a random player as center base
                        ServerPlayer randomPlayer = players.get(rand.nextInt(players.size()));
                        searchCenter = randomPlayer.blockPosition();
                    } else {
                        // Fallback to server spawn if no players are online
                        searchCenter = level.getSharedSpawnPos();
                    }
                }

                LOGGER.info("[ADQ] Searching quest origin around center {} with radius {} blocks.",
                        searchCenter.toShortString(), (int)R);

                BlockPos startPosRaw = null;
                BlockPos endingPosRaw = null;

                // 2. Find Starting spot within player proximity and world border
                for (int attempt = 0; attempt < 20; attempt++) {
                    double angle = rand.nextDouble() * 2 * Math.PI;
                    double dist = rand.nextDouble() * R;
                    int randomX = searchCenter.getX() + (int)(dist * Math.cos(angle));
                    int randomZ = searchCenter.getZ() + (int)(dist * Math.sin(angle));

                    BlockPos targetOrigin = new BlockPos(randomX, 64, randomZ);
                    if (!isWellWithinBorder(level, targetOrigin)) {
                        continue;
                    }

                    var startResult = level.getChunkSource().getGenerator().findNearestMapStructure(
                            level,
                            targetHolderSet,
                            targetOrigin,
                            64,
                            false
                    );
                    if (startResult != null) {
                        BlockPos foundStart = startResult.getFirst();
                        double distFromCenter = Math.sqrt(foundStart.distSqr(searchCenter));
                        if (distFromCenter >= ADQConfig.MIN_START_DISTANCE.get() && isWellWithinBorder(level, foundStart)) {
                            startPosRaw = foundStart;
                            break;
                        }
                    }

                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }

                if (startPosRaw == null) {
                    announceGenerationFailure(level);
                    return;
                }

                int minDistance = ADQConfig.MIN_DISTANCE.get();
                int maxDistance = ADQConfig.MAX_DISTANCE.get();

                // 3. Find Ending spot
                for (int attempt = 0; attempt < 20; attempt++) {
                    double angle = rand.nextDouble() * 2 * Math.PI;
                    double distance = minDistance + rand.nextDouble() * (maxDistance - minDistance);
                    BlockPos targetOrigin = startPosRaw.offset(
                            (int) (distance * Math.cos(angle)),
                            0,
                            (int) (distance * Math.sin(angle))
                    );

                    if (!isWellWithinBorder(level, targetOrigin)) {
                        continue;
                    }

                    var endResult = level.getChunkSource().getGenerator().findNearestMapStructure(
                            level,
                            targetHolderSet,
                            targetOrigin,
                            64,
                            false
                    );

                    if (endResult != null) {
                        BlockPos foundPos = endResult.getFirst();
                        double distBlocks = Math.sqrt(foundPos.distSqr(startPosRaw));
                        if (distBlocks >= minDistance && isWellWithinBorder(level, foundPos)) {
                            endingPosRaw = foundPos;
                            break;
                        }
                    }

                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }

                if (endingPosRaw == null) {
                    announceGenerationFailure(level);
                    return;
                }

                final BlockPos finalStartPosRaw = startPosRaw;
                final BlockPos finalEndPosRaw = endingPosRaw;
                LOGGER.info(
                        "[ADQ] Found {} route candidates from {} to {}.",
                        locationMode,
                        finalStartPosRaw.toShortString(),
                        finalEndPosRaw.toShortString());

                // 4. Retain both endpoint chunks until their surfaces have been validated.
                scheduledFinalization = true;
                int startChunkX = net.minecraft.core.SectionPos.blockToSectionCoord(finalStartPosRaw.getX());
                int startChunkZ = net.minecraft.core.SectionPos.blockToSectionCoord(finalStartPosRaw.getZ());
                int endChunkX = net.minecraft.core.SectionPos.blockToSectionCoord(finalEndPosRaw.getX());
                int endChunkZ = net.minecraft.core.SectionPos.blockToSectionCoord(finalEndPosRaw.getZ());
                level.getServer().execute(() -> loadStructureRouteChunks(
                        level,
                        finalStartPosRaw,
                        finalEndPosRaw,
                        startChunkX,
                        startChunkZ,
                        endChunkX,
                        endChunkZ,
                        useCustom,
                        finalSelectedTemplate,
                        templatesSource,
                        rand,
                        triggerPlayerUuid,
                        locationMode));

            } catch (Exception e) {
                LOGGER.error("[ADQ] Error in async quest generator thread", e);
            } finally {
                if (!scheduledFinalization) {
                    isGenerating.set(false);
                    level.getServer().execute(() -> QuestBoardMenuHandler.resyncToAllPlayers(level.getServer()));
                }
            }
        });
    }

    private static void loadStructureRouteChunks(
            ServerLevel level,
            BlockPos startPosRaw,
            BlockPos endPosRaw,
            int startChunkX,
            int startChunkZ,
            int endChunkX,
            int endChunkZ,
            boolean useCustom,
            CustomQuestTemplate selectedTemplate,
            List<CustomQuestTemplate> templatesSource,
            net.minecraft.util.RandomSource rand,
            UUID triggerPlayerUuid,
            ADQConfig.QuestLocationMode locationMode) {
        net.minecraft.server.level.ServerChunkCache chunkSource = level.getChunkSource();
        ChunkPos startTicketPos = new ChunkPos(startChunkX, startChunkZ);
        ChunkPos endTicketPos = new ChunkPos(endChunkX, endChunkZ);

        try {
            chunkSource.addRegionTicket(STRUCTURE_SEARCH_TICKET, startTicketPos, 2, startTicketPos);
            chunkSource.addRegionTicket(STRUCTURE_SEARCH_TICKET, endTicketPos, 2, endTicketPos);

            var startFuture = chunkSource.getChunkFuture(
                    startChunkX,
                    startChunkZ,
                    net.minecraft.world.level.chunk.status.ChunkStatus.FULL,
                    true);
            var endFuture = chunkSource.getChunkFuture(
                    endChunkX,
                    endChunkZ,
                    net.minecraft.world.level.chunk.status.ChunkStatus.FULL,
                    true);

            startFuture.thenCombine(endFuture, (startResult, endResult) -> new ChunkAccess[] {
                    startResult.orElse(null),
                    endResult.orElse(null)
            }).whenComplete((loadedChunks, error) -> level.getServer().execute(() -> {
                try {
                    if (error != null) {
                        LOGGER.error("[ADQ] Failed to load structure route chunks", error);
                        failGeneration(level, triggerPlayerUuid);
                        return;
                    }
                    if (loadedChunks == null || loadedChunks[0] == null || loadedChunks[1] == null) {
                        LOGGER.warn("[ADQ] One or both structure route chunks were unavailable.");
                        failGeneration(level, triggerPlayerUuid);
                        return;
                    }

                    BlockPos startingPos = resolveStructureLanding(
                            level,
                            loadedChunks[0],
                            startPosRaw);
                    BlockPos endingPos = resolveStructureLanding(
                            level,
                            loadedChunks[1],
                            endPosRaw);
                    if (startingPos == null || endingPos == null) {
                        LOGGER.warn(
                                "[ADQ] Structure route surface validation failed: start {} -> {}, end {} -> {}.",
                                startPosRaw.toShortString(),
                                startingPos == null ? "invalid" : startingPos.toShortString(),
                                endPosRaw.toShortString(),
                                endingPos == null ? "invalid" : endingPos.toShortString());
                        failGeneration(level, triggerPlayerUuid);
                        return;
                    }

                    registerGeneratedQuest(
                            level,
                            startingPos,
                            endingPos,
                            useCustom,
                            selectedTemplate,
                            templatesSource,
                            rand,
                            triggerPlayerUuid,
                            locationMode.name());
                } finally {
                    chunkSource.removeRegionTicket(
                            STRUCTURE_SEARCH_TICKET,
                            startTicketPos,
                            2,
                            startTicketPos);
                    chunkSource.removeRegionTicket(
                            STRUCTURE_SEARCH_TICKET,
                            endTicketPos,
                            2,
                            endTicketPos);
                }
            }));
        } catch (Exception error) {
            chunkSource.removeRegionTicket(
                    STRUCTURE_SEARCH_TICKET,
                    startTicketPos,
                    2,
                    startTicketPos);
            chunkSource.removeRegionTicket(
                    STRUCTURE_SEARCH_TICKET,
                    endTicketPos,
                    2,
                    endTicketPos);
            LOGGER.error("[ADQ] Could not request structure route chunks", error);
            failGeneration(level, triggerPlayerUuid);
        }
    }

    private static void startRandomQuestSearch(
            ServerLevel level,
            UUID triggerPlayerUuid,
            boolean useCustom,
            CustomQuestTemplate selectedTemplate,
            List<CustomQuestTemplate> templatesSource) {
        try {
            net.minecraft.util.RandomSource rand = net.minecraft.util.RandomSource.create();
            BlockPos searchCenter = null;

            if (triggerPlayerUuid != null) {
                ServerPlayer triggerPlayer = level.getServer().getPlayerList().getPlayer(triggerPlayerUuid);
                if (triggerPlayer != null) {
                    searchCenter = triggerPlayer.blockPosition();
                }
            }

            if (searchCenter == null) {
                List<ServerPlayer> players = level.players();
                searchCenter = players.isEmpty()
                        ? level.getSharedSpawnPos()
                        : players.get(rand.nextInt(players.size())).blockPosition();
            }

            double searchRadius = ADQConfig.MIN_PLAYER_RADIUS.get();
            double minStartRadius = ADQConfig.MIN_START_DISTANCE.get();
            if (searchRadius < minStartRadius) {
                LOGGER.warn(
                        "[ADQ] RANDOM search radius {} is smaller than minStartDistance {}.",
                        (int) searchRadius,
                        (int) minStartRadius);
                failGeneration(level, triggerPlayerUuid);
                return;
            }

            int configuredAttempts = ADQConfig.RANDOM_SEARCH_ATTEMPTS.get();
            boolean skyIslandMode = ADQConfig.SKY_ISLAND_MODE.get();
            int attempts = skyIslandMode
                    ? configuredAttempts
                    : Math.min(configuredAttempts, NORMAL_RANDOM_ATTEMPTS);
            long epoch = randomSearchEpoch.get();

            LOGGER.info(
                    "[ADQ] RANDOM dry-land search started around {}: skyIslandMode={}, "
                            + "maxChunksPerEndpoint={}, one search action per server tick.",
                    searchCenter.toShortString(),
                    skyIslandMode,
                    attempts);

            findRandomRoute(
                    level,
                    searchCenter,
                    minStartRadius,
                    searchRadius,
                    ADQConfig.MIN_DISTANCE.get(),
                    Math.max(ADQConfig.MIN_DISTANCE.get(), ADQConfig.MAX_DISTANCE.get()),
                    attempts,
                    skyIslandMode,
                    rand.nextDouble() * Math.PI * 2.0,
                    epoch)
                .whenComplete((route, error) -> enqueueRandomSearchStep(
                        level.getServer(),
                        epoch,
                        () -> {
                            if (error != null) {
                                LOGGER.error("[ADQ] RANDOM dry-land search failed", error);
                                failGeneration(level, triggerPlayerUuid);
                                return;
                            }
                            if (route == null) {
                                failGeneration(level, triggerPlayerUuid);
                                return;
                            }
                            registerGeneratedQuest(
                                    level,
                                    route.start(),
                                    route.end(),
                                    useCustom,
                                    selectedTemplate,
                                    templatesSource,
                                    rand,
                                    triggerPlayerUuid,
                                    "RANDOM");
                        }));
        } catch (Exception error) {
            LOGGER.error("[ADQ] Could not start tick-paced RANDOM search", error);
            failGeneration(level, triggerPlayerUuid);
        }
    }

    private record RandomRoute(BlockPos start, BlockPos end) {
    }

    private static java.util.concurrent.CompletableFuture<RandomRoute> findRandomRoute(
            ServerLevel level,
            BlockPos searchCenter,
            double minStartRadius,
            double maxStartRadius,
            double minDeliveryRadius,
            double maxDeliveryRadius,
            int attempts,
            boolean skyIslandMode,
            double phase,
            long epoch) {
        return findRandomDryLanding(
                level,
                searchCenter,
                minStartRadius,
                maxStartRadius,
                attempts,
                skyIslandMode,
                phase,
                "pickup",
                epoch)
            .thenCompose(start -> {
                if (start == null) {
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                }
                return findRandomDryLanding(
                        level,
                        start,
                        minDeliveryRadius,
                        maxDeliveryRadius,
                        attempts,
                        skyIslandMode,
                        phase + Math.PI,
                        "delivery",
                        epoch)
                    .thenApply(end -> end == null ? null : new RandomRoute(start, end));
            });
    }

    private static java.util.concurrent.CompletableFuture<BlockPos> findRandomDryLanding(
            ServerLevel level,
            BlockPos origin,
            double minRadius,
            double maxRadius,
            int attempts,
            boolean skyIslandMode,
            double phase,
            String endpointName,
            long epoch) {
        java.util.concurrent.CompletableFuture<BlockPos> result = new java.util.concurrent.CompletableFuture<>();
        enqueueRandomSearchStep(
                level.getServer(),
                epoch,
                () -> searchNextRandomChunk(
                        level,
                        origin,
                        minRadius,
                        maxRadius,
                        attempts,
                        skyIslandMode,
                        phase,
                        endpointName,
                        0,
                        new HashSet<>(),
                        result,
                        epoch));
        return result;
    }

    private static void searchNextRandomChunk(
            ServerLevel level,
            BlockPos origin,
            double minRadius,
            double maxRadius,
            int attempts,
            boolean skyIslandMode,
            double phase,
            String endpointName,
            int nextAttempt,
            Set<Long> visitedChunks,
            java.util.concurrent.CompletableFuture<BlockPos> result,
            long epoch) {
        if (result.isDone() || epoch != randomSearchEpoch.get()) {
            return;
        }

        int attempt = nextAttempt;
        RandomLandingPlanner.Candidate candidate = null;
        int chunkX = 0;
        int chunkZ = 0;
        while (attempt < attempts) {
            candidate = RandomLandingPlanner.candidate(
                    origin.getX(),
                    origin.getZ(),
                    minRadius,
                    maxRadius,
                    attempt,
                    attempts,
                    phase);
            chunkX = net.minecraft.core.SectionPos.blockToSectionCoord(candidate.x());
            chunkZ = net.minecraft.core.SectionPos.blockToSectionCoord(candidate.z());
            long chunkKey = net.minecraft.world.level.ChunkPos.asLong(chunkX, chunkZ);
            attempt++;

            if (visitedChunks.add(chunkKey)
                    && isWellWithinBorder(level, new BlockPos(candidate.x(), 64, candidate.z()))) {
                break;
            }
            candidate = null;
        }

        if (candidate == null) {
            LOGGER.warn(
                    "[ADQ] RANDOM {} search exhausted {} unique-chunk attempts without dry land.",
                    endpointName,
                    visitedChunks.size());
            result.complete(null);
            return;
        }

        final int completedAttempt = attempt;
        final int candidateChunkX = chunkX;
        final int candidateChunkZ = chunkZ;
        if (completedAttempt > 1 && (completedAttempt - 1) % 16 == 0) {
            LOGGER.info(
                    "[ADQ] RANDOM {} search checked {}/{} candidate chunks.",
                    endpointName,
                    completedAttempt - 1,
                    attempts);
        }

        try {
            net.minecraft.server.level.ServerChunkCache chunkSource = level.getChunkSource();
            ChunkPos ticketPos = new ChunkPos(candidateChunkX, candidateChunkZ);
            chunkSource.addRegionTicket(RANDOM_SEARCH_TICKET, ticketPos, 2, ticketPos);
            chunkSource.getChunkFuture(
                            candidateChunkX,
                            candidateChunkZ,
                            net.minecraft.world.level.chunk.status.ChunkStatus.FULL,
                            true)
                .whenComplete((chunkResult, error) -> {
                    try {
                        enqueueRandomSearchStep(level.getServer(), epoch, () -> {
                            try {
                                if (error != null) {
                                    LOGGER.warn(
                                        "[ADQ] Failed to load RANDOM {} candidate chunk {},{}; continuing.",
                                            endpointName,
                                            candidateChunkX,
                                            candidateChunkZ,
                                            error);
                                } else {
                                    ChunkAccess loadedChunk = chunkResult == null
                                            ? null
                                            : chunkResult.orElse(null);
                                    if (loadedChunk == null) {
                                        LOGGER.warn(
                                                "[ADQ] RANDOM {} candidate chunk {},{} was unavailable: {}; continuing.",
                                                endpointName,
                                                candidateChunkX,
                                                candidateChunkZ,
                                                chunkResult == null ? "missing result" : chunkResult.getError());
                                    }
                                    BlockPos landing = findDryLandingInChunk(
                                            level,
                                            loadedChunk,
                                            candidateChunkX,
                                            candidateChunkZ,
                                            origin,
                                            minRadius,
                                            maxRadius,
                                            skyIslandMode);
                                    if (landing != null) {
                                        LOGGER.info(
                                                "[ADQ] RANDOM {} found dry land at {} after {} chunk checks.",
                                                endpointName,
                                                landing.toShortString(),
                                                completedAttempt);
                                        result.complete(landing);
                                        return;
                                    }
                                }
                                searchNextRandomChunk(
                                        level,
                                        origin,
                                        minRadius,
                                        maxRadius,
                                        attempts,
                                        skyIslandMode,
                                        phase,
                                        endpointName,
                                        completedAttempt,
                                        visitedChunks,
                                        result,
                                        epoch);
                            } finally {
                                chunkSource.removeRegionTicket(
                                        RANDOM_SEARCH_TICKET,
                                        ticketPos,
                                        2,
                                        ticketPos);
                            }
                        });
                    } catch (Exception schedulingError) {
                        result.completeExceptionally(schedulingError);
                    }
                });
        } catch (Exception chunkRequestError) {
            LOGGER.warn(
                    "[ADQ] Could not request RANDOM {} candidate chunk {},{}; continuing.",
                    endpointName,
                    candidateChunkX,
                    candidateChunkZ,
                    chunkRequestError);
            enqueueRandomSearchStep(
                    level.getServer(),
                    epoch,
                    () -> searchNextRandomChunk(
                            level,
                            origin,
                            minRadius,
                            maxRadius,
                            attempts,
                            skyIslandMode,
                            phase,
                            endpointName,
                            completedAttempt,
                            visitedChunks,
                            result,
                            epoch));
        }
    }

    private static BlockPos findDryLandingInChunk(
            ServerLevel level,
            ChunkAccess loadedChunk,
            int chunkX,
            int chunkZ,
            BlockPos origin,
            double minRadius,
            double maxRadius,
            boolean skyIslandMode) {
        if (loadedChunk == null) {
            return null;
        }
        int[] samples = skyIslandMode ? SKY_CHUNK_SAMPLES : NORMAL_CHUNK_SAMPLES;
        int minBlockX = chunkX << 4;
        int minBlockZ = chunkZ << 4;

        for (int index = 0; index < samples.length; index += 2) {
            int x = minBlockX + samples[index];
            int z = minBlockZ + samples[index + 1];
            double distance = Math.hypot(x - origin.getX(), z - origin.getZ());
            if (distance < minRadius || distance > maxRadius) {
                continue;
            }

            BlockPos landing = resolveDrySurface(level, loadedChunk, x, z);
            if (landing != null && isWellWithinBorder(level, landing)) {
                return landing;
            }
        }
        return null;
    }

    private static BlockPos resolveDrySurface(ServerLevel level, int x, int z) {
        return resolveDrySurface(
                level.getMinBuildHeight(),
                x,
                z,
                (sampleX, sampleZ) -> level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        sampleX,
                        sampleZ),
                level::getBlockState,
                level::getFluidState);
    }

    private static BlockPos resolveDrySurface(
            ServerLevel level,
            ChunkAccess chunk,
            int x,
            int z) {
        return resolveDrySurface(
                level.getMinBuildHeight(),
                x,
                z,
                (sampleX, sampleZ) -> chunk.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        sampleX,
                        sampleZ) + 1,
                chunk::getBlockState,
                chunk::getFluidState);
    }

    private static BlockPos resolveStructureLanding(
            ServerLevel level,
            ChunkAccess chunk,
            BlockPos structurePos) {
        BlockPos exactLanding = resolveDrySurface(
                level,
                chunk,
                structurePos.getX(),
                structurePos.getZ());
        if (exactLanding != null) {
            return exactLanding;
        }

        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        List<BlockPos> candidates = new ArrayList<>();
        for (int localX = 1; localX <= 14; localX++) {
            for (int localZ = 1; localZ <= 14; localZ++) {
                candidates.add(new BlockPos(minX + localX, 0, minZ + localZ));
            }
        }
        candidates.sort(Comparator.comparingDouble(candidate ->
                candidate.distSqr(new BlockPos(structurePos.getX(), 0, structurePos.getZ()))));

        for (BlockPos candidate : candidates) {
            BlockPos landing = resolveDrySurface(
                    level,
                    chunk,
                    candidate.getX(),
                    candidate.getZ());
            if (landing != null) {
                LOGGER.info(
                        "[ADQ] Moved structure landing from {} to nearby safe ground at {}.",
                        structurePos.toShortString(),
                        landing.toShortString());
                return landing;
            }
        }
        return null;
    }

    private static BlockPos resolveDrySurface(
            int minBuildHeight,
            int x,
            int z,
            IntBinaryOperator heightReader,
            Function<BlockPos, net.minecraft.world.level.block.state.BlockState> blockStateReader,
            Function<BlockPos, net.minecraft.world.level.material.FluidState> fluidStateReader) {
        int centerY = heightReader.applyAsInt(x, z);
        if (!DryLandingRules.canHaveGround(centerY, minBuildHeight)) {
            return null;
        }

        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int sampleX = x + dx;
                int sampleZ = z + dz;
                int sampleY = heightReader.applyAsInt(sampleX, sampleZ);
                if (!DryLandingRules.canHaveGround(sampleY, minBuildHeight)) {
                    return null;
                }

                BlockPos groundPos = new BlockPos(sampleX, sampleY - 1, sampleZ);
                var groundState = blockStateReader.apply(groundPos);
                if (groundState.isAir()
                        || groundState.is(net.minecraft.tags.BlockTags.LEAVES)
                        || !groundState.getFluidState().isEmpty()
                        || !fluidStateReader.apply(groundPos.above()).isEmpty()) {
                    return null;
                }
                minY = Math.min(minY, sampleY);
                maxY = Math.max(maxY, sampleY);
            }
        }

        if (maxY - minY > 2) {
            return null;
        }
        return new BlockPos(x, centerY, z);
    }

    private static void registerGeneratedQuest(
            ServerLevel level,
            BlockPos startingPos,
            BlockPos endingPos,
            boolean useCustom,
            CustomQuestTemplate selectedTemplate,
            List<CustomQuestTemplate> templatesSource,
            net.minecraft.util.RandomSource rand,
            UUID triggerPlayerUuid,
            String locationMode) {
        try {
            UUID questId = UUID.randomUUID();
            String name;
            String description;
            String weightClass;
            double actualWeight;
            List<String> rewards = new ArrayList<>();
            String schematicName;

            if (useCustom) {
                CustomQuestTemplate template = selectedTemplate;
                if (template == null) {
                    List<CustomQuestTemplate> templatesWithoutCoords = new ArrayList<>();
                    for (CustomQuestTemplate candidate : templatesSource) {
                        if (parseCoordinates(candidate.pickupPos) == null
                                || parseCoordinates(candidate.dropoffPos) == null) {
                            templatesWithoutCoords.add(candidate);
                        }
                    }
                    if (templatesWithoutCoords.isEmpty()) {
                        templatesWithoutCoords = templatesSource;
                    }
                    template = templatesWithoutCoords.get(rand.nextInt(templatesWithoutCoords.size()));
                }
                name = template.name;
                description = template.description;
                weightClass = template.weightClass;
                actualWeight = template.actualWeight;
                rewards.addAll(template.rewards);
                schematicName = template.schematicName;
            } else {
                CustomQuestTemplate nameTemplate = templatesSource.get(rand.nextInt(templatesSource.size()));
                CustomQuestTemplate descTemplate = templatesSource.get(rand.nextInt(templatesSource.size()));
                CustomQuestTemplate weightTemplate = templatesSource.get(rand.nextInt(templatesSource.size()));
                CustomQuestTemplate schematicTemplate = templatesSource.get(rand.nextInt(templatesSource.size()));
                CustomQuestTemplate rewardTemplate = templatesSource.get(rand.nextInt(templatesSource.size()));

                name = nameTemplate.name;
                description = descTemplate.description;
                weightClass = weightTemplate.weightClass;
                actualWeight = weightTemplate.actualWeight;
                rewards.addAll(rewardTemplate.rewards);
                schematicName = schematicTemplate.schematicName;
            }

            QuestModel quest = new QuestModel(
                    questId,
                    name,
                    description,
                    startingPos,
                    endingPos,
                    weightClass,
                    actualWeight,
                    rewards);
            quest.setCreationTime(System.currentTimeMillis());
            quest.setSchematicName(schematicName);

            synchronized (availableQuests) {
                availableQuests.add(quest);
            }
            saveQuests();

            LOGGER.info(
                    "[ADQ] Generated {} quest: '{}' [{} class, {}kpg, Schematic: {}] from {} to {}",
                    locationMode,
                    name,
                    weightClass,
                    (int) actualWeight,
                    quest.getSchematicName(),
                    startingPos.toShortString(),
                    endingPos.toShortString());
        } catch (Exception error) {
            LOGGER.error("[ADQ] Error finalising quest on server thread", error);
        } finally {
            finishGeneration(level, triggerPlayerUuid);
        }
    }

    private static void failGeneration(ServerLevel level, UUID triggerPlayerUuid) {
        announceGenerationFailure(level);
        finishGeneration(level, triggerPlayerUuid);
    }

    private static void finishGeneration(ServerLevel level, UUID triggerPlayerUuid) {
        isGenerating.set(false);
        if (triggerPlayerUuid != null) {
            ServerPlayer triggerPlayer = level.getServer().getPlayerList().getPlayer(triggerPlayerUuid);
            if (triggerPlayer != null) {
                ADQEventHandler.clearActionCooldown(triggerPlayer, "generate");
                ADQEventHandler.clearActionCooldown(triggerPlayer, "fill");
            }
        }
        QuestBoardMenuHandler.resyncToAllPlayers(level.getServer());
    }

    private static void announceGenerationFailure(ServerLevel level) {
        LOGGER.warn("[ADQ] Failed to locate suitable trade routes within distance and world border limits.");
        level.getServer().execute(() -> {
            if (ADQConfig.ANNOUNCE_GEN_FAIL.get()) {
                level.getServer().getPlayerList().broadcastSystemMessage(
                    net.minecraft.network.chat.Component.literal("§6§l[ADQ] §cFailed to procedurally generate a new trade contract. No suitable trade routes found within the world border."),
                    false
                );
            }
        });
    }



    public static class ParsedCoords {
        public final int x;
        public final int y;
        public final int z;
        public final boolean hasY;

        public ParsedCoords(int x, int y, int z, boolean hasY) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.hasY = hasY;
        }
    }

    private static ParsedCoords parseCoordinates(String coordStr) {
        if (coordStr == null || coordStr.trim().isEmpty()) {
            return null;
        }
        try {
            String[] parts = coordStr.split(",");
            if (parts.length == 2) {
                int x = Integer.parseInt(parts[0].trim());
                int z = Integer.parseInt(parts[1].trim());
                return new ParsedCoords(x, 0, z, false);
            } else if (parts.length == 3) {
                int x = Integer.parseInt(parts[0].trim());
                int y = Integer.parseInt(parts[1].trim());
                int z = Integer.parseInt(parts[2].trim());
                return new ParsedCoords(x, y, z, true);
            }
        } catch (NumberFormatException e) {
            LOGGER.error("[ADQ] Failed to parse coordinates: " + coordStr, e);
        }
        return null;
    }

    private static BlockPos resolvePosition(ServerLevel level, ParsedCoords coords) {
        int x = coords.x;
        int z = coords.z;
        if (coords.hasY && coords.y != 0) {
            BlockPos configuredPos = new BlockPos(x, coords.y, z);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos groundPos = configuredPos.offset(dx, -1, dz);
                    var groundState = level.getBlockState(groundPos);
                    if (groundState.isAir()
                            || groundState.is(net.minecraft.tags.BlockTags.LEAVES)
                            || !groundState.getFluidState().isEmpty()
                            || !level.getFluidState(groundPos.above()).isEmpty()) {
                        return null;
                    }
                }
            }
            return configuredPos;
        }

        level.getChunkAt(new BlockPos(x, 64, z));
        return resolveDrySurface(level, x, z);
    }

    public static boolean isWellWithinBorder(ServerLevel level, BlockPos pos) {
        net.minecraft.world.level.border.WorldBorder border = level.getWorldBorder();
        double safetyBuffer = 150.0;
        return pos.getX() >= border.getMinX() + safetyBuffer && pos.getX() <= border.getMaxX() - safetyBuffer &&
               pos.getZ() >= border.getMinZ() + safetyBuffer && pos.getZ() <= border.getMaxZ() - safetyBuffer;
    }

    public static List<CustomQuestTemplate> getCustomTemplates() {
        return customTemplates;
    }
}
