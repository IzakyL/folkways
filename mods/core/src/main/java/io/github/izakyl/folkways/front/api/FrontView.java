package io.github.izakyl.folkways.front.api;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.List;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

public interface FrontView {

    Settings settings(ResourceLocation owner);

    List<? extends ZoneView> zonesIn(ServerLevel level);

    List<? extends PathView> paths();

    // Lets go a zone or a path, including the settings kept for it.
    boolean erase(UUID id);

    // How much of it the colony holds: in its marked containers and in its residents' packs.
    long stocked(MinecraftServer server, ItemSpec what);
}
