package io.github.izakyl.folkways.vanilla.toms;

import com.tom.storagemod.menu.StorageTerminalMenu;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.world.item.ItemStack;
import io.github.izakyl.folkways.core.shell.toms.TomsIntegration;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

// A button on a Tom's terminal that is a colony store; it opens the order panel over the terminal.
@OnlyIn(Dist.CLIENT)
final class TomsOrdersClient {

    static final String KEY = "folkways.toms.orders";

    private static final int WIDTH = 64;
    private static final int HEIGHT = 16;

    @Nullable
    private static Button button;

    @Nullable
    private static Button catalog;
    @Nullable
    private static AbstractContainerScreen<?> terminal;
    @Nullable
    private static TomsOrderNetwork.Board craftables;
    private static boolean showCraftables;
    private static int first;
    private static final List<CraftSlot> slots = new ArrayList<>();

    private TomsOrdersClient() {
    }

    static void install() {
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Render.Post.class, TomsOrdersClient::renderCatalog);
        NeoForge.EVENT_BUS.addListener(ScreenEvent.MouseScrolled.Pre.class, TomsOrdersClient::scrollCatalog);
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Init.Post.class, TomsOrdersClient::screenInit);
        NeoForge.EVENT_BUS.addListener(ScreenEvent.MouseButtonPressed.Pre.class, TomsOrdersClient::pressed);
    }

    // The terminal answers every click itself and never hands one outside its frame to its other widgets.
    private static void pressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getScreen() != terminal) {
            return;
        }
        double x = event.getMouseX(), y = event.getMouseY();
        if (event.getButton() == 0 && (press(button, x, y) || press(catalog, x, y))) {
            event.setCanceled(true);
            return;
        }
        if (showCraftables) {
            for (CraftSlot slot : slots) {
                if (slot.contains(x, y)) {
                    if (event.getButton() == 0 && slot.active) {
                        press(slot, x, y);
                    }
                    event.setCanceled(true);
                    return;
                }
            }
        }
    }

    private static boolean press(@Nullable Button button, double x, double y) {
        if (button == null || !button.isMouseOver(x, y)) {
            return false;
        }
        button.playDownSound(Minecraft.getInstance().getSoundManager());
        button.onPress();
        return true;
    }

    private static void screenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen) || !TomsIntegration.terminal(screen.getMenu())) {
            return;
        }
        int x = screen.getGuiLeft() + screen.getXSize() - WIDTH;
        int y = screen.getGuiTop() - HEIGHT - 2;
        if (y < 0) {
            x = screen.getGuiLeft() + screen.getXSize() + 4;
            y = screen.getGuiTop() + 4;
        }
        Button added = Button.builder(Component.translatable(KEY + ".open"), pressed -> ask(TomsOrderNetwork.Action.OPEN))
            .bounds(x, y, WIDTH, HEIGHT)
            .tooltip(Tooltip.create(Component.translatable(KEY + ".open.hint")))
            .build();
        added.visible = false;
        event.addListener(added);
        button = added;
        terminal = screen;
        craftables = null;
        showCraftables = false;
        first = 0;
        slots.clear();
        catalog = Button.builder(Component.translatable(KEY + ".craftables"), pressed -> {
            showCraftables = !showCraftables;
            first = 0;
            pressed.setMessage(Component.translatable(KEY + (showCraftables ? ".stock" : ".craftables")));
            if (showCraftables) {
                ask(TomsOrderNetwork.Action.CATALOG);
            } else {
                slots.forEach(slot -> slot.visible = false);
            }
        }).bounds(x - WIDTH - 2, y, WIDTH, HEIGHT).build();
        catalog.visible = false;
        event.addListener(catalog);
        if (screen.getMenu() instanceof StorageTerminalMenu menu) {
            for (var slot : menu.storageSlotList) {
                var widget = new CraftSlot(screen.getGuiLeft() + slot.xDisplayPosition,
                    screen.getGuiTop() + slot.yDisplayPosition);
                slots.add(widget);
                event.addListener(widget);
            }
        }
        ask(TomsOrderNetwork.Action.PEEK);
    }

    static void ask(TomsOrderNetwork.Action action) {
        ask(action, "", 0);
    }

    static void ask(TomsOrderNetwork.Action action, String item, int count) {
        PacketDistributor.sendToServer(new TomsOrderNetwork.Ask(action, item, count));
    }

    private static void scrollCatalog(ScreenEvent.MouseScrolled.Pre event) {
        if (event.getScreen() != terminal || !showCraftables || slots.stream()
                .noneMatch(slot -> slot.contains(event.getMouseX(), event.getMouseY()))) {
            return;
        }
        first = Math.max(0, first - (int) Math.signum(event.getScrollDeltaY()) * slots.size());
        event.setCanceled(true);
    }

    private static void renderCatalog(ScreenEvent.Render.Post event) {
        if (event.getScreen() != terminal || !showCraftables || slots.isEmpty()) {
            return;
        }
        var search = terminal.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast)
            .findFirst().map(EditBox::getValue).orElse("");
        var matches = io.github.izakyl.folkways.core.front.api.ui.TextSearch.query(search);
        var entries = craftables == null ? List.<TomsOrders.Offer>of() : craftables.offers().stream()
            .filter(TomsOrders.Offer::made)
            .filter(offer -> matches.test(TomsOrders.icon(offer.item()).getHoverName().getString())
                || matches.test(offer.item().toString()))
            .toList();
        first = Math.min(first, Math.max(0, (entries.size() - 1) / slots.size() * slots.size()));
        for (int i = 0; i < slots.size(); i++) {
            CraftSlot slot = slots.get(i);
            slot.entry = first + i < entries.size() ? entries.get(first + i) : null;
            slot.visible = true;
            slot.active = slot.entry != null;
            ItemStack stack = slot.active ? TomsOrders.icon(slot.entry.item()) : ItemStack.EMPTY;
            slot.setMessage(stack.isEmpty() ? Component.empty() : stack.getHoverName());
            var graphics = event.getGuiGraphics();
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 400);
            graphics.fill(slot.getX(), slot.getY(), slot.getX() + 16, slot.getY() + 16, 0xff716784);
            if (!stack.isEmpty()) {
                graphics.renderItem(stack, slot.getX(), slot.getY());
                if (slot.contains(event.getMouseX(), event.getMouseY())) {
                    graphics.renderTooltip(Minecraft.getInstance().font, stack.getHoverName(),
                        event.getMouseX(), event.getMouseY());
                }
            }
            graphics.pose().popPose();
        }
    }

    private static final class CraftSlot extends Button {
        @Nullable
        private TomsOrders.Offer entry;

        CraftSlot(int x, int y) {
            super(x, y, 16, 16, Component.empty(), ignored -> { }, DEFAULT_NARRATION);
            visible = false;
        }

        boolean contains(double x, double y) {
            return x >= getX() && x < getX() + 16 && y >= getY() && y < getY() + 16;
        }

        @Override
        protected void renderWidget(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            // Drawn over the terminal's own items in Render.Post.
        }

        @Override
        public void onPress() {
            if (entry != null && craftables != null) {
                TomsOrderPanel.open(craftables, entry.item());
            }
        }
    }

    static void heard(TomsOrderNetwork.Board board) {
        if (Minecraft.getInstance().screen == terminal && button != null && catalog != null) {
            button.visible = board.open();
            catalog.visible = board.open();
            if (!board.open()) {
                showCraftables = false;
                slots.forEach(slot -> slot.visible = false);
            }
        }
        if (board.answering() == TomsOrderNetwork.Action.CATALOG) {
            if (Minecraft.getInstance().screen == terminal && showCraftables) {
                craftables = board.open() ? board : null;
            }
            return;
        }
        if (board.answering() == TomsOrderNetwork.Action.OPEN && board.open() && !TomsOrderPanel.showing()) {
            TomsOrderPanel.open(board);
        } else if (board.answering() != TomsOrderNetwork.Action.PEEK) {
            TomsOrderPanel.heard(board);
        }
    }
}
