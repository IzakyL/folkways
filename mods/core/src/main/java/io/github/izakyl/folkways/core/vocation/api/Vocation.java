package io.github.izakyl.folkways.core.vocation.api;

import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.effect.perk.Perk;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

public final class Vocation {

    private final ResourceLocation id;
    private final int order;
    private final Keenness defaultPriority;
    private final List<Perk> perks;
    private final List<ItemSpec> kit;

    public Vocation(ResourceLocation id, List<Perk> perks, List<ItemSpec> kit, int order, Keenness defaultPriority) {
        this.id = id;
        this.order = order;
        this.defaultPriority = defaultPriority;
        this.perks = List.copyOf(perks);
        this.kit = List.copyOf(kit);
    }

    public ResourceLocation id() {
        return id;
    }

    public int order() {
        return order;
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
