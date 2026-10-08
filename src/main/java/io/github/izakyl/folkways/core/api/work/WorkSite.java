package io.github.izakyl.folkways.core.api.work;

import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

public sealed interface WorkSite {

    WorldPos where();

    default Realm realm() {
        return where().realm();
    }

    default BlockPos cell() {
        return where().cell();
    }

    record AtBlock(WorldPos pos) implements WorkSite {

        public AtBlock {
            Objects.requireNonNull(pos, "pos");
        }

        @Override
        public WorldPos where() {
            return pos;
        }
    }

    record AtEntity(UUID entity, WorldPos seenAt) implements WorkSite {

        @Override
        public WorldPos where() {
            return seenAt;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof AtEntity(UUID who, WorldPos ignored) && entity.equals(who);
        }

        @Override
        public int hashCode() {
            return entity.hashCode();
        }
    }

    record AtSeat(UUID vehicle, int seat, WorldPos seenAt) implements WorkSite {

        @Override
        public WorldPos where() {
            return seenAt;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof AtSeat(UUID which, int index, WorldPos ignored)
                && seat == index && vehicle.equals(which);
        }

        @Override
        public int hashCode() {
            return Objects.hash(vehicle, seat);
        }
    }

    static WorkSite at(Level level, BlockPos cell) {
        return new AtBlock(WorldPos.of(level, cell));
    }

    static WorkSite on(Level level, UUID entity, BlockPos seenAt) {
        return new AtEntity(entity, WorldPos.of(level, seenAt));
    }
}
