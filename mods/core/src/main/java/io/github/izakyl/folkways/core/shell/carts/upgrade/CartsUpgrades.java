package io.github.izakyl.folkways.core.shell.carts.upgrade;

public final class CartsUpgrades {
    private CartsUpgrades() {
    }

    public static void register() {
        V3_Namespaces.register();
    }
}
