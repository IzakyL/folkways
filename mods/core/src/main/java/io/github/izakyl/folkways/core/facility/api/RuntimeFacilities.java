package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.world.api.Stances;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** Definitions anchored on loaded entities, supplied by the owners of their controls. */
public final class RuntimeFacilities {

    private static final List<BiFunction<Colony, Entity, Optional<RuntimeFacility>>> SOURCES = new ArrayList<>();

    private RuntimeFacilities() {
    }

    @FunctionalInterface
    public interface Accepted {
        void run(ServerPlayer player, String routine);
    }

    public record RuntimeFacility(FacilityDefinition facility, Component title, boolean listed, List<Stances.Seat> seats,
                                Optional<Accepted> accepted) {

        public RuntimeFacility {
            seats = List.copyOf(seats);
        }
    }

    public static void register(BiFunction<Colony, Entity, Optional<RuntimeFacility>> source) {
        SOURCES.add(source);
    }

    static Map<String, RuntimeFacility> loaded(MinecraftServer server, Colony colony) {
        Map<String, RuntimeFacility> facilities = new LinkedHashMap<>();
        if (SOURCES.isEmpty()) {
            return facilities;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity.isAlive()) {
                    for (var source : SOURCES) {
                        source.apply(colony, entity).ifPresent(definition ->
                            facilities.putIfAbsent(definition.facility().name(), definition));
                    }
                }
            }
        }
        return facilities;
    }
}
