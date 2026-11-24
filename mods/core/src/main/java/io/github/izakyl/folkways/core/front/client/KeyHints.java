package io.github.izakyl.folkways.core.front.client;

import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.front.api.ui.Cue;
import io.github.izakyl.folkways.core.front.api.ui.OverheadTools;
import io.github.izakyl.folkways.core.front.engine.item.HeldBook;
import io.github.izakyl.folkways.core.front.ui.screen.Desk;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * The keys that do something right now, listed down the edge of the screen: with the book in hand, and in its panels.
 * A key that presses a button on screen is worn in that button's corner instead ({@link KeyBadge}).
 */
@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class KeyHints {

    private static final int MARGIN = 6;
    private static final int PADDING = 3;
    private static final int BED = 0x80000000;
    private static final float ABOVE_PANELS = 400.0F;

    private KeyHints() {
    }

    @SubscribeEvent
    public static void onHud(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (Overhead.on() || Overhead.screened(minecraft) || minecraft.player == null || minecraft.options.hideGui) {
            return;
        }
        Optional<HeldBook> book = HeldBook.any(minecraft.player);
        if (book.isEmpty()) {
            return;
        }
        List<Cue> cues = inHand(minecraft, book.get());
        GuiGraphics graphics = event.getGuiGraphics();
        draw(graphics, cues, (graphics.guiHeight() - cues.size() * CueLines.LINE) / 2);
    }

    /** The overhead screen owns these hints so its tool overlay and reserved bottom controls cannot cover them. */
    static void renderOverhead(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!Overhead.activeScreen() || minecraft.player == null || minecraft.options.hideGui) {
            return;
        }
        Optional<HeldBook> book = HeldBook.any(minecraft.player);
        if (book.isEmpty()) {
            return;
        }
        List<Cue> cues = inHand(minecraft, book.get());
        if (cues.isEmpty()) {
            return;
        }
        Font font = minecraft.font;
        int width = CueLines.width(font, cues);
        int height = graphics.guiHeight();
        int top = OverheadScreen.hintsTop(MARGIN + width + PADDING);
        // Down the left edge, short of whatever the selected tool's own controls hold below or beside it.
        int bottom = OverheadScreen.hintsBottom(height);
        bottom = Math.min(bottom, height - MARGIN
            - Math.max(0, OverheadTools.selectedHandler().map(OverheadTools.Handler::bottomInset).orElse(0)));
        for (ScreenRectangle area : OverheadTools.selectedHandler().map(OverheadTools.Handler::reserved)
                .orElse(List.of())) {
            if (area.left() < MARGIN + width + PADDING && area.right() > MARGIN - PADDING && area.top() > top) {
                bottom = Math.min(bottom, area.top() - MARGIN);
            }
        }
        int available = Math.max(0, bottom - top);
        int cueHeight = cues.size() * CueLines.LINE;
        float scale = Math.min(1.0F, available / (float) cueHeight);
        if (scale <= 0.0F) {
            return;
        }

        int foot = cues.size() * CueLines.LINE - (CueLines.LINE - KeyGlyph.HEIGHT);
        graphics.pose().pushPose();
        graphics.pose().translate(MARGIN, top, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.fill(-PADDING, -PADDING, width + PADDING, foot + PADDING, BED);
        CueLines.draw(graphics, font, cues, 0, 0);
        graphics.pose().popPose();
    }

    @SubscribeEvent
    public static void onScreen(ScreenEvent.Render.Post event) {
        ModularUI ui = ModularUI.of(event.getScreen());
        if (!Desk.on(ui)) {
            return;
        }
        List<Cue> cues = List.of(Cue.of(Desk.floating(ui) ? "folkways.keys.close.window" : "folkways.keys.close",
            Cue.Key.ESCAPE));
        GuiGraphics graphics = event.getGuiGraphics();
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, ABOVE_PANELS);
        draw(graphics, cues, graphics.guiHeight() - MARGIN - cues.size() * CueLines.LINE);
        graphics.pose().popPose();
    }

    private static void draw(GuiGraphics graphics, List<Cue> cues, int top) {
        Font font = Minecraft.getInstance().font;
        int bottom = top + cues.size() * CueLines.LINE - (CueLines.LINE - KeyGlyph.HEIGHT);
        graphics.fill(MARGIN - PADDING, top - PADDING,
            MARGIN + CueLines.width(font, cues) + PADDING, bottom + PADDING, BED);
        CueLines.draw(graphics, font, cues, MARGIN, top);
    }

    private static List<Cue> inHand(Minecraft minecraft, HeldBook book) {
        List<Cue> cues = new ArrayList<>();
        boolean compactToolHints =
            Overhead.on() && OverheadTools.selectedHandler().map(OverheadTools.Handler::compactHints).orElse(false);
        if (book.colonyId() == null) {
            cues.add(Cue.of("folkways.keys.open", Cue.Key.USE));
        } else if (HeldPlacement.holding()) {
            placing(cues);
        } else {
            List<Cue> toolCues =
                Overhead.on()
                ? OverheadTools.selectedHandler().map(OverheadTools.Handler::cues).orElse(List.of())
                : List.of();
            if (!toolCues.isEmpty()) {
                cues.addAll(toolCues);
            } else {
                switch (BookTools.gesture()) {
                    case POINT -> pointing(minecraft, cues);
                    case BOX -> marking(minecraft, cues);
                    case LINE -> lining(minecraft, cues);
                }
            }
        }
        // From above the tools are buttons: Tab and the wheel over them are worn on the buttons they press.
        if (!Overhead.on() && book.colonyId() != null) {
            cues.add(Cue.of("folkways.keys.gesture", Cue.Key.SNEAK, Cue.Key.WHEEL));
        }
        if (Overhead.on() && !compactToolHints) {
            cues.add(Overhead.edgeScroll() ? Cue.of("folkways.keys.overhead.edge")
                : Cue.of("folkways.keys.overhead.fly", Cue.Key.WALK));
            cues.add(Cue.of("folkways.keys.overhead.up", Cue.Key.JUMP));
            cues.add(Cue.of("folkways.keys.overhead.down", Cue.Key.SNEAK));
            if (Overhead.tacticalControls()) {
                cues.add(Cue.of("folkways.keys.overhead.pan", Cue.Key.DRAG));
                cues.add(Cue.of("folkways.keys.overhead.orbit", Cue.Key.ALTERNATE, Cue.Key.DRAG));
            } else {
                cues.add(Cue.of("folkways.keys.overhead.turn", Cue.Key.DRAG));
            }
            cues.add(Cue.of("folkways.keys.overhead.near", Cue.Key.WHEEL));
            cues.add(Cue.of("folkways.keys.overhead.leave", Cue.Key.OVERHEAD));
        } else if (!Overhead.on() && book.colonyId() != null) {
            cues.add(Cue.of("folkways.keys.overhead", Cue.Key.OVERHEAD));
        }
        return cues;
    }

    // An offer with its preview out in the world is confirmed in its panel. From above the shape becomes what was
    // picked among the tools, by its own button, which wears the key; otherwise the book asks what it should become.
    private static void setup(List<Cue> cues) {
        if (ShapePreview.showing()) {
            cues.add(Cue.of("folkways.keys.setup.preview", Cue.Key.USE));
        } else if (!Overhead.on()) {
            cues.add(Cue.of("folkways.keys.choose", Cue.Key.USE));
        }
    }

    // From above the drawing is let go by a button of its own, which wears its key; sneak there only takes the camera
    // down.
    private static void discard(List<Cue> cues) {
        if (!Overhead.on()) {
            cues.add(Cue.of("folkways.keys.discard", Cue.Key.SNEAK, Cue.Key.CLICK));
        }
    }

    private static void placing(List<Cue> cues) {
        HeldPlacement.name().ifPresent(name -> cues.add(Cue.of(
            Component.translatable("folkways.keys.place", name), Cue.Key.CLICK)));
        cues.add(Cue.of(Component.translatable("folkways.keys.place.reach", HeldPlacement.reach()),
            Cue.Key.MODIFIER, Cue.Key.WHEEL));
        // From above, turning and flipping have buttons, which wear these keys.
        if (!Overhead.on()) {
            cues.add(Cue.of("folkways.keys.place.turn", Cue.Key.ALTERNATE, Cue.Key.WHEEL));
            cues.add(Cue.of("folkways.keys.place.flip", Cue.Key.MODIFIER, Cue.Key.ALTERNATE, Cue.Key.WHEEL));
        }
        discard(cues);
    }

    private static void pointing(Minecraft minecraft, List<Cue> cues) {
        Aim aim = Aim.of(minecraft.player);
        if (aim.entity(minecraft.player).isPresent() || aim.block(minecraft.player).isPresent()) {
            cues.add(Cue.of("folkways.keys.take", Cue.Key.CLICK));
        }
        cues.add(opening(minecraft));
    }

    private static void marking(Minecraft minecraft, List<Cue> cues) {
        if (ZoneSelection.marked().isPresent()) {
            setup(cues);
            cues.add(Cue.of("folkways.keys.push", Cue.Key.MODIFIER, Cue.Key.WHEEL));
            cues.add(Cue.of("folkways.keys.push.far", Cue.Key.ALTERNATE, Cue.Key.WHEEL));
            cues.add(Cue.of("folkways.keys.shift", Cue.Key.MODIFIER, Cue.Key.ALTERNATE, Cue.Key.WHEEL));
            cues.add(Cue.of("folkways.keys.restart", Cue.Key.CLICK));
            discard(cues);
            return;
        }
        boolean cornered = ZoneSelection.drafting();
        cues.add(Cue.of(cornered ? "folkways.keys.corner.opposite" : "folkways.keys.corner", Cue.Key.CLICK));
        cues.add(Cue.of("folkways.keys.corner.air", Cue.Key.MODIFIER, Cue.Key.CLICK));
        cues.add(Cue.of(Component.translatable("folkways.keys.reach", ZoneSelection.reach()),
            Cue.Key.MODIFIER, Cue.Key.WHEEL));
        if (cornered) {
            discard(cues);
        } else {
            cues.add(opening(minecraft));
        }
    }

    private static void lining(Minecraft minecraft, List<Cue> cues) {
        if (!PathSelection.drafting()) {
            cues.add(Cue.of("folkways.keys.point", Cue.Key.CLICK));
            cues.add(opening(minecraft));
            return;
        }
        cues.add(Cue.of("folkways.keys.point.next", Cue.Key.CLICK));
        if (PathSelection.aimedPoint(minecraft.player).isPresent()) {
            cues.add(Cue.of("folkways.keys.point.hold", Cue.Key.MODIFIER, Cue.Key.CLICK));
        }
        cues.add(Cue.of("folkways.keys.point.along", Cue.Key.MODIFIER, Cue.Key.WHEEL));
        cues.add(Cue.of("folkways.keys.point.across", Cue.Key.ALTERNATE, Cue.Key.WHEEL));
        cues.add(Cue.of("folkways.keys.point.raise", Cue.Key.MODIFIER, Cue.Key.ALTERNATE, Cue.Key.WHEEL));
        if (PathSelection.drawn().isPresent()) {
            setup(cues);
        }
        discard(cues);
    }

    /** What the use key opens when nothing is being drawn: the zone or path aimed at, or else the colony's panel. */
    private static Cue opening(Minecraft minecraft) {
        boolean aimed = ZoneSelection.aimedZone(minecraft.player).isPresent()
            || PathSelection.aimedPath(minecraft).isPresent();
        return Cue.of(aimed ? "folkways.keys.settings" : "folkways.keys.panel", Cue.Key.USE);
    }
}
