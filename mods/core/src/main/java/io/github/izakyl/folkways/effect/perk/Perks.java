package io.github.izakyl.folkways.effect.perk;

import io.github.izakyl.folkways.effect.Effects;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.resources.ResourceLocation;

public interface Perks {

    int rank(String perkId);

    Map<String, Integer> ranks();

    int level(ResourceLocation vocation);

    int xp(ResourceLocation vocation);

    // Cumulative experience for the next level; zero once the track no longer grows.
    int nextLevelXp(ResourceLocation vocation);

    int maxLevel(ResourceLocation vocation);

    Map<String, Integer> ranks(ResourceLocation vocation);

    Map<String, Integer> globalRanks();

    // What the ranks held grant.
    Effects effects();

    static Perks fixed(Map<String, Integer> ranks) {
        Map<String, Integer> held = Map.copyOf(ranks);
        return new Perks() {

            private Effects granted = PerkPool.effects(held);
            private int grants = PerkPool.grants();

            @Override
            public int rank(String perkId) {
                return held.getOrDefault(perkId, 0);
            }

            @Override
            public Map<String, Integer> ranks() {
                return held;
            }

            @Override
            public int level(ResourceLocation vocation) {
                return 0;
            }

            @Override
            public int xp(ResourceLocation vocation) {
                return 0;
            }

            @Override
            public int nextLevelXp(ResourceLocation vocation) {
                return 0;
            }

            @Override
            public int maxLevel(ResourceLocation vocation) {
                return 0;
            }

            @Override
            public Map<String, Integer> ranks(ResourceLocation vocation) {
                var pool = PerkPool.vocationCandidates(vocation, Map.of());
                return held.entrySet().stream().filter(entry -> pool.contains(entry.getKey()))
                    .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
            }

            @Override
            public Map<String, Integer> globalRanks() {
                return held.entrySet().stream().filter(entry -> PerkPool.global(entry.getKey()))
                    .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
            }

            @Override
            public Effects effects() {
                if (grants != PerkPool.grants()) {
                    granted = PerkPool.effects(held);
                    grants = PerkPool.grants();
                }
                return granted;
            }
        };
    }
}
