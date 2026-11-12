package io.github.izakyl.folkways.core.vocation.api;

import io.github.izakyl.folkways.core.effect.perk.Perk;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

public final class Vocation {

    private final ResourceLocation id;
    private final int order;
    private final boolean defaultAllowed;
    private final Keenness defaultPriority;
    private final List<Perk> perks;
    private final List<ItemSpec> kit;
    private final VocationGroup group;

    public Vocation(ResourceLocation id, List<Perk> perks, List<ItemSpec> kit, int order, Keenness defaultPriority) {
        this(id, perks, kit, order, defaultPriority, VocationGroup.OTHER);
    }

    public Vocation(ResourceLocation id, List<Perk> perks, List<ItemSpec> kit, int order, Keenness defaultPriority,
        VocationGroup group) {
        this(id, perks, kit, order, defaultPriority, group, true);
    }

    public Vocation(ResourceLocation id, List<Perk> perks, List<ItemSpec> kit, int order, Keenness defaultPriority,
        VocationGroup group, boolean defaultAllowed) {
        this.defaultAllowed = defaultAllowed;
        this.id = id;
        this.group = group == null ? VocationGroup.OTHER : group;
        this.order = order;
        this.defaultPriority = defaultPriority;
        this.perks = List.copyOf(perks);
        this.kit = List.copyOf(kit);
    }

    public ResourceLocation id() {
        return id;
    }

    public boolean defaultAllowed() {
        return defaultAllowed;
    }

    public int order() {
        return order;
    }

    public VocationGroup group() {
        return group;
    }

    public Keenness defaultPriority() {
        return defaultPriority;
    }

    public List<Perk> perks() {
        return perks;
    }

    public List<ItemSpec> kit() {
        return Vocations.kit(id, kit);
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
