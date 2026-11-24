package io.github.izakyl.folkways.core.front.engine.operation;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.item.api.ItemFilter;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.core.front.engine.colony.ColonySchemas;
import io.github.izakyl.folkways.core.front.engine.colony.ColonySettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;

public final class SettingChanges {

    private SettingChanges() {
    }

    public static List<ItemFilter> keptItems(Schema.Setting.Items items, List<ItemFilter> filters) {
        List<ItemFilter> kept = new ArrayList<>();
        for (ItemFilter filter : filters) {
            boolean allowed = switch (filter.kind()) {
                case ITEM -> BuiltInRegistries.ITEM.getOptional(filter.id())
                        .filter(item -> item != Items.AIR && (!items.blocks() || item instanceof BlockItem))
                        .isPresent();
                case TAG, QUERY -> !items.single();
            };
            if (kept.size() < 64 && allowed && !kept.contains(filter)) {
                kept.add(filter);
            }
        }
        return List.copyOf(kept);
    }

    public static Optional<ColonySettings.Value> validated(Schema.Setting declared, ColonySettings.Value value) {
        return switch (declared) {
            case Schema.Setting.Flag ignored -> value instanceof ColonySettings.Value.Flag
                    ? Optional.of(value) : Optional.empty();
            case Schema.Setting.Count count -> value instanceof ColonySettings.Value.Count set
                    ? Optional.of(new ColonySettings.Value.Count(Math.clamp(set.value(), count.min(), count.max())))
                    : Optional.empty();
            case Schema.Setting.Choice choice -> value instanceof ColonySettings.Value.Choice set
                    && choice.options().contains(set.value()) ? Optional.of(value) : Optional.empty();
            case Schema.Setting.Items items -> {
                if (!(value instanceof ColonySettings.Value.Items set)) {
                    yield Optional.empty();
                }
                List<ItemFilter> kept = keptItems(items, set.value());
                yield items.single() && kept.size() != 1 ? Optional.empty()
                        : Optional.of(new ColonySettings.Value.Items(kept));
            }
        };
    }

    public static Schema schema(Colony colony, ResourceLocation scope, Optional<UUID> drawn) {
        return drawn.map(id -> colony.claim(id).map(held -> ColonySchemas.ofDelegation(held.owner()))
                .orElseGet(Schema::none)).orElseGet(() -> ColonySchemas.of(scope));
    }

    public static Optional<ColonySettings> settings(Colony colony, ResourceLocation scope, Optional<UUID> drawn) {
        ColonyFront front = ColonyFront.of(colony);
        if (drawn.isEmpty()) {
            return Optional.of(front.settings(scope));
        }
        return front.zone(drawn.get()).map(zone -> zone.settings())
                .or(() -> front.path(drawn.get()).map(path -> path.settings()));
    }

    public static boolean set(Colony colony, ResourceLocation scope, Optional<UUID> drawn, String key,
            ColonySettings.Value value) {
        Schema schema = schema(colony, scope, drawn);
        var valid = schema.find(key).flatMap(declared -> validated(declared, value));
        if (valid.isEmpty() || settings(colony, scope, drawn).isEmpty()) {
            return false;
        }
        ColonyFront front = ColonyFront.of(colony);
        return drawn.map(id -> front.setDrawn(id, settings(colony, scope, drawn).orElseThrow()
                .with(key, valid.get()).conformedTo(schema)))
                .orElseGet(() -> front.setSetting(scope, key, valid.get()));
    }
}
