package io.github.izakyl.folkways.plugins.dispatch;

import java.util.Objects;

public final class PackageNetworks {

    private static volatile PackageNetwork current = PackageNetwork.absent();

    private PackageNetworks() {
    }

    public static PackageNetwork get() {
        return current;
    }

    public static void install(PackageNetwork network) {
        current = Objects.requireNonNull(network, "network");
    }
}
