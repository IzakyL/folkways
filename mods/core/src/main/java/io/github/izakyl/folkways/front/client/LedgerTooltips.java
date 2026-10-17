package io.github.izakyl.folkways.front.client;

import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.mojang.datafixers.util.Either;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.front.api.ui.Cues;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.ui.screen.Desk;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;
import org.joml.Matrix4f;

/**
 * Tooltips over the desk are slips of the ledger's paper: words in ink, without the shadow light words need on the
 * world's dark cards. An item's tooltip keeps its own look, whose names are coloured for a dark ground.
 */
@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class LedgerTooltips {

    private static final int PAPER = 0xFFEADCB6;
    private static final int EDGE = 0xFF6E4A2C;
    private static final int EDGE_DARK = 0xFF4A3020;
    private static final int LINE_HEIGHT = 10;
    // Words past this wrap: tooltips here are given as whole sentences, which vanilla would only wrap at the screen.
    private static final int WRAP = 220;

    private LedgerTooltips() {
    }

    /** A line of a tooltip's words, inked. */
    public record Line(FormattedText text) implements TooltipComponent {
    }

    /** Key hints, inked. */
    public record Inked(Cues cues) implements TooltipComponent {
    }

    static ClientTooltipComponent lines(Line line) {
        Font font = Minecraft.getInstance().font;
        List<FormattedCharSequence> split = font.split(line.text(), WRAP);
        return new Lines(split.isEmpty() ? List.of(FormattedCharSequence.EMPTY) : split);
    }

    @SubscribeEvent
    public static void onGather(RenderTooltipEvent.GatherComponents event) {
        if (!event.getItemStack().isEmpty() || !Desk.on(ModularUI.of(Minecraft.getInstance().screen))) {
            return;
        }
        event.getTooltipElements().replaceAll(element -> element.map(
            text -> Either.<FormattedText, TooltipComponent>right(new Line(text)),
            image -> Either.<FormattedText, TooltipComponent>right(image instanceof Cues cues ? new Inked(cues) : image)));
    }

    @SubscribeEvent
    public static void onColor(RenderTooltipEvent.Color event) {
        boolean ledger = event.getComponents().stream()
            .anyMatch(component -> component instanceof Lines || component instanceof CueLines lines && lines.inked());
        if (!ledger) {
            return;
        }
        event.setBackground(PAPER);
        event.setBorderStart(EDGE);
        event.setBorderEnd(EDGE_DARK);
    }

    private record Lines(List<FormattedCharSequence> lines) implements ClientTooltipComponent {

        @Override
        public int getHeight() {
            return lines.size() * LINE_HEIGHT;
        }

        @Override
        public int getWidth(Font font) {
            int widest = 0;
            for (FormattedCharSequence line : lines) {
                widest = Math.max(widest, font.width(line));
            }
            return widest;
        }

        @Override
        public void renderText(Font font, int x, int y, Matrix4f matrix, MultiBufferSource.BufferSource buffer) {
            for (int index = 0; index < lines.size(); index++) {
                font.drawInBatch(lines.get(index), x, y + index * LINE_HEIGHT, Rows.INK, false, matrix, buffer,
                    Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
            }
        }
    }
}
