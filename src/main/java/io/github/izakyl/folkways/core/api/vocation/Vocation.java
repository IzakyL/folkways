package io.github.izakyl.folkways.core.api.vocation;

import io.github.izakyl.folkways.core.api.perk.Perk;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

public final class Vocation {

    private final ResourceLocation id;
    private final List<Perk> perks;
    private final List<ItemSpec> kit;

    Vocation(ResourceLocation id, List<Perk> perks, List<ItemSpec> kit) {
        this.id = id;
        this.perks = List.copyOf(perks);
        this.kit = List.copyOf(kit);
    }

    public ResourceLocation id() {
        return id;
    }

    public List<Perk> perks() {
        return perks;
    }

    public List<ItemSpec> kit() {
        return kit;
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
