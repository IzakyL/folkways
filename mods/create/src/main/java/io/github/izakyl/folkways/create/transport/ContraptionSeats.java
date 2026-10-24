package io.github.izakyl.folkways.create.transport;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public final class ContraptionSeats {

    private ContraptionSeats() {
    }

    public static Optional<UUID> occupant(AbstractContraptionEntity vehicle, int seatIndex) {
        Contraption contraption = vehicle.getContraption();
        if (!hasSeat(contraption, seatIndex)) {
            return Optional.empty();
        }
        return contraption.getSeatMapping().entrySet().stream()
            .filter(entry -> entry.getValue() == seatIndex)
            .map(Map.Entry::getKey)
            .findFirst();
    }

    public static Optional<WorldPos> position(AbstractContraptionEntity vehicle, int seatIndex) {
        Contraption contraption = vehicle.getContraption();
        if (!hasSeat(contraption, seatIndex)) {
            return Optional.empty();
        }
        BlockPos local = contraption.getSeats().get(seatIndex);
        return Optional.of(WorldPos.of(vehicle.level(), BlockPos.containing(
            vehicle.toGlobalVector(Vec3.atCenterOf(local), 1.0F))));
    }

    public static boolean board(AbstractContraptionEntity vehicle, int seatIndex, Entity rider) {
        Contraption contraption = vehicle.getContraption();
        if (!hasSeat(contraption, seatIndex) || rider.level() != vehicle.level()
                || occupant(vehicle, seatIndex).isPresent()) {
            return false;
        }
        Vec3 previous = rider.position();
        Vec3 position = vehicle.toGlobalVector(Vec3.atCenterOf(contraption.getSeats().get(seatIndex)), 1.0F);
        rider.teleportTo(position.x, position.y, position.z);
        vehicle.addSittingPassenger(rider, seatIndex);
        boolean boarded = contraption.getSeatMapping().containsKey(rider.getUUID());
        if (!boarded) {
            rider.teleportTo(previous.x, previous.y, previous.z);
        }
        return boarded;
    }

    public static void disembark(Entity rider) {
        if (rider.isPassenger()) {
            rider.stopRiding();
        }
    }

    private static boolean hasSeat(Contraption contraption, int seatIndex) {
        return contraption != null && seatIndex >= 0 && seatIndex < contraption.getSeats().size();
    }
}
