package io.github.izakyl.folkways.core.api.perk;

import io.github.izakyl.folkways.core.api.perk.Perk;
import io.github.izakyl.folkways.core.api.perk.PerkPool;

public final class GlobalPerks {

    public static final String LONGARM = "global_longarm";

    public static final String BRISK = "global_brisk";

    public static final String PACKMULE = "global_packmule";

    public static final String ASCETIC = "global_ascetic";

    private GlobalPerks() {
    }

    public static void install() {
        PerkPool.install(
            new Perk(PerkPool.STOIC, 1),
            new Perk(LONGARM, 4),
            new Perk(BRISK, 4),
            new Perk(PACKMULE, 4),
            new Perk(ASCETIC, 4));
    }
}
