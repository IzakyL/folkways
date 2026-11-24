package io.github.izakyl.folkways.vanilla.upgrade;

import io.github.izakyl.folkways.core.base.persist.upgrade.Upgrades;

public final class VanillaUpgrades {
    private VanillaUpgrades() {
    }

    public static void register() {
        V3_Namespaces.register();
        Upgrades.part("vanilla/equipment", new V4_EquipmentProfiles());
        // After core/facilities, which writes the facility parameters this adds to.
        Upgrades.part("vanilla/compost_settings", new V5_CompostSettings());
    }
}
