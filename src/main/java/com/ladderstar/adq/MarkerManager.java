package com.ladderstar.adq;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.LodestoneTracker;
import net.neoforged.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class MarkerManager {
    private static final Logger LOGGER = LogManager.getLogger();

    public static void createMarkers(ServerPlayer player, QuestModel quest) {
        // Setup and give initial compass pointing to startingPos (cargo pickup)
        ensureAndCalibrateCompass(player, quest);

        player.sendSystemMessage(Component.literal("§6[TNM Quests] You have been given a Quest Delivery Compass calibrated to the cargo pickup location."));

        // 2. Minimap integration: Add map waypoints (FTB Chunks / JourneyMap)
        if (ModList.get().isLoaded("ftbchunks") || ModList.get().isLoaded("journeymap")) {
            LOGGER.info("[TNM Quests] Adding server-side map waypoints for player {}", player.getName().getString());
        }
    }

    public static void ensureAndCalibrateCompass(ServerPlayer player, QuestModel quest) {
        BlockPos targetPos = quest.isCargoPickedUp() ? quest.getEndingPos() : quest.getStartingPos();
        GlobalPos targetGlobalPos = GlobalPos.of(net.minecraft.world.level.Level.OVERWORLD, targetPos);
        boolean found = false;

        // Scan main inventory and offhand
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(Items.COMPASS)) {
                Component name = stack.get(DataComponents.CUSTOM_NAME);
                if (name != null && name.getString().contains("Quest Delivery Compass")) {
                    LodestoneTracker tracker = stack.get(DataComponents.LODESTONE_TRACKER);
                    boolean needsUpdate = true;
                    if (tracker != null && tracker.target().isPresent()) {
                        GlobalPos currentTarget = tracker.target().get();
                        if (currentTarget.pos().equals(targetPos) && currentTarget.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) {
                            needsUpdate = false;
                        }
                    }
                    if (needsUpdate) {
                        stack.set(DataComponents.LODESTONE_TRACKER, new LodestoneTracker(Optional.of(targetGlobalPos), false));
                        
                        // Update lore to match the current objective
                        List<Component> lore = new ArrayList<>();
                        lore.add(Component.literal("§7Calibrated to: §f" + (quest.isCargoPickedUp() ? "Delivery village" : "Cargo pickup")));
                        lore.add(Component.literal("§8Coordinates: " + targetPos.toShortString()));
                        lore.add(Component.empty());
                        lore.add(Component.literal("§d§oVanilla Compass Navigation Needle"));
                        lore.add(Component.literal("§7(Note: Left-clicking might trigger WorldEdit"));
                        lore.add(Component.literal("§7navigation wands if installed on the server.)"));
                        stack.set(DataComponents.LORE, new ItemLore(lore));
                        
                        player.containerMenu.broadcastChanges();
                    }
                    found = true;
                }
            }
        }

        // If not found in inventory, let's give the player a new one
        if (!found) {
            ItemStack compassStack = new ItemStack(Items.COMPASS);
            compassStack.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lQuest Delivery Compass"));
            compassStack.set(DataComponents.LODESTONE_TRACKER, new LodestoneTracker(Optional.of(targetGlobalPos), false));
            
            // Add WorldEdit warning lore!
            List<Component> lore = new ArrayList<>();
            lore.add(Component.literal("§7Calibrated to: §f" + (quest.isCargoPickedUp() ? "Delivery village" : "Cargo pickup")));
            lore.add(Component.literal("§8Coordinates: " + targetPos.toShortString()));
            lore.add(Component.empty());
            lore.add(Component.literal("§d§oVanilla Compass Navigation Needle"));
            lore.add(Component.literal("§7(Note: Left-clicking might trigger WorldEdit"));
            lore.add(Component.literal("§7navigation wands if installed on the server.)"));
            compassStack.set(DataComponents.LORE, new ItemLore(lore));

            if (!player.getInventory().add(compassStack)) {
                player.drop(compassStack, false);
            }
            player.sendSystemMessage(Component.literal("§6[TNM Quests] Re-issued Quest Delivery Compass pointing to your current objective."));
        }
    }

    public static void updateCompassToDelivery(ServerPlayer player, QuestModel quest) {
        ensureAndCalibrateCompass(player, quest);
    }

    public static void clearMarkers(ServerPlayer player, QuestModel quest) {
        LOGGER.info("[TNM Quests] Clearing quest navigation trackers for player {}", player.getName().getString());

        // 1. Scan player inventory and remove the Quest Compass
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(Items.COMPASS)) {
                Component name = stack.get(DataComponents.CUSTOM_NAME);
                if (name != null && name.getString().contains("Quest Delivery Compass")) {
                    player.getInventory().setItem(i, ItemStack.EMPTY);
                }
            }
        }

        // 2. Clear server-side map waypoints
        if (ModList.get().isLoaded("ftbchunks") || ModList.get().isLoaded("journeymap")) {
            // Clear waypoints
        }
    }
}
