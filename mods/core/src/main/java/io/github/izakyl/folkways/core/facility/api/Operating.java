package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.card.api.Card;
import io.github.izakyl.folkways.core.facility.api.control.Reading;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * One facility as its card reaches it: what it is set to, which background triggers are on, its runs, and the three
 * things a press on the card can do to it. Every source of facilities (definitions, drawn holdings, prefabs, entities)
 * answers through this, so a card works the same on any of them. All of it runs on the server thread.
 */
public interface Operating {

    /** The kind used to group facilities in the colony book. */
    default ResourceLocation kind() {
        return ResourceLocation.fromNamespaceAndPath("folkways_core", "defined");
    }
    UUID id();

    Facility facility();

    Component title();

    /** The item it is shown with in lists, such as its delegation's icon. */
    default Optional<net.minecraft.resources.ResourceLocation> icon() {
        return Optional.empty();
    }

    default Card card() {
        return facility().card();
    }

    /** The level its readings are taken in while it is loaded there. */
    Optional<ServerLevel> loaded(MinecraftServer server);

    /** What its parameters are set to, every unset one at its card's fallback. */
    Map<String, Value> parameters();

    /** Whether each switchable background trigger is on. */
    Map<String, Boolean> switches();

    /** The routines of its runs still in the plan, oldest first. */
    List<String> running();

    Optional<Recent> recent();

    /**
     * Why a press that runs this routine cannot be made now: {@code "running"}, {@code "state"} while the readings
     * disagree with {@code enabledWhen}, {@code "unknown"} while they cannot be read.
     */
    Optional<String> disabled(String routine, Map<String, Value> enabledWhen);

    /** Runs a manual routine for the player, as an order. */
    List<DefinedFacilities.Problem> run(ServerPlayer by, String routine, Map<String, Value> arguments);

    List<DefinedFacilities.Problem> setParameter(String parameter, Value value);

    List<DefinedFacilities.Problem> toggle(String trigger, boolean enabled);

    /** The range a numeric reading's signal declares, when it declares one. */
    default Optional<double[]> range(ServerLevel level, String reading) {
        return Optional.empty();
    }

    default Map<String, Reading> readings(ServerLevel level) {
        return facility().readings(level);
    }

    /** What its card's overlays resolve to now. */
    default List<Overlay> overlays(ServerLevel level) {
        return List.of();
    }

    record Recent(String routine, String ending, Optional<String> reason) {
    }

    /** An overlay in world coordinates. */
    sealed interface Overlay {
        record Ring(Vec3 center, double radius) implements Overlay {
        }

        /** A polyline with an optional mark at the last point. */
        record Path(List<Vec3> points, boolean endsAtMark) implements Overlay {
            public Path {
                points = List.copyOf(points);
            }
        }
    }
}
