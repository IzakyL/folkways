package io.github.izakyl.folkways.cookery.seeds;

import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.world.item.Item;

/** Compatibility facade; seed reserves and native composting belong to Vanilla. */
public final class SeedBudget extends io.github.izakyl.folkways.plugins.compost.SeedBudget {
    public SeedBudget(Supplier<Snapshot> snapshots, Supplier<Set<Item>> seeds) {
        super(snapshots, seeds);
    }
}
