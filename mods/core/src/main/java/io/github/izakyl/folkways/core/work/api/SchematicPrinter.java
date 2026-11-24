package io.github.izakyl.folkways.core.work.api;

import io.github.izakyl.folkways.core.world.api.StructurePayload;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** Block and block entity operations for schematic placement. */
public final class SchematicPrinter {
    /**
     * One shot of the cannon: the cell aimed at, the target cells it leaves placed (a door's upper half comes with
     * its lower one), and the cells whose requirements the shot pays for.
     */
    public record Launch(BlockPos anchor, Set<BlockPos> produces, List<BlockPos> charged) {

        public Launch {
            anchor = anchor.immutable();
            produces = Set.copyOf(produces);
            charged = List.copyOf(charged);
        }

        public static Launch of(BlockPos anchor, Set<BlockPos> produces) {
            return new Launch(anchor, produces, List.of(anchor));
        }
    }

    /** The schematic as the printer sees it, once it has been placed into an empty schematic level. */
    public interface Printed {

        BlockState state(BlockPos pos);

        @Nullable
        BlockEntity entity(BlockPos pos);
    }

    public interface Adapter {

        /** Places the template into the printer's empty level, as the printer does when it loads a schematic. */
        Printed print(ServerLevel level, StructureTemplate template, Vec3i size);

        /** The printer's {@code setZeroAge}: what every read state is turned into before anything else sees it. */
        BlockState zeroAge(BlockState state);

        void turn(BlockEntity entity, Mirror mirror, Rotation rotation);

        /** What the cannon carries of a block entity's data: only what it trusts, never contents. */
        @Nullable
        CompoundTag prepare(ServerLevel level, BlockState state, @Nullable BlockEntity entity);

        /** Cells the cannon never aims at: they are made by another shot, or not at all. */
        boolean ignored(BlockState state);

        Costs.Quote requirement(BlockState state, @Nullable BlockEntity entity);

        /** Places a schematic cell using Folkways rules and integration-specific hooks. */
        void place(ServerLevel level, BlockPos pos, BlockState state, ItemStack stack, @Nullable CompoundTag data);

        default Optional<Launch> launch(Function<BlockPos, BlockState> target, BlockPos pos) {
            BlockState state = target.apply(pos);
            if (ignored(state)) {
                return Optional.empty();
            }
            Set<BlockPos> produces = new LinkedHashSet<>();
            produces.add(pos.immutable());
            partner(state, pos).filter(other -> {
                BlockState there = target.apply(other);
                return there.is(state.getBlock()) && ignored(there);
            }).ifPresent(produces::add);
            return Optional.of(Launch.of(pos, produces));
        }

        /** Fires a shot whose cells are already addressed in the world. */
        default void fire(ServerLevel level, Launch launch, Function<BlockPos, BlockState> states,
                Function<BlockPos, BlockData> data, ItemStack stack) {
            BlockState state = states.apply(launch.anchor());
            BlockData kept = data.apply(launch.anchor());
            if (StructureCompat.of(state).isPresent()) {
                StructurePayload payload = kept.payload(state);
                StructureCompat.inspect(state, payload);
                CompoundTag nativeData = StructureCompat.of(state).orElseThrow()
                    .placementData(level.registryAccess(), launch.anchor(), state, payload);
                place(level, launch.anchor(), state, stack, nativeData);
            } else {
                BlockEntity entity = kept.entity(level, launch.anchor(), state);
                place(level, launch.anchor(), state, stack, prepare(level, state, entity));
            }
        }
    }

    private static volatile Adapter adapter = new VanillaSchematics();

    private SchematicPrinter() {
    }

    public static void install(Adapter installed) {
        adapter = installed;
    }

    public static Adapter adapter() {
        return adapter;
    }

    /** The parts vanilla blocks make for themselves when placed: a two-high block's top, a bed's head. */
    public static Optional<BlockPos> partner(BlockState state, BlockPos pos) {
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
            && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
            return Optional.of(pos.above());
        }
        if (state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.FOOT) {
            return Optional.of(pos.relative(state.getValue(BedBlock.FACING)));
        }
        return Optional.empty();
    }

}
