package io.github.izakyl.folkways.core.work.internal;

import io.github.izakyl.folkways.core.vocation.api.Vocation;
import io.github.izakyl.folkways.core.vocation.api.VocationSpec;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.effect.Modifier;
import io.github.izakyl.folkways.effect.Scope;
import io.github.izakyl.folkways.effect.Stat;
import io.github.izakyl.folkways.effect.Stats;
import io.github.izakyl.folkways.effect.perk.Perk;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

public final class HaulWork {

    public static final ResourceLocation DOMAIN =
        ResourceLocation.fromNamespaceAndPath("folkways", "haul");

    public static final String QUICK_HANDS = "hauling_quick_hands";

    public static final String HEAVY = "hauling_heavy";
    public static final String SWIFT = "hauling_swift";
    public static final String BATCH = "hauling_batch";
    public static final String ENDURANCE = "hauling_endurance";

    public static final Stat TRAVEL_SPEED = new Stat(id("haul_travel_speed"), 1, 1, 3);
    public static final Stat BATCH_SIZE = new Stat(id("haul_batch_size"), 1, 1, 4);

    public static final VocationSpec SPEC =
        new VocationSpec(Vocations.HAULING, List.of(
            new Perk(HEAVY, 3, Perk.Grant.multiplied(Stats.PACK_ROOM, () -> List.of(1.25, 1.5, 2.0))),
            new Perk(SWIFT, 3, Perk.Grant.multiplied(TRAVEL_SPEED, () -> List.of(1.08, 1.16, 1.25))),
            new Perk(QUICK_HANDS, 3, Perk.Grant.haste(Vocations.HAULING, () -> List.of(0.85, 0.72, 0.60))),
            new Perk(BATCH, 3, Perk.Grant.added(BATCH_SIZE, () -> List.of(1, 2, 3))),
            new Perk(ENDURANCE, 3, rank -> List.of(new Modifier.Multiply(Stats.FATIGUE,
                List.of(0.85, 0.70, 0.55).get(rank - 1), Scope.trade(Vocations.HAULING))))));

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("folkways", path);
    }

    private static final Workload.Haste HASTE = Workload.Haste.of(Vocations.HAULING);

    private HaulWork() {
    }

    public static Vocation trade() {
        return Vocations.required(Vocations.HAULING);
    }

    public static double haste(Worker who) {
        return HASTE.of(who);
    }
}
