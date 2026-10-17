package io.github.izakyl.folkways.front.client;

import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.front.api.ui.Cue;
import io.github.izakyl.folkways.front.engine.item.HeldBook;
import io.github.izakyl.folkways.front.ui.screen.Desk;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** The keys that do something right now, listed down the edge of the screen: with the book in hand, and in its panels. */
@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
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
        if (Overhead.screened(minecraft) || minecraft.player == null || minecraft.options.hideGui) {
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
        if (book.colonyId() == null) {
            cues.add(Cue.of("folkways.keys.open", Cue.Key.USE));
        } else if (HeldPlacement.holding()) {
            placing(cues);
        } else {
            switch (book.gesture()) {
                case POINT -> pointing(minecraft, cues);
                case BOX -> marking(minecraft, cues);
                case LINE -> lining(minecraft, cues);
            }
        }
        cues.add(Overhead.on() ? Cue.of("folkways.keys.gesture", Cue.Key.TAB)
            : Cue.of("folkways.keys.gesture", Cue.Key.SNEAK, Cue.Key.WHEEL));
        if (Overhead.on()) {
            cues.add(Cue.of("folkways.keys.overhead.fly", Cue.Key.WALK));
            cues.add(Cue.of("folkways.keys.overhead.up", Cue.Key.JUMP));
            cues.add(Cue.of("folkways.keys.overhead.down", Cue.Key.SNEAK));
            cues.add(Cue.of("folkways.keys.overhead.turn", Cue.Key.DRAG));
            cues.add(Cue.of("folkways.keys.overhead.near", Cue.Key.WHEEL));
            cues.add(Cue.of("folkways.keys.overhead.leave", Cue.Key.OVERHEAD));
        } else if (book.colonyId() != null) {
            cues.add(Cue.of("folkways.keys.overhead", Cue.Key.OVERHEAD));
        }
        return cues;
    }

    // With a preview out in the world, going back into the window is where it is built.
    private static Cue setup() {
        return Cue.of(ShapePreview.showing() ? "folkways.keys.setup.preview" : "folkways.keys.setup", Cue.Key.USE);
    }

    // From above, sneak only takes the camera down, and the drawing is let go with a key of its own.
    private static Cue discard() {
        return Overhead.on() ? Cue.of("folkways.keys.discard", Cue.Key.BACKSPACE)
            : Cue.of("folkways.keys.discard", Cue.Key.SNEAK, Cue.Key.CLICK);
    }

    private static void placing(List<Cue> cues) {
        HeldPlacement.name().ifPresent(name -> cues.add(Cue.of(
            Component.translatable("folkways.keys.place", name), Cue.Key.CLICK)));
        cues.add(Cue.of(Component.translatable("folkways.keys.place.reach", HeldPlacement.reach()),
            Cue.Key.MODIFIER, Cue.Key.WHEEL));
        cues.add(Cue.of("folkways.keys.place.turn", Cue.Key.ALTERNATE, Cue.Key.WHEEL));
        cues.add(Cue.of("folkways.keys.place.flip", Cue.Key.MODIFIER, Cue.Key.ALTERNATE, Cue.Key.WHEEL));
        cues.add(discard());
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
            cues.add(setup());
            cues.add(Cue.of("folkways.keys.push", Cue.Key.MODIFIER, Cue.Key.WHEEL));
            cues.add(Cue.of("folkways.keys.push.far", Cue.Key.ALTERNATE, Cue.Key.WHEEL));
            cues.add(Cue.of("folkways.keys.shift", Cue.Key.MODIFIER, Cue.Key.ALTERNATE, Cue.Key.WHEEL));
            cues.add(Cue.of("folkways.keys.restart", Cue.Key.CLICK));
            cues.add(discard());
            return;
        }
        boolean cornered = ZoneSelection.drafting();
        cues.add(Cue.of(cornered ? "folkways.keys.corner.opposite" : "folkways.keys.corner", Cue.Key.CLICK));
        cues.add(Cue.of("folkways.keys.corner.air", Cue.Key.MODIFIER, Cue.Key.CLICK));
        cues.add(Cue.of(Component.translatable("folkways.keys.reach", ZoneSelection.reach()),
            Cue.Key.MODIFIER, Cue.Key.WHEEL));
        cues.add(cornered ? discard() : opening(minecraft));
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
            cues.add(setup());
        }
        cues.add(discard());
    }

    /** What the use key opens when nothing is being drawn: the zone or path aimed at, or else the colony's panel. */
    private static Cue opening(Minecraft minecraft) {
        boolean aimed = ZoneSelection.aimedZone(minecraft.player).isPresent()
            || PathSelection.aimedPath(minecraft).isPresent();
        return Cue.of(aimed ? "folkways.keys.settings" : "folkways.keys.panel", Cue.Key.USE);
    }
}
