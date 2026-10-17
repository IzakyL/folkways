package io.github.izakyl.folkways.front.engine.colony;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

public final class Residents {

    private Residents() {
    }

    public static List<Body> of(Colony colony, MinecraftServer server) {
        Map<UUID, Body> found = new LinkedHashMap<>();
        for (Resident resident : colony.residents()) {
            anywhere(server, resident.id()).ifPresent(body -> found.put(resident.id(), body));
        }
        return List.copyOf(found.values());
    }

    public static Optional<Body> of(Colony colony, MinecraftServer server, UUID resident) {
        for (Resident named : colony.residents()) {
            if (named.id().equals(resident)) {
                return anywhere(server, resident);
            }
        }
        return Optional.empty();
    }

    private static Optional<Body> anywhere(MinecraftServer server, UUID resident) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(resident);
            if (entity != null) {
                return Bodies.of(entity);
            }
        }
        return Optional.empty();
    }
}
