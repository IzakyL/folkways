package io.github.izakyl.folkways.fuzz;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Where a live case runs: a stone platform high over the void, far from spawn and {@link #SPACING} from the next,
 * so up to {@link #RING} cases run side by side on one server. Its chunks are force-loaded, so they tick as a
 * player's would: residents move, blocks fall and update, items lie where they drop. A low wall runs round the
 * edge so nobody walks off it. The platform runs {@code half} cells either side of its origin: {@link #HALF}, unless
 * the target asks for more room.
 */
public record Plot(ServerLevel level, BlockPos origin, int index, int half) {

    public static final int SPACING = 256;
    public static final int RING = 8;
    public static final int FLOOR = 100;
    /** The platform runs from -{@code HALF} to +{@code HALF} cells round the origin, walls included. */
    public static final int HALF = 20;
    /** The most room a target may ask for, so plots stay apart with chunks to spare between them. */
    public static final int WIDEST = 96;
    private static final int HEADROOM = 24;
    /** How far under the platform a case may dig, and so how deep {@link #clear} reaches. */
    public static final int DEPTH = 10;
    private static final int FLAGS = Block.UPDATE_ALL;

    public static Plot of(ServerLevel level, int index) {
        return of(level, index, HALF);
    }

    public static Plot of(ServerLevel level, int index, int half) {
        int slot = Math.floorMod(index, RING);
        return new Plot(level, new BlockPos(1024 + slot * SPACING, FLOOR, 1024), index, Math.clamp(half, 4, WIDEST));
    }

    public BlockPos at(int x, int y, int z) {
        return origin.offset(x, y, z);
    }

    public void put(BlockPos at, BlockState state) {
        level.setBlock(at, state, FLAGS);
    }

    /** Force-loads the plot and lays a fresh platform on it: stone underfoot, a wall round it, air above. */
    public void claim() {
        for (int cx = (origin.getX() - half - 16) >> 4; cx <= (origin.getX() + half + 16) >> 4; cx++) {
            for (int cz = (origin.getZ() - half - 16) >> 4; cz <= (origin.getZ() + half + 16) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
        clear();
        for (int x = -half; x <= half; x++) {
            for (int z = -half; z <= half; z++) {
                put(at(x, 0, z), Blocks.STONE.defaultBlockState());
                if (Math.abs(x) == half || Math.abs(z) == half) {
                    put(at(x, 1, z), Blocks.STONE_BRICKS.defaultBlockState());
                    put(at(x, 2, z), Blocks.STONE_BRICK_WALL.defaultBlockState());
                }
            }
        }
    }

    /** Everything off the plot: every block over it and every entity on it but players. */
    public void clear() {
        for (Entity stray : entities()) {
            stray.discard();
        }
        for (int x = -half - 2; x <= half + 2; x++) {
            for (int z = -half - 2; z <= half + 2; z++) {
                for (int y = -DEPTH; y <= HEADROOM; y++) {
                    BlockPos at = at(x, y, z);
                    if (!level.getBlockState(at).isAir()) {
                        level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
        for (Entity stray : entities()) {
            stray.discard();
        }
    }

    /** The box over the platform, from below its floor to above anything a case builds. */
    public AABB box() {
        return new AABB(at(-half - 2, -DEPTH, -half - 2).getCenter(), at(half + 2, HEADROOM, half + 2).getCenter());
    }

    public List<Entity> entities() {
        return level.getEntities((Entity) null, box(), entity -> !(entity instanceof Player));
    }
}
