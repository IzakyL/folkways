package io.github.izakyl.folkways.core.api.perk;

import java.util.Map;
import net.minecraft.resources.ResourceLocation;

public interface Perks {

    int rank(String perkId);

    Map<String, Integer> ranks();

    int level(ResourceLocation vocation);

    static Perks fixed(Map<String, Integer> ranks) {
        Map<String, Integer> held = Map.copyOf(ranks);
        return new Perks() {

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
        };
    }
}
