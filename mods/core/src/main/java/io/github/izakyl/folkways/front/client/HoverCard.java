package io.github.izakyl.folkways.front.client;

import io.github.izakyl.folkways.front.client.ColonyLookCard.Row;
import io.github.izakyl.folkways.front.engine.net.LookLines;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.EntityHitResult;

/** The card of whatever of the colony's the mouse is over, drawn beside the pointer in the view from above. */
final class HoverCard {
    private static final int PADDING = 4;
    private static final int OFFSET = 12;
    private static final int BED = 0xC0000000;

    private HoverCard() {
    }

    static void render(GuiGraphics graphics, Minecraft minecraft, int mouseX, int mouseY) {
        List<Row> rows = rows(minecraft);
        if (rows.isEmpty()) {
            return;
        }
        LookCardLayout.Laid laid = LookCardLayout.of(minecraft.font, rows);
        int x = Mth.clamp(mouseX + OFFSET, PADDING, Math.max(PADDING, graphics.guiWidth() - laid.width() - PADDING));
        int y = Mth.clamp(mouseY + OFFSET, PADDING, Math.max(PADDING, graphics.guiHeight() - laid.height() - PADDING));
        graphics.fill(x - PADDING, y - PADDING, x + laid.width() + PADDING, y + laid.height() + PADDING, BED);
        for (LookCardLayout.Piece piece : laid.pieces()) {
            switch (piece) {
                case LookCardLayout.Piece.Fill fill ->
                    graphics.fill(x + fill.x0(), y + fill.y0(), x + fill.x1(), y + fill.y1(), fill.color());
                case LookCardLayout.Piece.Icon icon ->
                    graphics.blitSprite(icon.sprite(), x + icon.x(), y + icon.y(), icon.width(), icon.height());
                case LookCardLayout.Piece.Item item -> {
                    float scale = item.size() / 16.0F;
                    graphics.pose().pushPose();
                    graphics.pose().translate(x + item.x(), y + item.y(), 0.0F);
                    graphics.pose().scale(scale, scale, 1.0F);
                    graphics.renderItem(item.stack(), 0, 0);
                    graphics.pose().popPose();
                }
                case LookCardLayout.Piece.Text text ->
                    graphics.drawString(minecraft.font, text.text(), x + text.x(), y + text.y(), text.color(), false);
            }
        }
    }

    // A resident stands in front of the ground they stand on, so their card comes before a zone's.
    private static List<Row> rows(Minecraft minecraft) {
        if (Layer.RESIDENTS.cards()) {
            Optional<EntityHitResult> entity = Aim.of(minecraft.player).entity(minecraft.player);
            if (entity.isPresent()) {
                for (LookLines look : ColonyLookState.residents()) {
                    if (look.entityId() == entity.get().getEntity().getId()) {
                        return ColonyLookCard.rows(look.lines());
                    }
                }
            }
        }
        return ColonyLookCard.rows(ColonyHighlightState.aimedLines(minecraft));
    }
}
