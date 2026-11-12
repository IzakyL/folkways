package io.github.izakyl.folkways.vanilla.combat.weapon;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfile;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfiles;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The colony's equipment profiles as a patrol reads them. Profiles and assignments live in Core's equipment ledger;
 * this only translates entries to and from their combat kinds, so an edit here keeps the entries a patrol does not
 * supply, such as nested items and accessories.
 */
public final class LoadoutProfiles {
    private final EquipmentProfiles ledger;

    private LoadoutProfiles(EquipmentProfiles ledger) {
        this.ledger = ledger;
    }

    public static LoadoutProfiles of(Colony colony) {
        return new LoadoutProfiles(EquipmentProfiles.of(colony));
    }

    public EquipmentProfiles ledger() {
        return ledger;
    }

    public long revision(UUID id) {
        return ledger.revision(id);
    }

    public List<LoadoutProfile> all() {
        return ledger.all().stream().map(LoadoutProfile::of).toList();
    }

    public Optional<LoadoutProfile> get(UUID id) {
        return ledger.get(id).map(LoadoutProfile::of);
    }

    public Optional<LoadoutProfile> assigned(UUID resident) {
        return ledger.assigned(resident).map(LoadoutProfile::of);
    }

    public List<UUID> residents(UUID profile) {
        return ledger.residents(profile);
    }

    public LoadoutProfile create(String name) {
        return LoadoutProfile.of(ledger.create(name));
    }

    public LoadoutProfile duplicate(UUID id) {
        return LoadoutProfile.of(ledger.duplicate(id));
    }

    public void replace(LoadoutProfile profile) {
        ledger.replace(profile.into(ledger.get(profile.id())
            .orElseThrow(() -> new IllegalArgumentException("unknown profile"))));
    }

    public void delete(UUID id) {
        ledger.delete(id);
    }

    public void assign(Collection<UUID> residents, Optional<UUID> profile) {
        ledger.assign(residents, profile);
    }

    /** Appends an entry; weapons are inventory entries and bring their ammunition. */
    public LoadoutProfile add(UUID id, LoadoutProfile.Entry entry) {
        EquipmentProfile profile = ledger.get(id).orElseThrow(() -> new IllegalArgumentException("unknown profile"));
        if (entry.kind() == LoadoutProfile.Kind.MOUNT) {
            ledger.replace(profile.riding(true));
            return LoadoutProfile.of(ledger.get(id).orElseThrow());
        }
        return LoadoutProfile.of(ledger.add(id, entry.equipment()));
    }
}
