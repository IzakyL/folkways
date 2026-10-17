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

    private static final List<Double> REACH_BY_RANK = List.of(0.5, 1.0, 1.5, 2.0);

    private static final List<Double> STRIDE_BY_RANK = List.of(1.15, 1.30, 1.45, 1.60);

    private static final List<Double> ROOM_BY_RANK = List.of(1.5, 2.0, 2.5, 3.0);

    private GlobalPerks() {
    }

    // The ascetic's ladder is the server's to set: one entry per rank, times the rate food is worn down at.
    public static void install(Supplier<? extends List<? extends Number>> asceticAppetite) {
        PerkPool.install(
            new Perk(PerkPool.STOIC, 1, Grant.flag(Stats.PATIENT)),
            new Perk(LONGARM, 4, Grant.added(Stats.REACH, () -> REACH_BY_RANK)),
            new Perk(BRISK, 4, Grant.multiplied(Stats.MOVE_SPEED, () -> STRIDE_BY_RANK)),
            new Perk(PACKMULE, 4, Grant.multiplied(Stats.PACK_ROOM, () -> ROOM_BY_RANK)),
            new Perk(ASCETIC, 4, Grant.multiplied(Stats.APPETITE, asceticAppetite)));
    }
}
