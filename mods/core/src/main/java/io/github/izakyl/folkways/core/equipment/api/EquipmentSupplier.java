package io.github.izakyl.folkways.core.equipment.api;

import io.github.izakyl.folkways.core.colony.api.Colony;
import net.minecraft.server.MinecraftServer;

/**
 * A module that brings residents the profile entries it understands: Core fills named slots and personal
 * inventory at any time; a patrol supplies its mount. Each entry belongs to the first supplier claiming it.
 */
public interface EquipmentSupplier {
    boolean supplies(EquipmentProfile.Entry entry);

    /** The state of this supplier's entries for the profile's assigned residents; server thread only. */
    EquipmentPreview preview(Colony colony, EquipmentProfile profile, MinecraftServer server);
}
