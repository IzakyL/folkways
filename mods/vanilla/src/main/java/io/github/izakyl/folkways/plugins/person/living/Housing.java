package io.github.izakyl.folkways.plugins.person.living;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

public final class Housing {

    private Housing() {
    }

    static boolean isBed(BlockState state) {
        return state.getBlock() instanceof BedBlock;
    }

    // A marked bed is kept at its foot and outlined with its pillow.
    public static Optional<BlockPos> pillow(BlockGetter level, BlockPos pos, BlockState state) {
        if (!isBed(state) || state.getValue(BedBlock.PART) != BedPart.FOOT) {
            return Optional.empty();
        }
        BlockPos head = pos.relative(BedBlock.getConnectedDirection(state));
        return isBed(level.getBlockState(head)) ? Optional.of(head) : Optional.empty();
    }

    static BlockPos sleepingCell(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (isBed(state) && state.getValue(BedBlock.PART) == BedPart.HEAD) {
            return pos.relative(state.getValue(BedBlock.FACING).getOpposite());
        }
        return pos;
    }
}
