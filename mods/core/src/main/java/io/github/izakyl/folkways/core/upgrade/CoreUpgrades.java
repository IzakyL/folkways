package io.github.izakyl.folkways.core.upgrade;

import io.github.izakyl.folkways.core.base.persist.upgrade.Upgrades;

public final class CoreUpgrades {
    private CoreUpgrades() {
    }

    public static void register() {
        Upgrades.part("core/claims", new V2_Claims());
        V3_Namespaces.register();
        V5_Namespaces.register();
        Upgrades.part("core/seat_stance", new V5_SeatStance());
        Upgrades.part("core/facilities", new V5_Facilities());
    }
}
