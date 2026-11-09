package io.github.izakyl.folkways.vanilla.upgrade;

public final class VanillaUpgrades {
    private VanillaUpgrades() {
    }

    public static void register() {
        V3_Namespaces.register();
    }
}
