package io.github.izakyl.folkways.core.api.vocation;

import io.github.izakyl.folkways.effect.perk.Perk;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

public final class Vocation {

    private final ResourceLocation id;
    private final int order;
    private final List<Perk> perks;
    private final List<ItemSpec> kit;

    Vocation(ResourceLocation id, List<Perk> perks, List<ItemSpec> kit, int order) {
        this.id = id;
        this.order = order;
        this.perks = List.copyOf(perks);
        this.kit = List.copyOf(kit);
    }

    public ResourceLocation id() {
        return id;
    }

    public int order() {
        return order;
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
