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
import java.util.*;

public final class Task {
    static final class Knobs implements Settings {
        final Map<String, Integer> counts = new HashMap<>();
        final Map<String, String> items = new HashMap<>();
        boolean gate = true;
        Knobs(int height, int thick, int spacing, String... palette) {
            counts.put("height", height); counts.put("thickness", thick); counts.put("spacing", spacing);
            String[] keys = {"wall", "base", "walkway", "trim", "timber", "panel", "roof"};
            for (int i = 0; i < keys.length; i++) items.put(keys[i], palette[i]);
        }
        public boolean flag(String key) { return key.equals("gate") && gate; }
        public int count(String key) { return counts.getOrDefault(key, 0); }
        public ResourceLocation choice(String key) { return null; }
        public Optional<ItemSpec> items(String key) {
            return Optional.ofNullable(items.get(key)).map(id -> ItemSpec.of(ResourceLocation.withDefaultNamespace(id)));
        }
    }

    static int hill(int x, int z) {
        double a = 7 * Math.exp(-(Math.pow(x - 62, 2) + Math.pow(z + 36, 2)) / 200.0);
        double b = 5 * Math.exp(-(Math.pow(x + 40, 2) + Math.pow(z - 30, 2)) / 400.0);
        double c = 0.12 * (x - 10) * (z > 10 && x > 10 ? 1 : 0);
        return 3 + (int) Math.round(a + b + Math.max(0, c));
    }

    public static Object run(Context ctx) throws Exception {
        return Sync.stamp(build(ctx));
    }

    private static Object build(Context ctx) {
        var args = Args.of(ctx);
        ServerLevel level = ctx.server().overworld();
        var pattern = Pattern.parse(ResourceLocation.parse("folkways:city_wall"), args.string("source")).orElseThrow();
        var air = Blocks.AIR.defaultBlockState();
        for (int x = -72; x <= 72; x++) for (int z = -72; z <= 72; z++) {
            int top = hill(x, z);
            for (int y = 0; y <= 40; y++) {
                var state = y < top ? Blocks.STONE.defaultBlockState() : y == top ? Blocks.GRASS_BLOCK.defaultBlockState() : air;
                level.setBlock(new BlockPos(x, y, z), state, 2 | 16);
            }
        }
        String[] stone = {"stone_bricks", "cobblestone", "smooth_stone", "polished_andesite", "stripped_spruce_log", "spruce_planks", "deepslate_tiles"};
        String[] sand = {"sandstone", "cut_sandstone", "smooth_sandstone", "chiseled_sandstone", "stripped_dark_oak_log", "dark_oak_planks", "red_nether_bricks"};
        String[] brick = {"deepslate_bricks", "cobbled_deepslate", "polished_andesite", "polished_deepslate", "stripped_mangrove_log", "mangrove_planks", "deepslate_tiles"};
        List<Object[]> scenes = List.of(
            new Object[] {"straight", List.of(new int[] {-64, -56}, new int[] {0, -56}), new Knobs(7, 5, 20, stone)},
            new Object[] {"corner-hill", List.of(new int[] {16, -64}, new int[] {60, -64}, new int[] {60, -22}), new Knobs(7, 5, 20, stone)},
            new Object[] {"u-sand", List.of(new int[] {-64, 12}, new int[] {-64, 50}, new int[] {-26, 50}, new int[] {-26, 12}), new Knobs(9, 7, 24, sand)},
            new Object[] {"slant-deep", List.of(new int[] {16, 22}, new int[] {38, 34}, new int[] {52, 60}), new Knobs(6, 4, 0, brick)}
        );
        var reports = new ArrayList<Object>();
        for (Object[] scene : scenes) {
            @SuppressWarnings("unchecked") var marks = (List<int[]>) scene[1];
            var points = new ArrayList<BlockPos>();
            for (int[] m : marks) points.add(new BlockPos(m[0], hill(m[0], m[1]) + 1, m[1]));
            Drawn drawn = pattern.drawOn(new Commission(new Hint.Path(points), (Settings) scene[2], World.of(level), Direction.NORTH, 0L));
            if (!(drawn instanceof Drawn.Ready ready)) {
                reports.add(Map.of("scene", scene[0], "refused", drawn.toString()));
                continue;
            }
            var seen = new HashSet<BlockPos>();
            var roles = new TreeMap<String, Integer>();
            for (var cell : ready.draft().cells()) {
                var pos = ready.corner().offset(cell.offset());
                if (!seen.add(pos)) throw new AssertionError("duplicate " + pos);
                roles.merge(cell.source().role(), 1, Integer::sum);
                level.setBlock(pos, cell.state(), 2 | 16);
            }
            reports.add(Map.of("scene", scene[0], "blocks", seen.size(), "roles", roles.toString(),
                "corner", ready.corner().toShortString(), "size", ready.draft().extent().size().toShortString()));
        }
        return Map.of("ok", true, "scenes", reports);
    }
}
