package com.ladderstar.adq.client;

import com.ladderstar.adq.QuestModel;
import com.ladderstar.adq.network.ServerboundQuestActionPacket;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.gui.AllIcons;
import com.simibubi.create.foundation.gui.widget.IconButton;
import net.createmod.catnip.gui.AbstractSimiScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class QuestBoardScreen extends AbstractSimiScreen {

    private final List<QuestModel> quests = new ArrayList<>();
    private long cooldownRemainingSeconds;
    private QuestModel activeQuest;
    private long nextQuestTimerSeconds;
    private final boolean isOp;
    private boolean isGenerating;

    private int currentPage = 0;
    private int leftPos;
    private int topPos;
    private int tickCounter = 0;

    // Separate cooldown tracks for each interactive action
    private int acceptCooldownTicks = 0;
    private int cancelCooldownTicks = 0;
    private int reissueCooldownTicks = 0;
    private int generateCooldownTicks = 0;
    private int fillCooldownTicks = 0;
    private int wipeCooldownTicks = 0;
    private int reloadCooldownTicks = 0;

    public QuestBoardScreen(List<QuestModel> quests, long cooldownRemainingSeconds, long nextQuestTimerSeconds) {
        this(quests, cooldownRemainingSeconds, nextQuestTimerSeconds, false);
    }

    public QuestBoardScreen(List<QuestModel> quests, long cooldownRemainingSeconds, long nextQuestTimerSeconds, boolean isGenerating) {
        this.quests.addAll(quests);
        this.cooldownRemainingSeconds = cooldownRemainingSeconds;
        this.nextQuestTimerSeconds = nextQuestTimerSeconds;
        this.isGenerating = isGenerating;

        // Find player's active quest
        QuestModel active = null;
        net.minecraft.client.player.LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            UUID playerUuid = player.getUUID();
            for (QuestModel quest : quests) {
                if (playerUuid.equals(quest.getAcceptedBy()) && !quest.isCompleted()) {
                    active = quest;
                    break;
                }
            }
            this.isOp = player.hasPermissions(2);
        } else {
            this.isOp = false;
        }
        this.activeQuest = active;
    }

    public void updateQuests(List<QuestModel> newQuests, long cooldownRemainingSeconds, long nextQuestTimerSeconds) {
        this.updateQuests(newQuests, cooldownRemainingSeconds, nextQuestTimerSeconds, false);
    }

    public void updateQuests(List<QuestModel> newQuests, long cooldownRemainingSeconds, long nextQuestTimerSeconds, boolean isGenerating) {
        this.quests.clear();
        this.quests.addAll(newQuests);
        this.cooldownRemainingSeconds = cooldownRemainingSeconds;
        this.nextQuestTimerSeconds = nextQuestTimerSeconds;
        this.isGenerating = isGenerating;
        this.tickCounter = 0;

        // Server sync confirms success of the last action. Reset all cooldowns.
        this.acceptCooldownTicks = 0;
        this.cancelCooldownTicks = 0;
        this.reissueCooldownTicks = 0;
        this.generateCooldownTicks = 0;
        this.fillCooldownTicks = 0;
        this.wipeCooldownTicks = 0;
        this.reloadCooldownTicks = 0;

        // Find player's active quest
        QuestModel active = null;
        net.minecraft.client.player.LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            UUID playerUuid = player.getUUID();
            for (QuestModel quest : this.quests) {
                if (playerUuid.equals(quest.getAcceptedBy()) && !quest.isCompleted()) {
                    active = quest;
                    break;
                }
            }
        }
        this.activeQuest = active;
        this.refreshWidgets();
    }

    @Override
    public void tick() {
        super.tick();
        boolean refreshNeeded = false;
        if (acceptCooldownTicks > 0) { acceptCooldownTicks--; if (acceptCooldownTicks == 0) refreshNeeded = true; }
        if (cancelCooldownTicks > 0) { cancelCooldownTicks--; if (cancelCooldownTicks == 0) refreshNeeded = true; }
        if (reissueCooldownTicks > 0) { reissueCooldownTicks--; if (reissueCooldownTicks == 0) refreshNeeded = true; }
        if (generateCooldownTicks > 0) { generateCooldownTicks--; if (generateCooldownTicks == 0) refreshNeeded = true; }
        if (fillCooldownTicks > 0) { fillCooldownTicks--; if (fillCooldownTicks == 0) refreshNeeded = true; }
        if (wipeCooldownTicks > 0) { wipeCooldownTicks--; if (wipeCooldownTicks == 0) refreshNeeded = true; }
        if (reloadCooldownTicks > 0) { reloadCooldownTicks--; if (reloadCooldownTicks == 0) refreshNeeded = true; }

        if (refreshNeeded) {
            this.refreshWidgets();
        }

        tickCounter++;
        if (tickCounter >= 20) {
            tickCounter = 0;
            boolean cooldownChanged = false;
            if (nextQuestTimerSeconds > 0) {
                nextQuestTimerSeconds--;
            }
            if (cooldownRemainingSeconds > 0) {
                cooldownRemainingSeconds--;
                cooldownChanged = true;
                if (cooldownRemainingSeconds == 0) {
                    cooldownChanged = true;
                }
            }
            if (cooldownChanged) {
                this.refreshWidgets();
            }
        }
    }

    @Override
    protected void init() {
        super.init();
        this.leftPos = (this.width - AllGuiTextures.CLIPBOARD.getWidth()) / 2;
        this.topPos = (this.height - AllGuiTextures.CLIPBOARD.getHeight()) / 2;
        refreshWidgets();
    }

    private void refreshWidgets() {
        this.clearWidgets();

        if (this.activeQuest != null) {
            // Abandon Contract button (Mechanical Stop Icon)
            IconButton abandonBtn = new IconButton(leftPos + 85, topPos + 195, AllIcons.I_STOP);
            abandonBtn.setToolTip(Component.literal("Abandon Contract"));
            abandonBtn.setActive(cancelCooldownTicks == 0);
            this.addRenderableWidget(abandonBtn.withCallback(() -> {
                this.cancelCooldownTicks = 100; // 5 seconds
                this.refreshWidgets();
                sendPacket(new ServerboundQuestActionPacket(ServerboundQuestActionPacket.Action.CANCEL, null));
            }));

            // Reissue Compass button (Mechanical Reset/Refresh Icon)
            IconButton reissueBtn = new IconButton(leftPos + 135, topPos + 195, AllIcons.I_CONFIG_RESET);
            reissueBtn.setToolTip(Component.literal("Reissue Compass"));
            reissueBtn.setActive(reissueCooldownTicks == 0);
            this.addRenderableWidget(reissueBtn.withCallback(() -> {
                this.reissueCooldownTicks = 100; // 5 seconds
                this.refreshWidgets();
                sendPacket(new ServerboundQuestActionPacket(ServerboundQuestActionPacket.Action.REISSUE, null));
            }));
        } else {
            // Available quests logic (2 per page)
            int totalQuests = quests.size();
            int maxPages = Math.max(1, (int) Math.ceil(totalQuests / 2.0));
            if (currentPage >= maxPages) {
                currentPage = maxPages - 1;
            }
            if (currentPage < 0) currentPage = 0;

            // Page navigation buttons using Create configuration arrow icons
            if (currentPage > 0) {
                IconButton prevBtn = new IconButton(leftPos + 25, topPos + 220, AllIcons.I_CONFIG_PREV);
                prevBtn.setToolTip(Component.literal("Previous Page"));
                this.addRenderableWidget(prevBtn.withCallback(() -> {
                    currentPage--;
                    refreshWidgets();
                }));
            }

            if (currentPage < maxPages - 1) {
                IconButton nextBtn = new IconButton(leftPos + 195, topPos + 220, AllIcons.I_CONFIG_NEXT);
                nextBtn.setToolTip(Component.literal("Next Page"));
                this.addRenderableWidget(nextBtn.withCallback(() -> {
                    currentPage++;
                    refreshWidgets();
                }));
            }

            // Quests Accept buttons (Glowing Green Play Buttons)
            int startIdx = currentPage * 2;
            for (int i = 0; i < 2; i++) {
                int questIdx = startIdx + i;
                if (questIdx < totalQuests) {
                    QuestModel quest = quests.get(questIdx);
                    boolean canAccept = quest.getAcceptedBy() == null && (cooldownRemainingSeconds == 0 || this.isOp);

                    int btnY = topPos + 74 + (i * 68);
                    IconButton acceptBtn = new IconButton(leftPos + 175, btnY, AllIcons.I_PLAY);
                    acceptBtn.green = true;
                    acceptBtn.setToolTip(Component.literal("Accept Contract"));
                    acceptBtn.setActive(canAccept && acceptCooldownTicks == 0);
                    this.addRenderableWidget(acceptBtn.withCallback(() -> {
                        this.acceptCooldownTicks = 100; // 5 seconds
                        this.refreshWidgets();
                        sendPacket(new ServerboundQuestActionPacket(ServerboundQuestActionPacket.Action.ACCEPT, quest.getQuestId()));
                    }));
                }
            }

            // Flight Manual button (Train Schedule Scroll Icon)
            IconButton manualBtn = new IconButton(leftPos + 113, topPos + 195, AllIcons.I_VIEW_SCHEDULE);
            manualBtn.setToolTip(Component.literal("Flight Manual"));
            this.addRenderableWidget(manualBtn.withCallback(() -> {
                Minecraft.getInstance().setScreen(new FlightManualScreen(quests, cooldownRemainingSeconds, nextQuestTimerSeconds));
            }));
        }

        // Render admin buttons at the bottom of the screen (Compact cluster of icons)
        if (this.isOp) {
            int adminY = topPos + AllGuiTextures.CLIPBOARD.getHeight() + 10;
            
            // Admin: Generate Contract (Play Icon)
            IconButton adminGen = new IconButton(leftPos + 65, adminY, AllIcons.I_PLAY);
            adminGen.setToolTip(Component.literal("Admin: Generate Contract"));
            adminGen.setActive(!this.isGenerating && generateCooldownTicks == 0);
            this.addRenderableWidget(adminGen.withCallback(() -> {
                this.generateCooldownTicks = 100; // 5 seconds
                this.refreshWidgets();
                sendPacket(new ServerboundQuestActionPacket(ServerboundQuestActionPacket.Action.GENERATE, null));
            }));

            // Admin: Fill Contracts (Roller Fill Icon)
            IconButton adminFill = new IconButton(leftPos + 95, adminY, AllIcons.I_ROLLER_FILL);
            adminFill.setToolTip(Component.literal("Admin: Fill Contracts"));
            adminFill.setActive(fillCooldownTicks == 0);
            this.addRenderableWidget(adminFill.withCallback(() -> {
                this.fillCooldownTicks = 100; // 5 seconds
                this.refreshWidgets();
                sendPacket(new ServerboundQuestActionPacket(ServerboundQuestActionPacket.Action.FILL, null));
            }));

            // Admin: Wipe All Quests (Trash Discard Icon)
            IconButton adminWipe = new IconButton(leftPos + 125, adminY, AllIcons.I_CONFIG_DISCARD);
            adminWipe.setToolTip(Component.literal("Admin: Wipe All Quests"));
            adminWipe.setActive(wipeCooldownTicks == 0);
            this.addRenderableWidget(adminWipe.withCallback(() -> {
                this.wipeCooldownTicks = 100; // 5 seconds
                this.refreshWidgets();
                sendPacket(new ServerboundQuestActionPacket(ServerboundQuestActionPacket.Action.DELETE_ALL, null));
            }));

            // Admin: Reload Data (Reset Arrow Icon)
            IconButton adminReload = new IconButton(leftPos + 155, adminY, AllIcons.I_CONFIG_RESET);
            adminReload.setToolTip(Component.literal("Admin: Reload Data"));
            adminReload.setActive(reloadCooldownTicks == 0);
            this.addRenderableWidget(adminReload.withCallback(() -> {
                this.reloadCooldownTicks = 100; // 5 seconds
                this.refreshWidgets();
                sendPacket(new ServerboundQuestActionPacket(ServerboundQuestActionPacket.Action.RELOAD, null));
            }));
        }
    }

    private void sendPacket(ServerboundQuestActionPacket packet) {
        if (Minecraft.getInstance().getConnection() != null) {
            Minecraft.getInstance().getConnection().send(packet);
        }
    }

    private void drawCenteredNoShadow(GuiGraphics graphics, String text, int x, int y, int color) {
        graphics.drawString(this.font, text, x - this.font.width(text) / 2, y, color, false);
    }

    @Override
    protected void renderWindow(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        // Render the wooden clipboard texture
        AllGuiTextures.CLIPBOARD.render(graphics, leftPos, topPos);

        int inkColor = 0xFF5B453A; // Create's ink brown color
        int titleColor = 0xFF8B0000; // Deep Crimson Red for active quest warning or page titles

        // Render flight board title on paper header (Lowered to clear the clip, no shadow)
        drawCenteredNoShadow(graphics, "FLIGHT LEDGER", leftPos + 122, topPos + 28, inkColor);

        if (this.activeQuest != null) {
            // Render Active Contract header (no shadow)
            drawCenteredNoShadow(graphics, "ACTIVE CONTRACT", leftPos + 122, topPos + 40, titleColor);

            int textY = topPos + 55;
            int textX = leftPos + 42;
            int wrapWidth = 160;

            // Name (blue/brass highlighted and wrapped)
            textY = drawWrappedText(graphics, "§1" + activeQuest.getName(), textX, textY, wrapWidth, inkColor);
            textY += 4;

            // Format description split into lines for readability
            String desc = activeQuest.getDescription();
            if (desc.startsWith("Contract details: ")) {
                desc = desc.substring(18);
            }
            textY = drawWrappedText(graphics, "§8" + desc, textX, textY, wrapWidth, inkColor);
            textY += 6;

            // Separation line
            graphics.fill(leftPos + 22, textY, leftPos + 222, textY + 1, 0xFFC0B8A8);
            textY += 6;

            // Route metrics
            graphics.drawString(this.font, "§9Pickup pos: §0" + activeQuest.getStartingPos().toShortString(), textX, textY, inkColor, false);
            textY += 10;
            graphics.drawString(this.font, "§9Delivery pos: §0" + activeQuest.getEndingPos().toShortString(), textX, textY, inkColor, false);
            textY += 10;
            graphics.drawString(this.font, "§9Distance: §0" + (int) activeQuest.getDistance() + " blocks", textX, textY, inkColor, false);
            textY += 10;

            // Rewards display on active contract screen
            if (!activeQuest.getRewards().isEmpty()) {
                List<String> rewardStrings = new java.util.ArrayList<>();
                for (String rawReward : activeQuest.getRewards()) {
                    String[] parts = rawReward.split(":");
                    if (parts.length >= 2) {
                        String rName = parts[1].replace("_", " ");
                        if (!rName.isEmpty()) {
                            rName = Character.toUpperCase(rName.charAt(0)) + rName.substring(1);
                        }
                        String count = parts.length > 2 ? parts[2] : "1";
                        rewardStrings.add(rName + " x" + count);
                    }
                }
                String rewardsJoined = "§2Rewards: §0" + String.join(", ", rewardStrings);
                drawWrappedText(graphics, rewardsJoined, textX, textY, wrapWidth, inkColor);
            }
        } else {
            // Render available quests
            int totalQuests = quests.size();
            int maxPages = Math.max(1, (int) Math.ceil(totalQuests / 2.0));

            // Page text indicator (no shadow)
            String pageStr = (currentPage + 1) + " / " + maxPages;
            drawCenteredNoShadow(graphics, pageStr, leftPos + 122, topPos + 225, inkColor);

            if (cooldownRemainingSeconds > 0) {
                long mins = cooldownRemainingSeconds / 60;
                long secs = cooldownRemainingSeconds % 60;
                drawCenteredNoShadow(graphics, "Next Quest In: " + mins + "m " + secs + "s", leftPos + 122, topPos + 40, titleColor);
            } else if (nextQuestTimerSeconds > 0) {
                long mins = nextQuestTimerSeconds / 60;
                long secs = nextQuestTimerSeconds % 60;
                drawCenteredNoShadow(graphics, "Next contract in: " + mins + "m " + secs + "s", leftPos + 122, topPos + 40, inkColor);
            }

            int startIdx = currentPage * 2;
            int textX = leftPos + 42;
            int wrapWidth = 130; // Bound text horizontally to prevent Accept button overlap

            for (int i = 0; i < 2; i++) {
                int questIdx = startIdx + i;
                if (questIdx < totalQuests) {
                    QuestModel quest = quests.get(questIdx);
                    int textY = topPos + 52 + (i * 68);

                    // Draw dividing line between quests
                    if (i > 0) {
                        graphics.fill(leftPos + 22, textY - 6, leftPos + 222, textY - 5, 0xFFC0B8A8);
                    }

                    // Quest Title (wrapped to prevent spill)
                    textY = drawWrappedText(graphics, "§1" + quest.getName(), textX, textY, wrapWidth, inkColor);

                    // Mass info on its own line
                    textY = drawWrappedText(graphics, "§9Mass: §0" + (int) quest.getActualWeight() + " kpg", textX, textY, wrapWidth, inkColor);

                    // Route info on its own line
                    textY = drawWrappedText(graphics, "§9Route: §0" + (int) quest.getDistance() + " blocks", textX, textY, wrapWidth, inkColor);

                    // Payout details (all rewards)
                    if (!quest.getRewards().isEmpty()) {
                        List<String> rewardStrings = new java.util.ArrayList<>();
                        for (String rawReward : quest.getRewards()) {
                            String[] parts = rawReward.split(":");
                            if (parts.length >= 2) {
                                String rName = parts[1].replace("_", " ");
                                if (!rName.isEmpty()) {
                                    rName = Character.toUpperCase(rName.charAt(0)) + rName.substring(1);
                                }
                                String count = parts.length > 2 ? parts[2] : "1";
                                rewardStrings.add(rName + " x" + count);
                            }
                        }
                        String rewardsJoined = "§2Pay: §0" + String.join(", ", rewardStrings);
                        drawWrappedText(graphics, rewardsJoined, textX, textY, wrapWidth, inkColor);
                    }
                }
            }

            if (totalQuests == 0) {
                drawCenteredNoShadow(graphics, "No contracts posted.", leftPos + 122, topPos + 100, inkColor);
            }
        }
    }

    private int drawWrappedText(GuiGraphics graphics, String text, int x, int y, int maxWidth, int color) {
        List<String> lines = wrapText(text, maxWidth);
        for (String line : lines) {
            graphics.drawString(this.font, line, x, y, color, false);
            y += 9;
        }
        return y;
    }

    private List<String> wrapText(String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder currentLine = new StringBuilder();
        for (String word : words) {
            String testLine = currentLine.length() == 0 ? word : currentLine + " " + word;
            if (this.font.width(testLine) > maxWidth) {
                lines.add(currentLine.toString());
                currentLine = new StringBuilder(word);
            } else {
                currentLine = new StringBuilder(testLine);
            }
        }
        if (currentLine.length() > 0) {
            lines.add(currentLine.toString());
        }
        return lines;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        // Draw standard screen translucent background overlay
        this.renderBackground(graphics, mouseX, mouseY, partialTicks);
        super.render(graphics, mouseX, mouseY, partialTicks);
    }
}
