package io.github.izakyl.folkways.core.rts.client;

import com.lowdragmc.lowdraglib2.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib2.gui.texture.SpriteTexture;
import io.github.izakyl.folkways.core.combat.api.Portraits;
import io.github.izakyl.folkways.core.front.api.ui.Rows;
import io.github.izakyl.folkways.core.front.ui.screen.DeskTextures;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

/** Server-confirmed shortcut and selected-unit cards; clicks submit selection or group operations. */
final class CombatCards {
    static final int HEIGHT = 60;
    private static final int CONTENT_INSET = 14;
    private static final int RED = 0xFF9A3324;
    private static final int GREEN = 0xFF557A46;
    private static final GuiTextureGroup PANEL = DeskTextures.combatPanel();
    private static final SpriteTexture BUTTON = button("button");
    private static final SpriteTexture HOVER = button("button_hover");
    private static final SpriteTexture PRESSED = button("button_pressed");

    enum Kind {
        GROUP,
        UNIT,
        PREVIOUS,
        NEXT
    }

    record Action(Kind kind, int slot, UUID resident) {
    }

    private record Hit(
        int x, int y, int width, int height, Action action, Component tooltip, SpriteTexture texture) {
        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
    }

    private final List<Hit> hits = new ArrayList<>();
    private int offset;
    private int pageSize = 1;
    private int drawnWidth;
    private int drawnHeight;

    int top() {
        return Minecraft.getInstance().getWindow().getGuiScaledHeight() - HEIGHT;
    }

    int visibleTop() {
        return top() - 3;
    }

    void reset() {
        offset = 0;
        hits.clear();
    }

    void page(boolean forward, int count) {
        offset =
            Math.clamp(
            offset + (forward ? pageSize : -pageSize), 0, Math.max(0, (count - 1) / pageSize * pageSize));
    }

    Action clicked(double x, double y) {
        var window = Minecraft.getInstance().getWindow();
        if (drawnWidth != window.getGuiScaledWidth() || drawnHeight != window.getGuiScaledHeight()) {
            return null;
        }
        return hits.stream().filter(hit -> hit.contains(x, y)).map(Hit::action).findFirst().orElse(null);
    }

    void render(
        GuiGraphics graphics,
        int mouseX,
        int mouseY,
        Set<UUID> selected,
        Map<UUID, LivingEntity> residents,
        Map<Integer, List<UUID>> groups,
        int points,
        Component mode,
        Component status,
        Component failure,
        boolean routeFailure) {
        Minecraft minecraft = Minecraft.getInstance();
        drawnWidth = minecraft.getWindow().getGuiScaledWidth();
        drawnHeight = minecraft.getWindow().getGuiScaledHeight();
        int top = top();
        hits.clear();
        // LDLib sprites are buffered; finish them before scissoring text or drawing vanilla faces.
        PANEL.draw(graphics, mouseX, mouseY, 4, visibleTop(), drawnWidth - 8, HEIGHT - 1, 0);
        graphics.flush();
        Component fullStatus = points > 0
            ? Component.translatable("folkways.rts.status_with_route", status, points) : status;
        Component visibleStatus = failure == null ? status : failure;
        int stateColor = failure != null || routeFailure ? RED : Rows.INK;
        int statusLeft = CONTENT_INSET;
        int statusRight = Math.max(statusLeft, drawnWidth - CONTENT_INSET);
        int statusY = top + 17;
        graphics.drawString(
            minecraft.font, Component.translatable("folkways.rts.input_mode", mode),
            statusLeft, top + 5, Rows.INK, false);
        int statusWidth = Math.max(0, statusRight - statusLeft);
        int textWidth = minecraft.font.width(visibleStatus);
        float statusScale =
            textWidth <= statusWidth || textWidth == 0 ? 1.0F : Math.max(0.68F, statusWidth / (float) textWidth);
        graphics.enableScissor(statusLeft, statusY - 1, statusRight, statusY + minecraft.font.lineHeight);
        graphics.pose().pushPose();
        graphics.pose().translate(statusLeft, statusY, 0);
        graphics.pose().scale(statusScale, statusScale, 1.0F);
        graphics.drawString(minecraft.font, visibleStatus, 0, 0, stateColor, false);
        graphics.pose().popPose();
        graphics.disableScissor();
        String selectedCount = Integer.toString(selected.size());
        graphics.drawString(
            minecraft.font,
            selectedCount,
            drawnWidth - CONTENT_INSET - minecraft.font.width(selectedCount),
            top + 5,
            Rows.INK,
            false);
        if (mouseY >= statusY - 1 && mouseY < statusY + minecraft.font.lineHeight
            && mouseX >= statusLeft && mouseX < drawnWidth - CONTENT_INSET) {
            List<Component> tooltip = new ArrayList<>();
            if (failure != null) {
                tooltip.add(failure);
            }
            tooltip.add(fullStatus);
            tooltip.add(Component.translatable("folkways.rts.rest_note"));
            tooltip.add(Component.translatable("folkways.rts.suspend_note"));
            tooltip.add(Component.translatable("folkways.rts.help"));
            graphics.renderTooltip(minecraft.font, tooltip, Optional.empty(), ItemStack.EMPTY, mouseX, mouseY);
        }

        // Reserve three centered portraits and both arrows at the target small-window width.
        int minimumUnitRow = 3 * 22 + 2 * 2 + 2 * 12;
        // At minimum GUI width, omit gaps so one portrait and both paging arrows still fit.
        int groupGap = drawnWidth < 380 ? 0 : 1;
        int groupWidth = Math.max(
            12, Math.min(34, (drawnWidth / 2 - CONTENT_INSET - 8 - minimumUnitRow / 2 - 8 * groupGap) / 9));
        int groupX = CONTENT_INSET;
        for (int slot = 1; slot <= 9; slot++) {
            List<UUID> members = groups.getOrDefault(slot, List.of());
            long available = members.stream().filter(residents::containsKey).count();
            boolean chosen = !selected.isEmpty() && selected.equals(Set.copyOf(members));
            int x = groupX + (slot - 1) * (groupWidth + groupGap), y = top + 30;
            boolean hovered = mouseX >= x && mouseX < x + groupWidth && mouseY >= y && mouseY < y + 16;
            SpriteTexture texture = chosen ? PRESSED : hovered ? HOVER : BUTTON;
            Hit hit = new Hit(
                x, y, groupWidth, 16, new Action(Kind.GROUP, slot, null),
                Component.translatable("folkways.rts.group_card", slot, available, members.size()), texture);
            hit.texture().draw(graphics, mouseX, mouseY, x, y, groupWidth, 16, 0);
            graphics.flush();
            graphics.drawString(
                minecraft.font, Integer.toString(slot), x + 4, y + 4,
                Rows.INK, false);
            LivingEntity representative =
                members.stream().map(residents::get).filter(Objects::nonNull).findFirst().orElse(null);
            if (representative != null && groupWidth >= 28) {
                face(graphics, representative, x + 11, y + 2, 12);
            }
            if (!members.isEmpty() && groupWidth >= 30) {
                String count = Long.toString(available);
                graphics.drawString(
                    minecraft.font, count, x + groupWidth - minecraft.font.width(count) - 2, y + 4, Rows.INK, false);
            }
            hits.add(hit);
        }

        List<UUID> people = List.copyOf(selected);
        int unitY = top + 28;
        int groupRight = groupX + 9 * groupWidth + 8 * groupGap;
        int unitWidth = Math.max(0, drawnWidth - 2 * (groupRight + 8));
        boolean showUnitCards = unitWidth >= 22;
        int arrowWidth = people.size() > 1 && unitWidth >= 50 ? 12 : 0;
        int unitCardWidth = 22, unitGap = 2;
        pageSize = showUnitCards ? Math.max(1, (unitWidth - arrowWidth * 2 + unitGap) / (unitCardWidth + unitGap)) : 1;
        pageSize = Math.min(12, pageSize);
        offset = Math.clamp(offset, 0, Math.max(0, (people.size() - 1) / pageSize * pageSize));
        int count = showUnitCards ? Math.min(pageSize, people.size() - offset) : 0;
        int rowWidth = count * (unitCardWidth + unitGap) - (count == 0 ? 0 : unitGap);
        int cardsLeft = (drawnWidth - rowWidth) / 2;
        for (int index = 0; index < count; index++) {
            UUID id = people.get(offset + index);
            LivingEntity resident = residents.get(id);
            int x = cardsLeft + index * (unitCardWidth + unitGap), y = unitY;
            boolean hovered = mouseX >= x && mouseX < x + unitCardWidth && mouseY >= y && mouseY < y + 20;
            SpriteTexture texture = hovered ? HOVER : BUTTON;
            texture.draw(graphics, mouseX, mouseY, x, y, unitCardWidth, 20, 0);
            graphics.flush();
            if (resident != null) {
                face(graphics, resident, x + 4, y + 2, 14);
                int health =
                    Math.round(14 * Mth.clamp(resident.getHealth() / Math.max(1, resident.getMaxHealth()), 0, 1));
                int healthColor =
                    resident.getHealth() / Math.max(1, resident.getMaxHealth()) > .5F ? GREEN : RED;
                graphics.fill(x + 4, y + 16, x + 18, y + 18, 0xFFC4AD7E);
                graphics.fill(x + 4, y + 16, x + 4 + health, y + 18, healthColor);
                hits.add(
                    new Hit(
                    x,
                    y,
                    unitCardWidth,
                    20,
                    new Action(Kind.UNIT, 0, id),
                    Component.translatable(
                    "folkways.rts.unit_card",
                    resident.getDisplayName(),
                    Mth.ceil(resident.getHealth()),
                    Mth.ceil(resident.getMaxHealth())),
                    texture));
            }
        }
        if (arrowWidth > 0) {
            arrow(graphics, cardsLeft - arrowWidth, unitY, false);
            arrow(graphics, cardsLeft + rowWidth, unitY, true);
        }
        hits.stream()
            .filter(hit -> hit.contains(mouseX, mouseY))
            .findFirst()
            .ifPresent(
            hit ->
                graphics.renderTooltip(
                    minecraft.font, hit.tooltip(), mouseX, Math.min(mouseY, top - 2)));
    }

    private static SpriteTexture button(String name) {
        return SpriteTexture.of("folkways_core:textures/gui/desk/" + name + ".png")
            .setSprite(0, 0, 16, 16).setBorder(4);
    }

    private void arrow(GuiGraphics graphics, int x, int y, boolean forward) {
        BUTTON.draw(graphics, 0, 0, x, y, 12, 19, 0);
        graphics.flush();
        var font = Minecraft.getInstance().font;
        String label = forward ? ">" : "<";
        graphics.drawString(font, label, x + (12 - font.width(label)) / 2, y + 5, Rows.INK, false);
        hits.add(
            new Hit(
            x,
            y,
            12,
            19,
            new Action(forward ? Kind.NEXT : Kind.PREVIOUS, 0, null),
            Component.translatable(forward ? "folkways.rts.next_cards" : "folkways.rts.previous_cards"),
            BUTTON));
    }

    private static void face(GuiGraphics graphics, LivingEntity resident, int x, int y, int size) {
        Portraits.draw(graphics, resident, x, y, size);
    }
}
