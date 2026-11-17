package io.github.izakyl.folkways.core.rts.client;

import com.lowdragmc.lowdraglib2.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib2.gui.texture.SpriteTexture;
import io.github.izakyl.folkways.core.combat.api.Portraits;
import io.github.izakyl.folkways.core.front.api.ui.Rows;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

/**
 * The shortcut-group dock at the bottom left: nine group buttons and nothing else. A bound group shows its
 * number, a member's face and how many members are here; an empty slot only its number. Clicks recall a group,
 * Ctrl+click binds the selection to it.
 */
final class CombatCards {
    static final int HEIGHT = 32;
    private static final int BOTTOM = 2;
    private static final int LEFT = 2;
    private static final int PAD = 4;
    private static final int GROUP = 32;
    private static final int ROW = 24;
    private static final int GAP = 2;
    private static final int RED = 0xFF9A3324;
    // A slim popup frame over paper: the combat panel's oak rails are too thick for a one-row dock.
    private static final GuiTextureGroup PANEL = new GuiTextureGroup(
        SpriteTexture.of("folkways_core:textures/gui/desk/paper.png").setSprite(0, 0, 64, 64)
            .setWrapMode(SpriteTexture.WrapMode.REPEAT),
        SpriteTexture.of("folkways_core:textures/gui/desk/popup.png").setSprite(0, 0, 24, 24).setBorder(4));
    private static final SpriteTexture BUTTON = button("button");
    private static final SpriteTexture HOVER = button("button_hover");
    private static final SpriteTexture PRESSED = button("button_pressed");
    static final int WIDTH = 2 * PAD + 9 * GROUP + 8 * GAP;

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

    private record Hit(int x, int y, int width, int height, int slot, Component tooltip, SpriteTexture texture) {
        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
    }

    private final List<Hit> hits = new ArrayList<>();
    private int drawnWidth;
    private int drawnHeight;

    int top() {
        return Minecraft.getInstance().getWindow().getGuiScaledHeight() - HEIGHT - BOTTOM;
    }

    int visibleTop() {
        return top();
    }

    /** Whether a point lies on the dock itself; the world beside it stays clickable. */
    boolean covers(double x, double y) {
        return y >= top() && y < top() + HEIGHT && x >= LEFT && x < LEFT + WIDTH;
    }

    void reset() {
        hits.clear();
    }

    /** The group slot under the pointer, or 0. */
    int clicked(double x, double y) {
        var window = Minecraft.getInstance().getWindow();
        if (drawnWidth != window.getGuiScaledWidth() || drawnHeight != window.getGuiScaledHeight()) {
            return 0;
        }
        return hits.stream().filter(hit -> hit.contains(x, y)).mapToInt(Hit::slot).findFirst().orElse(0);
    }

    void render(GuiGraphics graphics, int mouseX, int mouseY, Set<UUID> selected, Map<UUID, LivingEntity> residents,
                Map<Integer, List<UUID>> groups, Component failure) {
        Minecraft minecraft = Minecraft.getInstance();
        var font = minecraft.font;
        drawnWidth = minecraft.getWindow().getGuiScaledWidth();
        drawnHeight = minecraft.getWindow().getGuiScaledHeight();
        int top = top();
        int rowY = top + (HEIGHT - ROW) / 2;
        hits.clear();

        // LDLib sprites are buffered; finish them before drawing text or vanilla faces.
        PANEL.draw(graphics, mouseX, mouseY, LEFT, top, WIDTH, HEIGHT, 0);
        graphics.flush();

        int x = LEFT + PAD;
        for (int slot = 1; slot <= 9; slot++) {
            List<UUID> members = groups.getOrDefault(slot, List.of());
            long available = members.stream().filter(residents::containsKey).count();
            boolean chosen = !selected.isEmpty() && selected.equals(Set.copyOf(members));
            boolean hovered = mouseX >= x && mouseX < x + GROUP && mouseY >= rowY && mouseY < rowY + ROW;
            SpriteTexture texture = chosen ? PRESSED : hovered ? HOVER : BUTTON;
            texture.draw(graphics, mouseX, mouseY, x, rowY, GROUP, ROW, 0);
            graphics.flush();
            String number = Integer.toString(slot);
            if (members.isEmpty()) {
                graphics.drawString(font, number, x + (GROUP - font.width(number) + 1) / 2, rowY + 8,
                    Rows.QUIET, false);
            } else {
                graphics.drawString(font, number, x + 3, rowY + 3, Rows.INK, false);
                LivingEntity representative =
                    members.stream().map(residents::get).filter(Objects::nonNull).findFirst().orElse(null);
                if (representative != null) {
                    Portraits.draw(graphics, representative, x + GROUP - 17, rowY + 3, 14);
                }
                String count = Long.toString(available);
                graphics.pose().pushPose();
                graphics.pose().translate(x + 3, rowY + ROW - 8, 0);
                graphics.pose().scale(.75F, .75F, 1);
                graphics.drawString(font, count, 0, 0, available < members.size() ? RED : Rows.QUIET, false);
                graphics.pose().popPose();
            }
            hits.add(new Hit(x, rowY, GROUP, ROW, slot,
                Component.translatable("folkways.rts.group_card", slot, available, members.size()), texture));
            x += GROUP + GAP;
        }

        // A refused order says why just above the dock until it fades.
        if (failure != null) {
            int width = font.width(failure);
            graphics.fill(LEFT, top - 13, LEFT + width + 8, top - 1, 0xC0F3E7C8);
            graphics.drawString(font, failure, LEFT + 4, top - 11, RED, false);
        }

        hits.stream()
            .filter(hit -> hit.contains(mouseX, mouseY))
            .findFirst()
            .ifPresent(hit -> graphics.renderTooltip(font, hit.tooltip(), mouseX, Math.min(mouseY, top - 2)));
    }

    private static SpriteTexture button(String name) {
        return SpriteTexture.of("folkways_core:textures/gui/desk/" + name + ".png")
            .setSprite(0, 0, 16, 16).setBorder(4);
    }
}
