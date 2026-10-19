import blockwright.minecraft.core.Args;
import blockwright.minecraft.core.Sync;
import dev.blockwright.api.Context;
import io.github.izakyl.folkways.plugins.build.draft.*;
import io.github.izakyl.folkways.plugins.build.Joints;
import io.github.izakyl.folkways.front.api.Settings;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;
import java.util.function.IntBinaryOperator;

public final class Task {
    static final class Knobs implements Settings {
        final Map<String, String> chosen;
        Knobs(Map<String, String> chosen) { this.chosen = chosen; }
        public boolean flag(String key) { return false; }
        public int count(String key) { return 0; }
        public ResourceLocation choice(String key) { return null; }
        public Optional<ItemSpec> items(String key) {
            return Optional.of(ItemSpec.of(ResourceLocation.parse(chosen.get(key))));
        }
    }

    static void terrain(ServerLevel level, int x0, int z0, int x1, int z1, int water, IntBinaryOperator height) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                int h = height.applyAsInt(x, z);
                for (int y = -14; y <= 30; y++) {
                    BlockState state;
                    if (y <= h - 3) state = Blocks.STONE.defaultBlockState();
                    else if (y < h) state = Blocks.DIRT.defaultBlockState();
                    else if (y == h) state = h < water ? Blocks.SAND.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
                    else if (y <= water) state = Blocks.WATER.defaultBlockState();
                    else state = Blocks.AIR.defaultBlockState();
                    level.setBlock(new BlockPos(x, y, z), state, 2);
                }
            }
        }
    }

    static Map<String, Object> lay(ServerLevel level, Pattern pattern, BlockPos a, BlockPos b, Map<String, String> knobs) {
        return lay(level, pattern, List.of(a, b), knobs);
    }

    static Map<String, Object> lay(ServerLevel level, Pattern pattern, List<BlockPos> marks, Map<String, String> knobs) {
        Drawn drawn = pattern.drawOn(new Commission(new Hint.Path(marks), new Knobs(knobs), World.of(level),
            Direction.NORTH, 0L));
        if (!(drawn instanceof Drawn.Ready ready)) {
            return Map.of("refused", drawn.toString());
        }
        for (Draft.Cell cell : ready.draft().cells()) {
            level.setBlock(ready.corner().offset(cell.offset()), cell.state(), 3);
        }
        int joined = 0;
        for (Joint joint : ready.draft().joints()) {
            BlockPos from = ready.corner().offset(joint.from());
            BlockPos to = ready.corner().offset(joint.to());
            var cost = Joints.joiner().cost(level, from, to);
            if (cost.isPresent() && Joints.joiner().join(level, from, to, cost.get()).joined()) {
                joined++;
            }
        }
        return Map.of("cells", ready.draft().cells().size(), "size", ready.draft().extent().size().toString(),
            "joints", ready.draft().joints().size(), "joined", joined);
    }

    public static Object run(Context ctx) throws Exception {
        return Sync.stamp(build(ctx));
    }

    private static Object build(Context ctx) {
        var args = Args.of(ctx);
        var pattern = Pattern.parse(ResourceLocation.parse("folkways:railway"), args.string("source")).orElseThrow();
        ServerLevel level = ctx.server().overworld();
        Map<String, String> stone = Map.of("track", "create:track", "ballast", "minecraft:tuff",
            "bed", "minecraft:cobblestone", "masonry", "minecraft:bricks", "accent", "minecraft:polished_andesite");
        Map<String, String> brick = Map.of("track", "create:track", "ballast", "minecraft:andesite",
            "bed", "minecraft:mud_bricks", "masonry", "minecraft:stone_bricks", "accent", "minecraft:polished_deepslate");
        Map<String, Object> out = new LinkedHashMap<>();
        terrain(level, -60, -24, 60, 24, -3, (x, z) -> {
            int h = 3;
            h = Math.max(h, 3 + (int) Math.max(0, Math.min(15, 22 - Math.abs(x + 26) * 0.9 - Math.abs(z) * 0.25)));
            h = Math.max(h, 3 + (int) Math.max(0, 5 - Math.abs(x - 32) * 0.6 - Math.abs(z) * 0.2));
            if (x >= -2 && x <= 22) h = Math.min(h, Math.min(3, -7 + Math.abs(x - 10)));
            if (x > 40) h = 3 - Math.min(4, (x - 40) / 2);
            return h;
        });
        out.put("mixed", lay(level, pattern, new BlockPos(-48, 3, 0), new BlockPos(48, 3, 0), stone));
        terrain(level, -60, 28, 60, 104, -20, (x, z) -> 3 + (int) Math.max(0, 8 - Math.hypot(x - 2, z - 56) * 0.35)
            + (int) (1.5 * Math.sin(x / 9.0) + 1.5 * Math.cos(z / 11.0)));
        out.put("grade", lay(level, pattern, List.of(new BlockPos(-50, 4, 40), new BlockPos(-10, 10, 40),
            new BlockPos(20, 10, 70), new BlockPos(20, 10, 98)), brick));
        terrain(level, 0, 108, 60, 172, -20, (x, z) -> 3 + (int) Math.max(0, Math.min(12, 16 - Math.hypot(x - 24, z - 136) * 0.55)));
        out.put("diagonal", lay(level, pattern, new BlockPos(4, 3, 116), new BlockPos(44, 3, 156), stone));
        terrain(level, -60, 200, 60, 240, -9, (x, z) -> Math.abs(x) < 26 ? Math.max(-12, 3 - (26 - Math.abs(x)) / 2) : 3);
        out.put("viaduct", lay(level, pattern, new BlockPos(-40, 3, 220), new BlockPos(40, 3, 220), stone));
        return out;
    }
}
