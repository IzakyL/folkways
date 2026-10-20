package io.github.izakyl.folkways.cookery.seeds;

import io.github.izakyl.folkways.core.api.colony.Colony;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Cookery contributes real straw-hat sources; the destructive budget is shared with Vanilla. */
public final class SeedSupport extends io.github.izakyl.folkways.plugins.compost.SeedSupport {
    private final Colony colony;
    private final SeedBudget budget;

    public SeedSupport(Colony colony, MinecraftServer server) {
        super(colony, server);
        this.colony = colony;
        this.budget = new SeedBudget(this::snapshot, this::catalogue);
    }

    @Override public SeedBudget budget(ServerLevel level) { return budget; }

    public boolean enabled() {
        return loadedBodies().values().stream().anyMatch(body ->
            io.github.izakyl.folkways.cookery.gear.CookeryGear.permitsSeeds(colony, body));
    }
}
