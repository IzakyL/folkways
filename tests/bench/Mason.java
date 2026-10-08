import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import java.util.*;
import dev.blockwright.api.Context;
import blockwright.minecraft.core.Args;
import blockwright.minecraft.core.Fail;
import blockwright.minecraft.core.Sync;

// Lays a town the bench planned on real ground. args: palette [block state..], ops [[op, ..ints]..]:
//   ["clear", x0, z0, x1, z1]                    trees, plants and snow off the ground of every column
//   ["pad", x0, z0, x1, z1, y, top, core, edge, headroom]  ground levelled so its top is at y - 1
//   ["fill", x0, y0, z0, x1, y1, z1, state, keep] keep=1 only replaces air, plants and fluids
//   ["set", x, y, z, state]
//   ["footing", x, z, y, state]                  a column from the ground up to y
//   ["tree", x, y, z, kind]                      0 oak, 1 birch, 2 spruce, 3 fancy oak
// Connected blocks (fences, walls, panes, stairs) are re-shaped against their neighbours at the end.
public final class Task {
    static int num(Object o) { return ((Number) o).intValue(); }

    static boolean ground(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty() && state.blocksMotion()
            && !state.is(BlockTags.LOGS) && !state.is(BlockTags.LEAVES) && !state.is(Blocks.BAMBOO)
            && !state.is(Blocks.CACTUS) && !state.is(Blocks.MUSHROOM_STEM) && !state.is(Blocks.BROWN_MUSHROOM_BLOCK)
            && !state.is(Blocks.RED_MUSHROOM_BLOCK) && !state.is(Blocks.BEE_NEST) && !state.is(Blocks.MOSS_CARPET);
    }

    static boolean soft(BlockState state) {
        return state.isAir() || state.canBeReplaced() || !state.getFluidState().isEmpty()
            || state.is(BlockTags.LEAVES) || state.is(BlockTags.FLOWERS);
    }

    static boolean connects(BlockState state) {
        Block b = state.getBlock();
        return b instanceof FenceBlock || b instanceof WallBlock || b instanceof IronBarsBlock || b instanceof StairBlock
            || b instanceof FenceGateBlock;
    }

    ServerLevel level;
    final Set<Long> reshape = new HashSet<>();
    int placed = 0;

    void put(int x, int y, int z, BlockState state) {
        var pos = new BlockPos(x, y, z);
        if (level.getBlockState(pos) == state) return;
        level.setBlock(pos, state, 2);
        placed++;
        if (connects(state)) reshape.add(pos.asLong());
    }

    int groundAt(int x, int z) {
        var at = new BlockPos.MutableBlockPos();
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        for (; y > level.getMinBuildHeight(); y--) if (ground(level.getBlockState(at.set(x, y, z)))) break;
        return y;
    }

    public static Object run(Context ctx) throws Exception {
        var mason = new Task();
        return Sync.stamp(mason.lay(ctx.server(), Args.of(ctx)));
    }

    Object lay(MinecraftServer server, Args args) throws Exception {
        level = server.overworld();
        var blocks = level.holderLookup(Registries.BLOCK);
        List<BlockState> palette = new ArrayList<>();
        for (String s : args.strings("palette")) palette.add(BlockStateParser.parseForBlock(blocks, s, false).blockState());
        BlockState air = Blocks.AIR.defaultBlockState();
        var features = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE);
        List<ResourceKey<ConfiguredFeature<?, ?>>> trees = List.of(TreeFeatures.OAK, TreeFeatures.BIRCH, TreeFeatures.SPRUCE, TreeFeatures.FANCY_OAK);
        var random = RandomSource.create(20260926);
        var generator = level.getChunkSource().getGenerator();
        Map<String, Integer> counts = new TreeMap<>();
        int grown = 0;
        List<Object> ops = args.list("ops");
        if (ops == null) throw Fail.invalid("args.ops is required (an array of ops)");
        for (Object raw : ops) {
            var op = (List<?>) raw;
            String kind = (String) op.get(0);
            int[] a = new int[op.size() - 1];
            for (int i = 1; i < op.size(); i++) a[i - 1] = num(op.get(i));
            counts.merge(kind, 1, Integer::sum);
            switch (kind) {
                case "clear" -> {
                    var at = new BlockPos.MutableBlockPos();
                    var cut = new ArrayDeque<BlockPos>();
                    for (int x = a[0]; x <= a[2]; x++) for (int z = a[1]; z <= a[3]; z++) {
                        int g = groundAt(x, z), top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                        for (int y = g + 1; y <= top; y++) {
                            BlockState s = level.getBlockState(at.set(x, y, z));
                            if (s.is(BlockTags.LOGS)) cut.add(at.immutable());
                            if (!s.isAir() && s.getFluidState().isEmpty()) put(x, y, z, air);
                        }
                        if (level.getBlockState(at.set(x, g, z)).is(Blocks.SNOW_BLOCK)) put(x, g, z, Blocks.GRASS_BLOCK.defaultBlockState());
                    }
                    // A rectangular clearing can cut the trunk while leaving its branches
                    // outside the box. Remove the attached crown too, and notify leaves so
                    // they decay instead of leaving permanent floating tree fragments.
                    var visited = new HashSet<BlockPos>();
                    while (!cut.isEmpty()) {
                        var log = cut.removeFirst();
                        if (!visited.add(log)) continue;
                        level.updateNeighborsAt(log, Blocks.AIR);
                        for (var near : BlockPos.betweenClosed(log.offset(-1, -1, -1), log.offset(1, 1, 1))) {
                            if (near.getX() < a[0] - 6 || near.getX() > a[2] + 6 || near.getZ() < a[1] - 6 || near.getZ() > a[3] + 6) continue;
                            if (level.getBlockState(near).is(BlockTags.LOGS)) {
                                cut.add(near.immutable());
                                level.setBlock(near, air, 3);
                                placed++;
                            }
                        }
                    }
                }
                case "pad" -> {
                    int y = a[4], head = a[8];
                    BlockState top = palette.get(a[5]), core = palette.get(a[6]), edge = palette.get(a[7]);
                    for (int x = a[0]; x <= a[2]; x++) for (int z = a[1]; z <= a[3]; z++) {
                        boolean rim = x == a[0] || x == a[2] || z == a[1] || z == a[3];
                        int g = groundAt(x, z);
                        int ceiling = Math.max(y + head, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z));
                        for (int yy = y; yy <= ceiling; yy++) put(x, yy, z, air);
                        // Seal shallow caves under the pad as well as filling above the old
                        // surface. A market foundation must not be a grass lid over a hollow.
                        for (int yy = Math.min(g + 1, y - 5); yy < y - 1; yy++) put(x, yy, z, rim ? edge : core);
                        put(x, y - 1, z, top);
                    }
                }
                case "fill" -> {
                    BlockState s = palette.get(a[6]);
                    var at = new BlockPos.MutableBlockPos();
                    for (int x = Math.min(a[0], a[3]); x <= Math.max(a[0], a[3]); x++)
                        for (int y = Math.min(a[1], a[4]); y <= Math.max(a[1], a[4]); y++)
                            for (int z = Math.min(a[2], a[5]); z <= Math.max(a[2], a[5]); z++)
                                if (a[7] == 0 || soft(level.getBlockState(at.set(x, y, z)))) put(x, y, z, s);
                }
                case "set" -> put(a[0], a[1], a[2], palette.get(a[3]));
                case "footing" -> {
                    for (int yy = groundAt(a[0], a[1]) + 1; yy <= a[2]; yy++) put(a[0], yy, a[1], palette.get(a[3]));
                }
                case "tree" -> {
                    var holder = features.getHolder(trees.get(a[3]));
                    if (holder.isPresent() && holder.get().value().place(level, generator, random, new BlockPos(a[0], a[1], a[2]))) grown++;
                }
                default -> throw Fail.invalid("unknown op " + kind);
            }
        }
        int reshaped = 0;
        for (long packed : reshape) {
            var pos = BlockPos.of(packed);
            BlockState was = level.getBlockState(pos);
            BlockState now = Block.updateFromNeighbourShapes(was, level, pos);
            if (now != was) { level.setBlock(pos, now, 2); reshaped++; }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("ops", counts);
        out.put("placed", placed);
        out.put("reshaped", reshaped);
        out.put("trees", grown);
        return out;
    }
}
