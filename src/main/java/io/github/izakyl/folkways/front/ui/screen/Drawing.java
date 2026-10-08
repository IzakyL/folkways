package io.github.izakyl.folkways.front.ui.screen;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
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
import net.minecraft.world.item.ItemStack;

public final class Drawing {

    private static final String PICKED = "> ";

    public interface Held {

        Optional<Component> measure();

        void drop();

        void hand(ResourceLocation delegation, net.minecraft.nbt.CompoundTag settings);

        default void take(ResourceLocation offer) {
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

    private record Candidate(Picked pick, Shape.Gesture gesture, Component name, Button row) {
    }

    private final List<Candidate> candidates = new ArrayList<>();
    private final Label question = Rows.text(Component.empty());
    private final Label measure = Rows.note(Component.empty());
    private final Label unavailable = Rows.text(Component.translatable("folkways.zoneconfig.no_types"));
    private final Button confirm = new Button();
    private UIElement body;
    private final Map<ResourceLocation, DraftSettings> parameters = new java.util.LinkedHashMap<>();

    private Shape.Gesture holding;
    private Picked picked;

    private Drawing() {
    }

    public static Drawing create() {
        return new Drawing();
    }

    public UIElement build() {
        UIElement table = Rows.strip();
        table.layout(layout -> layout.flexWrap(FlexWrap.WRAP).gapAll(Rows.GAP));
        for (Delegation declared : Enrollments.delegations()) {
            if (declared.shape().gesture().drawn()) {
                add(table, new Picked.Ground(declared.id()), declared.shape().gesture(),
                    groundText(declared), ItemStack.EMPTY);
            }
        }
        for (Map.Entry<Shape.Gesture, Supplier<List<Offered>>> gesture : OFFERS.entrySet()) {
            for (Offered offered : gesture.getValue().get()) {
                add(table, new Picked.Offer(offered.id()), gesture.getKey(),
                    Component.translatable(offered.nameKey()), SettingsPage.iconOf(offered.icon()));
            }
        }

        question.layout(layout -> layout.widthPercent(100).flexShrink(1).minWidth(0));
        measure.layout(layout -> layout.widthPercent(100).flexShrink(1).minWidth(0));
        confirm.setText(Component.translatable("folkways.zoneconfig.confirm"));
        confirm.setOnClick(event -> hand());
        Tokens.name(confirm, "folkways.zoneconfig.confirm");
        Button cancel = new Button()
            .setText(Component.translatable("folkways.action.cancel"))
            .setOnClick(event -> drop());
        Tokens.name(cancel, "folkways.zoneconfig.cancel");

        UIElement forms = Rows.page();
        for (Delegation declared : Enrollments.delegations()) {
            if (!declared.shape().gesture().drawn()) continue;
            DraftSettings form = new DraftSettings(declared.schema());
            parameters.put(declared.id(), form);
            forms.addChildren(form.build());
        }
        var scroller = Rows.fills(Rows.box());
        scroller.addScrollViewChild(Rows.page().addChildren(table, forms));
        body = Rows.page().addChildren(question, measure, unavailable, scroller,
            Rows.actions(cancel, confirm));
        body.layout(layout -> layout.flexGrow(1).flexShrink(1).minHeight(0));
        body.setDisplay(false);

        UIElement root = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.COLUMN).widthPercent(100))
            .addChildren(body);
        root.addEventListener(UIEvents.TICK, event -> refresh());
        return root;
    }

    private void add(UIElement table, Picked pick, Shape.Gesture gesture, Component name, ItemStack icon) {
        Button row = new Button().setText(name);
        Tokens.name(row, switch (pick) {
            case Picked.Ground ground -> "folkways.zoneconfig.kind." + Tokens.of(ground.delegation());
            case Picked.Offer offer -> "folkways.zoneconfig.offer." + Tokens.of(offer.id());
        });
        row.layout(layout -> layout.flexShrink(0));
        if (!icon.isEmpty()) {
            row.addPreIcon(new ItemStackTexture(icon));
        }
        row.setOnClick(event -> {
            picked = pick;
            refresh();
        });
        candidates.add(new Candidate(pick, gesture, name, row));
        table.addChildren(row);
    }

    private void refresh() {
        if (!LDLib2.isRemote()) {
            return;
        }
        holding = null;
        Component measured = null;
        for (Map.Entry<Shape.Gesture, Held> entry : HELD.entrySet()) {
            Optional<Component> answer = entry.getValue().measure();
            if (answer.isPresent()) {
                holding = entry.getKey();
                measured = answer.get();
                break;
            }
        }
        body.setDisplay(holding != null);
        if (holding == null) {
            picked = null;
            return;
        }
        question.setText(Component.translatable(holding == Shape.Gesture.LINE
            ? "folkways.zoneconfig.path" : "folkways.zoneconfig.new"));
        measure.setText(measured);
        boolean offered = false;
        boolean available = false;
        for (Candidate candidate : candidates) {
            boolean shown = candidate.gesture() == holding;
            candidate.row().setDisplay(shown);
            available |= shown;
            boolean marked = shown && candidate.pick().equals(picked);
            offered |= marked;
            candidate.row().setText(Component.literal(marked ? PICKED : "").append(candidate.name()));
            if (marked) {
                candidate.row().addClass("folkways_chosen");
            } else {
                candidate.row().removeClass("folkways_chosen");
            }
        }
        unavailable.setDisplay(!available);
        if (!offered) {
            picked = null;
        }
        parameters.forEach((id, form) -> form.display(
            picked instanceof Picked.Ground ground && ground.delegation().equals(id)));
        confirm.setDisplay(picked != null);
    }

    private static Component groundText(Delegation declared) {
        Component cap = switch (declared.shape()) {
            case Shape.Volume volume ->
                Component.translatable("folkways.zoneconfig.cells", volume.maxCells());
            case Shape.Path path ->
                Component.translatable("folkways.zoneconfig.points", path.maxPoints());
            case Shape.Block ignored -> Component.empty();
            case Shape.Body ignored -> Component.empty();
        };
        return Component.translatable("folkways.delegation."
                + declared.id().getNamespace() + "." + declared.id().getPath())
            .append(" ")
            .append(cap);
    }

    private void drop() {
        held().ifPresent(Held::drop);
    }

    private void hand() {
        Picked pick = picked;
        if (pick == null) {
            return;
        }
        held().ifPresent(held -> {
            switch (pick) {
                case Picked.Ground ground -> held.hand(ground.delegation(),
                    parameters.get(ground.delegation()).settings().save());
                case Picked.Offer offer -> held.take(offer.id());
            }
        });
    }

    private Optional<Held> held() {
        return holding == null ? Optional.empty() : Optional.ofNullable(HELD.get(holding));
    }
}
