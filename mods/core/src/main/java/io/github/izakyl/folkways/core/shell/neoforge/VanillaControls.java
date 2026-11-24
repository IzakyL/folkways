package io.github.izakyl.folkways.core.shell.neoforge;

import io.github.izakyl.folkways.core.facility.api.control.Control;
import io.github.izakyl.folkways.core.facility.api.Controls;
import io.github.izakyl.folkways.core.facility.api.control.Reading;
import io.github.izakyl.folkways.core.facility.api.control.Readings;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Vanilla signals: a lever is on or off, a button is pressed. Both show their state as the block's power. */
public final class VanillaControls {

    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways_core", "vanilla");
    // Reaching over to throw a lever takes a moment, like any small handiwork.
    private static final Control.Cost THROW = new Control.Cost.Ticks(5);

    private VanillaControls() {
    }

    public static void install() {
        Readings.register(Readings.POWERED, VanillaControls::powered);
        Controls.register(ID, Controls.VANILLA, (level, pos) -> {
            BlockState state = level.getBlockState(pos.block(level));
            if (state.getBlock() instanceof LeverBlock) {
                return List.of(new Lever(pos));
            }
            if (state.getBlock() instanceof ButtonBlock) {
                return List.of(new Button(pos));
            }
            return List.of();
        });
    }

    private static Optional<Value> powered(ServerLevel level, WorldPos at) {
        BlockPos pos = at.block(level);
        BlockState state = level.getBlockState(pos);
        if (state.hasProperty(BlockStateProperties.POWERED)) {
            return Optional.of(Value.of(state.getValue(BlockStateProperties.POWERED)));
        }
        return state.isAir() ? Optional.empty() : Optional.of(Value.of(level.hasNeighborSignal(pos)));
    }

    record Lever(WorldPos anchor) implements Signal {

        @Override
        public String part() {
            return "lever";
        }

        @Override
        public SignalType type() {
            return SignalType.BOOL;
        }

        @Override
        public boolean valid(ServerLevel level) {
            return anchor.in(level) && level.isLoaded(anchor.block(level))
                && level.getBlockState(anchor.block(level)).getBlock() instanceof LeverBlock;
        }

        @Override
        public Control.Cost cost() {
            return THROW;
        }

        @Override
        public Write write(ServerLevel level, Mob writer, Value value) {
            if (!valid(level) || !(value instanceof Value.Bool(boolean on))) {
                return Write.REFUSED;
            }
            BlockPos pos = anchor.block(level);
            BlockState state = level.getBlockState(pos);
            if (state.getValue(LeverBlock.POWERED) != on) {
                ((LeverBlock) state.getBlock()).pull(state, level, pos, null);
            }
            return Write.WRITTEN;
        }

        @Override
        public Optional<Reading> feedback() {
            return Readings.of(Readings.POWERED, anchor);
        }
    }

    record Button(WorldPos anchor) implements Signal {

        @Override
        public String part() {
            return "button";
        }

        @Override
        public SignalType type() {
            return SignalType.PULSE;
        }

        @Override
        public boolean valid(ServerLevel level) {
            return anchor.in(level) && level.isLoaded(anchor.block(level))
                && level.getBlockState(anchor.block(level)).getBlock() instanceof ButtonBlock;
        }

        @Override
        public Control.Cost cost() {
            return THROW;
        }

        // A button already down is left to spring back on its own: pressing it again would do nothing.
        @Override
        public Write write(ServerLevel level, Mob writer, Value value) {
            if (!valid(level) || !(value instanceof Value.Pulse)) {
                return Write.REFUSED;
            }
            BlockPos pos = anchor.block(level);
            BlockState state = level.getBlockState(pos);
            if (state.getValue(ButtonBlock.POWERED)) {
                return Write.BUSY;
            }
            ((ButtonBlock) state.getBlock()).press(state, level, pos, null);
            return Write.WRITTEN;
        }
    }
}
