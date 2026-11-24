package io.github.izakyl.folkways.core.front.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.izakyl.folkways.core.front.api.Category;
import io.github.izakyl.folkways.core.front.api.Shape;
import io.github.izakyl.folkways.core.front.api.ui.Cue;
import io.github.izakyl.folkways.core.front.api.ui.OverheadTools;
import io.github.izakyl.folkways.core.front.api.ui.Placing;
import io.github.izakyl.folkways.core.front.engine.item.HeldBook;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Frees the mouse over the view from above, and hands what it does in the world to the book. Its controls keep to the
 * edges, each to its own: the mode switch at the top right; in the build mode the book's tools along the top, a row of
 * groups with the open group's tools revealed under it, the colony's markings to show down the right, and what is
 * being drawn, with what to do with it, along the bottom. A key that presses a button is worn in that button's
 * corner; the key hints down the left keep to what has no button. Another mode lays out only
 * its own tools; the bottom and the right are then the tool's, for its cards and docks.
 */
final class OverheadScreen extends Screen {
    private static final int MARGIN = 6;
    private static final int GAP = 2;
    /** Between the tools that pick what to draw and the shelves beside them. */
    private static final int GROUP_GAP = 6;
    private static final int STATUS_HEIGHT = 12;
    private static final int STATUS = 0xFFF0EEE6;
    private static final int BED = 0x80000000;
    /** How far down the game's toasts (a tutorial's, an advancement's) reach from the top right. */
    private static final int TOASTS = 32;
    /** The thin line joining an open group to the tools it reveals. */
    private static final int STEM = 0x806BA88A;

    /** What the top holds over the key hints, and how much the bottom holds under them. */
    private static ScreenRectangle tools = ScreenRectangle.empty();
    private static int bottomBand;

    /** The top of the key hints, as wide as they are drawn: clear of the tools when they reach over them. */
    static int hintsTop(int hintsRight) {
        return tools.width() > 0 && hintsRight + GAP >= tools.left() ? tools.bottom() + 2 * GAP : MARGIN;
    }

    /** The bottom of the key hints, clear of what is being drawn and its buttons. */
    static int hintsBottom(int height) {
        return height - bottomBand - MARGIN;
    }

    private long lastFrameMs = Util.getMillis();
    private Laid laid;
    private int statusTop;
    /** The groups and the open group's tools, where the wheel walks the group. */
    private ScreenRectangle wheeled = ScreenRectangle.empty();
    private Chip openChip;
    private ScreenRectangle revealed = ScreenRectangle.empty();

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

    /** What the buttons were laid out for; they are laid out again when it changes. */
    private record Laid(
        String picked, ResourceLocation open, Shape.Gesture gesture, boolean drafting, boolean ready,
        boolean placing, ResourceLocation tool, ResourceLocation mode, List<String> offered) {

        static Laid now() {
            List<String> offered = BookTools.entries().stream()
                .map(entry -> entry.key() + (entry.unavailable().isPresent() ? "!" : ""))
                .toList();
            return new Laid(BookTools.picked().map(BookTools.Entry::key).orElse(""), BookTools.openCategory().id(),
                BookTools.gesture(), BookTools.drafting(), BookTools.ready(), HeldPlacement.holding(),
                OverheadTools.selected().map(OverheadTools.Tool::id).orElse(null), OverheadTools.mode(), offered);
        }

        boolean building() {
            return mode.equals(OverheadTools.BUILD);
        }
    }

    OverheadScreen() {
        super(Component.empty());
    }

    @Override
    protected void init() {
        laid = Laid.now();
        tools = ScreenRectangle.empty();
        wheeled = ScreenRectangle.empty();
        revealed = ScreenRectangle.empty();
        openChip = null;
        bottomBand = 0;
        if (HeldBook.bound(minecraft.player).isEmpty()) {
            return;
        }
        boolean busy = laid.placing() || laid.drafting();
        int modeLeft = modes(busy);
        if (laid.building()) {
            building(busy, modeLeft);
        } else {
            moded(busy, modeLeft);
        }
    }

    /** The mode switch at the top right, under where the game's toasts show; gives its left edge. */
    private int modes(boolean busy) {
        List<Chip> chips = new ArrayList<>();
        for (OverheadTools.Mode mode : OverheadTools.modes()) {
            boolean building = mode.id().equals(OverheadTools.BUILD);
            if (!building && OverheadTools.tools(mode.id()).isEmpty()) {
                continue;
            }
            Component name = Component.translatable(mode.nameKey());
            Chip chip = new Chip(name, new ItemStack(BuiltInRegistries.ITEM.get(mode.icon())),
                () -> laid.mode().equals(mode.id()), () -> switchTo(mode.id()));
            if (!building && !laid.mode().equals(mode.id())) {
                waitsForDraft(chip, name, busy);
            }
            // From another mode, Tab goes back to building.
            if (building && !laid.building()) {
                chip.keys(Cue.Key.TAB);
            }
            chips.add(chip);
        }
        int left = width - MARGIN - widthOf(chips, 0);
        row(chips, left, TOASTS + 2 * GAP, 0);
        return left;
    }

    private static void switchTo(ResourceLocation mode) {
        if (mode.equals(OverheadTools.mode())) {
            return;
        }
        if (mode.equals(OverheadTools.BUILD)) {
            OverheadTools.deselect();
            return;
        }
        OverheadTools.tools(mode).stream().findFirst().ifPresent(tool -> {
            BookTools.drop();
            HeldPlacement.clear();
            OverheadTools.select(tool.id());
        });
    }

    /**
     * The build mode: the groups of the book's tools along the top with the open group's tools under it, the layers
     * down the right, and what is being drawn along the bottom.
     */
    private void building(boolean busy, int modeLeft) {
        int bandLeft = MARGIN;
        int bandRight = modeLeft - 4 * GAP;

        Map<Category, List<BookTools.Entry>> grouped = BookTools.grouped();
        ResourceLocation open = laid.open();
        List<Chip> groups = new ArrayList<>();
        // Tab opens the next group with a tool to pick, as BookTools.cycleCategory walks them; that one wears it.
        List<Category> walked = grouped.entrySet().stream()
            .filter(group -> group.getValue().stream().anyMatch(entry -> entry.unavailable().isEmpty()))
            .map(Map.Entry::getKey).toList();
        int walkedAt = walked.stream().map(Category::id).toList().indexOf(open);
        Category tabbed = walked.size() < 2 ? null : walked.get(Math.floorMod(Math.max(0, walkedAt) + 1,
            walked.size()));
        for (Map.Entry<Category, List<BookTools.Entry>> group : grouped.entrySet()) {
            Category category = group.getKey();
            boolean lit = category.id().equals(open);
            Component name = Component.translatable(category.nameKey());
            Chip chip = Chip.tool(name, new ItemStack(BuiltInRegistries.ITEM.get(category.icon())),
                () -> laid.open().equals(category.id()), () -> BookTools.open(category), lit);
            chip.active = group.getValue().stream().anyMatch(entry -> entry.unavailable().isEmpty());
            chip.setTooltip(Tooltip.create(name));
            if (category.equals(tabbed)) {
                chip.keys(Cue.Key.TAB);
            }
            groups.add(chip);
            if (lit) {
                openChip = chip;
            }
        }
        int groupsWidth = widthOf(groups, GAP);
        int groupsLeft = centred(groupsWidth, bandLeft, bandRight);
        row(groups, groupsLeft, MARGIN, GAP);

        List<Chip> picks = new ArrayList<>();
        Category openCategory = Category.of(open);
        for (BookTools.Entry entry : grouped.getOrDefault(openCategory, List.of())) {
            boolean picked = laid.tool() == null && laid.picked().equals(entry.key());
            Chip chip = Chip.tool(entry.name(), entry.icon(),
                () -> laid.tool() == null && laid.picked().equals(entry.key()),
                () -> BookTools.choose(entry.key()), picked);
            chip.active = entry.unavailable().isEmpty();
            chip.setTooltip(Tooltip.create(told(entry.name(), entry.how(), entry.unavailable())));
            // The wheel over the tools walks the group from the one picked.
            if (picked) {
                chip.keys(Cue.Key.WHEEL);
            }
            picks.add(chip);
        }
        List<Chip> shelves = new ArrayList<>();
        for (Placing.Shelf shelf : Placing.shelves()) {
            if (!Category.of(shelf.category()).equals(openCategory)) {
                continue;
            }
            Component name = Component.translatable(shelf.nameKey());
            Chip chip = Chip.tool(name, new ItemStack(BuiltInRegistries.ITEM.get(shelf.icon())), () -> false,
                shelf.open(), false);
            waitsForDraft(chip, name, busy);
            shelves.add(chip);
        }
        int revealedTop = MARGIN + Chip.HEIGHT + 2 * GAP + 1;
        int picksWidth = widthOf(picks, GAP) + (shelves.isEmpty() ? 0
            : (picks.isEmpty() ? 0 : GROUP_GAP) + widthOf(shelves, GAP));
        // The open group's tools hang under it, as far as the band lets them.
        int under = openChip == null ? groupsLeft + groupsWidth / 2 : openChip.getX() + openChip.getWidth() / 2;
        int picksLeft = Math.max(bandLeft, Math.min(under - picksWidth / 2, bandRight - picksWidth));
        int at = row(picks, picksLeft, revealedTop, GAP);
        if (!shelves.isEmpty()) {
            row(shelves, picks.isEmpty() ? picksLeft : at - GAP + GROUP_GAP, revealedTop, GAP);
        }
        int left = picksWidth > 0 ? Math.min(groupsLeft, picksLeft) : groupsLeft;
        int right = Math.max(groupsLeft + groupsWidth, picksLeft + picksWidth);
        int bottom = picksWidth > 0 ? revealedTop + Chip.HEIGHT : MARGIN + Chip.HEIGHT;
        tools = new ScreenRectangle(left, MARGIN, right - left, bottom - MARGIN);
        wheeled = tools;
        revealed = picksWidth > 0 ? new ScreenRectangle(picksLeft, revealedTop, picksWidth, Chip.HEIGHT)
            : ScreenRectangle.empty();

        layers();

        List<Chip> drafted = new ArrayList<>();
        // A shape held to be set down takes the clicks, so its own buttons stand in for the book's.
        if (laid.placing()) {
            drafted.add(act("folkways.keys.place.turn", () -> HeldPlacement.turn(1))
                .keys(Cue.Key.ALTERNATE, Cue.Key.WHEEL));
            drafted.add(act("folkways.keys.place.flip", () -> HeldPlacement.flip(1))
                .keys(Cue.Key.MODIFIER, Cue.Key.ALTERNATE, Cue.Key.WHEEL));
            drafted.add(act("folkways.keys.discard", HeldPlacement::discard).keys(Cue.Key.BACKSPACE));
        } else {
            if (laid.ready()) {
                boolean picked = !laid.picked().isEmpty() && !laid.picked().equals(BookTools.POINT);
                drafted.add(act(picked ? "folkways.keys.setup" : "folkways.keys.choose", BookTools::hand)
                    .keys(Cue.Key.USE));
            }
            if (laid.drafting() && laid.gesture() == Shape.Gesture.LINE) {
                drafted.add(act("folkways.overhead.undo", PathSelection::undo));
            }
            if (laid.drafting()) {
                drafted.add(act("folkways.keys.discard",
                    laid.gesture() == Shape.Gesture.LINE ? PathSelection::discard : ZoneSelection::discard)
                    .keys(Cue.Key.BACKSPACE));
            }
        }
        statusTop = height - MARGIN - STATUS_HEIGHT;
        row(drafted, (width - widthOf(drafted, GAP)) / 2, statusTop - GAP - Chip.HEIGHT, GAP);
        bottomBand = drafted.isEmpty() ? STATUS_HEIGHT + GAP : STATUS_HEIGHT + Chip.HEIGHT + 3 * GAP;
    }

    /** What the colony's markings show, a view filter rather than a tool: a column down the right edge. */
    private void layers() {
        List<Chip> layers = new ArrayList<>();
        int widest = 0;
        for (Layer layer : Layer.values()) {
            Chip[] made = new Chip[1];
            made[0] = new Chip(Component.translatable(layer.nameKey()), ItemStack.EMPTY,
                () -> 2 - layer.showing().ordinal(), () -> {
                    layer.toggle();
                    told(made[0], layer);
                });
            told(made[0], layer);
            layers.add(made[0]);
            widest = Math.max(widest, made[0].getWidth());
        }
        int columnHeight = layers.size() * (Chip.HEIGHT + GAP) - GAP;
        int top = Math.max(TOASTS + 2 * GAP + Chip.HEIGHT + 4 * GAP, (height - columnHeight) / 2);
        for (Chip chip : layers) {
            chip.setWidth(widest);
            chip.setPosition(width - MARGIN - widest, top);
            addRenderableWidget(chip);
            top += Chip.HEIGHT + GAP;
        }
    }

    /** Any mode but building: its own tools along the top when it has several, and nothing of the book's. */
    private void moded(boolean busy, int modeLeft) {
        List<Chip> chips = new ArrayList<>();
        List<OverheadTools.Tool> own = OverheadTools.tools(laid.mode());
        if (own.size() > 1) {
            for (OverheadTools.Tool tool : own) {
                Component name = Component.translatable(tool.nameKey());
                Chip chip = Chip.tool(name, new ItemStack(BuiltInRegistries.ITEM.get(tool.icon())),
                    () -> tool.id().equals(laid.tool()),
                    () -> {
                        BookTools.drop();
                        HeldPlacement.clear();
                        OverheadTools.select(tool.id());
                    },
                    tool.id().equals(laid.tool()));
                waitsForDraft(chip, name, busy);
                chips.add(chip);
            }
        }
        OverheadTools.selected().filter(tool -> !tool.actionKey().isEmpty())
            .ifPresent(tool -> chips.add(act(tool.actionKey(), tool.open())));
        if (chips.isEmpty()) {
            return;
        }
        int rowWidth = widthOf(chips, GAP);
        int left = centred(rowWidth, MARGIN, modeLeft - 4 * GAP);
        row(chips, left, MARGIN, GAP);
        tools = new ScreenRectangle(left, MARGIN, rowWidth, Chip.HEIGHT);
    }

    private static int centred(int rowWidth, int bandLeft, int bandRight) {
        int left = (bandLeft + bandRight - rowWidth) / 2;
        return Math.max(bandLeft, Math.min(left, bandRight - rowWidth));
    }

    private static Component told(Component name, Component how, Optional<Component> unavailable) {
        MutableComponent told = name.copy().append("\n").append(how.copy().withStyle(ChatFormatting.GRAY));
        unavailable.ifPresent(why -> told.append("\n").append(why.copy().withStyle(ChatFormatting.RED)));
        return told;
    }

    // What leaves the book's drawing for something else waits until the drawing is finished or let go.
    private static void waitsForDraft(Chip chip, Component name, boolean busy) {
        chip.active = !busy;
        chip.setTooltip(Tooltip.create(busy
            ? name.copy().append("\n").append(Component.translatable("folkways.overhead.tools.finish_draft")
                .withStyle(ChatFormatting.GRAY))
            : name));
    }

    /** A layer's button says, hovered, how much of the layer is drawn. */
    private static void told(Chip chip, Layer layer) {
        chip.setTooltip(Tooltip.create(Component.translatable(layer.showing().nameKey())));
    }

    private static Chip act(String nameKey, Runnable pressed) {
        return new Chip(Component.translatable(nameKey), ItemStack.EMPTY, () -> true, pressed);
    }

    /** Lays chips out in a line from the left; gives where the next would go. */
    private int row(List<Chip> chips, int left, int top, int gap) {
        int at = left;
        for (Chip chip : chips) {
            chip.setPosition(at, top);
            addRenderableWidget(chip);
            at += chip.getWidth() + gap;
        }
        return at;
    }

    private static int widthOf(List<Chip> chips, int gap) {
        int width = Math.max(0, chips.size() - 1) * gap;
        for (Chip chip : chips) {
            width += chip.getWidth();
        }
        return width;
    }

    @Override
    public void tick() {
        if (!Laid.now().equals(laid)) {
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

        if (laid.building()) {
            status().ifPresent(status -> {
                int textWidth = font.width(status);
                int left = (width - textWidth) / 2;
                graphics.fill(left - 3, statusTop, left + textWidth + 3, statusTop + STATUS_HEIGHT, BED);
                graphics.drawString(font, status, left, statusTop + 2, STATUS, true);
            });
            // The open group's tools hang from it on a thin stem.
            if (openChip != null && revealed.width() > 0) {
                int stem = openChip.getX() + openChip.getWidth() / 2;
                int stemX = Math.max(revealed.left(), Math.min(stem, revealed.right() - 1));
                graphics.fill(stemX, openChip.getY() + Chip.HEIGHT, stemX + 1, revealed.top(), STEM);
            }
        }
        ColonyDebugState.panel(graphics, minecraft);
        for (var renderable : renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
        int bottomInset = handler().map(OverheadTools.Handler::bottomInset).orElse(0);
        if (!overButton(mouseX, mouseY) && mouseY < height - bottomInset && !reserved(mouseX, mouseY)) {
            HoverCard.render(graphics, minecraft, mouseX, mouseY);
        }
    }

    /** Whether a point lies on what the selected tool's own controls cover. */
    private boolean reserved(double mouseX, double mouseY) {
        return handler().map(OverheadTools.Handler::reserved).orElse(List.of()).stream()
            .anyMatch(area -> mouseX >= area.left() && mouseX < area.right()
                && mouseY >= area.top() && mouseY < area.bottom());
    }

    private Optional<Component> status() {
        return switch (laid.gesture()) {
            case BOX -> ZoneSelection.status();
            case LINE -> PathSelection.status();
            case POINT -> Optional.empty();
        };
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
        // The wheel over the book's tools walks the open group, as it walks along the hotbar.
        if (laid != null && laid.building() && wheeled.width() > 0 && mouseX >= wheeled.left()
            && mouseX < wheeled.right() && mouseY >= wheeled.top() && mouseY < wheeled.bottom()) {
            if (!laid.placing() && !laid.drafting() && scrollY != 0.0D) {
                BookTools.cycleWithin(scrollY < 0.0D ? 1 : -1);
            }
            return true;
        }
        if (!FolkwaysClientGameEvents.scroll(minecraft, scrollY)) {
            Overhead.dolly(scrollY);
        }
        return true;
    }

    /**
     * Sneak here only takes the camera down, so what it does with a click or the wheel in the player's own eyes
     * has keys of its own: Tab opens the next group of the book's tools (from another mode, it goes back to
     * building), and Backspace or Delete lets go of the drawing.
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (toolKeys.contains(keyCode)) {
            return true;
        }
        if (keyCode == InputConstants.KEY_TAB) {
            toolKeys.add(keyCode);
            tab(hasShiftDown() ? -1 : 1);
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

    private void tab(int step) {
        if (HeldBook.bound(minecraft.player).isEmpty() || OverheadTools.selected().isPresent()) {
            OverheadTools.deselect();
            return;
        }
        BookTools.cycleCategory(step);
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
