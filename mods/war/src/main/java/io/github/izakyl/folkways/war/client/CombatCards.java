package io.github.izakyl.folkways.war.client;

import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import io.github.izakyl.folkways.plugins.person.look.ResidentLooks;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/** Server-confirmed shortcut and selected-unit cards; clicks submit selection or group operations. */
final class CombatCards {
    static final int HEIGHT = 75;
    private static final int GREEN = 0xFF8FEAC3;
    private static final int TEXT = 0xFFF0EEE6;
    private static final int GOLD = 0xFFFFDC7A;
    enum Kind { GROUP, UNIT, PREVIOUS, NEXT }
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
            Map<UUID, ResidentEntity> residents, Map<Integer, List<UUID>> groups, int points, Component notice) {
        Minecraft minecraft = Minecraft.getInstance();
        drawnWidth = minecraft.getWindow().getGuiScaledWidth();
        drawnHeight = minecraft.getWindow().getGuiScaledHeight();
        int top = top();
        hits.clear();
        graphics.fill(4, top, drawnWidth - 4, drawnHeight - 4, 0xCB101A17);
        graphics.fill(4, top, drawnWidth - 4, top + 1, 0xFF557A68);
        graphics.drawString(minecraft.font, Component.translatable("folkways.war.selected", selected.size()), 9, top + 4, GREEN, true);
        if (points > 0) {
            Component queue = Component.translatable("folkways.war.route_queue", points);
            graphics.drawString(minecraft.font, queue, drawnWidth - minecraft.font.width(queue) - 9, top + 4, GOLD, true);
        }

        int groupWidth = Math.min(54, (drawnWidth - 18) / 9 - 2);
        int groupX = (drawnWidth - 9 * (groupWidth + 2) + 2) / 2;
        for (int slot = 1; slot <= 9; slot++) {
            List<UUID> members = groups.getOrDefault(slot, List.of());
            long available = members.stream().filter(residents::containsKey).count();
            boolean chosen = !members.isEmpty() && selected.equals(Set.copyOf(members.stream().filter(residents::containsKey).toList()));
            int x = groupX + (slot - 1) * (groupWidth + 2), y = top + 16;
            graphics.fill(x, y, x + groupWidth, y + 19, chosen ? 0xFF315943 : 0xD01C2924);
            graphics.renderOutline(x, y, groupWidth, 19, chosen ? GREEN : 0xFF577064);
            graphics.drawString(minecraft.font, Integer.toString(slot), x + 3, y + 3, members.isEmpty() ? 0xFF789084 : GOLD, true);
            ResidentEntity representative = members.stream().map(residents::get).filter(java.util.Objects::nonNull).findFirst().orElse(null);
            if (representative != null && groupWidth >= 34) { face(graphics, representative, x + 12, y + 3, 12); }
            if (!members.isEmpty()) {
                String count = Long.toString(available);
                graphics.drawString(minecraft.font, count, x + groupWidth - minecraft.font.width(count) - 3, y + 8, TEXT, true);
            }
            hits.add(new Hit(x, y, groupWidth, 19, new Action(Kind.GROUP, slot, null),
                Component.translatable("folkways.war.group_card", slot, available, members.size())));
        }

        List<UUID> people = List.copyOf(selected);
        pageSize = Math.max(1, Math.min(12, (drawnWidth - 58) / 29));
        offset = Math.clamp(offset, 0, Math.max(0, (people.size() - 1) / pageSize * pageSize));
        int count = Math.min(pageSize, people.size() - offset);
        int startX = (drawnWidth - count * 29 + 2) / 2;
        for (int index = 0; index < count; index++) {
            UUID id = people.get(offset + index);
            ResidentEntity resident = residents.get(id);
            int x = startX + index * 29, y = top + 38;
            graphics.fill(x, y, x + 27, y + 25, 0xFF273D32);
            graphics.renderOutline(x, y, 27, 25, GREEN);
            if (resident != null) {
                // Skin faces also work for custom geometry's declared fallback, without rerendering each entity.
                face(graphics, resident, x + 5, y + 2, 17);
                int health = Math.round(23 * Mth.clamp(resident.getHealth() / Math.max(1, resident.getMaxHealth()), 0, 1));
                graphics.fill(x + 2, y + 21, x + 25, y + 23, 0xFF592F2D);
                graphics.fill(x + 2, y + 21, x + 2 + health, y + 23, GREEN);
                hits.add(new Hit(x, y, 27, 25, new Action(Kind.UNIT, 0, id),
                    Component.translatable("folkways.war.unit_card", resident.getDisplayName(),
                        Mth.ceil(resident.getHealth()), Mth.ceil(resident.getMaxHealth()))));
            }
        }
        if (people.size() > pageSize) {
            arrow(graphics, 9, top + 42, false);
            arrow(graphics, drawnWidth - 24, top + 42, true);
        }
        if (!notice.getString().isEmpty()) {
            graphics.enableScissor(8, top + 64, drawnWidth - 8, drawnHeight - 4);
            graphics.drawString(minecraft.font, notice, 9, top + 64, GOLD, true);
            graphics.disableScissor();
        }
        hits.stream().filter(hit -> hit.contains(mouseX, mouseY)).findFirst().ifPresent(hit ->
            graphics.renderTooltip(minecraft.font, hit.tooltip(), mouseX, Math.min(mouseY, top - 2)));
    }

    private void arrow(GuiGraphics graphics, int x, int y, boolean forward) {
        graphics.fill(x, y, x + 15, y + 17, 0xFF315943);
        graphics.drawCenteredString(Minecraft.getInstance().font, forward ? ">" : "<", x + 7, y + 4, TEXT);
        hits.add(new Hit(x, y, 15, 17, new Action(forward ? Kind.NEXT : Kind.PREVIOUS, 0, null),
            Component.translatable(forward ? "folkways.war.next_cards" : "folkways.war.previous_cards")));
    }

    private static void face(GuiGraphics graphics, ResidentEntity resident, int x, int y, int size) {
        PlayerFaceRenderer.draw(graphics, ResidentLooks.of(resident).appearance().skin().texture(), x, y, size);
    }
}
