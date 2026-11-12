package io.github.izakyl.folkways.core.equipment.api;

import java.util.Objects;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Slots shown together on the equipment page. Body armour stands left of the figure and riding settings right of
 * it; every {@link Placement#ACCESSORY} group shares one column beside them with a chooser between groups, so an
 * accessory mod can add as many groups (necklaces, rings, belts) as its data declares.
 */
public record GearGroup(ResourceLocation id, Component name, Placement placement, int order) {
    public enum Placement {
        LEFT,
        RIGHT,
        ACCESSORY
    }

    public GearGroup {
        Objects.requireNonNull(id);
        Objects.requireNonNull(name);
        Objects.requireNonNull(placement);
    }
}
