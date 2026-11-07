package io.github.izakyl.folkways.core.vocation.api;

import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.effect.perk.Perk;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

public record VocationSpec(ResourceLocation id, List<Perk> perks, List<ItemSpec> kit, int order,
    Keenness defaultPriority) {

    public VocationSpec {
        perks = List.copyOf(perks);
        kit = List.copyOf(kit);
    }

    public VocationSpec(ResourceLocation id, List<Perk> perks, List<ItemSpec> kit, int order) {
        this(id, perks, kit, order, Keenness.DEFAULT);
    }

    public VocationSpec priority(Keenness priority) {
        return new VocationSpec(id, perks, kit, order, priority);
    }

    public VocationSpec(ResourceLocation id, List<Perk> perks, List<ItemSpec> kit) {
        this(id, perks, kit, Integer.MAX_VALUE);
    }

    /** Lower values come first; equal values use the full id in lexical order on both sides. */
    public VocationSpec ordered(int order) {
        return new VocationSpec(id, perks, kit, order, defaultPriority);
    }

    public VocationSpec(ResourceLocation id, List<Perk> perks) {
        this(id, perks, List.of());
    }

    public static VocationSpec of(ResourceLocation id) {
        return new VocationSpec(id, List.of(), List.of());
    }
}
