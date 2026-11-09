package io.github.izakyl.folkways.core.vocation.api;

import java.util.List;
import net.minecraft.resources.ResourceLocation;

/**
 * A broad kind of work that gathers vocations under one heading in the colony book, where it folds into one
 * column that sets all of them at once. Groups only arrange and bulk-edit; licences, keenness and perks stay
 * with each vocation.
 */
public record VocationGroup(ResourceLocation id, int order) {

    public static final VocationGroup GATHERING = core("gathering", 100);
    public static final VocationGroup PROCESSING = core("processing", 200);
    public static final VocationGroup CONSTRUCTION = core("construction", 300);
    public static final VocationGroup TRANSPORT = core("transport", 400);
    public static final VocationGroup DEFENCE = core("defence", 500);

    /** Vocations that name no group come last, under no heading. */
    public static final VocationGroup OTHER = core("other", Integer.MAX_VALUE);

    public static final List<VocationGroup> BUILT_IN =
        List.of(GATHERING, PROCESSING, CONSTRUCTION, TRANSPORT, DEFENCE);

    public String translationKey() {
        return "folkways.vocation_group." + id.getNamespace() + "." + id.getPath();
    }

    private static VocationGroup core(String path, int order) {
        return new VocationGroup(ResourceLocation.fromNamespaceAndPath("folkways_core", path), order);
    }
}
