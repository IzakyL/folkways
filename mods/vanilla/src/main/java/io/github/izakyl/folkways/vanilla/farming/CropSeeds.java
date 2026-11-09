package io.github.izakyl.folkways.vanilla.farming;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.LevelReader;

/** The actual farming catalogue and outstanding sowing requests, without optional-mod tag guesses. */
public final class CropSeeds {
    public record Sowing(UUID node, ItemSpec seed, long count) {
    }

    private CropSeeds() {
    }

    public static Set<Item> catalogue(LevelReader level, BlockPos at) {
        Set<Item> seeds = new LinkedHashSet<>();
        for (var id : Crop.sowable()) {
            Crop.of(level, at, id).ifPresent(crop -> {
                seeds.add(crop.seed());
                crop.behavior().ifPresent(behavior -> seeds.addAll(behavior.seeds()));
            });
        }
        return java.util.Collections.unmodifiableSet(seeds);
    }

    public static List<Item> forCrop(LevelReader level, BlockPos at, ResourceLocation cropId) {
        return Crop.of(level, at, cropId).map(crop -> crop.behavior()
            .map(CropBehavior::seeds).orElseGet(() -> List.of(crop.seed()))).orElseGet(List::of);
    }

    public static List<Sowing> sowing(Colony colony, ServerLevel level) {
        return colony.service(FarmingContent.ID, FarmPresence.class)
            .map(presence -> presence.sowingPlans(level)).orElseGet(List::of);
    }
}
