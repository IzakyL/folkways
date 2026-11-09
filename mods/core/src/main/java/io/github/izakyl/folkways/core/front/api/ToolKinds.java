package io.github.izakyl.folkways.core.front.api;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;

/** Delegations configured through their own book tool rather than the ordinary drawing picker. */
public final class ToolKinds {
    private static final Set<ResourceLocation> KINDS = ConcurrentHashMap.newKeySet();

    private ToolKinds() {
    }

    public static void register(ResourceLocation delegation) {
        KINDS.add(delegation);
    }

    public static boolean contains(ResourceLocation delegation) {
        return KINDS.contains(delegation);
    }
}
