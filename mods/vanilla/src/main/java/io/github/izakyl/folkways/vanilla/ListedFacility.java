package io.github.izakyl.folkways.vanilla;

import io.github.izakyl.folkways.core.card.api.Card;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.Facility;
import io.github.izakyl.folkways.core.facility.api.Operating;
import io.github.izakyl.folkways.core.facility.api.Routine;
import io.github.izakyl.folkways.core.facility.api.Trigger;
import io.github.izakyl.folkways.core.facility.api.control.Reading;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/** A gameplay facility whose state is already maintained by a colony service. */
public interface ListedFacility extends Facility, Operating {
    @Override
    default Facility facility() {
        return this;
    }

    @Override
    default Card card() {
        return new Card(List.of(new Card.Element.Lines()));
    }

    // As with colony panel facilities, use the overworld to read status when there is no world address.
    @Override
    default WorldPos site() {
        return WorldPos.of(Level.OVERWORLD, BlockPos.ZERO);
    }

    @Override
    default Optional<ServerLevel> loaded(MinecraftServer server) {
        return site().level(server);
    }

    @Override
    default Map<String, Routine.Use> routines() {
        return Map.of();
    }

    @Override
    default List<Trigger> triggers() {
        return List.of();
    }

    @Override
    default Map<String, Value> parameters() {
        return Map.of();
    }

    @Override
    default Map<String, Reading> readings(ServerLevel level) {
        return Map.of();
    }

    @Override
    default Map<String, Boolean> switches() {
        return Map.of();
    }

    @Override
    default List<String> running() {
        return List.of();
    }

    @Override
    default Optional<Recent> recent() {
        return Optional.empty();
    }

    @Override
    default Optional<String> disabled(String routine, Map<String, Value> enabledWhen) {
        return Optional.empty();
    }

    @Override
    default List<DefinedFacilities.Problem> run(ServerPlayer by, String routine, Map<String, Value> arguments) {
        return List.of(new DefinedFacilities.Problem("routine", "manual", routine));
    }

    @Override
    default List<DefinedFacilities.Problem> setParameter(String parameter, Value value) {
        return List.of(new DefinedFacilities.Problem("parameters." + parameter, "missing", parameter));
    }

    @Override
    default List<DefinedFacilities.Problem> toggle(String trigger, boolean enabled) {
        return List.of(new DefinedFacilities.Problem("triggers." + trigger, "background", trigger));
    }
}
