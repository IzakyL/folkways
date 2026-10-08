import blockwright.minecraft.core.Args;
import blockwright.minecraft.core.Sync;
import dev.blockwright.api.Context;
import io.github.izakyl.folkways.plugins.build.draft.*;
import io.github.izakyl.folkways.front.api.Settings;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

public final class Task {
    static final class Knobs implements Settings {
        final Map<String, Object> values = new HashMap<>();
        Knobs set(String key, Object value) { values.put(key, value); return this; }
        public boolean flag(String key) { return !Boolean.FALSE.equals(values.getOrDefault(key, true)); }
        public int count(String key) { return (Integer) values.getOrDefault(key, key.equals("width") ? 5 : 12); }
        public ResourceLocation choice(String key) { return null; }
        public Optional<ItemSpec> items(String key) {
            Object v = values.get(key);
            String[] ids = v instanceof String[] many ? many : switch (key) {
                case "surface" -> new String[] {"andesite", "cobblestone", "polished_andesite"};
                case "edge" -> new String[] {"stone_bricks"};
                case "fill" -> new String[] {"dirt"};
                case "post" -> new String[] {"spruce_fence"};
                default -> new String[] {"lantern"};
            };
            List<ItemSpec> specs = new ArrayList<>();
            for (String id : ids) specs.add(ItemSpec.of(ResourceLocation.withDefaultNamespace(id)));
            return Optional.of(ItemSpec.anyOf(specs));
        }
    }
    static int height(int x, int z) {
        double h = 10 + 5 * Math.sin(x / 11.0) + 4 * Math.cos(z / 9.0) + 2.5 * Math.sin((x + z) / 6.0)
            + 6 * Math.exp(-((x - 5) * (x - 5) + (z + 20) * (z + 20)) / 120.0);
        return (int) Math.round(h);
    }
    static boolean pond(int x, int z) {
        return (x - 30) * (x - 30) / 90.0 + (z - 30) * (z - 30) / 40.0 < 1.0;
    }
    public static Object run(Context ctx) throws Exception {
        return Sync.stamp(build(ctx));
    }

    private static Object build(Context ctx) {
        var args = Args.of(ctx);
        var libraries = Map.of(ResourceLocation.parse("folkways:grading"), args.string("grading"));
        var pattern = Pattern.parse(ResourceLocation.parse("folkways:road"), args.string("road"),
            id -> Optional.ofNullable(libraries.get(id))).orElseThrow();
        ServerLevel level = ctx.server().overworld();
        int r = 72;
        for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
            int h = height(x, z);
            boolean wet = pond(x, z);
            int top = wet ? 5 : h;
            for (int y = -3; y <= 40; y++) {
                BlockState state;
                if (y <= top - 3) state = Blocks.STONE.defaultBlockState();
                else if (y < top) state = Blocks.DIRT.defaultBlockState();
                else if (y == top) state = wet ? Blocks.SAND.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
                else if (wet && y <= 8) state = Blocks.WATER.defaultBlockState();
                else if (!wet && y == top + 1 && Math.floorMod(x * 31 + z * 17, 7) == 0) state = Blocks.SHORT_GRASS.defaultBlockState();
                else state = Blocks.AIR.defaultBlockState();
                level.setBlock(new BlockPos(x, y, z), state, 2);
            }
        }
        int[][] trees = {{-20, -26}, {-2, -12}, {22, 4}, {-34, 10}, {8, 18}, {-12, 30}};
        for (int[] t : trees) {
            int h = height(t[0], t[1]);
            for (int y = 1; y <= 5; y++) level.setBlock(new BlockPos(t[0], h + y, t[1]), Blocks.OAK_LOG.defaultBlockState(), 2);
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = 3; dy <= 6; dy++) {
                if (Math.abs(dx) + Math.abs(dz) + Math.max(0, dy - 5) * 2 > 3 || (dx == 0 && dz == 0 && dy <= 5)) continue;
                level.setBlock(new BlockPos(t[0] + dx, h + dy, t[1] + dz),
                    Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true), 2);
            }
        }
        List<Object> report = new ArrayList<>();
        Object[][] roads = {
            {new int[][] {{-50, -40}, {-20, -30}, {4, -12}}, new Knobs()},
            {new int[][] {{-55, 40}, {-20, 38}, {-10, 10}}, new Knobs().set("width", 7)
                .set("surface", new String[] {"mud_bricks", "packed_mud"})
                .set("edge", new String[] {"bricks"}).set("post", new String[] {"dark_oak_fence"})},
            {new int[][] {{30, 60}, {30, 45}, {30, 14}, {55, -5}}, new Knobs().set("width", 4)
                .set("surface", new String[] {"smooth_sandstone", "sandstone"}).set("edge", new String[] {"cut_sandstone"})
                .set("post", new String[] {"sandstone_wall"})},
            {new int[][] {{-60, -60}, {-30, -58}}, new Knobs().set("width", 3).set("lights", false)},
        };
        for (Object[] road : roads) {
            List<BlockPos> points = new ArrayList<>();
            for (int[] p : (int[][]) road[0]) points.add(new BlockPos(p[0], height(p[0], p[1]), p[1]));
            Drawn drawn = pattern.drawOn(new Commission(new Hint.Path(points), (Settings) road[1], World.of(level),
                Direction.NORTH, 7L));
            if (drawn instanceof Drawn.Ready ready) {
                for (var cell : ready.draft().cells()) level.setBlock(ready.corner().offset(cell.offset()), cell.state(), 2);
                Map<BlockPos, Double> tops = new HashMap<>();
                Map<BlockPos, String> what = new HashMap<>();
                for (var cell : ready.draft().cells()) {
                    String role = cell.source().role();
                    if (!role.equals("surface") && !role.equals("edge")) continue;
                    BlockPos at = ready.corner().offset(cell.offset());
                    var state = cell.state();
                    double top = at.getY() + (state.getBlock() instanceof net.minecraft.world.level.block.SlabBlock
                        && state.getValue(net.minecraft.world.level.block.SlabBlock.TYPE)
                        == net.minecraft.world.level.block.state.properties.SlabType.BOTTOM ? 0.5 : 1.0);
                    BlockPos key = new BlockPos(at.getX(), 0, at.getZ());
                    if (tops.merge(key, top, Math::max) == top) what.put(key, at.getY() + "=" + state);
                }
                List<String> pits = new ArrayList<>();
                for (var entry : tops.entrySet()) {
                    int lower = 0, around = 0;
                    for (Direction side : Direction.Plane.HORIZONTAL) {
                        Double beside = tops.get(entry.getKey().relative(side));
                        if (beside == null) continue;
                        around++;
                        if (beside > entry.getValue()) lower++;
                    }
                    if (around >= 3 && lower == around && pits.size() < 6)
                        pits.add(entry.getKey().toShortString() + " " + what.get(entry.getKey()));
                }
                report.add(Map.of("points", points.toString(), "cells", ready.draft().cells().size(),
                    "size", ready.draft().extent().size().toString(), "pits", pits));
            } else {
                var refused = (Drawn.Refused) drawn;
                report.add(Map.of("points", points.toString(), "refused", refused.why() + " " + refused.detail()));
            }
        }
        return Map.of("ok", true, "roads", report);
    }
}
