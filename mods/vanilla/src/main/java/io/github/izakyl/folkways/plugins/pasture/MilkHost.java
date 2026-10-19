package io.github.izakyl.folkways.plugins.pasture;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Intent;
import io.github.izakyl.folkways.core.api.work.Produce;
import io.github.izakyl.folkways.core.api.work.Refinement;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Workshop;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;

record MilkHost(Quarry cow, Herd herd) implements Workshop {

    static final ItemSpec BUCKET = ItemSpec.of(BuiltInRegistries.ITEM.getKey(Items.BUCKET));
    static final ItemSpec MILK = ItemSpec.of(BuiltInRegistries.ITEM.getKey(Items.MILK_BUCKET));

    static final int LABOR_TICKS = 20;

    @Override
    public ResourceLocation id() {
        return PastureContent.ID;
    }

    @Override
    public UUID key() {
        return cow.subject();
    }

    @Override
    public WorkSite site() {
        return cow.at();
    }

    @Override
    public List<Refinement.Change> options(Intent intent, Grown graph) {
        if (!(intent instanceof Produce produce) || !produce.wanted().admits(MILK)) {
            return List.of();
        }
        return List.of(Refinement.Change.of(Grown.of(new MilkNode(cow.at(), cow.stances(), cow.subject(), herd))));
    }
}
