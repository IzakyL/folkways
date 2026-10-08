package io.github.izakyl.folkways.core.api.vocation;

import io.github.izakyl.folkways.core.api.perk.Perk;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

public record VocationSpec(ResourceLocation id, List<Perk> perks, List<ItemSpec> kit) {

    public VocationSpec {
        perks = List.copyOf(perks);
        kit = List.copyOf(kit);
    }

    public VocationSpec(ResourceLocation id, List<Perk> perks) {
        this(id, perks, List.of());
    }

    public static VocationSpec of(ResourceLocation id) {
        return new VocationSpec(id, List.of(), List.of());
    }
}
