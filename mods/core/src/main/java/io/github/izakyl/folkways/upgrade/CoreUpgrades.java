package io.github.izakyl.folkways.upgrade;

import io.github.izakyl.folkways.base.persist.upgrade.Upgrades;

public final class CoreUpgrades {
    private CoreUpgrades() {
    }

    public static void register() {
        Upgrades.part("core/claims", new V2_Claims());
    }
}
