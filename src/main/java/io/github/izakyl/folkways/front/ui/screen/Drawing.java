package io.github.izakyl.folkways.front.ui.screen;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.izakyl.folkways.front.api.Delegation;
import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import io.github.izakyl.folkways.front.engine.Enrollments;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public final class Drawing {

    private static final int LIST_WIDTH = 104;
    private static final int MARK_WIDTH = 2;
    private static final int MARK_ON = 0xFFE0BC76;

    public interface Held {

        Optional<Component> measure();

        /** How much was drawn, in what a shape's limit counts: the cells of a box, the points of a line. */
        int extent();

        void drop();

        void hand(ResourceLocation delegation, net.minecraft.nbt.CompoundTag settings);

        /** What an offer asks before it takes the shape, to stand beside the list as a delegation's settings do. */
        default Optional<UIElement> offered(ResourceLocation offer) {
            return Optional.empty();
        }

        /** The offer whose preview of this shape stands in the world now, to open on again. */
        default Optional<ResourceLocation> previewed() {
            return Optional.empty();
        }
    }

    public record Offered(ResourceLocation id, String nameKey, ResourceLocation icon) {
    }

    private static final Map<Shape.Gesture, Held> HELD = new EnumMap<>(Shape.Gesture.class);

    private static Supplier<java.util.UUID> selection = () -> null;

    public static void selection(Supplier<java.util.UUID> installed) { selection = installed; }

    static String selected() {
        java.util.UUID id = selection.get();
        return id == null ? "" : id.toString();
    }

    static boolean active() {
        return HELD.values().stream().anyMatch(held -> held.measure().isPresent());
    }

    private static final Map<Shape.Gesture, Supplier<List<Offered>>> OFFERS = new EnumMap<>(Shape.Gesture.class);

    public static void install(Shape.Gesture gesture, Held held) {
        HELD.put(gesture, held);
    }

    public static void offers(Shape.Gesture gesture, Supplier<List<Offered>> installed) {
        OFFERS.put(gesture, installed);
    }

    private sealed interface Picked {

        record Ground(ResourceLocation delegation) implements Picked {
        }

        record Offer(ResourceLocation id) implements Picked {
        }
    }

    private record Candidate(Picked pick, Shape.Gesture gesture, Button row, UIElement mark, UIElement pane, int most,
                             Component limit) {
    }

    private final List<Candidate> candidates = new ArrayList<>();
    private final Label unavailable = Rows.text(Component.translatable("folkways.zoneconfig.no_types"));
    private final Label over = Rows.name(Component.empty());
    private final Button confirm = new Button();
    private UIElement body;
    private UIElement split;
    private UIElement commit;
    private final Map<ResourceLocation, DraftSettings> parameters = new java.util.LinkedHashMap<>();

    private Shape.Gesture holding;
    private Candidate picked;
    private Candidate worn;

    private Drawing() {
    }

    public static Drawing create() {
        return new Drawing();
    }

    public UIElement build() {
        // What the shape can become is listed down the left; whichever is picked asks what it needs on the right.
        UIElement list = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.COLUMN).widthPercent(100).gapAll(1));
        UIElement detail = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
                .flexGrow(1).flexShrink(1).minWidth(0).minHeight(0).gapAll(5));
        detail.setOverflowVisible(false);
        for (Delegation declared : Enrollments.delegations()) {
            if (declared.shape().gesture().drawn()) {
                add(list, detail, new Picked.Ground(declared.id()), declared.shape().gesture(),
                    Component.translatable("folkways.delegation."
                        + declared.id().getNamespace() + "." + declared.id().getPath()),
                    declared.icon(), mostOf(declared), limitOf(declared), settingsOf(declared));
            }
        }
        for (Map.Entry<Shape.Gesture, Supplier<List<Offered>>> gesture : OFFERS.entrySet()) {
            for (Offered offered : gesture.getValue().get()) {
                add(list, detail, new Picked.Offer(offered.id()), gesture.getKey(),
                    Component.translatable(offered.nameKey()), offered.icon(), Integer.MAX_VALUE, Component.empty(),
                    Rows.page());
            }
        }

        confirm.setText(Component.translatable("folkways.action.confirm"));
        confirm.setOnClick(event -> hand());
        Tokens.name(confirm, "folkways.zoneconfig.confirm");
        confirm.layout(layout -> layout.flexShrink(0));
        commit = Rows.strip().addChildren(over, confirm);
        detail.addChildren(commit);
        Button cancel = new Button()
            .setText(Component.translatable("folkways.action.cancel"))
            .setOnClick(event -> drop());
        cancel.layout(layout -> layout.flexShrink(0));
        Tokens.name(cancel, "folkways.zoneconfig.cancel");

        ScrollerView kinds = Rows.fills(Rows.box());
        kinds.addScrollViewChild(list);
        UIElement side = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
                .width(LIST_WIDTH).flexShrink(0).minHeight(0).gapAll(5))
            .addChildren(kinds, cancel);
        split = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.ROW)
                .widthPercent(100).flexGrow(1).flexShrink(1).minHeight(0).gapAll(Rows.GAP))
            .addChildren(side, detail);

        body = Rows.page().addChildren(unavailable, split);
        body.layout(layout -> layout.flexGrow(1).flexShrink(1).minHeight(0));
        body.setDisplay(false);

        UIElement root = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.COLUMN).widthPercent(100))
            .addChildren(body);
        root.addEventListener(UIEvents.TICK, event -> refresh());
        return root;
    }

    private UIElement settingsOf(Delegation declared) {
        DraftSettings form = new DraftSettings(declared.schema());
        parameters.put(declared.id(), form);
        ScrollerView scroller = Rows.fills(Rows.box());
        scroller.addScrollViewChild(form.build());
        form.display(true);
        UIElement pane = Rows.page();
        if (declared.schema().settings().isEmpty()) {
            pane.addChildren(Rows.note(Component.translatable("folkways.zoneconfig.no_settings")));
        }
        return pane.addChildren(scroller);
    }

    private void add(UIElement list, UIElement detail, Picked pick, Shape.Gesture gesture, Component name,
            ResourceLocation icon, int most, Component limit, UIElement pane) {
        Button row = new Button().noText();
        Tokens.name(row, switch (pick) {
            case Picked.Ground ground -> "folkways.zoneconfig.kind." + Tokens.of(ground.delegation());
            case Picked.Offer offer -> "folkways.zoneconfig.offer." + Tokens.of(offer.id());
        });
        row.layout(layout -> layout.flexDirection(FlexDirection.ROW)
            .widthPercent(100).height(Rows.ROW_HEIGHT).flexShrink(0)
            .alignItems(AlignItems.CENTER).gapAll(Rows.GAP - 1).paddingAll(1));
        row.addClass("folkways_nav");
        row.setOverflowVisible(false);
        UIElement mark = new UIElement()
            .layout(layout -> layout.width(MARK_WIDTH).heightPercent(100).flexShrink(0))
            .style(style -> style.background(new ColorRectTexture(MARK_ON)));
        mark.setDisplay(false);
        row.addChildren(mark, Rows.icon(SettingsPage.iconOf(icon)), Rows.name(name));
        pane.layout(layout -> layout.flexGrow(1).flexShrink(1).minHeight(0));
        pane.setDisplay(false);
        Candidate candidate = new Candidate(pick, gesture, row, mark, pane, most, limit);
        row.setOnClick(event -> pick(candidate));
        candidates.add(candidate);
        list.addChildren(row);
        detail.addChildren(pane);
    }

    private void pick(Candidate candidate) {
        picked = candidate;
        if (candidate.pick() instanceof Picked.Offer offer) {
            candidate.pane().clearAllChildren();
            held().flatMap(held -> held.offered(offer.id())).ifPresent(candidate.pane()::addChildren);
        }
        refresh();
    }

    private void refresh() {
        if (!LDLib2.isRemote()) {
            return;
        }
        holding = null;
        for (Map.Entry<Shape.Gesture, Held> entry : HELD.entrySet()) {
            if (entry.getValue().measure().isPresent()) {
                holding = entry.getKey();
                break;
            }
        }
        body.setDisplay(holding != null);
        if (holding == null) {
            if (picked != null) {
                // What an offer drew was drawn for the shape now gone.
                candidates.stream().filter(candidate -> candidate.pick() instanceof Picked.Offer)
                    .forEach(candidate -> candidate.pane().clearAllChildren());
                picked = null;
            }
            return;
        }
        Optional<ResourceLocation> previewed = held().flatMap(Held::previewed);
        Candidate first = null;
        Candidate resumed = null;
        boolean stands = false;
        for (Candidate candidate : candidates) {
            boolean shown = candidate.gesture() == holding;
            candidate.row().setDisplay(shown);
            if (shown && first == null) {
                first = candidate;
            }
            if (shown && candidate.pick() instanceof Picked.Offer offer && previewed.equals(Optional.of(offer.id()))) {
                resumed = candidate;
            }
            stands |= shown && candidate == picked;
        }
        unavailable.setDisplay(first == null);
        split.setDisplay(first != null);
        if (!stands) {
            picked = null;
            if (first != null) {
                // The page opens again on an offer whose preview is out in the world, and otherwise on the
                // first, so it never opens on an empty half.
                pick(resumed != null ? resumed : first);
                return;
            }
        }
        for (Candidate candidate : candidates) {
            candidate.pane().setDisplay(candidate == picked);
        }
        commit.setDisplay(picked != null && picked.pick() instanceof Picked.Ground);
        // A limit is only spoken of once what was drawn is past it, in the place of the button it withholds.
        boolean fits = picked == null || held().map(Held::extent).orElse(0) <= picked.most();
        confirm.setDisplay(fits);
        over.setText(fits ? Component.empty() : picked.limit());
        if (worn != picked) {
            worn = picked;
            for (Candidate candidate : candidates) {
                boolean marked = candidate == picked;
                candidate.mark().setDisplay(marked);
                if (marked) {
                    candidate.row().addClass("folkways_selected");
                } else {
                    candidate.row().removeClass("folkways_selected");
                }
            }
        }
    }

    private static int mostOf(Delegation declared) {
        return switch (declared.shape()) {
            case Shape.Volume volume -> volume.maxCells();
            case Shape.Path path -> path.maxPoints();
            case Shape.Block ignored -> Integer.MAX_VALUE;
            case Shape.Body ignored -> Integer.MAX_VALUE;
        };
    }

    private static Component limitOf(Delegation declared) {
        return switch (declared.shape()) {
            case Shape.Volume volume ->
                Component.translatable("folkways.zoneconfig.cells", volume.maxCells());
            case Shape.Path path ->
                Component.translatable("folkways.zoneconfig.points", path.maxPoints());
            case Shape.Block ignored -> Component.empty();
            case Shape.Body ignored -> Component.empty();
        };
    }

    private void drop() {
        held().ifPresent(Held::drop);
    }

    private void hand() {
        if (picked == null || !(picked.pick() instanceof Picked.Ground ground)
                || held().map(Held::extent).orElse(0) > picked.most()) {
            return;
        }
        held().ifPresent(held -> held.hand(ground.delegation(),
            parameters.get(ground.delegation()).settings().save()));
    }

    private Optional<Held> held() {
        return holding == null ? Optional.empty() : Optional.ofNullable(HELD.get(holding));
    }
}
