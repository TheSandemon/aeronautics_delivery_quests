package com.ladderstar.adq;

import com.ladderstar.adq.network.ClientboundQuestSyncPacket;
import com.ladderstar.adq.network.ServerboundQuestActionPacket;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.UUID;

public class QuestBoardMenuHandler {

    public static void openBoard(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;

        List<QuestModel> quests = QuestGenerator.getAvailableQuests();
        UUID playerUuid = player.getUUID();

        long cooldownTime = QuestGenerator.getCooldown(playerUuid);
        long timeSinceClaim = System.currentTimeMillis() - cooldownTime;
        long oneHourMs = 3600000L;
        long cooldownRemainingSeconds = 0;
        if (timeSinceClaim < oneHourMs) {
            cooldownRemainingSeconds = (oneHourMs - timeSinceClaim) / 1000L;
        }

        long gameTime = serverPlayer.serverLevel().getGameTime();
        long intervalTicks = (long) ADQConfig.QUEST_INTERVAL.get() * 60L * 20L;
        long ticksUntilNext = intervalTicks - (gameTime % intervalTicks);
        long nextQuestTimerSeconds = ticksUntilNext / 20L;

        // Send payload packet to client to open our custom ledger screen
        serverPlayer.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
            new ClientboundQuestSyncPacket(quests, cooldownRemainingSeconds, nextQuestTimerSeconds, true, QuestGenerator.isGenerating())
        ));
    }

    public static void handleServerPacketAction(ServerPlayer player, ServerboundQuestActionPacket.Action action, UUID questId) {
        String actionKey = action.name().toLowerCase();
        if (!ADQEventHandler.checkAndSetActionCooldown(player, actionKey)) {
            resyncToAllPlayers(player.getServer());
            return;
        }
        switch (action) {
            case ACCEPT -> handleQuestAccept(player, questId);
            case CANCEL -> handleQuestCancel(player);
            case REISSUE -> handleCompassReissue(player);
            case GENERATE -> handleAdminGenerate(player);
            case FILL -> handleAdminFill(player);
            case DELETE_ALL -> handleAdminDeleteAll(player);
            case RELOAD -> handleAdminReload(player);
        }
    }

    /**
     * Admin Action: Generate a single quest.
     * Triggers a single quest generation task asynchronously in the background.
     */
    public static void handleAdminGenerate(ServerPlayer player) {
        if (!player.hasPermissions(2)) {
            player.sendSystemMessage(Component.literal("§cYou do not have permission to run admin commands."));
            return;
        }
        if (QuestGenerator.getAvailableQuests().size() >= ADQConfig.MAX_ACTIVE_QUESTS.get()) {
            player.sendSystemMessage(Component.literal("§cQuest board is already full."));
            return;
        }
        QuestGenerator.generateNewQuestAsync(player.serverLevel(), player.getUUID());
        player.sendSystemMessage(Component.literal("§a§l[TNM Quests] Admin: Procedural quest generation started in background."));
    }

    /**
     * Admin Action: Fill Quests.
     * Calculates the remaining available slots on the board up to the configured limit,
     * and triggers that many individual asynchronous quest generation tasks.
     */
    public static void handleAdminFill(ServerPlayer player) {
        if (!player.hasPermissions(2)) {
            player.sendSystemMessage(Component.literal("§cYou do not have permission to run admin commands."));
            return;
        }
        int maxQuests = ADQConfig.MAX_ACTIVE_QUESTS.get();
        int currentCount = QuestGenerator.getAvailableQuests().size();
        if (currentCount >= maxQuests) {
            player.sendSystemMessage(Component.literal("§cQuest board is already full."));
            return;
        }
        int needed = maxQuests - currentCount;
        for (int i = 0; i < needed; i++) {
            QuestGenerator.generateNewQuestAsync(player.serverLevel(), player.getUUID());
        }
        player.sendSystemMessage(Component.literal("§a§l[TNM Quests] Admin: Triggered procedural generation for §e" + needed + " §acontracts."));
    }

    public static void resyncToAllPlayers(net.minecraft.server.MinecraftServer server) {
        if (server == null) return;
        List<QuestModel> quests = QuestGenerator.getAvailableQuests();
        
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID playerUuid = player.getUUID();
            long cooldownTime = QuestGenerator.getCooldown(playerUuid);
            long timeSinceClaim = System.currentTimeMillis() - cooldownTime;
            long oneHourMs = 3600000L;
            long cooldownRemainingSeconds = 0;
            if (timeSinceClaim < oneHourMs) {
                cooldownRemainingSeconds = (oneHourMs - timeSinceClaim) / 1000L;
            }

            long gameTime = player.serverLevel().getGameTime();
            long intervalTicks = (long) ADQConfig.QUEST_INTERVAL.get() * 60L * 20L;
            long ticksUntilNext = intervalTicks - (gameTime % intervalTicks);
            long nextQuestTimerSeconds = ticksUntilNext / 20L;

            player.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                new ClientboundQuestSyncPacket(quests, cooldownRemainingSeconds, nextQuestTimerSeconds, false, QuestGenerator.isGenerating())
            ));
        }
    }

    public static void handleQuestAccept(ServerPlayer player, UUID questId) {
        if (questId == null) return;

        QuestModel targetQuest = null;
        for (QuestModel quest : QuestGenerator.getAvailableQuests()) {
            if (quest.getQuestId().equals(questId)) {
                targetQuest = quest;
                break;
            }
        }

        if (targetQuest == null) {
            player.sendSystemMessage(Component.literal("§c[TNM Quests] Contract not found on the board!"));
            return;
        }

        UUID playerUuid = player.getUUID();
        long cooldownTime = QuestGenerator.getCooldown(playerUuid);
        long timeSinceClaim = System.currentTimeMillis() - cooldownTime;
        long oneHourMs = 3600000L;
        if (timeSinceClaim < oneHourMs && !player.hasPermissions(2)) {
            long remainingSeconds = (oneHourMs - timeSinceClaim) / 1000L;
            player.sendSystemMessage(Component.literal("§cYou cannot accept a new contract yet! Cooldown remaining: §e" 
                    + (remainingSeconds / 60) + "m " + (remainingSeconds % 60) + "s."));
            return;
        }

        if (targetQuest.getAcceptedBy() != null) {
            player.sendSystemMessage(Component.literal("§cThis contract has already been claimed by another pilot!"));
            return;
        }

        // Verify player has no active quest
        for (QuestModel q : QuestGenerator.getAvailableQuests()) {
            if (playerUuid.equals(q.getAcceptedBy())) {
                player.sendSystemMessage(Component.literal("§cYou are already on an active delivery contract! Abandon it first."));
                return;
            }
        }

        targetQuest.setAcceptedBy(playerUuid);
        targetQuest.setAcceptedTime(System.currentTimeMillis());
        QuestGenerator.setCooldown(playerUuid, System.currentTimeMillis());
        QuestGenerator.saveQuests();

        double distToPickup = Math.sqrt(player.blockPosition().distSqr(targetQuest.getStartingPos()));
        player.sendSystemMessage(Component.literal("§a§l[TNM Quests] Contract Accepted: §e" + targetQuest.getName()));
        player.sendSystemMessage(Component.literal("§7Proceed to the starting location at " + targetQuest.getStartingPos().toShortString() + " (§e" + (int)distToPickup + " blocks§7) to secure the cargo."));

        if (player.getServer() != null && ADQConfig.ANNOUNCE_ACCEPT.get()) {
            player.getServer().getPlayerList().broadcastSystemMessage(
                Component.literal("§6§l[TNM Quests] §a" + player.getName().getString() + " §7has accepted the contract: §e" + targetQuest.getName()),
                false
            );
        }

        MarkerManager.createMarkers(player, targetQuest);
        ADQEventHandler.clearActionCooldown(player, "accept");
        resyncToAllPlayers(player.getServer());
    }

    public static void handleQuestCancel(ServerPlayer player) {
        QuestModel activeQuest = null;
        for (QuestModel quest : QuestGenerator.getAvailableQuests()) {
            if (player.getUUID().equals(quest.getAcceptedBy()) && !quest.isCompleted()) {
                activeQuest = quest;
                break;
            }
        }
        if (activeQuest == null) {
            player.sendSystemMessage(Component.literal("§cYou do not have any active delivery contracts to cancel."));
            return;
        }

        CargoAssembler.removeCargo(player.serverLevel(), activeQuest);
        MarkerManager.clearMarkers(player, activeQuest);

        activeQuest.setAcceptedBy(null);
        activeQuest.setCargoEntityId(null);
        activeQuest.setCargoPickedUp(false);
        activeQuest.setAcceptedTime(0);

        QuestGenerator.saveQuests();
        player.sendSystemMessage(Component.literal("§c§l[TNM Quests] Quest Canceled: §fThe delivery cargo has been recalled."));

        if (player.getServer() != null && ADQConfig.ANNOUNCE_CANCEL.get()) {
            player.getServer().getPlayerList().broadcastSystemMessage(
                Component.literal("§6§l[TNM Quests] §c" + player.getName().getString() + " §7has canceled the contract: §e" + activeQuest.getName() + "§7. Cargo recalled."),
                false
            );
        }

        ADQEventHandler.clearActionCooldown(player, "cancel");
        resyncToAllPlayers(player.getServer());
    }

    public static void handleCompassReissue(ServerPlayer player) {
        QuestModel activeQuest = null;
        for (QuestModel quest : QuestGenerator.getAvailableQuests()) {
            if (player.getUUID().equals(quest.getAcceptedBy()) && !quest.isCompleted()) {
                activeQuest = quest;
                break;
            }
        }
        if (activeQuest == null) {
            player.sendSystemMessage(Component.literal("§cYou do not have any active delivery contracts to reissue a compass for."));
            return;
        }
        if (hasCompassInInventory(player)) {
            player.sendSystemMessage(Component.literal("§cYou already possess a Quest Delivery Compass in your inventory!"));
            return;
        }
        MarkerManager.ensureAndCalibrateCompass(player, activeQuest);
        player.sendSystemMessage(Component.literal("§aQuest Delivery Compass reissued."));
        ADQEventHandler.clearActionCooldown(player, "reissue");
        resyncToAllPlayers(player.getServer());
    }


    public static void handleAdminDeleteAll(ServerPlayer player) {
        if (!player.hasPermissions(2)) {
            player.sendSystemMessage(Component.literal("§cYou do not have permission to run admin commands."));
            return;
        }
        List<QuestModel> quests = QuestGenerator.getAvailableQuests();
        synchronized (quests) {
            int count = quests.size();
            for (QuestModel quest : quests) {
                if (quest.getAcceptedBy() != null) {
                    ServerPlayer p = player.getServer().getPlayerList().getPlayer(quest.getAcceptedBy());
                    if (p != null) {
                        MarkerManager.clearMarkers(p, quest);
                        p.sendSystemMessage(Component.literal("§c§l[TNM Quests] Quest Force Deleted by Admin: §fThe delivery cargo has been recalled."));
                    }
                    CargoAssembler.removeCargo(player.serverLevel(), quest);
                }
            }
            quests.clear();
            QuestGenerator.saveQuests();
            player.sendSystemMessage(Component.literal("§a§l[TNM Quests] Admin: Successfully deleted all " + count + " quests."));
            ADQEventHandler.clearActionCooldown(player, "delete_all");
        }
        resyncToAllPlayers(player.getServer());
    }

    public static void handleAdminReload(ServerPlayer player) {
        if (!player.hasPermissions(2)) {
            player.sendSystemMessage(Component.literal("§cYou do not have permission to run admin commands."));
            return;
        }
        try {
            QuestGenerator.loadQuests();
            QuestGenerator.loadCooldowns();
            player.sendSystemMessage(Component.literal("§a§l[TNM Quests] Admin: Quests and Cooldowns successfully reloaded from disk!"));
            ADQEventHandler.clearActionCooldown(player, "reload");
            resyncToAllPlayers(player.getServer());
        } catch (Exception e) {
            player.sendSystemMessage(Component.literal("§cFailed to reload quest data: " + e.getMessage()));
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

    private static void resyncQuestsToPlayer(ServerPlayer player) {
        openBoard(player);
    }
}
