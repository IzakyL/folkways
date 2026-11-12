package io.github.izakyl.folkways.vanilla.upgrade;

import io.github.izakyl.folkways.core.base.persist.upgrade.Upgrades;

public final class VanillaUpgrades {
    private VanillaUpgrades() {
    }

    public static void register() {
        V3_Namespaces.register();
        Upgrades.part("vanilla/equipment", new V4_EquipmentProfiles());
    }
}
