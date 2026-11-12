package io.github.izakyl.folkways.core.equipment.api;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/**
 * A colony's equipment profiles and the one profile, if any, each resident follows. Changes are made on the server
 * thread; every resident whose profile changed is announced through {@link EquipmentSuppliers#listen}.
 */
public interface EquipmentProfiles {
    ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways_core", "equipment");

    static EquipmentProfiles of(Colony colony) {
        return colony.service(ID, EquipmentProfiles.class).orElseThrow();
    }

    List<EquipmentProfile> all();

    Optional<EquipmentProfile> get(UUID id);

    Optional<EquipmentProfile> assigned(UUID resident);

    List<UUID> residents(UUID profile);

    /** Whether a route includes one of this resident's pending personal deliveries. */
    default boolean supplying(UUID resident, Collection<UUID> route) {
        return false;
    }

    /** Increases with every accepted change, so an editor can refuse to overwrite a newer one. */
    long revision(UUID profile);

    EquipmentProfile create(String name);

    EquipmentProfile duplicate(UUID id);

    void replace(EquipmentProfile profile);

    void delete(UUID id);

    /** Validates every resident before changing any; an empty profile clears the assignment. */
    void assign(Collection<UUID> residents, Optional<UUID> profile);

    /** Forgets the assignments of residents no longer in the roster; Core also does this on its own sweep. */
    void retain(Collection<UUID> roster);

    /** Appends an entry. A weapon also adds its ammunition as a carried entry, unless the profile carries it. */
    EquipmentProfile add(UUID profile, EquipmentProfile.Entry entry);

    /** Fills or replaces a slot, keeping the entry's identity when it was filled. */
    default EquipmentProfile put(UUID profile, ResourceLocation slot, ItemSpec items) {
        EquipmentProfile current = get(profile).orElseThrow();
        Optional<EquipmentProfile.Entry> held = current.in(slot);
        if (held.isEmpty()) {
            return add(profile, EquipmentProfile.Entry.slotted(slot, items));
        }
        replace(current.with(current.entries().stream()
            .map(entry -> entry == held.get() ? entry.withItems(items) : entry).toList()));
        return get(profile).orElseThrow();
    }
}
