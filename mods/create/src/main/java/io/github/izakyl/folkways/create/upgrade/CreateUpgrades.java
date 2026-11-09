package io.github.izakyl.folkways.create.upgrade;

public final class CreateUpgrades {
    private CreateUpgrades() {
    }

    public static void register() {
        V3_Namespaces.register();
    }
}
