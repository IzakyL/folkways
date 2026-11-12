package io.github.izakyl.folkways.core.front.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.izakyl.folkways.core.front.api.Shape;
import io.github.izakyl.folkways.core.front.api.ui.OverheadTools;
import io.github.izakyl.folkways.core.front.api.ui.Placing;
import io.github.izakyl.folkways.core.front.engine.item.BookGestures;
import io.github.izakyl.folkways.core.front.engine.item.ColonyBookItem;
import io.github.izakyl.folkways.core.front.engine.item.HeldBook;
import io.github.izakyl.folkways.core.front.engine.net.SetBookGesturePacket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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

    static int hintsTop() {
        return MARGIN + 3 * Chip.HEIGHT + 3 * GAP + STATUS_HEIGHT + 4;
    }

    private long lastFrameMs = Util.getMillis();
    private Laid laid;

    private enum Drag {
        NONE,
        BLOCKED,
        LEGACY,
        PAN,
        ORBIT
    }

    private Drag middleDrag = Drag.NONE;
    private final Map<Integer, OverheadTools.Handler> toolDrags = new HashMap<>();
    private final Set<Integer> cancelledButtons = new HashSet<>();
    private final Set<Integer> toolKeys = new HashSet<>();
    private long toolRevision = OverheadTools.selectionRevision();

    private Optional<OverheadTools.Handler> handler() {
        if (toolRevision != OverheadTools.selectionRevision()) {
            cancelToolDrags();
            toolRevision = OverheadTools.selectionRevision();
        }
        if (minecraft.screen != this || HeldPlacement.holding()) {
            cancelToolDrags();
            return Optional.empty();
        }
        return OverheadTools.selectedHandler();
    }

    private void cancelToolDrags() {
        toolDrags.values().stream().distinct().forEach(OverheadTools.Handler::cancelPointer);
        cancelledButtons.addAll(toolDrags.keySet());
        toolDrags.clear();
    }

    /** A gesture keeps the camera profile it started with until its middle button is released. */
    Optional<Boolean> dragTacticalControls() {
        return switch (middleDrag) {
            case PAN, ORBIT -> Optional.of(true);
            case LEGACY -> Optional.of(false);
            default -> Optional.empty();
        };
    }

    private record ActionState(Component name, boolean on) {
    }

    /** What the buttons were laid out for; they are laid out again when it changes. */
    private record Laid(
        Shape.Gesture gesture, boolean drafting, boolean ready, boolean placing, ResourceLocation tool,
        Object selection, List<ActionState> actions, ResourceLocation mode) {

        static Laid now(Minecraft minecraft) {
            Shape.Gesture gesture = HeldBook.bound(minecraft.player).map(HeldBook::gesture).orElse(null);
            boolean placing = HeldPlacement.holding();
            ResourceLocation tool = OverheadTools.selected().map(OverheadTools.Tool::id).orElse(null);
            Object selection = OverheadTools.active().map(OverheadTools.FocusedTool::selectionKey).orElse(null);
            List<ActionState> actions = OverheadTools.active().stream()
                .flatMap(extra -> extra.actions().stream())
                .map(action -> new ActionState(action.name(), action.on()))
                .toList();
            if (OverheadTools.active().isPresent() || (tool == null && gesture == Shape.Gesture.BOX)) {
                return new Laid(gesture, ZoneSelection.drafting(), ZoneSelection.marked().isPresent(),
                    placing, tool, selection, actions, OverheadTools.mode());
            }
            if (tool == null && gesture == Shape.Gesture.LINE) {
                return new Laid(gesture, PathSelection.drafting(), PathSelection.drawn().isPresent(),
                    placing, null, null, actions, OverheadTools.mode());
            }
            return new Laid(gesture, false, false, placing, tool, selection, actions, OverheadTools.mode());
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
        List<Chip> modes = new ArrayList<>();
        for (OverheadTools.Mode mode : OverheadTools.modes()) {
            Chip chip = new Chip(Component.translatable(mode.nameKey()),
                new ItemStack(BuiltInRegistries.ITEM.get(mode.icon())),
                () -> OverheadTools.mode().equals(mode.id()), () -> {
                    if (!OverheadTools.mode().equals(mode.id())) {
                        cancelDraftsForCycle();
                        ZoneSelection.clear();
                        PathSelection.clear();
                        ShapePreview.clear();
                        OverheadTools.selectMode(mode.id());
                    }
                });
            modes.add(chip);
        }
        row(modes, (width - widthOf(modes)) / 2, MARGIN);

        List<Chip> layers = new ArrayList<>();
        for (Layer layer : OverheadTools.mode().equals(OverheadTools.BUILD) ? Layer.values() : new Layer[0]) {
            Chip[] made = new Chip[1];
            made[0] = new Chip(Component.translatable(layer.nameKey()), ItemStack.EMPTY,
                () -> 2 - layer.showing().ordinal(), () -> {
                    layer.toggle();
                    told(made[0], layer);
                });
            told(made[0], layer);
            layers.add(made[0]);
        }
        row(layers, MARGIN, MARGIN + Chip.HEIGHT + GAP);

        List<Chip> gestures = new ArrayList<>();
        for (Shape.Gesture gesture : OverheadTools.mode().equals(OverheadTools.BUILD)
                ? BookGestures.offered() : List.<Shape.Gesture>of()) {
            ItemStack icon = book.get().stack().copy();
            ColonyBookItem.setGesture(icon, gesture);
            gestures.add(
                new Chip(
                Component.translatable("folkways.overhead.gesture." + gesture.getSerializedName()),
                icon,
                () -> laid.tool() == null && laid.gesture() == gesture,
                () -> {
                    OverheadTools.deselect();
                    PacketDistributor.sendToServer(new SetBookGesturePacket(book.get().hand(), gesture));
                }));
        }
        List<OverheadTools.Tool> modeTools = OverheadTools.tools().stream()
            .filter(tool -> tool.mode().equals(OverheadTools.mode())).toList();
        for (OverheadTools.Tool tool : OverheadTools.mode().equals(OverheadTools.BUILD) || modeTools.size() > 1
                ? modeTools : List.<OverheadTools.Tool>of()) {
            Chip chip =
                new Chip(
                Component.translatable(tool.nameKey()),
                new ItemStack(BuiltInRegistries.ITEM.get(tool.icon())),
                () -> tool.id().equals(laid.tool()),
                () -> {
                    ZoneSelection.clear();
                    PathSelection.clear();
                    HeldPlacement.clear();
                    ShapePreview.clear();
                    OverheadTools.select(tool.id());
                });
            chip.active = !laid.placing() && !laid.drafting();
            if (!chip.active) {
                chip.setTooltip(Tooltip.create(Component.translatable("folkways.overhead.tools.finish_draft")));
            }
            gestures.add(chip);
        }
        row(gestures, (width - widthOf(gestures)) / 2, MARGIN + Chip.HEIGHT + GAP);

        List<Chip> drafted = new ArrayList<>();
        if (OverheadTools.active().isPresent()) {
            for (OverheadTools.Action action : OverheadTools.active().orElseThrow().actions()) {
                drafted.add(new Chip(action.name(), ItemStack.EMPTY, action::on, action.run()));
            }
            wrapped(drafted, MARGIN + 2 * (Chip.HEIGHT + GAP) + STATUS_HEIGHT + GAP);
            return;
        }
        // A shape held to be set down takes the clicks, so its own buttons stand in for the gesture's.
        if (laid.placing()) {
            drafted.add(act("folkways.keys.place.turn", () -> HeldPlacement.turn(1)));
            drafted.add(act("folkways.keys.place.flip", () -> HeldPlacement.flip(1)));
            drafted.add(act("folkways.keys.discard", HeldPlacement::discard));
            row(drafted, (width - widthOf(drafted)) / 2, MARGIN + 2 * (Chip.HEIGHT + GAP) + STATUS_HEIGHT + GAP);
            return;
        }
        if (OverheadTools.selected().isPresent()) {
            OverheadTools.Tool tool = OverheadTools.selected().orElseThrow();
            if (!tool.actionKey().isEmpty()) {
                drafted.add(act(tool.actionKey(), tool.open()));
            }
            row(drafted, (width - widthOf(drafted)) / 2, MARGIN + 2 * (Chip.HEIGHT + GAP) + STATUS_HEIGHT + GAP);
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
        row(drafted, (width - widthOf(drafted)) / 2, MARGIN + 2 * (Chip.HEIGHT + GAP) + STATUS_HEIGHT + GAP);
    }

    private void wrapped(List<Chip> chips, int top) {
        int left = MARGIN;
        for (Chip chip : chips) {
            if (left + chip.getWidth() > width - MARGIN) {
                left = MARGIN;
                top += Chip.HEIGHT + GAP;
            }
            chip.setPosition(left, top);
            addRenderableWidget(chip);
            left += chip.getWidth() + GAP;
        }
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

        OverheadTools.renderOverlays(graphics, partialTick);
        handler().ifPresent(handler -> handler.render(graphics, mouseX, mouseY, partialTick));
        KeyHints.renderOverhead(graphics);

        status().ifPresent(status -> {
            int textWidth = font.width(status);
            int left = (width - textWidth) / 2;
            int top = MARGIN + 2 * (Chip.HEIGHT + GAP);
            graphics.fill(left - 3, top, left + textWidth + 3, top + STATUS_HEIGHT, BED);
            graphics.drawString(font, status, left, top + 2, STATUS, true);
        });
        ColonyDebugState.panel(graphics, minecraft);
        for (var renderable : renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
        int bottomInset = handler().map(OverheadTools.Handler::bottomInset).orElse(0);
        if (!overButton(mouseX, mouseY) && mouseY < height - bottomInset) {
            HoverCard.render(graphics, minecraft, mouseX, mouseY);
        }
    }

    private Optional<Component> status() {
        if (OverheadTools.active().isPresent()) {
            return ZoneSelection.status().or(() -> OverheadTools.active().map(OverheadTools.FocusedTool::status));
        }
        Optional<OverheadTools.Tool> tool = OverheadTools.selected();
        if (tool.isPresent()) {
            return Optional.of(Component.translatable(tool.get().nameKey()));
        }
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
        handler();
        cancelledButtons.remove(button);
        if (button == InputConstants.MOUSE_BUTTON_MIDDLE) {
            // Camera gestures replace a tool's unfinished pointer gesture, whichever button began
            // first.
            cancelToolDrags();
            // Ownership is decided at press, not by whichever modifier happens to be held on a
            // later frame.
            middleDrag =
                !Overhead.tacticalControls()
                ? Drag.LEGACY
                : overButton(mouseX, mouseY) ? Drag.BLOCKED : hasAltDown() ? Drag.ORBIT : Drag.PAN;
            if (middleDrag == Drag.PAN || middleDrag == Drag.ORBIT) {
                Overhead.beginTacticalDrag(minecraft);
                return true;
            }
        }
        if (overButton(mouseX, mouseY)) {
            boolean taken = super.mouseClicked(mouseX, mouseY, button);
            // A button pressed is not left holding the keyboard, where Enter and Space would press
            // it again.
            setFocused(null);
            return taken;
        }
        if (button != InputConstants.MOUSE_BUTTON_MIDDLE) {
            Optional<OverheadTools.Handler> handler = handler();
            if (middleDrag != Drag.NONE && handler.isPresent()) {
                cancelledButtons.add(button);
                return true;
            }
            if (handler.isPresent() && handler.get().mouseClicked(mouseX, mouseY, button)) {
                toolDrags.put(button, handler.get());
                return true;
            }
        }
        return switch (button) {
            case InputConstants.MOUSE_BUTTON_LEFT -> FolkwaysClientGameEvents.click(minecraft);
            case InputConstants.MOUSE_BUTTON_RIGHT -> FolkwaysClientGameEvents.use(minecraft);
            default -> false;
        };
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        handler();
        if (cancelledButtons.contains(button)) {
            return true;
        }
        if (button != InputConstants.MOUSE_BUTTON_MIDDLE) {
            OverheadTools.Handler owner = toolDrags.get(button);
            if (owner != null && handler().filter(active -> active == owner).isPresent()) {
                owner.mouseDragged(mouseX, mouseY, button, dragX, dragY);
                return true;
            }
            return false;
        }
        switch (middleDrag) {
            case LEGACY -> Overhead.turn(dragX, dragY);
            case PAN -> Overhead.pan(minecraft, dragX, dragY);
            case ORBIT -> Overhead.orbit(dragX, dragY);
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        handler();
        if (button == InputConstants.MOUSE_BUTTON_MIDDLE) {
            middleDrag = Drag.NONE;
        }
        if (cancelledButtons.remove(button)) {
            return true;
        }
        OverheadTools.Handler owner = toolDrags.remove(button);
        if (owner != null) {
            if (handler().filter(active -> active == owner).isPresent()) {
                owner.mouseReleased(mouseX, mouseY, button);
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (ColonyDebugState.scroll(mouseX, mouseY, scrollY)) {
            return true;
        }
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
        if (toolKeys.contains(keyCode)) {
            return true;
        }
        if (keyCode == InputConstants.KEY_TAB) {
            toolKeys.add(keyCode);
            cycleGestureOrTool();
            return true;
        }
        // Tool shortcuts precede button focus: selecting a toolbar chip must not disable its
        // hotkeys.
        if (handler().filter(handler -> handler.keyPressed(keyCode, scanCode, modifiers)).isPresent()) {
            toolKeys.add(keyCode);
            return true;
        }
        switch (keyCode) {
            case InputConstants.KEY_BACKSPACE, InputConstants.KEY_DELETE -> {
                if (OverheadTools.active().isPresent()) {
                    ZoneSelection.discard();
                    return true;
                }
                if (OverheadTools.selected().isPresent()) {
                    return true;
                }
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

    /** Tab walks the book's gestures and then each registered overhead tool before returning to the first gesture. */
    private void cycleGestureOrTool() {
        cancelDraftsForCycle();
        Optional<HeldBook> held = HeldBook.bound(minecraft.player);
        if (held.isEmpty()) {
            OverheadTools.deselect();
            return;
        }
        List<Shape.Gesture> gestures = BookGestures.offered();
        List<OverheadTools.Tool> tools = OverheadTools.tools();
        Optional<OverheadTools.Tool> selected = OverheadTools.selected();
        if (selected.isPresent()) {
            int at = tools.indexOf(selected.get());
            if (at >= 0 && at + 1 < tools.size()) {
                OverheadTools.select(tools.get(at + 1).id());
                return;
            }
            OverheadTools.deselect();
            setGesture(held.get(), gestures.get(0));
            return;
        }

        int at = gestures.indexOf(held.get().gesture());
        if (at >= 0 && at + 1 < gestures.size()) {
            setGesture(held.get(), gestures.get(at + 1));
        } else if (!tools.isEmpty()) {
            OverheadTools.select(tools.get(0).id());
        } else {
            setGesture(held.get(), gestures.get(0));
        }
    }

    private static void cancelDraftsForCycle() {
        if (HeldPlacement.holding()) {
            HeldPlacement.discard();
        }
        if (ZoneSelection.drafting()) {
            ZoneSelection.discard();
        }
        if (PathSelection.drafting()) {
            PathSelection.discard();
        }
    }

    private static void setGesture(HeldBook book, Shape.Gesture gesture) {
        OverheadTools.deselect();
        PacketDistributor.sendToServer(new SetBookGesturePacket(book.hand(), gesture));
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (toolKeys.remove(keyCode)) {
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        Overhead.leave(minecraft);
    }

    @Override
    public void removed() {
        OverheadTools.selectedHandler().ifPresent(OverheadTools.Handler::cancel);
        toolDrags.clear();
        cancelledButtons.clear();
        toolKeys.clear();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
