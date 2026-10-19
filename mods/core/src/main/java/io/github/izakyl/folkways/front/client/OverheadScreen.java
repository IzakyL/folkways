package io.github.izakyl.folkways.front.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.api.ui.Placing;
import io.github.izakyl.folkways.front.engine.item.BookGestures;
import io.github.izakyl.folkways.front.engine.item.ColonyBookItem;
import io.github.izakyl.folkways.front.engine.item.HeldBook;
import io.github.izakyl.folkways.front.engine.net.SetBookGesturePacket;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Frees the mouse over the view from above, and hands what it does in the world to the book. Along the top it
 * lays out what the keys do out of the player's own eyes, to be clicked: the gestures, what is being drawn and
 * what to do with it, and which of the colony's markings are drawn at all.
 */
final class OverheadScreen extends Screen {
    private static final int MARGIN = 6;
    private static final int GAP = 2;
    private static final int STATUS_HEIGHT = 12;
    private static final int STATUS = 0xFFF0EEE6;
    private static final int BED = 0x80000000;

    private long lastFrameMs = Util.getMillis();
    private Laid laid;

    /** What the buttons were laid out for; they are laid out again when it changes. */
    private record Laid(Shape.Gesture gesture, boolean drafting, boolean ready, boolean placing) {

        static Laid now(Minecraft minecraft) {
            Shape.Gesture gesture = HeldBook.bound(minecraft.player).map(HeldBook::gesture).orElse(null);
            boolean placing = HeldPlacement.holding();
            if (gesture == Shape.Gesture.BOX) {
                return new Laid(gesture, ZoneSelection.drafting(), ZoneSelection.marked().isPresent(), placing);
            }
            if (gesture == Shape.Gesture.LINE) {
                return new Laid(gesture, PathSelection.drafting(), PathSelection.drawn().isPresent(), placing);
            }
            return new Laid(gesture, false, false, placing);
        }
    }

    OverheadScreen() {
        super(Component.empty());
    }

    @Override
    protected void init() {
        laid = Laid.now(minecraft);
        Optional<HeldBook> book = HeldBook.bound(minecraft.player);
        if (book.isEmpty()) {
            return;
        }
        List<Chip> layers = new ArrayList<>();
        for (Layer layer : Layer.values()) {
            Chip[] made = new Chip[1];
            made[0] = new Chip(Component.translatable(layer.nameKey()), ItemStack.EMPTY,
                () -> 2 - layer.showing().ordinal(), () -> {
                    layer.toggle();
                    told(made[0], layer);
                });
            told(made[0], layer);
            layers.add(made[0]);
        }
        row(layers, MARGIN, MARGIN);

        List<Chip> gestures = new ArrayList<>();
        for (Shape.Gesture gesture : BookGestures.offered()) {
            ItemStack icon = book.get().stack().copy();
            ColonyBookItem.setGesture(icon, gesture);
            gestures.add(new Chip(Component.translatable("folkways.overhead.gesture." + gesture.getSerializedName()),
                icon, () -> laid.gesture() == gesture,
                () -> PacketDistributor.sendToServer(new SetBookGesturePacket(book.get().hand(), gesture))));
        }
        row(gestures, (width - widthOf(gestures)) / 2, MARGIN);

        List<Chip> drafted = new ArrayList<>();
        // A shape held to be set down takes the clicks, so its own buttons stand in for the gesture's.
        if (laid.placing()) {
            drafted.add(act("folkways.keys.place.turn", () -> HeldPlacement.turn(1)));
            drafted.add(act("folkways.keys.place.flip", () -> HeldPlacement.flip(1)));
            drafted.add(act("folkways.keys.discard", HeldPlacement::discard));
            row(drafted, (width - widthOf(drafted)) / 2, MARGIN + Chip.HEIGHT + GAP + STATUS_HEIGHT + GAP);
            return;
        }
        for (Placing.Shelf shelf : Placing.shelves()) {
            drafted.add(new Chip(Component.translatable(shelf.nameKey()),
                new ItemStack(BuiltInRegistries.ITEM.get(shelf.icon())), () -> true, shelf.open()));
        }
        if (laid.ready()) {
            drafted.add(act("folkways.keys.setup", () -> FolkwaysClientGameEvents.use(minecraft)));
        }
        if (laid.drafting() && laid.gesture() == Shape.Gesture.LINE) {
            drafted.add(act("folkways.overhead.undo", PathSelection::undo));
        }
        if (laid.drafting()) {
            drafted.add(act("folkways.keys.discard",
                laid.gesture() == Shape.Gesture.LINE ? PathSelection::discard : ZoneSelection::discard));
        }
        row(drafted, (width - widthOf(drafted)) / 2, MARGIN + Chip.HEIGHT + GAP + STATUS_HEIGHT + GAP);
    }

    /** A layer's button says, hovered, how much of the layer is drawn. */
    private static void told(Chip chip, Layer layer) {
        chip.setTooltip(Tooltip.create(Component.translatable(layer.showing().nameKey())));
    }

    private static Chip act(String nameKey, Runnable pressed) {
        return new Chip(Component.translatable(nameKey), ItemStack.EMPTY, () -> true, pressed);
    }

    private void row(List<Chip> chips, int left, int top) {
        int at = left;
        for (Chip chip : chips) {
            chip.setPosition(at, top);
            addRenderableWidget(chip);
            at += chip.getWidth() + GAP;
        }
    }

    private static int widthOf(List<Chip> chips) {
        int width = Math.max(0, chips.size() - 1) * GAP;
        for (Chip chip : chips) {
            width += chip.getWidth();
        }
        return width;
    }

    @Override
    public void tick() {
        if (!Laid.now(minecraft).equals(laid)) {
            rebuildWidgets();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        long now = Util.getMillis();
        Overhead.fly(minecraft, (now - lastFrameMs) / 1000.0D);
        lastFrameMs = now;

        status().ifPresent(status -> {
            int textWidth = font.width(status);
            int left = (width - textWidth) / 2;
            int top = MARGIN + Chip.HEIGHT + GAP;
            graphics.fill(left - 3, top, left + textWidth + 3, top + STATUS_HEIGHT, BED);
            graphics.drawString(font, status, left, top + 2, STATUS, true);
        });
        for (var renderable : renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
        if (!overButton(mouseX, mouseY)) {
            HoverCard.render(graphics, minecraft, mouseX, mouseY);
        }
    }

    private Optional<Component> status() {
        if (laid.gesture() == Shape.Gesture.BOX) {
            return ZoneSelection.status();
        }
        return laid.gesture() == Shape.Gesture.LINE ? PathSelection.status() : Optional.empty();
    }

    private boolean overButton(double mouseX, double mouseY) {
        return children().stream().anyMatch(child -> child.isMouseOver(mouseX, mouseY));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (overButton(mouseX, mouseY)) {
            boolean taken = super.mouseClicked(mouseX, mouseY, button);
            // A button pressed is not left holding the keyboard, where Enter and Space would press it again.
            setFocused(null);
            return taken;
        }
        return switch (button) {
            case InputConstants.MOUSE_BUTTON_LEFT -> FolkwaysClientGameEvents.click(minecraft);
            case InputConstants.MOUSE_BUTTON_RIGHT -> FolkwaysClientGameEvents.use(minecraft);
            default -> false;
        };
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button != InputConstants.MOUSE_BUTTON_MIDDLE) {
            return false;
        }
        Overhead.turn(dragX, dragY);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!FolkwaysClientGameEvents.scroll(minecraft, scrollY)) {
            Overhead.dolly(scrollY);
        }
        return true;
    }

    /**
     * Sneak here only takes the camera down, so what it does with a click or the wheel in the player's own eyes
     * has keys of its own: Tab turns the book to the next gesture, Backspace or Delete lets go of the drawing.
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        switch (keyCode) {
            case InputConstants.KEY_TAB -> {
                HeldBook.bound(minecraft.player).ifPresent(book -> PacketDistributor.sendToServer(
                    new SetBookGesturePacket(book.hand(), BookGestures.next(book.gesture()))));
                return true;
            }
            case InputConstants.KEY_BACKSPACE, InputConstants.KEY_DELETE -> {
                if (HeldPlacement.holding()) {
                    HeldPlacement.discard();
                } else if (laid.gesture() == Shape.Gesture.LINE) {
                    PathSelection.discard();
                } else if (laid.gesture() == Shape.Gesture.BOX) {
                    ZoneSelection.discard();
                }
                return true;
            }
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
    }

    @Override
    public void onClose() {
        Overhead.leave(minecraft);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
