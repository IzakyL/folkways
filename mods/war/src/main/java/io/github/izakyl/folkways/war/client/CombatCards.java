package io.github.izakyl.folkways.war.client;

import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import io.github.izakyl.folkways.plugins.person.look.ResidentLooks;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

/** Server-confirmed shortcut and selected-unit cards; clicks submit selection or group operations. */
final class CombatCards {
    static final int HEIGHT = 60;
    private static final int GREEN = 0xFF8FEAC3;
    private static final int TEXT = 0xFFF0EEE6;
    private static final int GOLD = 0xFFFFDC7A;
    enum Kind { GROUP, UNIT, PREVIOUS, NEXT, ATTACK_MOVE, STOP, RELEASE }
    record Action(Kind kind, int slot, UUID resident) { }
    private record Hit(int x, int y, int width, int height, Action action, Component tooltip) {
        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
    }
    private final List<Hit> hits = new ArrayList<>();
    private int offset;
    private int pageSize = 1;
    private int drawnWidth;
    private int drawnHeight;

    int top() { return Minecraft.getInstance().getWindow().getGuiScaledHeight() - HEIGHT; }
    void reset() { offset = 0; hits.clear(); }
    void page(boolean forward, int count) {
        offset = Math.clamp(offset + (forward ? pageSize : -pageSize), 0, Math.max(0, (count - 1) / pageSize * pageSize));
    }
    Action clicked(double x, double y) {
        var window = Minecraft.getInstance().getWindow();
        if (drawnWidth != window.getGuiScaledWidth() || drawnHeight != window.getGuiScaledHeight()) { return null; }
        return hits.stream().filter(hit -> hit.contains(x, y)).map(Hit::action).findFirst().orElse(null);
    }

    void render(GuiGraphics graphics, int mouseX, int mouseY, Set<UUID> selected,
            Map<UUID, ResidentEntity> residents, Map<Integer, List<UUID>> groups, int points,
            Component status, Component notice, boolean attackMoveArmed, boolean routeFailure) {
        Minecraft minecraft = Minecraft.getInstance();
        drawnWidth = minecraft.getWindow().getGuiScaledWidth();
        drawnHeight = minecraft.getWindow().getGuiScaledHeight();
        int top = top();
        hits.clear();
        graphics.fill(4, top, drawnWidth - 4, drawnHeight - 4, 0xCB101A17);
        graphics.fill(4, top, drawnWidth - 4, top + 1, 0xFF557A68);
        Component visibleStatus = notice.getString().isEmpty() ? status : notice;
        Component fullStatus = visibleStatus;
        if (points > 0 && notice.getString().isEmpty()) {
            fullStatus = Component.translatable("folkways.war.status_with_route", visibleStatus, points);
        }
        int stateColor = routeFailure ? 0xFFFF8F82 : notice.getString().isEmpty() ? 0xFFB7C8BC : GOLD;
        int countWidth = minecraft.font.width(Integer.toString(selected.size())) + 8;
        int statusLeft = 9;
        int statusRight = Math.max(statusLeft, drawnWidth - 9 - countWidth);
        int statusY = top + 4;
        int statusWidth = Math.max(0, statusRight - statusLeft);
        int textWidth = minecraft.font.width(visibleStatus);
        float statusScale = textWidth <= statusWidth || textWidth == 0 ? 1.0F : Math.max(0.68F, statusWidth / (float) textWidth);
        graphics.enableScissor(statusLeft, top + 2, statusRight, top + 14);
        graphics.pose().pushPose();
        graphics.pose().translate(statusLeft, statusY, 0);
        graphics.pose().scale(statusScale, statusScale, 1.0F);
        graphics.drawString(minecraft.font, visibleStatus, 0, 0, stateColor, true);
        graphics.pose().popPose();
        graphics.disableScissor();
        String selectedCount = Integer.toString(selected.size());
        graphics.drawString(minecraft.font, selectedCount, drawnWidth - 9 - minecraft.font.width(selectedCount), statusY, GREEN, true);
        if (mouseY >= top + 2 && mouseY < top + 14 && mouseX >= 8 && mouseX < drawnWidth - 8) {
            List<Component> tooltip = new ArrayList<>();
            tooltip.add(fullStatus);
            if (!notice.getString().isEmpty()) { tooltip.add(status); }
            tooltip.add(Component.translatable("folkways.war.rest_note"));
            tooltip.add(Component.translatable("folkways.war.help"));
            graphics.renderTooltip(minecraft.font, tooltip, Optional.empty(), ItemStack.EMPTY, mouseX, mouseY);
        }

        int contentWidth = Math.max(1, drawnWidth - 16);
        int groupWidth = Math.max(12, Math.min(34, (contentWidth - 8) / 9));
        int groupGap = 1;
        int groupRowWidth = 9 * groupWidth + 8 * groupGap;
        int groupX = (drawnWidth - groupRowWidth) / 2;
        for (int slot = 1; slot <= 9; slot++) {
            List<UUID> members = groups.getOrDefault(slot, List.of());
            long available = members.stream().filter(residents::containsKey).count();
            boolean chosen = !members.isEmpty() && selected.equals(Set.copyOf(members.stream().filter(residents::containsKey).toList()));
            int x = groupX + (slot - 1) * (groupWidth + groupGap), y = top + 15;
            graphics.fill(x, y, x + groupWidth, y + 16, chosen ? 0xFF315943 : members.isEmpty() ? 0xA51C2924 : 0xD01C2924);
            graphics.renderOutline(x, y, groupWidth, 16, chosen ? GREEN : members.isEmpty() ? 0xFF3B4B43 : 0xFF577064);
            graphics.drawString(minecraft.font, Integer.toString(slot), x + 3, y + 4, members.isEmpty() ? 0xFF789084 : GOLD, true);
            ResidentEntity representative = members.stream().map(residents::get).filter(java.util.Objects::nonNull).findFirst().orElse(null);
            if (representative != null && groupWidth >= 28) { face(graphics, representative, x + 11, y + 2, 12); }
            if (!members.isEmpty() && groupWidth >= 30) {
                String count = Long.toString(available);
                graphics.drawString(minecraft.font, count, x + groupWidth - minecraft.font.width(count) - 2, y + 4, TEXT, true);
            }
            hits.add(new Hit(x, y, groupWidth, 16, new Action(Kind.GROUP, slot, null),
                Component.translatable("folkways.war.group_card", slot, available, members.size())));
        }

        List<UUID> people = List.copyOf(selected);
        int buttonY = top + 36, buttonGap = 3;
        Component attackLabel = Component.translatable("folkways.war.attack_move.short");
        Component stopLabel = Component.translatable("folkways.war.stop");
        Component releaseLabel = Component.translatable("folkways.war.release_control.short");
        int attackWidth = Math.max(48, minecraft.font.width(attackLabel) + 8);
        int stopWidth = Math.max(40, minecraft.font.width(stopLabel) + 8);
        int releaseWidth = Math.max(52, minecraft.font.width(releaseLabel) + 8);
        int totalWidth = attackWidth + stopWidth + releaseWidth + buttonGap * 2;
        int buttonX = drawnWidth - 8 - totalWidth;
        if (buttonX < 8) {
            double fit = (drawnWidth - 16.0 - buttonGap * 2) / (attackWidth + stopWidth + releaseWidth);
            attackWidth = Math.max(38, (int) (attackWidth * fit));
            stopWidth = Math.max(34, (int) (stopWidth * fit));
            releaseWidth = Math.max(42, (int) (releaseWidth * fit));
            totalWidth = attackWidth + stopWidth + releaseWidth + buttonGap * 2;
            buttonX = drawnWidth - 8 - totalWidth;
        }
        int unitLeft = 8, unitRight = Math.max(unitLeft, buttonX - 5);
        int unitWidth = unitRight - unitLeft;
        boolean showUnitCards = unitWidth >= 22;
        int arrowWidth = people.size() > 1 && unitWidth >= 50 ? 12 : 0;
        int unitCardWidth = 22, unitGap = 2;
        pageSize = showUnitCards ? Math.max(1, (unitWidth - arrowWidth * 2 + unitGap) / (unitCardWidth + unitGap)) : 1;
        pageSize = Math.min(12, pageSize);
        offset = Math.clamp(offset, 0, Math.max(0, (people.size() - 1) / pageSize * pageSize));
        int count = showUnitCards ? Math.min(pageSize, people.size() - offset) : 0;
        int cardsLeft = unitLeft + arrowWidth;
        int unitY = buttonY - 1;
        for (int index = 0; index < count; index++) {
            UUID id = people.get(offset + index);
            ResidentEntity resident = residents.get(id);
            int x = cardsLeft + index * (unitCardWidth + unitGap), y = unitY;
            graphics.fill(x, y, x + unitCardWidth, y + 20, 0xFF273D32);
            graphics.renderOutline(x, y, unitCardWidth, 20, GREEN);
            if (resident != null) {
                face(graphics, resident, x + 2, y + 1, 16);
                int health = Math.round(18 * Mth.clamp(resident.getHealth() / Math.max(1, resident.getMaxHealth()), 0, 1));
                int healthColor = resident.getHealth() / Math.max(1, resident.getMaxHealth()) > .5F ? GREEN : 0xFFFF776E;
                graphics.fill(x + 2, y + 17, x + 20, y + 19, 0xFF592F2D);
                graphics.fill(x + 2, y + 17, x + 2 + health, y + 19, healthColor);
                hits.add(new Hit(x, y, unitCardWidth, 20, new Action(Kind.UNIT, 0, id),
                    Component.translatable("folkways.war.unit_card", resident.getDisplayName(),
                        Mth.ceil(resident.getHealth()), Mth.ceil(resident.getMaxHealth()))));
            }
        }
        if (arrowWidth > 0) {
            arrow(graphics, unitLeft, buttonY, false);
            arrow(graphics, unitRight - arrowWidth, buttonY, true);
        }
        button(graphics, buttonX, buttonY, attackWidth, 19,
            attackLabel, attackMoveArmed, true,
            new Action(Kind.ATTACK_MOVE, 0, null), Component.translatable("folkways.war.attack_move.tooltip"));
        buttonX += attackWidth + buttonGap;
        button(graphics, buttonX, buttonY, stopWidth, 19,
            stopLabel, false, !selected.isEmpty(),
            new Action(Kind.STOP, 0, null), Component.translatable("folkways.war.stop.tooltip"));
        buttonX += stopWidth + buttonGap;
        button(graphics, buttonX, buttonY, releaseWidth, 19,
            releaseLabel, false, !selected.isEmpty(),
            new Action(Kind.RELEASE, 0, null), Component.translatable("folkways.war.release_control.tooltip"));
        hits.stream().filter(hit -> hit.contains(mouseX, mouseY)).findFirst().ifPresent(hit ->
            graphics.renderTooltip(minecraft.font, hit.tooltip(), mouseX, Math.min(mouseY, top - 2)));
    }

    private void button(GuiGraphics graphics, int x, int y, int width, int height, Component label,
            boolean selected, boolean enabled, Action action, Component tooltip) {
        int fill = !enabled ? 0xB01C2420 : selected ? 0xFF315943 : 0xE0283931;
        int edge = !enabled ? 0xFF45544C : selected ? GREEN : 0xFF718C7D;
        int color = !enabled ? 0xFF849087 : selected ? GOLD : TEXT;
        graphics.fill(x, y, x + width, y + height, fill);
        graphics.renderOutline(x, y, width, height, edge);
        var font = Minecraft.getInstance().font;
        int labelWidth = font.width(label);
        float textScale = labelWidth == 0 ? 1.0F : Math.min(1.0F, Math.max(0.5F, (width - 6.0F) / labelWidth));
        graphics.pose().pushPose();
        graphics.pose().translate(x + width / 2.0F, y + Math.max(3, (height - 9) / 2), 0);
        graphics.pose().scale(textScale, textScale, 1.0F);
        graphics.drawCenteredString(font, label, 0, 0, color);
        graphics.pose().popPose();
        hits.add(new Hit(x, y, width, height, action, tooltip));
    }

    private void arrow(GuiGraphics graphics, int x, int y, boolean forward) {
        graphics.fill(x, y, x + 12, y + 19, 0xFF315943);
        graphics.drawCenteredString(Minecraft.getInstance().font, forward ? ">" : "<", x + 6, y + 5, TEXT);
        hits.add(new Hit(x, y, 12, 19, new Action(forward ? Kind.NEXT : Kind.PREVIOUS, 0, null),
            Component.translatable(forward ? "folkways.war.next_cards" : "folkways.war.previous_cards")));
    }

    private static void face(GuiGraphics graphics, ResidentEntity resident, int x, int y, int size) {
        PlayerFaceRenderer.draw(graphics, ResidentLooks.of(resident).appearance().skin().texture(), x, y, size);
    }
}
