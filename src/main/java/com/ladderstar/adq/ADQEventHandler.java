package com.ladderstar.adq;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

@EventBusSubscriber(modid = AeronauticsDeliveryQuests.MODID)
public class ADQEventHandler {
    private static final Logger LOGGER = LogManager.getLogger();

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("[TNM Quests] Server starting. Loading quests...");
        QuestGenerator.resetRandomSearchQueue();
        ServerLevel overworld = event.getServer().overworld();
        ADQSchematicManager.loadSchematics(overworld);
        QuestGenerator.init(overworld);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        QuestGenerator.resetRandomSearchQueue();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        QuestGenerator.runRandomSearchStep(event.getServer());
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        LOGGER.info("[TNM Quests] Registering command structures...");
        registerCommands(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            // Only tick on the Overworld to prevent duplicate quest generation/ticking logs across dimensions/sublevels
            if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) {
                return;
            }

            // 1. Periodic quest generation check (Every questInterval minutes)
            long gameTime = level.getGameTime();
            long intervalTicks = (long) ADQConfig.QUEST_INTERVAL.get() * 60L * 20L;
            if (gameTime % intervalTicks == 0) {
                QuestGenerator.generateNewQuestAsync(level);
            }

            // 2. Track quest deliveries (every 20 ticks / 1 second)
            if (gameTime % 20 == 0) {
                DeliveryTracker.tick(level);
            }

            // 3. Render delivery/pickup bounds particles (every 10 ticks / 0.5 seconds)
            if (gameTime % 10 == 0) {
                DeliveryTracker.renderParticles(level);
            }

            // 4. Track Sable sublevels that split off active cargo bodies (every tick;
            //    the split marker is only readable for a short window after the split)
            if (net.neoforged.fml.ModList.get().isLoaded("sable")) {
                CargoFragmentTracker.tick(level);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // Check if player has an active quest
            boolean hasActiveQuest = false;
            for (QuestModel quest : QuestGenerator.getAvailableQuests()) {
                if (player.getUUID().equals(quest.getAcceptedBy()) && !quest.isCompleted()) {
                    hasActiveQuest = true;
                    break;
                }
            }
            // If they have no active quest, scan their inventory and delete any "Quest Delivery Compass" items
            if (!hasActiveQuest) {
                boolean removed = false;
                for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                    ItemStack stack = player.getInventory().getItem(i);
                    if (stack.is(Items.COMPASS)) {
                        Component name = stack.get(DataComponents.CUSTOM_NAME);
                        if (name != null && name.getString().contains("Quest Delivery Compass")) {
                            player.getInventory().setItem(i, ItemStack.EMPTY);
                            removed = true;
                        }
                    }
                }
                if (removed) {
                    player.containerMenu.broadcastChanges();
                    LOGGER.info("[TNM Quests] Purged orphan Quest Delivery Compass from logging-in player {}", player.getName().getString());
                }
            }
        }
    }

    // ===================== Cargo block protection & drop suppression =====================
    //
    // Two invariants, independent of each other:
    //  1. If enableCargoInvulnerability is ON, cargo blocks cannot be destroyed by
    //     players, mobs, or explosions (in the Sable sublevel dimension AND in the
    //     Overworld spawn region before/if the physics assembly happens).
    //  2. Cargo blocks NEVER drop their items, whether invulnerability is on or off.
    //     When breakable, the vanilla break is cancelled and the block is removed
    //     manually with drops disabled.

    /**
     * Returns the active quest whose cargo sublevel plot (main body or a tracked split
     * fragment) contains this block position, or null. Sable embeds sublevel blocks in
     * plots at remote holding-chunk coordinates of the host level, so this matches
     * against plot bounding boxes via CargoFragmentTracker (Sable-only code path).
     */
    private static QuestModel getQuestForCargoPlot(ServerLevel level, BlockPos pos) {
        if (!net.neoforged.fml.ModList.get().isLoaded("sable")) {
            return null;
        }
        return CargoFragmentTracker.getQuestForPlotPos(level, pos);
    }

    /**
     * Returns the active quest whose Overworld cargo spawn region contains this position, or null.
     * The region is sized from the quest's schematic (with a 1-block settle margin below),
     * covering the window where cargo blocks physically exist in the Overworld
     * (between template placement and Sable assembly, or if the assembly failed).
     */
    private static QuestModel getQuestForOverworldCargoPos(ServerLevel level, BlockPos pos) {
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) {
            return null;
        }
        for (QuestModel quest : QuestGenerator.getAvailableQuests()) {
            if (quest.getAcceptedBy() != null && !quest.isCompleted()) {
                int w = 3, h = 3, l = 3; // fallback when schematic is unavailable
                String schematicName = quest.getSchematicName();
                if (schematicName != null && !schematicName.isEmpty()) {
                    net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate template =
                        ADQSchematicManager.getSchematic(level, schematicName);
                    if (template != null) {
                        net.minecraft.core.Vec3i size = template.getSize();
                        w = size.getX();
                        h = size.getY();
                        l = size.getZ();
                    }
                }
                BlockPos startPos = quest.getStartingPos();
                BlockPos origin = startPos.offset(-w / 2, 0, -l / 2);
                if (pos.getX() >= origin.getX() && pos.getX() < origin.getX() + w &&
                    pos.getZ() >= origin.getZ() && pos.getZ() < origin.getZ() + l &&
                    pos.getY() >= origin.getY() - 1 && pos.getY() < origin.getY() + h) {
                    return quest;
                }
            }
        }
        return null;
    }

    /** Returns the active quest owning this cargo block position (sublevel plot or Overworld spawn region), or null. */
    private static QuestModel getQuestForCargoBlock(ServerLevel level, BlockPos pos) {
        QuestModel quest = getQuestForCargoPlot(level, pos);
        if (quest != null) {
            return quest;
        }
        return getQuestForOverworldCargoPos(level, pos);
    }

    @SubscribeEvent
    public static void onBlockBreak(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockPos pos = event.getPos();
        QuestModel quest = getQuestForCargoBlock(serverLevel, pos);
        if (quest == null) {
            return;
        }

        // Cargo blocks never drop items: always take over the break handling.
        event.setCanceled(true);

        if (ADQConfig.ENABLE_CARGO_INVULNERABILITY.get()) {
            if (event.getPlayer() instanceof ServerPlayer sp) {
                sp.sendSystemMessage(Component.literal("§c[TNM Quests] Cargo blocks are protected and cannot be broken."));
            }
        } else {
            // Breakable mode: remove the block manually with drops disabled.
            serverLevel.destroyBlock(pos, false, event.getPlayer());
        }
    }

    @SubscribeEvent
    public static void onBlockPlace(net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent event) {
        if (!ADQConfig.ENABLE_CARGO_INVULNERABILITY.get()) return;

        if (event.getLevel() instanceof ServerLevel serverLevel) {
            if (getQuestForCargoPlot(serverLevel, event.getPos()) != null) {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public static void onExplosionDetonate(net.neoforged.neoforge.event.level.ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        boolean invulnerable = ADQConfig.ENABLE_CARGO_INVULNERABILITY.get();

        // Filter every affected block that belongs to cargo (sublevel plot or Overworld
        // spawn region). Invulnerable: fully protected. Breakable: destroyed with no drops.
        java.util.List<BlockPos> noDropDestroy = new java.util.ArrayList<>();
        java.util.Iterator<BlockPos> it = event.getAffectedBlocks().iterator();
        while (it.hasNext()) {
            BlockPos p = it.next();
            if (getQuestForCargoBlock(serverLevel, p) != null) {
                it.remove();
                if (!invulnerable) {
                    noDropDestroy.add(p);
                }
            }
        }
        for (BlockPos p : noDropDestroy) {
            serverLevel.destroyBlock(p, false);
        }
    }

    @SubscribeEvent
    public static void onLivingDestroyBlock(net.neoforged.neoforge.event.entity.living.LivingDestroyBlockEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockPos pos = event.getPos();
        if (getQuestForCargoBlock(serverLevel, pos) == null) {
            return;
        }

        event.setCanceled(true);
        if (!ADQConfig.ENABLE_CARGO_INVULNERABILITY.get()) {
            // Breakable: mimic the destruction without dropping items.
            serverLevel.destroyBlock(pos, false, event.getEntity());
        }
    }

    private static final java.util.Map<String, Long> actionCooldowns = new java.util.concurrent.ConcurrentHashMap<>();

    public static boolean checkAndSetActionCooldown(ServerPlayer player, String action) {
        long now = System.currentTimeMillis();
        String key = player.getUUID().toString() + "_" + action;
        long lastUsed = actionCooldowns.getOrDefault(key, 0L);
        if (now - lastUsed < 5000L) {
            long remainingMs = 5000L - (now - lastUsed);
            double remainingSecs = remainingMs / 1000.0;
            player.sendSystemMessage(Component.literal(String.format("§cPlease wait %.1f seconds before running this command/action again.", remainingSecs)));
            return false;
        }
        actionCooldowns.put(key, now);
        return true;
    }

    public static void clearActionCooldown(ServerPlayer player, String action) {
        String key = player.getUUID().toString() + "_" + action;
        actionCooldowns.remove(key);
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("adq")
                .requires(source -> source.hasPermission(2))
                .executes(ADQEventHandler::openBoardCommand)
                .then(Commands.literal("cancel")
                    .executes(ADQEventHandler::cancelQuestCommand)
                )
                .then(Commands.literal("compass")
                    .executes(ADQEventHandler::reissueCompassCommand)
                )
                .then(Commands.literal("reload")
                    .requires(source -> source.hasPermission(2))
                    .executes(ADQEventHandler::reloadCommand)
                )
                .then(Commands.literal("generate")
                    .requires(source -> source.hasPermission(2))
                    .executes(ADQEventHandler::adminGenerateCommand)
                )
                .then(Commands.literal("complete")
                    .requires(source -> source.hasPermission(2))
                    .executes(ADQEventHandler::adminCompleteCommand)
                )
                .then(Commands.literal("delete")
                    .requires(source -> source.hasPermission(2))
                    .then(Commands.argument("index", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                        .executes(ADQEventHandler::adminDeleteCommand)
                    )
                )
                .then(Commands.literal("deleteall")
                    .requires(source -> source.hasPermission(2))
                    .executes(ADQEventHandler::adminDeleteAllCommand)
                )
        );
    }

    private static int openBoardCommand(CommandContext<CommandSourceStack> context) {
        try {
            ServerPlayer player = context.getSource().getPlayerOrException();
            if (!checkAndSetActionCooldown(player, "open")) return 0;
            context.getSource().getServer().execute(() -> {
                QuestBoardMenuHandler.openBoard(player);
                clearActionCooldown(player, "open");
            });
            return 1;
        } catch (Exception e) {
            context.getSource().sendFailure(Component.literal("This command can only be run by a player."));
            return 0;
        }
    }

    private static int reloadCommand(CommandContext<CommandSourceStack> context) {
        try {
            ServerPlayer player = context.getSource().getPlayer();
            if (player != null) {
                if (!checkAndSetActionCooldown(player, "reload")) return 0;
            }
            QuestGenerator.loadQuests();
            QuestGenerator.loadCooldowns();
            context.getSource().sendSuccess(() -> Component.literal("§a§l[TNM Quests] Admin: Quests and Cooldowns successfully reloaded from disk!"), true);
            if (player != null) {
                clearActionCooldown(player, "reload");
            }
            return 1;
        } catch (Exception e) {
            context.getSource().sendFailure(Component.literal("Failed to reload quest data: " + e.getMessage()));
            return 0;
        }
    }

    private static int cancelQuestCommand(CommandContext<CommandSourceStack> context) {
        try {
            ServerPlayer player = context.getSource().getPlayerOrException();
            if (!checkAndSetActionCooldown(player, "cancel")) return 0;
            boolean found = false;
            
            for (QuestModel quest : QuestGenerator.getAvailableQuests()) {
                if (player.getUUID().equals(quest.getAcceptedBy()) && !quest.isCompleted()) {
                    // Call assembler to remove cargo entity BEFORE resetting fields!
                    CargoAssembler.removeCargo(context.getSource().getLevel(), quest);
                    // Call marker manager to clear compass and waypoints
                    MarkerManager.clearMarkers(player, quest);

                    quest.setAcceptedBy(null);
                    quest.setCargoEntityId(null);
                    quest.setCargoPickedUp(false);
                    quest.setAcceptedTime(0);

                    QuestGenerator.saveQuests();
                    player.sendSystemMessage(Component.literal("§c§l[TNM Quests] Quest Canceled: §fThe delivery cargo has been recalled."));
                    
                    if (player.getServer() != null && ADQConfig.ANNOUNCE_CANCEL.get()) {
                        player.getServer().getPlayerList().broadcastSystemMessage(
                            Component.literal("§6§l[TNM Quests] §c" + player.getName().getString() + " §7has canceled the contract: §e" + quest.getName() + "§7. Cargo recalled."),
                            false
                        );
                    }
                    found = true;
                    break;
                }
            }
            
            if (!found) {
                player.sendSystemMessage(Component.literal("§cYou do not have any active delivery contracts."));
            } else {
                clearActionCooldown(player, "cancel");
            }
            return 1;
        } catch (Exception e) {
            context.getSource().sendFailure(Component.literal("This command can only be run by a player."));
            return 0;
        }
    }

    private static boolean hasCompassInInventory(ServerPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(Items.COMPASS)) {
                Component name = stack.get(DataComponents.CUSTOM_NAME);
                if (name != null && name.getString().contains("Quest Delivery Compass")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int reissueCompassCommand(CommandContext<CommandSourceStack> context) {
        try {
            ServerPlayer player = context.getSource().getPlayerOrException();
            if (!checkAndSetActionCooldown(player, "compass")) return 0;
            QuestModel activeQuest = null;
            
            for (QuestModel quest : QuestGenerator.getAvailableQuests()) {
                if (player.getUUID().equals(quest.getAcceptedBy()) && !quest.isCompleted()) {
                    activeQuest = quest;
                    break;
                }
            }
            
            if (activeQuest == null) {
                player.sendSystemMessage(Component.literal("§cYou do not have any active delivery contracts to reissue a compass for."));
                return 1;
            }

            if (hasCompassInInventory(player)) {
                player.sendSystemMessage(Component.literal("§cYou already possess a Quest Delivery Compass in your inventory!"));
                return 1;
            }
            
            MarkerManager.ensureAndCalibrateCompass(player, activeQuest);
            clearActionCooldown(player, "compass");
            return 1;
        } catch (Exception e) {
            context.getSource().sendFailure(Component.literal("This command can only be run by a player."));
            return 0;
        }
    }

    private static int adminGenerateCommand(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        ServerPlayer player = context.getSource().getPlayer();
        if (player != null) {
            if (!checkAndSetActionCooldown(player, "generate")) return 0;
        }
        if (QuestGenerator.getAvailableQuests().size() >= ADQConfig.MAX_ACTIVE_QUESTS.get()) {
            context.getSource().sendFailure(Component.literal("Quest board is already full."));
            return 0;
        }
        if (QuestGenerator.isGenerating()) {
            context.getSource().sendFailure(Component.literal("§cQuest generation is already running. Please wait."));
            return 0;
        }
        QuestGenerator.generateNewQuestAsync(level, player != null ? player.getUUID() : null);
        context.getSource().sendSuccess(() -> Component.literal("§a§l[TNM Quests] Admin: Procedural quest generation started in background. Check the quest board in a moment."), true);
        return 1;
    }

    private static int adminCompleteCommand(CommandContext<CommandSourceStack> context) {
        try {
            ServerPlayer player = context.getSource().getPlayerOrException();
            if (!checkAndSetActionCooldown(player, "complete")) return 0;
            boolean completed = false;
            
            for (QuestModel quest : QuestGenerator.getAvailableQuests()) {
                if (player.getUUID().equals(quest.getAcceptedBy()) && !quest.isCompleted()) {
                    DeliveryTracker.forceCompleteQuest(player, quest);
                    completed = true;
                    break;
                }
            }
            
            if (!completed) {
                player.sendSystemMessage(Component.literal("§cYou do not have any active delivery contracts to complete."));
            } else {
                clearActionCooldown(player, "complete");
            }
            return 1;
        } catch (Exception e) {
            context.getSource().sendFailure(Component.literal("This command can only be run by a player."));
            return 0;
        }
    }

    private static int adminDeleteCommand(CommandContext<CommandSourceStack> context) {
        try {
            ServerPlayer player = context.getSource().getPlayer();
            if (player != null) {
                if (!checkAndSetActionCooldown(player, "delete")) return 0;
            }
            int index = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "index") - 1; // 1-based to 0-based
            List<QuestModel> quests = QuestGenerator.getAvailableQuests();
            
            synchronized (quests) {
                if (index < 0 || index >= quests.size()) {
                    context.getSource().sendFailure(Component.literal("§c[TNM Quests] Invalid quest index. Please check active quest count."));
                    return 0;
                }
                
                QuestModel quest = quests.get(index);
                ServerLevel level = context.getSource().getLevel();
                
                // Clean up quest (cargo, markers) if active
                if (quest.getAcceptedBy() != null) {
                    ServerPlayer targetPlayer = level.getServer().getPlayerList().getPlayer(quest.getAcceptedBy());
                    if (targetPlayer != null) {
                        MarkerManager.clearMarkers(targetPlayer, quest);
                        targetPlayer.sendSystemMessage(Component.literal("§c§l[TNM Quests] Quest Force Deleted by Admin: §fThe delivery cargo has been recalled."));
                    }
                    CargoAssembler.removeCargo(level, quest);
                }
                
                quests.remove(index);
                QuestGenerator.saveQuests();
                QuestBoardMenuHandler.resyncToAllPlayers(context.getSource().getServer());
                context.getSource().sendSuccess(() -> Component.literal("§a§l[TNM Quests] Admin: Successfully deleted quest '" + quest.getName() + "'."), true);
                if (player != null) {
                    clearActionCooldown(player, "delete");
                }
            }
            return 1;
        } catch (Exception e) {
            context.getSource().sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int adminDeleteAllCommand(CommandContext<CommandSourceStack> context) {
        try {
            ServerPlayer player = context.getSource().getPlayer();
            if (player != null) {
                if (!checkAndSetActionCooldown(player, "deleteall")) return 0;
            }
            List<QuestModel> quests = QuestGenerator.getAvailableQuests();
            ServerLevel level = context.getSource().getLevel();
            
            synchronized (quests) {
                int count = quests.size();
                for (QuestModel quest : quests) {
                    if (quest.getAcceptedBy() != null) {
                        ServerPlayer targetPlayer = level.getServer().getPlayerList().getPlayer(quest.getAcceptedBy());
                        if (targetPlayer != null) {
                            MarkerManager.clearMarkers(targetPlayer, quest);
                            targetPlayer.sendSystemMessage(Component.literal("§c§l[TNM Quests] Quest Force Deleted by Admin: §fThe delivery cargo has been recalled."));
                        }
                        CargoAssembler.removeCargo(level, quest);
                    }
                }
                quests.clear();
                QuestGenerator.saveQuests();
                QuestBoardMenuHandler.resyncToAllPlayers(context.getSource().getServer());
                context.getSource().sendSuccess(() -> Component.literal("§a§l[TNM Quests] Admin: Successfully deleted all " + count + " quests."), true);
                if (player != null) {
                    clearActionCooldown(player, "deleteall");
                }
            }
            return 1;
        } catch (Exception e) {
            context.getSource().sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }
}
