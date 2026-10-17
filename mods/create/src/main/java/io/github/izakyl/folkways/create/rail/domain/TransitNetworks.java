package io.github.izakyl.folkways.create.rail.domain;

import java.util.Objects;

public final class TransitNetworks {

    private static volatile TransitNetwork current = TransitNetwork.absent();

    private TransitNetworks() {
    }

    public static TransitNetwork get() {
        return current;
    }

    public static void install(TransitNetwork network) {
        current = Objects.requireNonNull(network, "network");
    }
}
