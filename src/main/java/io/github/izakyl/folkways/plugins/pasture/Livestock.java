package io.github.izakyl.folkways.plugins.pasture;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

record Livestock(ResourceLocation type, ItemSpec feed, Optional<Item> lays) {

    static final Livestock COW = of(EntityType.COW, Items.WHEAT);

    static final List<Livestock> ALL = List.of(
        COW,
        of(EntityType.SHEEP, Items.WHEAT),
        of(EntityType.PIG, Items.CARROT),
        of(EntityType.CHICKEN, Items.WHEAT_SEEDS, Items.EGG));

    static Optional<Livestock> named(ResourceLocation type) {
        for (Livestock kind : ALL) {
            if (kind.type.equals(type)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    static List<ResourceLocation> types() {
        return ALL.stream().map(Livestock::type).toList();
    }

    private static Livestock of(EntityType<?> type, Item feed) {
        return new Livestock(BuiltInRegistries.ENTITY_TYPE.getKey(type),
            ItemSpec.of(BuiltInRegistries.ITEM.getKey(feed)), Optional.empty());
    }

    private static Livestock of(EntityType<?> type, Item feed, Item lays) {
        return new Livestock(BuiltInRegistries.ENTITY_TYPE.getKey(type),
            ItemSpec.of(BuiltInRegistries.ITEM.getKey(feed)), Optional.of(lays));
    }
}
