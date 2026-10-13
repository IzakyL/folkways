import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import java.util.*;
import dev.blockwright.api.Context;
import blockwright.minecraft.core.Args;

// The lie of the land around the town, from the world generator's own noise, without generating a chunk and off the
// server thread: a coarse grid the site picker and the rail planner read to choose where the town goes, which way a
// line can run and where the mine at its far end can stand.
// args: minX, minZ, maxX, maxZ, step. Answers row-major (x outer, z inner), one comma-separated string per x row:
// height (the ocean-floor heightmap) and kind (0 land, 1 water, 2 shunned land, 3 temperate land).
public final class Task {
    static final Set<String> SHUNNED = Set.of("jungle", "sparse_jungle", "bamboo_jungle", "desert", "badlands",
        "eroded_badlands", "wooded_badlands", "swamp", "mangrove_swamp", "snowy_plains", "ice_spikes", "snowy_taiga",
        "frozen_river", "mushroom_fields", "stony_peaks", "jagged_peaks", "frozen_peaks", "snowy_slopes", "grove",
        "windswept_hills", "windswept_gravelly_hills", "windswept_forest", "dark_forest", "beach", "stony_shore");
    static final List<String> SHUNNED_WORDS = List.of("desert", "badlands", "mesa", "savanna", "shrubland", "arid",
        "sand", "volcan", "peaks", "cliffs", "yellowstone", "skylands", "jungle", "snow", "frozen", "ice", "glacial",
        "swamp", "mangrove", "mushroom", "mirage", "caldera", "canyon", "gravel_beach");
    static final Set<String> TEMPERATE = Set.of("plains", "sunflower_plains", "flower_forest", "forest", "birch_forest",
        "meadow", "cherry_grove", "highlands", "alpine_highlands", "forested_highlands", "blooming_valley",
        "blooming_plateau", "lavender_valley", "lavender_forest", "moonlight_valley", "moonlight_grove", "valley_clearing",
        "temperate_highlands", "shield_clearing", "lush_valley");

    static boolean shunned(String biome) {
        if (SHUNNED.contains(biome) || biome.contains("ocean")) return true;
        for (String word : SHUNNED_WORDS) if (biome.contains(word)) return true;
        return false;
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], kv[i + 1]);
        return out;
    }

    public static Object run(Context ctx) throws Exception {
        Args args = Args.of(ctx);
        ServerLevel level = ctx.server().overworld();
        int minX = (int) args.integer("minX"), minZ = (int) args.integer("minZ");
        int maxX = (int) args.integer("maxX"), maxZ = (int) args.integer("maxZ"), step = (int) args.integer("step");
        var generator = level.getChunkSource().getGenerator();
        var random = level.getChunkSource().randomState();
        int sea = generator.getSeaLevel();
        int w = (maxX - minX) / step + 1, d = (maxZ - minZ) / step + 1;
        List<String> heights = new ArrayList<>(w), kinds = new ArrayList<>(w);
        for (int i = 0; i < w; i++) {
            var hr = new StringBuilder(d * 4);
            var kr = new StringBuilder(d * 2);
            for (int j = 0; j < d; j++) {
                int x = minX + i * step, z = minZ + j * step;
                int floor = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random);
                int kind;
                if (floor < sea) kind = 1;
                else {
                    // Straight from the biome source: a read off the server thread must not reach for chunks.
                    String biome = generator.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(floor),
                        QuartPos.fromBlock(z), random.sampler()).unwrapKey().map(k -> k.location().getPath()).orElse("");
                    kind = shunned(biome) ? 2 : TEMPERATE.contains(biome) ? 3 : 0;
                }
                if (j > 0) { hr.append(','); kr.append(','); }
                hr.append(floor);
                kr.append(kind);
            }
            heights.add(hr.toString());
            kinds.add(kr.toString());
        }
        return map("ok", true, "minX", minX, "minZ", minZ, "step", step, "width", w, "depth", d, "sea", sea,
            "height", heights, "kind", kinds);
    }
}
