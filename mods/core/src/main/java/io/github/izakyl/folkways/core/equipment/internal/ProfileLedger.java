package io.github.izakyl.folkways.core.equipment.internal;

import io.github.izakyl.folkways.core.equipment.api.EquipmentKits;
import io.github.izakyl.folkways.core.base.persist.Required;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.combat.api.LoadoutProvider;
import io.github.izakyl.folkways.core.combat.api.Loadouts;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfile;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfiles;
import io.github.izakyl.folkways.core.equipment.api.EquipmentSuppliers;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Colony ledger of immutable profiles and one optional assignment per resident. Saved goods no installed mod can
 * decode stay in the save unchanged and show as an unavailable item until they are replaced or removed.
 */
public final class ProfileLedger implements EquipmentProfiles {
    private record EntryKey(UUID profile, UUID entry) {
    }

    static final ItemSpec UNAVAILABLE =
        ItemSpec.of(ResourceLocation.fromNamespaceAndPath("folkways_core", "unavailable_profile_item"));
    private final Map<EntryKey, Tag> unavailableItems = new LinkedHashMap<>();
    private final Colony colony;
    private final CarriedKit carried;
    private final Map<UUID, EquipmentProfile> profiles = new LinkedHashMap<>();
    private final Map<UUID, Long> revisions = new LinkedHashMap<>();
    private final Map<UUID, UUID> assignments = new LinkedHashMap<>();

    ProfileLedger(Colony colony) {
        this.colony = colony;
        this.carried = new CarriedKit(colony, this);
        CompoundTag saved = colony.kept(ID);
        if (saved.isEmpty()) {
            return;
        }
        for (Tag value : Required.list(saved, "profiles", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) value;
            UUID id = Required.uuid(row, "id");
            List<EquipmentProfile.Entry> entries = new ArrayList<>();
            for (Tag item : Required.list(row, "entries", Tag.TAG_COMPOUND)) {
                CompoundTag entry = (CompoundTag) item;
                CompoundTag items = Required.compound(entry, "items");
                Optional<ItemSpec> decoded = ItemSpec.CODEC
                    .parse(registries().createSerializationContext(NbtOps.INSTANCE), items).result();
                if (decoded.isEmpty()) {
                    unavailableItems.put(new EntryKey(id, Required.uuid(entry, "id")), items.copy());
                }
                entries.add(new EquipmentProfile.Entry(Required.uuid(entry, "id"),
                    entry.contains("slot") ? Optional.of(ResourceLocation.parse(Required.string(entry, "slot")))
                        : Optional.empty(),
                    decoded.orElse(UNAVAILABLE), Required.integer(entry, "target"),
                    Required.integer(entry, "threshold"), entry.contains("parent")
                        ? Optional.of(Required.uuid(entry, "parent")) : Optional.empty()));
            }
            EquipmentProfile profile = new EquipmentProfile(id, Required.string(row, "name"), entries,
                row.getBoolean("riding"));
            if (profiles.putIfAbsent(profile.id(), profile) != null) {
                throw new IllegalArgumentException("duplicate equipment profile " + profile.id());
            }
        }
        for (Tag value : Required.list(saved, "assignments", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) value;
            UUID profile = Required.uuid(row, "profile");
            if (!profiles.containsKey(profile)
                || assignments.putIfAbsent(Required.uuid(row, "resident"), profile) != null) {
                throw new IllegalArgumentException("invalid equipment assignment for profile " + profile);
            }
        }
    }

    static HolderLookup.Provider registries() {
        return ServerLifecycleHooks.getCurrentServer().registryAccess();
    }

    CarriedKit carried() {
        return carried;
    }

    @Override
    public boolean supplying(UUID resident, Collection<UUID> route) {
        return carried.supplying(resident, route);
    }

    @Override
    public long revision(UUID id) {
        return revisions.getOrDefault(id, 0L);
    }

    @Override
    public List<EquipmentProfile> all() {
        return List.copyOf(profiles.values());
    }

    @Override
    public Optional<EquipmentProfile> get(UUID id) {
        return Optional.ofNullable(id).map(profiles::get);
    }

    @Override
    public Optional<EquipmentProfile> assigned(UUID resident) {
        return Optional.ofNullable(assignments.get(resident)).flatMap(this::get);
    }

    @Override
    public List<UUID> residents(UUID profile) {
        return assignments.entrySet().stream().filter(entry -> entry.getValue().equals(profile))
            .map(Map.Entry::getKey).toList();
    }

    private void thread() {
        if (!ServerLifecycleHooks.getCurrentServer().isSameThread()) {
            throw new IllegalStateException("equipment profiles require the server thread");
        }
    }

    @Override
    public EquipmentProfile create(String name) {
        thread();
        EquipmentProfile profile = new EquipmentProfile(UUID.randomUUID(), name, List.of());
        profiles.put(profile.id(), profile);
        save();
        return profile;
    }

    @Override
    public EquipmentProfile duplicate(UUID id) {
        EquipmentProfile source = get(id).orElseThrow();
        EquipmentProfile copy = create(source.name());
        for (EquipmentProfile.Entry entry : source.entries()) {
            Tag raw = unavailableItems.get(new EntryKey(source.id(), entry.id()));
            if (raw != null) {
                unavailableItems.put(new EntryKey(copy.id(), entry.id()), raw.copy());
            }
        }
        replace(new EquipmentProfile(copy.id(), copy.name(), source.entries(), source.riding()));
        return get(copy.id()).orElseThrow();
    }

    @Override
    public void replace(EquipmentProfile replacement) {
        thread();
        EquipmentProfile previous = get(replacement.id())
            .orElseThrow(() -> new IllegalArgumentException("unknown profile"));
        EquipmentProfile profile = previous.with(replacement.entries()).renamed(replacement.name())
            .riding(replacement.riding());
        if (previous.equals(profile)) {
            return;
        }
        EquipmentKits.validate(profile, previous);
        residents(profile.id()).forEach(resident -> validateLoaded(resident, profile));
        profiles.put(profile.id(), profile);
        unavailableItems.keySet().removeIf(key -> key.profile().equals(profile.id())
            && profile.entries().stream().noneMatch(entry -> entry.id().equals(key.entry())
                && entry.items().equals(UNAVAILABLE)));
        revisions.merge(profile.id(), 1L, Long::sum);
        save();
        residents(profile.id()).forEach(this::changed);
    }

    @Override
    public void delete(UUID id) {
        thread();
        if (!profiles.containsKey(id)) {
            throw new IllegalArgumentException("unknown profile");
        }
        List<UUID> removed = residents(id);
        assignments.values().removeIf(id::equals);
        profiles.remove(id);
        unavailableItems.keySet().removeIf(key -> key.profile().equals(id));
        save();
        removed.forEach(this::changed);
    }

    @Override
    public void assign(Collection<UUID> residents, Optional<UUID> profile) {
        thread();
        if (profile.isPresent() && !profiles.containsKey(profile.get())) {
            throw new IllegalArgumentException("unknown profile");
        }
        for (UUID id : residents) {
            profile.flatMap(this::get).ifPresent(chosen -> validateLoaded(id, chosen));
            if (colony.claimOf(id).isEmpty()
                || colony.residents().stream().noneMatch(resident -> resident.id().equals(id))) {
                throw new IllegalArgumentException("resident outside colony");
            }
        }
        List<UUID> moved = new ArrayList<>();
        for (UUID id : residents) {
            if (Optional.ofNullable(assignments.get(id)).equals(profile)) {
                continue;
            }
            if (profile.isPresent()) {
                assignments.put(id, profile.get());
            } else {
                assignments.remove(id);
            }
            moved.add(id);
        }
        save();
        moved.forEach(this::changed);
    }

    @Override
    public EquipmentProfile add(UUID id, EquipmentProfile.Entry entry) {
        EquipmentProfile profile = get(id).orElseThrow(() -> new IllegalArgumentException("unknown profile"));
        List<EquipmentProfile.Entry> entries = new ArrayList<>(profile.entries());
        entries.add(entry);
        boolean weapon = entry.slot().isEmpty() && entry.parent().isEmpty();
        if (weapon) {
            for (ItemStack stack : EquipmentProfile.samples(entry.items())) {
                Optional<LoadoutProvider> provider = Loadouts.using(stack);
                if (provider.isEmpty()) {
                    continue;
                }
                provider.get().ammunition(stack).ifPresent(ammo -> {
                    if (entries.stream().noneMatch(held -> !held.slotted() && held.items().equals(ammo))) {
                        int target = provider.get().suggestedAmount(stack);
                        entries.add(EquipmentProfile.Entry.carried(ammo, target, target));
                    }
                });
            }
        }
        replace(profile.with(entries));
        return get(id).orElseThrow();
    }

    @Override
    public void retain(Collection<UUID> residents) {
        if (assignments.keySet().removeIf(id -> !residents.contains(id))) {
            save();
        }
    }

    private void validateLoaded(UUID resident, EquipmentProfile profile) {
        for (var level : ServerLifecycleHooks.getCurrentServer().getAllLevels()) {
            var body = Optional.ofNullable(level.getEntity(resident))
                .flatMap(io.github.izakyl.folkways.core.resident.api.Bodies::of);
            if (body.isEmpty()) {
                continue;
            }
            for (var entry : profile.entries()) {
                if (entry.gear().filter(gear -> gear.personal()).isPresent()
                    && EquipmentProfile.samples(entry.items()).stream().noneMatch(stack ->
                        entry.gear().orElseThrow().fits(body.get(), stack))) {
                    throw new IllegalArgumentException("resident cannot equip " + entry.slot().orElseThrow());
                }
            }
        }
    }

    private void changed(UUID resident) {
        carried.invalidate(resident);
        EquipmentSuppliers.changed(colony, resident);
    }

    private void save() {
        CompoundTag saved = new CompoundTag();
        ListTag rows = new ListTag();
        for (EquipmentProfile profile : profiles.values()) {
            CompoundTag row = new CompoundTag();
            row.putUUID("id", profile.id());
            row.putString("name", profile.name());
            if (profile.riding()) {
                row.putBoolean("riding", true);
            }
            ListTag entries = new ListTag();
            for (EquipmentProfile.Entry entry : profile.entries()) {
                CompoundTag item = new CompoundTag();
                item.putUUID("id", entry.id());
                entry.parent().ifPresent(parent -> item.putUUID("parent", parent));
                entry.slot().ifPresent(slot -> item.putString("slot", slot.toString()));
                Tag raw = unavailableItems.get(new EntryKey(profile.id(), entry.id()));
                item.put("items", raw == null ? entry.items().save(registries()) : raw.copy());
                item.putInt("target", entry.target());
                item.putInt("threshold", entry.threshold());
                entries.add(item);
            }
            row.put("entries", entries);
            rows.add(row);
        }
        saved.put("profiles", rows);
        ListTag assigned = new ListTag();
        assignments.forEach((resident, profile) -> {
            CompoundTag row = new CompoundTag();
            row.putUUID("resident", resident);
            row.putUUID("profile", profile);
            assigned.add(row);
        });
        saved.put("assignments", assigned);
        colony.keep(ID, saved);
    }
}
