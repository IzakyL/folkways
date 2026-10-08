import blockwright.minecraft.core.Args;
import blockwright.minecraft.core.Sync;
import dev.blockwright.api.Context;
import io.github.izakyl.folkways.plugins.build.draft.*;
import io.github.izakyl.folkways.front.api.Settings;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
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
        public int count(String key) {
            return (Integer) values.getOrDefault(key, switch (key) {
                case "width" -> 5;
                case "bay" -> 4;
                default -> 3;
            });
        }
        public ResourceLocation choice(String key) {
            String picked = (String) values.getOrDefault(key, key.equals("roof") ? "gable" : "low");
            return ResourceLocation.fromNamespaceAndPath("pattern", picked);
        }
        public Optional<ItemSpec> items(String key) {
            Object v = values.get(key);
            String[] ids = v instanceof String[] many ? many : switch (key) {
                case "floor" -> new String[] {"stone_bricks", "polished_andesite"};
                case "footing" -> new String[] {"cobblestone"};
                case "pillar" -> new String[] {"stripped_spruce_log"};
                case "beam" -> new String[] {"spruce_planks"};
                case "tiles" -> new String[] {"deepslate_tiles"};
                case "ridge" -> new String[] {"polished_deepslate"};
                case "fence" -> new String[] {"spruce_fence"};
                default -> new String[] {"lantern"};
            };
            List<ItemSpec> specs = new ArrayList<>();
            for (String id : ids) specs.add(ItemSpec.of(ResourceLocation.withDefaultNamespace(id)));
            return Optional.of(ItemSpec.anyOf(specs));
        }
    }

    static int height(int x, int z) {
        double h = 10 + 4 * Math.sin(x / 16.0) + 3 * Math.cos(z / 14.0)
            + 5 * Math.exp(-((x + 25) * (x + 25) + (z - 5) * (z - 5)) / 260.0);
        return (int) Math.round(h);
    }

    public static Object run(Context ctx) throws Exception {
        return Sync.stamp(build(ctx));
    }

    private static Object build(Context ctx) {
        var args = Args.of(ctx);
        var pattern = Pattern.parse(ResourceLocation.parse("folkways:arcade"), args.string("arcade"),
            id -> Optional.empty()).orElseThrow();
        ServerLevel level = ctx.server().overworld();
        int r = 72;
        for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
            int top = height(x, z);
            for (int y = -3; y <= 40; y++) {
                BlockState state;
                if (y <= top - 3) state = Blocks.STONE.defaultBlockState();
                else if (y < top) state = Blocks.DIRT.defaultBlockState();
                else if (y == top) state = Blocks.GRASS_BLOCK.defaultBlockState();
                else if (y == top + 1 && Math.floorMod(x * 31 + z * 17, 7) == 0) state = Blocks.SHORT_GRASS.defaultBlockState();
                else state = Blocks.AIR.defaultBlockState();
                level.setBlock(new BlockPos(x, y, z), state, 2);
            }
        }
        // Trees standing in the way of two of the arcades, so their canopies have to be cleared.
        int[][] trees = {{-28, -10}, {-10, 6}, {30, -26}, {40, 36}, {-50, 20}};
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
        Object[][] arcades = {
            // A straight walk across gentle ground, every knob at its default.
            {new int[][] {{-60, -48}, {-20, -48}}, new Knobs()},
            // A bend climbing the hill, a pavilion at the turn and each end, steep roofs.
            {new int[][] {{-48, -12}, {-12, -12}, {-12, 24}}, new Knobs().set("pitch", "steep")},
            // A zigzag with a cornerwise stretch, narrow, single-sloped, open sides, sandstone and oak.
            {new int[][] {{8, -50}, {36, -30}, {56, -30}}, new Knobs().set("width", 3).set("bay", 3)
                .set("roof", "shed").set("railing", false).set("pavilions", false)
                .set("floor", new String[] {"smooth_sandstone", "sandstone"}).set("footing", new String[] {"sandstone"})
                .set("pillar", new String[] {"stripped_oak_log"}).set("beam", new String[] {"oak_planks"})
                .set("tiles", new String[] {"smooth_sandstone"}).set("ridge", new String[] {"cut_sandstone"})},
            // A wide walk up a long slope, tall bays, dark wood and brick.
            {new int[][] {{14, 20}, {60, 44}}, new Knobs().set("width", 7).set("bay", 6).set("height", 4)
                .set("lights", false)
                .set("floor", new String[] {"bricks"}).set("pillar", new String[] {"dark_oak_log"})
                .set("beam", new String[] {"dark_oak_planks"}).set("fence", new String[] {"dark_oak_fence"})
                .set("tiles", new String[] {"mud_bricks"}).set("ridge", new String[] {"bricks"})},
        };
        for (Object[] arcade : arcades) {
            List<BlockPos> points = new ArrayList<>();
            for (int[] p : (int[][]) arcade[0]) points.add(new BlockPos(p[0], height(p[0], p[1]), p[1]));
            Drawn drawn = pattern.drawOn(new Commission(new Hint.Path(points), (Settings) arcade[1], World.of(level),
                Direction.NORTH, 7L));
            if (drawn instanceof Drawn.Ready ready) {
                for (var cell : ready.draft().cells()) level.setBlock(ready.corner().offset(cell.offset()), cell.state(), 2);
                report.add(Map.of("points", points.toString(), "cells", ready.draft().cells().size(),
                    "size", ready.draft().extent().size().toString(), "reports", new TreeMap<>(ready.reports())));
            } else {
                var refused = (Drawn.Refused) drawn;
                report.add(Map.of("points", points.toString(), "refused", refused.why() + " " + refused.detail()));
            }
        }
        return Map.of("ok", true, "arcades", report);
    }
}
