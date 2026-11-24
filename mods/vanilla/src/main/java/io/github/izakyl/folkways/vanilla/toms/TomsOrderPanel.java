package io.github.izakyl.folkways.vanilla.toms;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.izakyl.folkways.core.front.api.ui.LonePanel;
import io.github.izakyl.folkways.core.front.api.ui.Pane;
import io.github.izakyl.folkways.core.front.api.ui.Rows;
import io.github.izakyl.folkways.core.front.api.ui.TextSearch;
import io.github.izakyl.folkways.core.front.api.ui.Tokens;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

/**
 * The order panel over a Tom's terminal, after an ME terminal's crafting request: pick what the colony can bring,
 * set how many, order it, and follow the requests already made for this network.
 */
@OnlyIn(Dist.CLIENT)
final class TomsOrderPanel {

    private static final String KEY = TomsOrdersClient.KEY;
    private static final String ID = "toms_orders";
    private static final float WIDTH = 320f;
    private static final float HEIGHT = 400f;
    private static final float CONFIRM_WIDTH = 224f;
    private static final float CONFIRM_HEIGHT = 130f;
    private static final int LIST_HEIGHT = Rows.ROW_HEIGHT * 5;
    private static final int REQUEST_HEIGHT = Rows.ROW_HEIGHT * 4;
    private static final int MOST = 9999;
    private static final int[] STEPS = {1, 10, 64};

    @Nullable
    private static TomsOrderPanel shown;

    private final boolean compact;
    private TomsOrderNetwork.Board board;
    private Pane pane;
    @Nullable
    private Screen screen;
    private String query = "";
    @Nullable
    private ResourceLocation chosen;
    private final UIElement offers = Rows.table();
    private final UIElement requests = Rows.table();
    private final UIElement chosenIcon = Rows.icon(ItemStack.EMPTY);
    private final Label chosenName = Rows.name(Component.empty());
    private final TextField amount = new TextField();
    private final Button confirm = new Button();
    private final Label said = Rows.note(Component.empty());
    private final Label empty = Rows.note(Component.translatable(KEY + ".none"));

    private TomsOrderPanel(TomsOrderNetwork.Board board, @Nullable ResourceLocation item) {
        this.board = board;
        compact = item != null;
        chosen = item;
    }

    // A panel whose screen was closed (Esc, or the terminal going away) stays open as a window but is no longer shown.
    static boolean showing() {
        return shown != null && shown.pane != null && shown.pane.isOpen()
            && Minecraft.getInstance().screen == shown.screen;
    }

    static void open(TomsOrderNetwork.Board board) {
        open(board, null);
    }

    static void open(TomsOrderNetwork.Board board, @Nullable ResourceLocation item) {
        TomsOrderPanel panel = new TomsOrderPanel(board, item);
        shown = panel;
        String title = KEY + (panel.compact ? ".confirm" : ".title");
        LonePanel.open(panel.compact ? "toms_order_confirm" : ID, Component.translatable(title),
            panel.compact ? CONFIRM_WIDTH : WIDTH, panel.compact ? CONFIRM_HEIGHT : HEIGHT, panel::build);
        panel.screen = Minecraft.getInstance().screen;
    }

    static void heard(TomsOrderNetwork.Board board) {
        if (!showing()) {
            return;
        }
        if (shown.compact) {
            if (board.answering() == TomsOrderNetwork.Action.PLACE) {
                if (board.open() && board.went()) {
                    shown.pane.close();
                    return;
                }
                shown.said.setText(board.closed().or(() -> board.said()).orElse(Component.empty())
                    .copy().withStyle(ChatFormatting.DARK_RED));
                shown.said.setDisplay(true);
                shown.confirm.setActive(true);
            }
            return;
        }
        if (!board.open()) {
            shown.said.setText(board.closed().orElse(Component.empty()));
            return;
        }
        shown.board = board;
        if (board.answering() == TomsOrderNetwork.Action.PLACE && board.went()) {
            shown.said.setText(Component.translatable(KEY + ".placed"));
        } else {
            shown.said.setText(board.said().map(text -> text.copy().withStyle(ChatFormatting.DARK_RED))
                .orElse(Component.empty()));
        }
        shown.refresh();
    }

    private UIElement build(Pane pane) {
        this.pane = pane;
        UIElement page = Rows.page();
        amount.setNumbersOnlyInt(1, MOST);
        amount.setText("1");
        amount.layout(layout -> layout.width(40).flexShrink(0));
        Tokens.name(amount, KEY + ".amount");
        UIElement steps = Rows.strip();
        steps.layout(layout -> layout.widthAuto().flexShrink(0));
        for (int step : STEPS) {
            Button add = new Button().setText(Component.literal("+" + step)).setOnClick(event -> add(step));
            add.layout(layout -> layout.widthAuto().flexShrink(0));
            Tokens.name(add, KEY + ".add." + step);
            steps.addChildren(add);
        }
        confirm.setText(Component.translatable(KEY + ".confirm"));
        confirm.setOnClick(event -> place());
        confirm.layout(layout -> layout.widthAuto().flexShrink(0));
        Tokens.name(confirm, KEY + ".confirm");
        UIElement picked = Rows.row().addChildren(chosenIcon, chosenName, amount);
        Tokens.name(chosenName, KEY + ".chosen");
        Tokens.name(said, KEY + ".said");

        if (compact) {
            Button cancel = new Button().setText(Component.translatable("folkways.action.cancel"))
                .setOnClick(event -> pane.close());
            cancel.layout(layout -> layout.widthAuto().flexShrink(0));
            Tokens.name(cancel, KEY + ".back");
            said.setDisplay(false);
            refreshChosen();
            return page.addChildren(picked, steps, said, Rows.strip().addChildren(cancel, Rows.spacer(), confirm));
        }

        TextField search = new TextField();
        search.layout(layout -> layout.widthPercent(100).flexShrink(0));
        search.textFieldStyle(style -> style.placeholder(Rows.placeholder(Component.translatable(KEY + ".search"))));
        search.setTextResponder(typed -> {
            query = typed == null ? "" : typed.trim();
            refreshOffers();
        });
        Tokens.name(search, KEY + ".search");

        ScrollerView list = Rows.box();
        list.layout(layout -> layout.widthPercent(100).height(LIST_HEIGHT).flexShrink(0));
        list.addScrollViewChild(offers);
        Tokens.name(list, KEY + ".offers");
        Tokens.name(empty, KEY + ".none");

        ScrollerView asked = Rows.box();
        asked.layout(layout -> layout.widthPercent(100).height(REQUEST_HEIGHT).flexShrink(0));
        asked.addScrollViewChild(requests);
        Tokens.name(asked, KEY + ".requests");

        page.addChildren(Rows.note(Component.translatable(KEY + ".help")), search, empty, list, picked,
            Rows.strip().addChildren(steps, Rows.spacer(), confirm), said, Rows.heading(Component.translatable(KEY + ".here")), asked);
        refresh();
        return page;
    }

    private void refresh() {
        if (chosen != null && board.offers().stream().noneMatch(offer -> offer.item().equals(chosen))) {
            chosen = null;
        }
        if (chosen == null && !board.offers().isEmpty()) {
            chosen = board.offers().getFirst().item();
        }
        refreshOffers();
        refreshChosen();
        refreshRequests();
    }

    private void refreshOffers() {
        offers.clearAllChildren();
        Predicate<String> matches = TextSearch.query(query);
        int listed = 0;
        for (TomsOrders.Offer offer : board.offers()) {
            ItemStack icon = TomsOrders.icon(offer.item());
            if (icon.isEmpty() || !query.isEmpty() && !matches.test(icon.getHoverName().getString())
                    && !matches.test(offer.item().toString())) {
                continue;
            }
            Button row = Rows.wearing();
            row.layout(layout -> layout.flexDirection(FlexDirection.ROW).widthPercent(100).flexShrink(0)
                .height(Rows.ROW_HEIGHT - 2).gapAll(Rows.GAP).paddingHorizontal(2));
            MutableComponent name = icon.getHoverName().copy();
            if (offer.item().equals(chosen)) {
                name = Component.literal("▶ ").append(name).withStyle(ChatFormatting.BOLD);
            }
            row.addChildren(Rows.icon(icon), Rows.worn(name), Rows.spacer(), Rows.worn(counts(offer)));
            row.setOnClick(event -> {
                chosen = offer.item();
                refreshOffers();
                refreshChosen();
            });
            Tokens.name(row, KEY + ".offer." + Tokens.of(offer.item()));
            offers.addChildren(row);
            listed++;
        }
        empty.setDisplay(listed == 0);
    }

    private static Component counts(TomsOrders.Offer offer) {
        MutableComponent text = Component.translatable(KEY + ".counts", offer.here(), offer.elsewhere());
        if (offer.made()) {
            text.append(Component.translatable(KEY + ".made"));
        }
        return text.withColor(Rows.QUIET & 0xFFFFFF);
    }

    private void refreshChosen() {
        ItemStack icon = chosen == null ? ItemStack.EMPTY : TomsOrders.icon(chosen);
        chosenIcon.style(style -> style.background(new com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture(icon)));
        chosenName.setText(icon.isEmpty() ? Component.translatable(KEY + ".pick") : icon.getHoverName());
        confirm.setActive(chosen != null);
    }

    private void refreshRequests() {
        requests.clearAllChildren();
        if (board.requests().isEmpty()) {
            requests.addChildren(Rows.note(Component.translatable(KEY + ".here.empty")));
            return;
        }
        for (TomsOrders.Request request : board.requests()) {
            ItemStack icon = TomsOrders.icon(request.item());
            UIElement row = Rows.row();
            UIElement words = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
                .flexGrow(1).flexShrink(1).minWidth(0));
            words.addChildren(Rows.value(Component.translatable(KEY + ".request", request.asked(), icon.getHoverName())),
                Rows.value(detail(request).withColor(Rows.QUIET & 0xFFFFFF)));
            Label status = Rows.value(status(request));
            Tokens.name(status, KEY + ".status." + Tokens.of(request.item()));
            row.addChildren(Rows.icon(icon), words, status);
            request.reason().ifPresent(reason -> row.style(style -> style.tooltips(reason)));
            if (request.status() != TomsOrders.Status.DELIVERED) {
                Button cancel = Rows.act();
                cancel.addChildren(Rows.actIcon(new ItemStack(net.minecraft.world.item.Items.BARRIER)));
                cancel.style(style -> style.tooltips(Component.translatable(KEY + ".cancel")));
                cancel.setOnClick(event -> TomsOrdersClient.ask(TomsOrderNetwork.Action.CANCEL,
                    request.item().toString(), 0));
                Tokens.name(cancel, KEY + ".cancel." + Tokens.of(request.item()));
                row.addChildren(cancel);
            }
            Tokens.name(row, KEY + ".request." + Tokens.of(request.item()));
            requests.addChildren(row);
        }
    }

    private static MutableComponent detail(TomsOrders.Request request) {
        if (request.status() == TomsOrders.Status.STALLED && request.reason().isPresent()) {
            return request.reason().get().copy();
        }
        MutableComponent detail = Component.translatable("folkways.panel.orders.once", request.held(), request.high());
        if (request.coming() > 0) {
            detail.append(Component.translatable("folkways.panel.orders.coming", request.coming()));
        }
        return detail;
    }

    private static Component status(TomsOrders.Request request) {
        String word = switch (request.status()) {
            case WAITING -> "waiting";
            case WORKING -> "working";
            case STALLED -> "stalled";
            case DELIVERED -> "delivered";
        };
        ChatFormatting color = switch (request.status()) {
            case WAITING -> ChatFormatting.GOLD;
            case WORKING -> ChatFormatting.DARK_AQUA;
            case STALLED -> ChatFormatting.DARK_RED;
            case DELIVERED -> ChatFormatting.DARK_GREEN;
        };
        return Component.translatable(KEY + ".status." + word).withStyle(color);
    }

    private void add(int step) {
        long now = typed().orElse(0);
        amount.setText(Long.toString(Math.min(MOST, now + step)));
    }

    private Optional<Integer> typed() {
        try {
            return Optional.of(Integer.parseInt(amount.getValue().trim())).filter(count -> count > 0);
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private void place() {
        if (chosen == null) {
            return;
        }
        Optional<Integer> count = typed();
        if (count.isEmpty()) {
            said.setText(Component.translatable("folkways.refusal.order.no_count").withStyle(ChatFormatting.DARK_RED));
            said.setDisplay(true);
            return;
        }
        if (compact) {
            confirm.setActive(false);
        }
        TomsOrdersClient.ask(TomsOrderNetwork.Action.PLACE, chosen.toString(), count.get());
    }
}
