import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import java.util.*;
import dev.blockwright.api.Context;
import blockwright.minecraft.core.Args;

// Generates the chunks of a box and reads the ground of every column in it, under trees and water.
// args: minX, minZ, maxX, maxZ. Answers row-major arrays (x outer, z inner): ground (the top solid block),
// water (the top fluid block over the ground, or -9999) and cover (0 soil, 1 sand, 2 rock, 3 tree over it),
// each as one comma-separated string per x row.
public final class Task {
    static Map<String, Object> map(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], kv[i + 1]);
        return out;
    }

    static boolean ground(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty() && state.blocksMotion()
            && !state.is(BlockTags.LOGS) && !state.is(BlockTags.LEAVES) && !state.is(Blocks.BAMBOO)
            && !state.is(Blocks.CACTUS) && !state.is(Blocks.MUSHROOM_STEM) && !state.is(Blocks.BROWN_MUSHROOM_BLOCK)
            && !state.is(Blocks.RED_MUSHROOM_BLOCK) && !state.is(Blocks.BEE_NEST) && !state.is(Blocks.MOSS_CARPET);
    }

    // One comma-separated string per x row, so the answer stays a few thousand nodes.
    static List<String> rows(int[] a, int w, int d) {
        List<String> out = new ArrayList<>(w);
        for (int i = 0; i < w; i++) {
            var row = new StringBuilder(d * 4);
            for (int j = 0; j < d; j++) { if (j > 0) row.append(','); row.append(a[i * d + j]); }
            out.add(row.toString());
        }
        return out;
    }

    public static Object run(Context ctx) throws Exception {
        Args args = Args.of(ctx);
        ServerLevel level = ctx.server().overworld();
        int minX = (int) args.integer("minX"), minZ = (int) args.integer("minZ"), maxX = (int) args.integer("maxX"), maxZ = (int) args.integer("maxZ");
        long started = System.nanoTime();
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) level.getChunk(cx, cz);
        long generated = System.nanoTime();
        int w = maxX - minX + 1, d = maxZ - minZ + 1;
        int[] ground = new int[w * d], water = new int[w * d], cover = new int[w * d];
        var at = new BlockPos.MutableBlockPos();
        int bottom = level.getMinBuildHeight();
        for (int i = 0; i < w; i++) for (int j = 0; j < d; j++) {
            int x = minX + i, z = minZ + j, k = i * d + j;
            int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            int y = top;
            int wet = -9999;
            boolean tree = false;
            for (; y > bottom; y--) {
                BlockState state = level.getBlockState(at.set(x, y, z));
                if (!state.getFluidState().isEmpty() && wet == -9999) wet = y;
                if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)) tree = true;
                if (ground(state)) break;
            }
            ground[k] = y;
            water[k] = wet;
            BlockState state = level.getBlockState(at.set(x, y, z));
            cover[k] = tree ? 3 : state.is(BlockTags.SAND) || state.is(Blocks.GRAVEL) ? 1
                : state.is(BlockTags.DIRT) || state.is(Blocks.FARMLAND) || state.is(Blocks.DIRT_PATH) || state.is(Blocks.CLAY) ? 0 : 2;
        }
        return map("ok", true, "minX", minX, "minZ", minZ, "width", w, "depth", d,
            "ground", rows(ground, w, d), "water", rows(water, w, d), "cover", rows(cover, w, d), "sea", level.getSeaLevel(),
            "generate_ms", (generated - started) / 1_000_000, "read_ms", (System.nanoTime() - generated) / 1_000_000);
    }
}
