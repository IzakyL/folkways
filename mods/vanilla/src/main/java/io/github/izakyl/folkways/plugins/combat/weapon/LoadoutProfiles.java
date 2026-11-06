package io.github.izakyl.folkways.plugins.combat.weapon;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/** Colony ledger of immutable profiles and one optional assignment per resident. */
public final class LoadoutProfiles {
    public static final ResourceLocation SAVED = ResourceLocation.fromNamespaceAndPath("folkways", "patrol_loadouts");

    private record EntryKey(UUID profile, UUID entry) {
    }

    private static final ItemSpec UNAVAILABLE =
        ItemSpec.of(ResourceLocation.fromNamespaceAndPath("folkways", "unavailable_profile_item"));
    private final Map<EntryKey, Tag> unavailableItems = new LinkedHashMap<>();
    private final Colony colony;
    private final Consumer<UUID> changed;
    private final Map<UUID, LoadoutProfile> profiles = new LinkedHashMap<>();
    private final Map<UUID, Long> revisions = new LinkedHashMap<>();
    private final Map<UUID, UUID> assignments = new LinkedHashMap<>();

    LoadoutProfiles(Colony colony, Consumer<UUID> changed) {
        this.colony = colony;
        this.changed = changed;
        CompoundTag saved = colony.kept(SAVED);
        for (Tag value : saved.getList("profiles", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) value;
            List<LoadoutProfile.Entry> entries = new ArrayList<>();
            for (Tag item : row.getList("entries", Tag.TAG_COMPOUND)) {
                CompoundTag entry = (CompoundTag) item;
                CompoundTag items = entry.getCompound("items");
                Optional<ItemSpec> decoded =
                    ItemSpec.CODEC
                    .parse(registries().createSerializationContext(NbtOps.INSTANCE), items)
                    .result();
                if (decoded.isEmpty()) {
                    unavailableItems.put(
                        new EntryKey(row.getUUID("id"), entry.getUUID("id")), items.copy());
                }
                ItemSpec spec = decoded.orElse(UNAVAILABLE);
                entries.add(
                    new LoadoutProfile.Entry(
                    entry.getUUID("id"),
                    LoadoutProfile.Kind.valueOf(entry.getString("kind")),
                    spec,
                    entry.contains("slot")
                    ? Optional.of(EquipmentSlot.byName(entry.getString("slot")))
                    : Optional.empty(),
                    entry.getInt("target"),
                    entry.getInt("threshold")));
            }
            LoadoutProfile profile = new LoadoutProfile(row.getUUID("id"), row.getString("name"), entries);
            profiles.put(profile.id(), profile);
        }
        for (Tag value : saved.getList("assignments", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) value;
            if (profiles.containsKey(row.getUUID("profile"))) {
                assignments.put(row.getUUID("resident"), row.getUUID("profile"));
            }
        }
    }

    /** Strip exact components, enchantments and held gun attachments from requested profile items. */
    public static CompoundTag withoutPins(CompoundTag saved) {
        CompoundTag items = saved.copy();
        ListTag alternatives = items.getList("anyOf", Tag.TAG_COMPOUND);
        for (int n = 0; n < alternatives.size(); n++) {
            alternatives.set(n, withoutPins(alternatives.getCompound(n)));
        }
        if (items.contains("components", Tag.TAG_COMPOUND)) {
            CompoundTag components = items.getCompound("components");
            if (components.getBoolean("exact")) {
                items.remove("components");
            } else {
                CompoundTag values = components.getCompound("values");
                values.remove("minecraft:enchantments");
                CompoundTag custom = values.getCompound("minecraft:custom_data");
                if (custom.contains("GunId")) {
                    for (String key : List.copyOf(custom.getAllKeys())) {
                        if (key.startsWith("Attachment")) {
                            custom.remove(key);
                        }
                    }
                }
                if (values.isEmpty()) {
                    items.remove("components");
                }
            }
        }
        return items;
    }

    static HolderLookup.Provider registries() {
        return ServerLifecycleHooks.getCurrentServer().registryAccess();
    }

    public static LoadoutProfiles of(Colony colony) {
        return PatrolSupplies.of(colony).profiles();
    }

    public long revision(UUID id) {
        return revisions.getOrDefault(id, 0L);
    }

    public List<LoadoutProfile> all() {
        return List.copyOf(profiles.values());
    }

    public Optional<LoadoutProfile> get(UUID id) {
        return Optional.ofNullable(profiles.get(id));
    }

    public Optional<LoadoutProfile> assigned(UUID resident) {
        return Optional.ofNullable(assignments.get(resident)).flatMap(this::get);
    }

    public List<UUID> residents(UUID profile) {
        return assignments.entrySet().stream()
            .filter(entry -> entry.getValue().equals(profile))
            .map(Map.Entry::getKey)
            .toList();
    }

    private void thread() {
        if (!ServerLifecycleHooks.getCurrentServer().isSameThread()) {
            throw new IllegalStateException("profiles require the server thread");
        }
    }

    public LoadoutProfile create(String name) {
        thread();
        LoadoutProfile profile = new LoadoutProfile(UUID.randomUUID(), name, List.of());
        profiles.put(profile.id(), profile);
        save();
        return profile;
    }

    public LoadoutProfile duplicate(UUID id) {
        LoadoutProfile source = get(id).orElseThrow();
        LoadoutProfile copy = create(source.name());
        for (LoadoutProfile.Entry entry : source.entries()) {
            Tag raw = unavailableItems.get(new EntryKey(source.id(), entry.id()));
            if (raw != null) {
                unavailableItems.put(new EntryKey(copy.id(), entry.id()), raw.copy());
            }
        }
        replace(new LoadoutProfile(copy.id(), copy.name(), source.entries()));
        return get(copy.id()).orElseThrow();
    }

    public void replace(LoadoutProfile profile) {
        thread();
        if (!profiles.containsKey(profile.id())) {
            throw new IllegalArgumentException("unknown profile");
        }
        if (profile.equals(profiles.get(profile.id()))) {
            return;
        }
        residents(profile.id()).forEach(changed);
        profiles.put(profile.id(), profile);
        unavailableItems
            .keySet()
            .removeIf(
            key ->
                key.profile().equals(profile.id())
                    && profile.entries().stream()
                    .noneMatch(
                    entry ->
                        entry.id().equals(key.entry())
                            && entry.items().equals(UNAVAILABLE)));
        revisions.merge(profile.id(), 1L, Long::sum);
        save();
    }

    public void delete(UUID id) {
        thread();
        if (!profiles.containsKey(id)) {
            throw new IllegalArgumentException("unknown profile");
        }
        residents(id).forEach(changed);
        assignments.values().removeIf(id::equals);
        profiles.remove(id);
        unavailableItems.keySet().removeIf(key -> key.profile().equals(id));
        save();
    }

    public void assign(Collection<UUID> residents, Optional<UUID> profile) {
        thread();
        if (profile.isPresent() && !profiles.containsKey(profile.get())) {
            throw new IllegalArgumentException("unknown profile");
        }
        for (UUID id : residents) {
            if (colony.holdingOf(id).isEmpty()
                || colony.residents().stream().noneMatch(resident -> resident.id().equals(id))) {
                throw new IllegalArgumentException("resident outside colony");
            }
        }
        for (UUID id : residents) {
            if (Optional.ofNullable(assignments.get(id)).equals(profile)) {
                continue;
            }
            changed.accept(id);
            if (profile.isPresent()) {
                assignments.put(id, profile.get());
            } else {
                assignments.remove(id);
            }
        }
        save();
    }

    public LoadoutProfile add(UUID id, LoadoutProfile.Entry entry) {
        LoadoutProfile profile = get(id).orElseThrow();
        List<LoadoutProfile.Entry> entries = new ArrayList<>(profile.entries());
        entries.add(entry);
        if (entry.kind() == LoadoutProfile.Kind.WEAPON) {
            for (ItemStack stack : LoadoutProfile.samples(entry.items())) {
                Optional<LoadoutProvider> provider = LoadoutRegistry.using(stack);
                if (provider.isEmpty()) {
                    continue;
                }
                provider.get()
                    .ammunition(stack)
                    .ifPresent(
                    ammo -> {
                        if (entries.stream()
                            .noneMatch(
                            held ->
                                held.kind() == LoadoutProfile.Kind.SUPPLY
                                    && held.items().equals(ammo))) {
                            int target = provider.get().suggestedAmount(stack);
                            entries.add(LoadoutProfile.Entry.supply(ammo, target, target));
                        }
                    });
            }
        }
        replace(new LoadoutProfile(id, profile.name(), entries));
        return get(id).orElseThrow();
    }

    void retain(Collection<UUID> residents) {
        if (assignments.keySet().removeIf(id -> !residents.contains(id))) {
            save();
        }
    }

    void save() {
        CompoundTag saved = new CompoundTag();
        saved.putInt("version", 4);
        ListTag rows = new ListTag();
        for (LoadoutProfile profile : profiles.values()) {
            CompoundTag row = new CompoundTag();
            row.putUUID("id", profile.id());
            row.putString("name", profile.name());
            ListTag entries = new ListTag();
            for (LoadoutProfile.Entry entry : profile.entries()) {
                CompoundTag item = new CompoundTag();
                item.putUUID("id", entry.id());
                item.putString("kind", entry.kind().name());
                Tag raw = unavailableItems.get(new EntryKey(profile.id(), entry.id()));
                item.put("items", raw == null ? entry.items().save(registries()) : raw.copy());
                entry.slot().ifPresent(slot -> item.putString("slot", slot.getName()));
                item.putInt("target", entry.target());
                item.putInt("threshold", entry.threshold());
                entries.add(item);
            }
            row.put("entries", entries);
            rows.add(row);
        }
        saved.put("profiles", rows);
        ListTag assigned = new ListTag();
        assignments.forEach(
            (resident, profile) -> {
                CompoundTag row = new CompoundTag();
                row.putUUID("resident", resident);
                row.putUUID("profile", profile);
                assigned.add(row);
            });
        saved.put("assignments", assigned);
        colony.keep(SAVED, saved);
    }
}
