package io.github.izakyl.folkways.plugins.build;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

/** Reads the active biome's placed ore features, including datapack replacements and modded ores. */
final class LocalMinerals {
    private static final TagKey<Block> MINERALS = TagKey.create(Registries.BLOCK,
        ResourceLocation.fromNamespaceAndPath("folkways", "prospectable_minerals"));
    private record Profile(double attempts, Map<Integer, Double> heights, boolean heightLimited) { }
    record Candidate(BlockState ore, double weight) { }
    private static final Map<ServerLevel, Map<PlacedFeature, Profile>> PROFILES = new WeakHashMap<>();

    private LocalMinerals() { }

    static void clearProfiles() { PROFILES.clear(); }

    static Optional<BlockState> choose(ServerLevel level, BlockPos pos, BlockState stone, RandomSource random) {
        List<Candidate> candidates = candidates(level, pos, stone);
        double total = candidates.stream().mapToDouble(Candidate::weight).sum();
        if (total <= 0) return Optional.empty();
        double roll = random.nextDouble() * total;
        for (Candidate candidate : candidates) {
            roll -= candidate.weight();
            if (roll < 0) return Optional.of(candidate.ore());
        }
        return Optional.of(candidates.getLast().ore());
    }

    static List<Candidate> candidates(ServerLevel level, BlockPos pos, BlockState stone) {
        List<Candidate> result = new ArrayList<>();
        var features = level.getBiome(pos).value().getGenerationSettings().features();
        var cache = PROFILES.computeIfAbsent(level, unused -> new IdentityHashMap<>());
        for (var step : features) for (var holder : step) {
            PlacedFeature placed = holder.value();
            if (!(placed.feature().value().config() instanceof OreConfiguration ore)) continue;
            Profile profile = cache.computeIfAbsent(placed, key -> profile(level, key));
            double height = profile.heightLimited() ? profile.heights().getOrDefault(pos.getY(), 0.0) : 1.0 / level.getHeight();
            double weight = profile.attempts() * ore.size * height;
            if (weight <= 0) continue;
            // Configured stone/dirt/gravel patches also use OreConfiguration; only mineral outputs qualify.
            for (var target : ore.targetStates) {
                if (target.state.is(MINERALS) && target.target.test(stone, RandomSource.create(0))) {
                    result.add(new Candidate(target.state, weight));
                    break;
                }
            }
        }
        return result;
    }

    private static Profile profile(ServerLevel level, PlacedFeature placed) {
        double attempts = 1;
        Map<Integer, Double> heights = Map.of();
        boolean heightLimited = false;
        WorldGenerationContext context = new WorldGenerationContext(level.getChunkSource().getGenerator(), level);
        for (var modifier : placed.placement()) {
            Optional<JsonElement> encoded = PlacementModifier.CODEC.encodeStart(JsonOps.INSTANCE, modifier).result();
            if (encoded.isEmpty() || !encoded.get().isJsonObject()) continue;
            JsonObject json = encoded.get().getAsJsonObject();
            String type = json.get("type").getAsString();
            if (type.equals("minecraft:count")) attempts *= expectedCount(json.get("count"));
            else if (type.equals("minecraft:rarity_filter")) attempts /= json.get("chance").getAsDouble();
            else if (type.equals("minecraft:height_range")) {
                heights = distribution(json.get("height"), context);
                heightLimited = true;
            }
        }
        return new Profile(attempts, heights, heightLimited);
    }

    private static double expectedCount(JsonElement json) {
        if (json.isJsonPrimitive()) return json.getAsDouble();
        JsonObject object = json.getAsJsonObject();
        String type = object.has("type") ? object.get("type").getAsString() : "";
        if (type.equals("minecraft:uniform")) return (object.get("min_inclusive").getAsDouble() + object.get("max_inclusive").getAsDouble()) / 2;
        var provider = IntProvider.CODEC.parse(JsonOps.INSTANCE, json).result();
        if (provider.isEmpty()) return 0;
        // Custom IntProviders can have skewed or weighted distributions, so min/max averaging is unsafe.
        // Estimate their expectation with 16,384 deterministic samples once per level and placed feature.
        RandomSource random = RandomSource.create(0x4f7265);
        double sum = 0;
        for (int i = 0; i < 16384; i++) sum += provider.get().sample(random);
        return sum / 16384;
    }

    /** Exact vanilla uniform/triangle/trapezoid probabilities; arbitrary providers use a stable cached estimate. */
    static Map<Integer, Double> distribution(JsonElement json, WorldGenerationContext context) {
        var parsed = HeightProvider.CODEC.parse(JsonOps.INSTANCE, json).result();
        if (parsed.isEmpty()) return Map.of();
        Map<Integer, Double> values = new HashMap<>();
        if (json.isJsonObject()) {
            JsonObject object = json.getAsJsonObject();
            String type = object.has("type") ? object.get("type").getAsString() : "";
            if (type.equals("minecraft:uniform") || type.equals("minecraft:trapezoid")) {
                int min = VerticalAnchor.CODEC.parse(JsonOps.INSTANCE, object.get("min_inclusive")).getOrThrow().resolveY(context);
                int max = VerticalAnchor.CODEC.parse(JsonOps.INSTANCE, object.get("max_inclusive")).getOrThrow().resolveY(context);
                if (max < min) return Map.of(min, 1.0);
                int span = max - min;
                int plateau = object.has("plateau") ? object.get("plateau").getAsInt() : 0;
                if (type.equals("minecraft:uniform") || plateau >= span) {
                    for (int y = min; y <= max; y++) values.put(y, 1.0 / (span + 1));
                } else {
                    int a = (span - plateau) / 2, b = span - a;
                    for (int offset = 0; offset <= span; offset++) {
                        int ways = Math.max(0, Math.min(a, offset) - Math.max(0, offset - b) + 1);
                        values.put(min + offset, ways / ((a + 1.0) * (b + 1.0)));
                    }
                }
                return values;
            }
        }
        // Sample the provider's full support using THIS level's generator context (including relative anchors).
        // No artificial Y clamp: lookup of the actual mined Y naturally rejects unreachable/out-of-range values.
        RandomSource random = RandomSource.create(0x4f7265);
        for (int i = 0; i < 32768; i++) values.merge(parsed.get().sample(random, context), 1.0 / 32768, Double::sum);
        return values;
    }
}
