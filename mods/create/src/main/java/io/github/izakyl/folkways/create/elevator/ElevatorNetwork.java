package io.github.izakyl.folkways.create.elevator;

import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.Set;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Mob;

public interface ElevatorNetwork {

    record Floor(int y, Stances.Cells platform, Stances.Cells waiting) {
        public Floor(int y, Stances.Cells platform) { this(y, platform, platform); }
    }

    record View(UUID vehicle, boolean powered, double contactY, double blocksPerTick,
                OptionalInt stoppedAt, List<Integer> places, List<Integer> freePlaces, List<Floor> floors) {
        public View {
            places = List.copyOf(places);
            freePlaces = List.copyOf(freePlaces);
            floors = List.copyOf(floors);
        }

        public Optional<Floor> floor(int y) {
            return floors.stream().filter(floor -> floor.y() == y).findFirst();
        }
    }

    Optional<View> inspect(MinecraftServer server, ElevatorRef lift);

    /** False while a physical body straddles the stopped cabin's entrance. */
    default boolean readyToDepart(MinecraftServer server, ElevatorRef lift) { return true; }

    boolean target(MinecraftServer server, ElevatorRef lift, int floor);

    Optional<UUID> occupant(MinecraftServer server, ElevatorRef lift, int place);

    boolean board(MinecraftServer server, ElevatorRef lift, int place, Mob body);

    Optional<Integer> placeOf(MinecraftServer server, ElevatorRef lift, Mob body);

    boolean disembark(MinecraftServer server, ElevatorRef lift, int place, Mob body);

    default boolean disembark(MinecraftServer server, ElevatorRef lift, int place, Mob body, Set<WorldPos> exits) {
        return disembark(server, lift, place, body);
    }
}
