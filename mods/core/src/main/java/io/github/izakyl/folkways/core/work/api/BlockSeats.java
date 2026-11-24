package io.github.izakyl.folkways.core.work.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/** Native seating at blocks that may create their seat entity only when boarded. */
public final class BlockSeats {

    public interface Adapter {

        default boolean isSeat(ServerLevel level, BlockPos at) {
            return false;
        }

        boolean sit(ServerLevel level, BlockPos seat, Entity body);

        Optional<UUID> sitting(ServerLevel level, BlockPos seat);
    }

    private static volatile Supplier<? extends Adapter> adapter = () -> null;

    private BlockSeats() {
    }

    public static void install(Supplier<? extends Adapter> source) {
        adapter = Objects.requireNonNull(source, "source");
    }

    public static Optional<Adapter> at(ServerLevel level, BlockPos cell) {
        return Optional.ofNullable(adapter.get()).filter(seats -> seats.isSeat(level, cell));
    }
}
