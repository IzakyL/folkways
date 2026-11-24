package io.github.izakyl.folkways.vanilla.compost;

import io.github.izakyl.folkways.core.card.api.Card;
import io.github.izakyl.folkways.core.card.api.notice.Line;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.front.api.ColonyPanel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * The seeds kept back from composting, one row per kind the colony has come across, on the card of composting's
 * settings: how many are kept, whether by the default or a reserve of the kind's own, and buttons that move it.
 */
final class SeedReservePanel implements ColonyPanel {
    static final String MORE = "compost_reserve_more";
    static final String FEWER = "compost_reserve_fewer";
    static final String RESET = "compost_reserve_reset";
    static final int STEP = 16;
    private static final String ITEM = "item";
    private static final String KEY = "folkways.settings.compost.";

    private final Colony colony;

    SeedReservePanel(Colony colony) {
        this.colony = colony;
    }

    @Override
    public List<Card.Row> rows(Page page, ColonyView view) {
        if (page != Page.SETTINGS) {
            return List.of();
        }
        if (!colony.inventory().snapshot().coverage().complete()) {
            colony.inventory().ensureObserved();
            return List.of();
        }
        List<Card.Row> rows = new ArrayList<>();
        for (Item item : CompostSettings.discovered(colony, view.level().getServer())) {
            int count = CompostSettings.reserve(colony, item);
            boolean own = CompostSettings.overridden(colony, item);
            Map<String, Value> which = Map.of(ITEM, new Value.Text(BuiltInRegistries.ITEM.getKey(item).toString()));
            Card.Row row = Card.Row.of(item.getDefaultInstance(), Line.text(named(item)), List.of(Line.text(
                    Component.translatable(KEY + "reserve", count,
                        Component.translatable(KEY + (own ? "custom" : "inherited"))))))
                .with(new Card.Act.Run(FEWER, which, new Card.Label("−" + STEP, false), Optional.empty()))
                .with(new Card.Act.Run(MORE, which, new Card.Label("+" + STEP, false), Optional.empty()));
            rows.add(own ? row.with(new Card.Act.Run(RESET, which, new Card.Label(KEY + "use_default", true),
                Optional.empty())) : row);
        }
        return List.copyOf(rows);
    }

    @Override
    public Optional<List<DefinedFacilities.Problem>> act(ServerPlayer by, String routine,
            Map<String, Value> arguments) {
        if (!List.of(MORE, FEWER, RESET).contains(routine)) {
            return Optional.empty();
        }
        ResourceLocation id = arguments.get(ITEM) instanceof Value.Text text ? ResourceLocation.tryParse(text.value())
            : null;
        Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
        if (item == Items.AIR) {
            return Optional.of(List.of(new DefinedFacilities.Problem(ITEM, "invalid", String.valueOf(id))));
        }
        switch (routine) {
            case MORE -> CompostSettings.reserve(colony, item, CompostSettings.reserve(colony, item) + STEP);
            case FEWER -> CompostSettings.reserve(colony, item, Math.max(0, CompostSettings.reserve(colony, item) - STEP));
            default -> CompostSettings.reset(colony, item);
        }
        return Optional.of(List.of());
    }

    // A kind of seed by each name it goes by, as Minecraft's seeds and their alternatives share one reserve.
    private static Component named(Item item) {
        MutableComponent name = Component.empty();
        for (Item alternative : SeedReserves.alternatives(item)) {
            if (!name.getSiblings().isEmpty()) {
                name.append(" / ");
            }
            name.append(alternative.getDescription());
        }
        return name.getSiblings().isEmpty() ? item.getDescription() : name;
    }
}
