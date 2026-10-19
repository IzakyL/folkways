package io.github.izakyl.folkways.core.engine.colony;

import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;

public final class ColonyLookup implements Colonies.Source {

    private ColonyLookup() {
    }

    public static void install() {
        Colonies.install(new ColonyLookup());
    }

    @Override
    public Optional<Colony> of(MinecraftServer server, UUID colony) {
        return ColonyData.find(server, colony).map(ColonyData::works);
    }

    @Override
    public Colony mint(MinecraftServer server) {
        return ColonyRegistry.get(server).mint(server).works();
    }

    @Override
    public boolean razed(MinecraftServer server, UUID colony) {
        return ColonyRegistry.get(server).isRazed(colony);
    }

    @Override
    public List<Colony> all(MinecraftServer server) {
        List<Colony> found = new ArrayList<>();
        for (ColonyData colony : ColonyRegistry.get(server).colonies()) {
            found.add(colony.works());
        }
        return List.copyOf(found);
    }
}
