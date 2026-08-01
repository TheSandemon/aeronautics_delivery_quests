package com.ladderstar.adq;

import net.neoforged.neoforge.common.ModConfigSpec;
import java.util.List;

public class ADQConfig {
    public enum QuestGenerationMode {
        CUSTOM,
        PROCEDURAL
    }

    public enum QuestLocationMode {
        VILLAGE,
        ANY_STRUCTURE,
        RANDOM
    }

    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue QUEST_INTERVAL;
    public static final ModConfigSpec.IntValue MAX_ACTIVE_QUESTS;
    public static final ModConfigSpec.IntValue MIN_DISTANCE;
    public static final ModConfigSpec.IntValue MAX_DISTANCE;
    public static final ModConfigSpec.IntValue QUEST_TIME_LIMIT;

    public static final ModConfigSpec.EnumValue<QuestLocationMode> QUEST_LOCATION_MODE;
    public static final ModConfigSpec.IntValue QUEST_EXPIRATION_TIME;
    public static final ModConfigSpec.IntValue PLAYER_RADIUS_SCALING;
    public static final ModConfigSpec.IntValue MIN_PLAYER_RADIUS;
    public static final ModConfigSpec.DoubleValue REWARD_REDUCTION_SCALE;
    public static final ModConfigSpec.BooleanValue ENABLE_CARGO_INVULNERABILITY;
    public static final ModConfigSpec.IntValue MIN_START_DISTANCE;
    public static final ModConfigSpec.IntValue CARGO_SPAWN_DISTANCE;
    public static final ModConfigSpec.BooleanValue SKY_ISLAND_MODE;
    public static final ModConfigSpec.IntValue RANDOM_SEARCH_ATTEMPTS;
    public static final ModConfigSpec.EnumValue<QuestGenerationMode> QUEST_GEN_MODE;

    public static final ModConfigSpec.BooleanValue ANNOUNCE_ACCEPT;
    public static final ModConfigSpec.BooleanValue ANNOUNCE_SECURE;
    public static final ModConfigSpec.BooleanValue ANNOUNCE_COMPLETE;
    public static final ModConfigSpec.BooleanValue ANNOUNCE_CANCEL;
    public static final ModConfigSpec.BooleanValue ANNOUNCE_FAIL;
    public static final ModConfigSpec.BooleanValue ANNOUNCE_GEN_FAIL;

    static {
        BUILDER.push("general");

        QUEST_INTERVAL = BUILDER
                .comment("Interval in minutes between automatic quest generation checks.")
                .defineInRange("questCreationInterval", 30, 1, 1440);

        MAX_ACTIVE_QUESTS = BUILDER
                .comment("Maximum number of concurrent available quests on the board.")
                .defineInRange("maxActiveQuestsPerBoard", 8, 0, 18);

        MIN_DISTANCE = BUILDER
                .comment("Minimum distance (in blocks) between quest start and delivery location.")
                .defineInRange("minDeliveryDistance", 500, 100, 10000);

        MAX_DISTANCE = BUILDER
                .comment("Maximum distance (in blocks) between quest start and delivery location.")
                .defineInRange("maxDeliveryDistance", 2000, 500, 50000);

        QUEST_TIME_LIMIT = BUILDER
                .comment("Time limit in minutes to complete an accepted quest before failure.")
                .defineInRange("questTimeLimit", 60, 5, 1440);

        QUEST_LOCATION_MODE = BUILDER
                .comment("Quest spawn location mode. Controls how pickup and delivery endpoints are located:\n" +
                         "  - 'VILLAGE': Targets Minecraft villages (using the #minecraft:village structure tag). Recommended for vanilla/thematic delivery routes.\n" +
                         "  - 'ANY_STRUCTURE': Targets any registered Overworld structure (surface/top-level only). Recommended for adventurous exploration.\n" +
                         "  - 'RANDOM': Targets random coordinates in the wild. Chunks are loaded dynamically to query the heightmap and find a safe surface landing zone.")
                .defineEnum("questLocationMode", QuestLocationMode.VILLAGE);

        QUEST_EXPIRATION_TIME = BUILDER
                .comment("Expiration timer in minutes for unclaimed quests on the board before they cycle out.")
                .defineInRange("questExpirationTime", 60, 5, 1440);

        PLAYER_RADIUS_SCALING = BUILDER
                .comment("Scaling amount of blocks added to the distance between the two furthest players to determine quest generation radius.")
                .defineInRange("playerRadiusScaling", 1000, 100, 10000);

        MIN_PLAYER_RADIUS = BUILDER
                .comment("Default search radius in blocks for quest generation if only one player is online.")
                .defineInRange("minPlayerRadius", 1000, 100, 10000);

        REWARD_REDUCTION_SCALE = BUILDER
                .comment("Scale of reward penalty applied for missing cargo blocks upon delivery (1.0 = fully proportional). Only applies if cargo invulnerability is disabled.")
                .defineInRange("rewardReductionScale", 1.0, 0.0, 5.0);

        ENABLE_CARGO_INVULNERABILITY = BUILDER
                .comment("If true, cargo blocks and subLevels are completely indestructible and immune to player breaks, placement, explosions, and mob griefing. Also bypasses reward scaling penalty.\n" +
                         "If false, cargo blocks can be destroyed (reducing the delivery payout proportionally to the missing mass).\n" +
                         "In BOTH modes, destroyed cargo blocks never drop their items.")
                .define("enableCargoInvulnerability", false);

        MIN_START_DISTANCE = BUILDER
                .comment("Minimum distance in blocks between the player and the generated quest starting pickup location.")
                .defineInRange("minStartDistance", 300, 0, 5000);

        CARGO_SPAWN_DISTANCE = BUILDER
                .comment("Distance in blocks from the pickup location at which the physical cargo is pre-spawned for the approaching pilot.\n" +
                         "Keep this comfortably above the server's typical client render distance so players never see the cargo pop in\n" +
                         "(e.g. a 10-chunk render distance is 160 blocks; the default 250 stays out of sight).")
                .defineInRange("cargoSpawnDistance", 250, 32, 1000);

        SKY_ISLAND_MODE = BUILDER
                .comment("Enables the sparse-terrain RANDOM search used by floating-island worlds.\n" +
                         "The search checks several positions in each candidate chunk and processes only one chunk at a time.\n" +
                         "Water is rejected in every mode regardless of this setting.")
                .define("skyIslandMode", false);

        RANDOM_SEARCH_ATTEMPTS = BUILDER
                .comment("Maximum unique chunks checked for each RANDOM endpoint.\n" +
                         "The search stops at the first dry landing and never loads candidate chunks concurrently.")
                .defineInRange("randomSearchAttempts", 96, 20, 512);

        QUEST_GEN_MODE = BUILDER
                .comment("Quest generation mode. Controls how quest information (name, reward, schematic, weight) is selected:\n" +
                         "  - 'CUSTOM': Loads full, structured quests directly from 'config/aeronautics_delivery_quests/custom_quests.json'. These are spawned exactly as defined by the server administrator.\n" +
                         "  - 'PROCEDURAL': Dynamically mixes and matches components. It randomly picks names, descriptions, weights, schematics, and rewards from different templates in 'custom_quests.json'.\n" +
                         "  - Fallback: If 'custom_quests.json' is missing, empty, or invalid, both modes auto-fallback to procedural generation drawing from 6+ default schematics and 5+ default balanced template definitions.")
                .defineEnum("questGenerationMode", QuestGenerationMode.CUSTOM);

        BUILDER.pop();

        BUILDER.push("announcements");

        ANNOUNCE_ACCEPT = BUILDER
                .comment("Whether to broadcast to the server when a player accepts a quest.")
                .define("announceAccept", true);

        ANNOUNCE_SECURE = BUILDER
                .comment("Whether to broadcast to the server when a player secures quest cargo.")
                .define("announceSecure", true);

        ANNOUNCE_COMPLETE = BUILDER
                .comment("Whether to broadcast to the server when a player completes a quest.")
                .define("announceComplete", true);

        ANNOUNCE_CANCEL = BUILDER
                .comment("Whether to broadcast to the server when a player cancels a quest.")
                .define("announceCancel", true);

        ANNOUNCE_FAIL = BUILDER
                .comment("Whether to broadcast to the server when a quest fails due to time expiration.")
                .define("announceFail", true);

        ANNOUNCE_GEN_FAIL = BUILDER
                .comment("Whether to broadcast to the server when quest generation fails to locate villages.")
                .define("announceGenFail", true);

        BUILDER.pop();

        SPEC = BUILDER.build();
    }
}
