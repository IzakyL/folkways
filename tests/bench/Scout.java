import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import java.util.*;
import dev.blockwright.api.Context;
import dev.blockwright.api.TaskException;
import blockwright.minecraft.core.Args;

// Picks where the bench town goes from the world generator's own noise, without generating a chunk:
// gentle dry land for the core, open water close by for the docks, and a dry ring for the railway.
// args: from {x, z}, reach, half (the core's half side), ring (railway ring's distance from the core edge).
public final class Task {
    static final int STEP = 16;
    static final Set<String> SHUNNED = Set.of("jungle", "sparse_jungle", "bamboo_jungle", "desert", "badlands",
        "eroded_badlands", "wooded_badlands", "swamp", "mangrove_swamp", "snowy_plains", "ice_spikes", "snowy_taiga",
        "frozen_river", "mushroom_fields", "stony_peaks", "jagged_peaks", "frozen_peaks", "snowy_slopes", "grove",
        "windswept_hills", "windswept_gravelly_hills", "windswept_forest", "dark_forest", "beach", "stony_shore");

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], kv[i + 1]);
        return out;
    }

    public static Object run(Context ctx) throws Exception {
        Args args = Args.of(ctx);
        ServerLevel level = ctx.server().overworld();
        Args from = args.requireObject("from");
        int fx = Math.floorDiv((int) from.integer("x"), STEP) * STEP, fz = Math.floorDiv((int) from.integer("z"), STEP) * STEP;
        int reach = (int) args.integer("reach"), half = (int) args.integer("half"), ring = (int) args.integer("ring");
        var generator = level.getChunkSource().getGenerator();
        var random = level.getChunkSource().randomState();
        int sea = generator.getSeaLevel();

        int span = reach + half + ring + 16;
        int n = span * 2 / STEP + 1;
        int[] height = new int[n * n];
        byte[] kind = new byte[n * n]; // 0 land, 1 water, 2 shunned land
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            int x = fx - span + i * STEP, z = fz - span + j * STEP;
            int floor = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random);
            height[i * n + j] = floor;
            if (floor < sea) kind[i * n + j] = 1;
            else {
                String biome = level.getBiome(new BlockPos(x, floor, z)).unwrapKey().map(k -> k.location().getPath()).orElse("");
                kind[i * n + j] = (byte) (SHUNNED.contains(biome) || biome.contains("ocean") ? 2 : 0);
            }
        }

        record Site(int x, int z, double score, int level, double spread, double water, double rail, double land) {}
        List<Site> sites = new ArrayList<>();
        int cells = half / STEP, outer = (half + ring) / STEP, band = 1;
        for (int dx = -reach; dx <= reach; dx += 32) for (int dz = -reach; dz <= reach; dz += 32) {
            int ci = (dx + span) / STEP, cj = (dz + span) / STEP;
            int land = 0, total = 0, shunned = 0, water = 0, around = 0, railDry = 0, railAll = 0;
            List<Integer> heights = new ArrayList<>();
            for (int i = -outer - band; i <= outer + band; i++) for (int j = -outer - band; j <= outer + band; j++) {
                int k = (ci + i) * n + (cj + j);
                int ring2 = Math.max(Math.abs(i), Math.abs(j));
                if (ring2 <= cells) {
                    total++;
                    if (kind[k] == 1) continue;
                    if (kind[k] == 2) shunned++;
                    land++;
                    heights.add(height[k]);
                } else if (ring2 >= outer - band) {
                    railAll++;
                    if (kind[k] != 1 && Math.abs(height[k] - sea - 6) < 14) railDry++;
                }
                if (ring2 > cells && ring2 <= cells + 3) {
                    around++;
                    if (kind[k] == 1) water++;
                }
            }
            if (heights.isEmpty()) continue;
            Collections.sort(heights);
            int median = heights.get(heights.size() / 2);
            double mean = heights.stream().mapToInt(h -> h).average().orElse(0);
            double spread = Math.sqrt(heights.stream().mapToDouble(h -> (h - mean) * (h - mean)).average().orElse(0));
            double landShare = land / (double) total, waterShare = water / (double) Math.max(1, around);
            double railShare = railDry / (double) Math.max(1, railAll);
            if (landShare < 0.93 || median < sea + 1 || median > sea + 24) continue;
            // A compact town and its streets need gentle land across the whole core,
            // not just enough individually flat rectangles on the sides of a steep hill.
            double score = -spread * 3.2 - 30.0 * shunned / Math.max(1, land) - 25 * (1 - landShare)
                + (waterShare >= 0.04 && waterShare <= 0.45 ? 10 : waterShare > 0 ? 3 : -6)
                + 18 * railShare - Math.hypot(dx, dz) / 320.0;
            sites.add(new Site(fx + dx, fz + dz, score, median, spread, waterShare, railShare, landShare));
        }
        if (sites.isEmpty()) throw new TaskException("refused", "no dry, gentle site within reach", map("sea", sea));
        sites.sort(Comparator.comparingDouble(Site::score).reversed());
        var best = sites.get(0);
        List<Object> runners = new ArrayList<>();
        for (var s : sites.subList(0, Math.min(5, sites.size())))
            runners.add(map("x", s.x(), "z", s.z(), "score", Math.round(s.score() * 10) / 10.0, "level", s.level(),
                "spread", Math.round(s.spread() * 10) / 10.0, "water", Math.round(s.water() * 100) / 100.0,
                "rail", Math.round(s.rail() * 100) / 100.0));
        return map("ok", true, "x", best.x(), "z", best.z(), "level", best.level(), "sea", sea,
            "spread", best.spread(), "water", best.water(), "rail", best.rail(), "land", best.land(),
            "candidates", sites.size(), "runners_up", runners);
    }
}
