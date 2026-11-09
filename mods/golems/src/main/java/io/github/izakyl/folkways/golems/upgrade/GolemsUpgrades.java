package io.github.izakyl.folkways.golems.upgrade;

public final class GolemsUpgrades {
    private GolemsUpgrades() {
    }

    public static void register() {
        V3_Namespaces.register();
    }
}
