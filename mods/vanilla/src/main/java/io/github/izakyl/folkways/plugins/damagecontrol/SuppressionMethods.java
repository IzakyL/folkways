package io.github.izakyl.folkways.plugins.damagecontrol;

import java.util.ArrayList;
import java.util.List;

public final class SuppressionMethods {
    private static volatile List<SuppressionMethod> methods = List.of(new VanillaSmother());

    private SuppressionMethods() {
    }

    public static synchronized void register(SuppressionMethod method) {
        List<SuppressionMethod> next = new ArrayList<>(methods);
        next.addFirst(method);
        methods = List.copyOf(next);
    }

    public static List<SuppressionMethod> all() {
        return methods;
    }
}
