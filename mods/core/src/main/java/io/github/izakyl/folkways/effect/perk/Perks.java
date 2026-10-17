package io.github.izakyl.folkways.effect.perk;

import io.github.izakyl.folkways.effect.Effects;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

public interface Perks {

    int rank(String perkId);

    Map<String, Integer> ranks();

    int level(ResourceLocation vocation);

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
