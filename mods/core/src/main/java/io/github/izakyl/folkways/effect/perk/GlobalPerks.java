package io.github.izakyl.folkways.effect.perk;

import io.github.izakyl.folkways.effect.Stats;
import io.github.izakyl.folkways.effect.perk.Perk.Grant;
import java.util.List;
import java.util.function.Supplier;

public final class GlobalPerks {

    public static final String LONGARM = "global_longarm";

    public static final String BRISK = "global_brisk";

    public static final String PACKMULE = "global_packmule";

    public static final String ASCETIC = "global_ascetic";

    private static final List<Double> REACH_BY_RANK = List.of(1.0, 2.0, 3.0);

    private static final List<Double> STRIDE_BY_RANK = List.of(1.10, 1.20, 1.30);

    private static final List<Double> ROOM_BY_RANK = List.of(1.15, 1.30, 1.45);

    private GlobalPerks() {
    }

    // The ascetic's ladder is the server's to set: one entry per rank, times the rate food is worn down at.
    public static void install(Supplier<? extends List<? extends Number>> asceticAppetite) {
        PerkPool.legacy(new Perk(PACKMULE, 3, Grant.multiplied(Stats.PACK_ROOM, () -> ROOM_BY_RANK)));
        PerkPool.install(
            new Perk(PerkPool.STOIC, 1, Grant.flag(Stats.PATIENT)),
            new Perk(LONGARM, 3, Grant.added(Stats.REACH, () -> REACH_BY_RANK)),
            new Perk(BRISK, 3, Grant.multiplied(Stats.MOVE_SPEED, () -> STRIDE_BY_RANK)),

            new Perk(ASCETIC, 3, Grant.multiplied(Stats.APPETITE, asceticAppetite)),
            new Perk("global_tireless", 3, Grant.multiplied(Stats.FATIGUE, () -> List.of(0.85, 0.70, 0.50))),
            new Perk("global_easygoing", 3, Grant.multiplied(Stats.DISCOMFORT, () -> List.of(0.80, 0.60, 0.40))),
            new Perk("global_sociable", 3, Grant.multiplied(Stats.SOCIAL_RECOVERY, () -> List.of(1.30, 1.60, 2.0))));
    }
}
