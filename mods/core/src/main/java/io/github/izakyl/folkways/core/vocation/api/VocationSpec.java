package io.github.izakyl.folkways.core.vocation.api;

import io.github.izakyl.folkways.core.effect.perk.Perk;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

public record VocationSpec(ResourceLocation id, List<Perk> perks, List<ItemSpec> kit, int order,
    Keenness defaultPriority, VocationGroup group, boolean defaultAllowed) {

    public VocationSpec {
        perks = List.copyOf(perks);
        kit = List.copyOf(kit);
        group = group == null ? VocationGroup.OTHER : group;
    }

    public VocationSpec(ResourceLocation id, List<Perk> perks, List<ItemSpec> kit, int order,
        Keenness defaultPriority, VocationGroup group) {
        this(id, perks, kit, order, defaultPriority, group, true);
    }

    public VocationSpec allowedByDefault(boolean allowed) {
        return new VocationSpec(id, perks, kit, order, defaultPriority, group, allowed);
    }

    public VocationSpec(ResourceLocation id, List<Perk> perks, List<ItemSpec> kit, int order,
        Keenness defaultPriority) {
        this(id, perks, kit, order, defaultPriority, VocationGroup.OTHER);
    }

    public VocationSpec(ResourceLocation id, List<Perk> perks, List<ItemSpec> kit, int order) {
        this(id, perks, kit, order, Keenness.DEFAULT);
    }

    public VocationSpec priority(Keenness priority) {
        return new VocationSpec(id, perks, kit, order, priority, group, defaultAllowed);
    }

    public VocationSpec(ResourceLocation id, List<Perk> perks, List<ItemSpec> kit) {
        this(id, perks, kit, Integer.MAX_VALUE);
    }

    /** Lower values come first within a group; equal values use the full id in lexical order on both sides. */
    public VocationSpec ordered(int order) {
        return new VocationSpec(id, perks, kit, order, defaultPriority, group, defaultAllowed);
    }

    /** The heading this vocation's column stands under in the colony book. */
    public VocationSpec grouped(VocationGroup group) {
        return new VocationSpec(id, perks, kit, order, defaultPriority, group, defaultAllowed);
    }

    public VocationSpec(ResourceLocation id, List<Perk> perks) {
        this(id, perks, List.of());
    }

    public static VocationSpec of(ResourceLocation id) {
        return new VocationSpec(id, List.of(), List.of());
    }
}
