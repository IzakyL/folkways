package io.github.izakyl.folkways.core.shell.golems.upgrade;

public final class GolemsUpgrades {
    private GolemsUpgrades() {
    }

    public static void register() {
        V3_Namespaces.register();
    }
}
