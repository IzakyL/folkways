package io.github.izakyl.folkways.core.shell.cookery.upgrade;

public final class CookeryUpgrades {
    private CookeryUpgrades() {
    }

    public static void register() {
        V3_Namespaces.register();
    }
}
