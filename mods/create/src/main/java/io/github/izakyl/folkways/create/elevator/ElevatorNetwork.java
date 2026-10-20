package io.github.izakyl.folkways.create.elevator;

import io.github.izakyl.folkways.core.api.work.Stances;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Mob;

public interface ElevatorNetwork {

    record Floor(int y, Stances.Cells platform) {
    }

    record View(UUID vehicle, boolean powered, double contactY, double blocksPerTick,
                OptionalInt stoppedAt, List<Integer> seats, List<Integer> freeSeats, List<Floor> floors) {
        public View {
            seats = List.copyOf(seats);
            freeSeats = List.copyOf(freeSeats);
            floors = List.copyOf(floors);
        }

        public Optional<Floor> floor(int y) {
            return floors.stream().filter(floor -> floor.y() == y).findFirst();
        }
    }

    Optional<View> inspect(MinecraftServer server, ElevatorRef lift);

    boolean target(MinecraftServer server, ElevatorRef lift, int floor);

    Optional<UUID> occupant(MinecraftServer server, ElevatorRef lift, int seat);

    boolean board(MinecraftServer server, ElevatorRef lift, int seat, Mob body);

    Optional<Integer> seatOf(MinecraftServer server, ElevatorRef lift, Mob body);

    void disembark(MinecraftServer server, ElevatorRef lift, int seat, Mob body);
}
