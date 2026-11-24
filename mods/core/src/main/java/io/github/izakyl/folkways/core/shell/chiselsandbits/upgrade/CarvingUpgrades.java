package io.github.izakyl.folkways.core.shell.chiselsandbits.upgrade;

public final class CarvingUpgrades {
    private CarvingUpgrades() {
    }

    public static void register() {
        V3_Namespaces.register();
    }
}
