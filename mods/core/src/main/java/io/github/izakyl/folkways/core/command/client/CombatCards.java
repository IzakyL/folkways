package io.github.izakyl.folkways.core.command.client;

import io.github.izakyl.folkways.core.combat.api.Portraits;
import io.github.izakyl.folkways.core.front.client.KeyBadge;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

/**
 * The shortcut-group dock at the bottom left, drawn as the rest of the view from above is: small dark cells, one for
 * each group that has members, each with its number key as a badge, a member's face and how many of them are here. A last cell with
 * a plus binds the selection to the first free group while something is selected. Clicks recall a group, Ctrl+click
 * binds the selection to it. The cells wrap upwards rather than run under the selection's command card.
 */
final class CombatCards {
    private static final int MARGIN = 6;
    private static final int CELL = 28;
    private static final int CELL_HEIGHT = 20;
    private static final int ADD = 14;
    private static final int GAP = 2;
    private static final int FACE = 14;
    private static final int BED = 0x80000000;
    private static final int BED_HOVERED = 0xB0000000;
    private static final int LABEL = 0xFFF0EEE6;
    private static final int QUIET = 0xFFA0A0A0;
    private static final int LIT = 0xFF6BA88A;
    private static final int SHORT = 0xFFFF8A80;

    private static final Map<ResourceLocation, Optional<ResourceLocation>> PICTURES = new java.util.HashMap<>();

    /**
     * A vehicle mod's own square icon for the entity type, by the convention Superb Warfare follows
     * ({@code <namespace>:textures/vehicle_icon/container/<path>.png}); absent when the pack has none.
     */
    static Optional<ResourceLocation> picture(ResourceLocation type) {
        return PICTURES.computeIfAbsent(type, key -> {
            var path = ResourceLocation.fromNamespaceAndPath(key.getNamespace(),
                "textures/vehicle_icon/container/" + key.getPath() + ".png");
            return Minecraft.getInstance().getResourceManager().getResource(path).map(found -> path);
        });
    }

    /** A press on the dock: recall a group, or bind the selection to it. */
    record Press(int slot, boolean bind) {
    }

    /** A cell as drawn: where, which group, whether it binds, and whether it was drawn pressed, hovered or plain. */
    private record Hit(int x, int y, int width, int height, int slot, boolean bind, String state,
            Component tooltip) {
        static boolean within(ScreenRectangle area, double mouseX, double mouseY) {
            return mouseX >= area.left() && mouseX < area.right() && mouseY >= area.top() && mouseY < area.bottom();
        }

        ScreenRectangle area() {
            return new ScreenRectangle(x, y, width, height);
        }

        Press press() {
            return new Press(slot, bind);
        }

        boolean contains(double mouseX, double mouseY) {
            return within(area(), mouseX, mouseY);
        }
    }

    private final List<Hit> hits = new ArrayList<>();
    private int drawnWidth;
    private int drawnHeight;
    private int drawnTop = Integer.MAX_VALUE;

    /** The top of the dock as last drawn; the screen's height when it shows nothing. */
    int visibleTop() {
        int height = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        return hits.isEmpty() ? height : Math.min(height, drawnTop);
    }

    /** The cells as last drawn, for the view to keep clear of. */
    List<ScreenRectangle> covered() {
        return hits.stream().map(Hit::area).toList();
    }

    /** Whether a point lies on a cell; the world between and beside them stays clickable. */
    boolean covers(double x, double y) {
        return hits.stream().anyMatch(hit -> hit.contains(x, y));
    }

    void reset() {
        hits.clear();
        drawnTop = Integer.MAX_VALUE;
    }

    /** What a press there does, if it lands on a cell drawn for this window's size. */
    Optional<Press> pressed(double x, double y) {
        var window = Minecraft.getInstance().getWindow();
        if (drawnWidth != window.getGuiScaledWidth() || drawnHeight != window.getGuiScaledHeight()) {
            return Optional.empty();
        }
        return hits.stream().filter(hit -> hit.contains(x, y)).map(Hit::press).findFirst();
    }

    /**
     * Draws the dock from the bottom left, short of the x {@code limit} and standing on the y {@code floor} (the
     * screen's bottom unless something else stands there), and above it why the last order was refused.
     */
    void render(GuiGraphics graphics, int mouseX, int mouseY, Set<UUID> selected, Map<UUID, LivingEntity> residents,
                Set<UUID> facilities, Map<Integer, List<UUID>> groups, Component failure, int limit, int floor) {
        Minecraft minecraft = Minecraft.getInstance();
        var font = minecraft.font;
        drawnWidth = minecraft.getWindow().getGuiScaledWidth();
        drawnHeight = minecraft.getWindow().getGuiScaledHeight();
        hits.clear();

        List<Integer> bound = new ArrayList<>();
        int free = 0;
        for (int slot = 1; slot <= 9; slot++) {
            if (groups.getOrDefault(slot, List.of()).isEmpty()) {
                free = free == 0 ? slot : free;
            } else {
                bound.add(slot);
            }
        }
        boolean adding = !selected.isEmpty() && free > 0;
        int right = Math.max(MARGIN + CELL, limit);
        int bottom = Math.min(drawnHeight, floor) - MARGIN;
        int x = MARGIN;
        int y = bottom - CELL_HEIGHT;
        for (int slot : bound) {
            if (x + CELL > right && x > MARGIN) {
                x = MARGIN;
                y -= CELL_HEIGHT + GAP;
            }
            List<UUID> members = groups.get(slot);
            long available = members.stream().filter(id -> residents.containsKey(id) || facilities.contains(id))
                .count();
            boolean chosen = !selected.isEmpty() && selected.equals(Set.copyOf(members));
            ScreenRectangle area = new ScreenRectangle(x, y, CELL, CELL_HEIGHT);
            boolean hovered = Hit.within(area, mouseX, mouseY);
            graphics.fill(x, y, x + CELL, y + CELL_HEIGHT, hovered ? BED_HOVERED : BED);
            if (chosen) {
                graphics.fill(x, y + CELL_HEIGHT - 1, x + CELL, y + CELL_HEIGHT, LIT);
            }
            // The number key that recalls it, worn as the command cards wear their keys.
            KeyBadge.at(graphics, Integer.toString(slot), x + 2, y + 2);
            small(graphics, Long.toString(available), x + 3, y + CELL_HEIGHT - 8,
                available < members.size() ? SHORT : LABEL);
            LivingEntity representative =
                members.stream().map(residents::get).filter(Objects::nonNull).findFirst().orElse(null);
            int faceX = x + CELL - FACE - 3;
            int faceY = y + (CELL_HEIGHT - FACE) / 2;
            if (representative != null) {
                Portraits.draw(graphics, representative, faceX, faceY, FACE);
            } else if (members.stream().anyMatch(facilities::contains)) {
                // A group of facilities wears the facility icon in the face's place.
                graphics.pose().pushPose();
                graphics.pose().translate(faceX, faceY, 0);
                graphics.pose().scale(FACE / 16F, FACE / 16F, 1);
                graphics.renderItem(FacilityCard.ICON, 0, 0);
                graphics.pose().popPose();
            }
            hits.add(new Hit(x, y, CELL, CELL_HEIGHT, slot, false, chosen ? "pressed" : hovered ? "hover" : "normal",
                Component.translatable("folkways.rts.group_card", slot, available, members.size())));
            x += CELL + GAP;
        }
        if (adding) {
            if (x + ADD > right && x > MARGIN) {
                x = MARGIN;
                y -= CELL_HEIGHT + GAP;
            }
            ScreenRectangle area = new ScreenRectangle(x, y, ADD, CELL_HEIGHT);
            boolean hovered = Hit.within(area, mouseX, mouseY);
            graphics.fill(x, y, x + ADD, y + CELL_HEIGHT, hovered ? BED_HOVERED : BED);
            graphics.drawString(font, "+", x + (ADD - font.width("+") + 1) / 2, y + (CELL_HEIGHT - 8) / 2,
                hovered ? LABEL : QUIET, true);
            hits.add(new Hit(x, y, ADD, CELL_HEIGHT, free, true, hovered ? "hover" : "normal",
                Component.translatable("folkways.rts.group_bind", free)));
        }
        drawnTop = hits.isEmpty() ? drawnHeight : y;

        // A refused order says why just above the dock until it fades.
        if (failure != null) {
            int width = font.width(failure);
            int top = (hits.isEmpty() ? bottom : drawnTop - GAP) - 12;
            graphics.fill(MARGIN, top, MARGIN + width + 6, top + 12, BED);
            graphics.drawString(font, failure, MARGIN + 3, top + 2, SHORT, true);
        }

        hits.stream()
            .filter(hit -> hit.contains(mouseX, mouseY))
            .findFirst()
            .ifPresent(hit -> graphics.renderTooltip(font, hit.tooltip(), mouseX, Math.min(mouseY, drawnTop - 2)));
    }

    private static void small(GuiGraphics graphics, String text, int x, int y, int color) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(.75F, .75F, 1);
        graphics.drawString(Minecraft.getInstance().font, text, 0, 0, color, true);
        graphics.pose().popPose();
    }
}
