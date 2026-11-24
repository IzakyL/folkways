package io.github.izakyl.folkways.core.shell.create.elevator;

import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Mob;

/** Operations on one physical pulley through its native network. */
public record ElevatorBinding(ElevatorRef ref, ElevatorNetwork network) {
    public Optional<ElevatorNetwork.View> inspect(MinecraftServer server) {
        return network.inspect(server, ref);
    }

    public boolean target(MinecraftServer server, int floor) {
        return network.target(server, ref, floor);
    }

    public boolean readyToDepart(MinecraftServer server) {
        return network.readyToDepart(server, ref);
    }

    public Optional<Integer> placeOf(MinecraftServer server, Mob body) {
        return network.placeOf(server, ref, body);
    }

    public Optional<UUID> occupant(MinecraftServer server, int place) {
        return network.occupant(server, ref, place);
    }

    public boolean board(MinecraftServer server, int place, Mob body) {
        return network.board(server, ref, place, body);
    }

    public boolean disembark(MinecraftServer server, int place, Mob body, Set<WorldPos> exits) {
        return network.disembark(server, ref, place, body, exits);
    }
}
