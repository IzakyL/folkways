package io.github.izakyl.folkways.vanilla.combat.weapon;

import io.github.izakyl.folkways.core.combat.api.Loadouts;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfile;
import io.github.izakyl.folkways.core.equipment.api.GearSlots;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A patrol's reading of an equipment profile: the entries it supplies, each with the part it plays in combat. The
 * inventory weapons are weapons and body slots are worn, the mount slots go to the
 * rider's horse and other carried goods are supplies. Nested items and slots of other modules are left out.
 */
public record LoadoutProfile(UUID id, String name, List<Entry> entries) {
    public enum Kind {
        WEAPON,
        ARMOR,
        MOUNT,
        HORSE_ARMOR,
        SUPPLY
    }

    public record Entry(UUID id, Kind kind, ItemSpec items, Optional<EquipmentSlot> slot, int target, int threshold) {
        public Entry {
            Objects.requireNonNull(id);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(items);
            Objects.requireNonNull(slot);
            if (target < 1
                || target > EquipmentProfile.MAX_TARGET
                || threshold < 0
                || threshold > target
                || kind != Kind.SUPPLY && (target != 1 || threshold != 1)
                || (kind == Kind.ARMOR || kind == Kind.HORSE_ARMOR) != slot.isPresent()
                || kind == Kind.ARMOR
                    && slot.filter(value -> value.getType() != EquipmentSlot.Type.HUMANOID_ARMOR).isPresent()
                || kind == Kind.HORSE_ARMOR && slot.orElseThrow() != EquipmentSlot.BODY
                || kind == Kind.MOUNT && !items.equals(ItemSpec.of(Items.SADDLE))) {
                throw new IllegalArgumentException("invalid profile entry");
            }
        }

        public boolean available() {
            return samples(items).stream().anyMatch(this::matches);
        }

        public boolean matches(ItemStack stack) {
            return Goods.matches(stack, items)
                && switch (kind) {
                    case WEAPON -> Loadouts.using(stack).isPresent();
                    case ARMOR -> equipmentSlot(stack) == slot.orElseThrow();
                    case MOUNT -> stack.is(Items.SADDLE);
                    case HORSE_ARMOR -> MountGear.horseArmor(stack);
                    case SUPPLY -> true;
                };
        }

        public static Entry weapon(ItemSpec items) {
            return new Entry(UUID.randomUUID(), Kind.WEAPON, items, Optional.empty(), 1, 1);
        }

        public static Entry armor(EquipmentSlot slot, ItemSpec items) {
            return new Entry(UUID.randomUUID(), Kind.ARMOR, items, Optional.of(slot), 1, 1);
        }

        public static Entry mount() {
            return new Entry(UUID.randomUUID(), Kind.MOUNT, ItemSpec.of(Items.SADDLE), Optional.empty(), 1, 1);
        }

        public static Entry horseArmor(ItemSpec items) {
            return new Entry(UUID.randomUUID(), Kind.HORSE_ARMOR, items, Optional.of(EquipmentSlot.BODY), 1, 1);
        }

        public static Entry supply(ItemSpec items, int target, int threshold) {
            return new Entry(UUID.randomUUID(), Kind.SUPPLY, items, Optional.empty(), target, threshold);
        }

        /** The profile entry this stands for; weapons are personal inventory. */
        EquipmentProfile.Entry equipment() {
            Optional<ResourceLocation> place = switch (kind) {
                case WEAPON -> Optional.empty();
                case ARMOR -> GearSlots.of(slot.orElseThrow());
                case MOUNT -> Optional.empty();
                case HORSE_ARMOR -> Optional.of(MountGear.HORSE_ARMOR);
                case SUPPLY -> Optional.empty();
            };
            return new EquipmentProfile.Entry(id, place, items, target, threshold);
        }

        static Optional<Entry> of(EquipmentProfile.Entry entry) {
            if (entry.parent().isPresent()) {
                return Optional.empty();
            }
            if (entry.slot().isEmpty()) {
                return Optional.of(new Entry(entry.id(), entry.weapon() ? Kind.WEAPON : Kind.SUPPLY, entry.items(),
                    Optional.empty(), entry.target(), entry.threshold()));
            }
            ResourceLocation at = entry.slot().get();
            if (at.equals(MountGear.HORSE_ARMOR)) {
                return Optional.of(new Entry(entry.id(), Kind.HORSE_ARMOR, entry.items(),
                    Optional.of(EquipmentSlot.BODY), 1, 1));
            }
            return GearSlots.equipment(at)
                .map(slot -> new Entry(entry.id(), Kind.ARMOR, entry.items(), Optional.of(slot), 1, 1));
        }
    }

    public LoadoutProfile {
        Objects.requireNonNull(id);
        Objects.requireNonNull(name);
        name = name.strip();
        entries = List.copyOf(entries);
        if (name.isEmpty()
            || name.length() > 80
            || entries.size() > EquipmentProfile.MAX_ENTRIES
            || new HashSet<>(entries.stream().map(Entry::id).toList()).size() != entries.size()
            || entries.stream().flatMap(entry -> entry.slot().stream()).distinct().count()
            != entries.stream().filter(entry -> entry.kind() == Kind.ARMOR || entry.kind() == Kind.HORSE_ARMOR).count()
            || entries.stream().filter(entry -> entry.kind() == Kind.MOUNT).count() > 1) {
            throw new IllegalArgumentException("invalid profile");
        }
    }

    private record Read(EquipmentProfile source, LoadoutProfile view) {
    }

    // Weapons ask for their profile every tick; a ledger profile is immutable, so its reading is kept until replaced.
    private static final Map<UUID, Read> READ = new ConcurrentHashMap<>();

    /** The patrol's part of an equipment profile, in profile order. */
    public static LoadoutProfile of(EquipmentProfile profile) {
        Read kept = READ.get(profile.id());
        if (kept != null && kept.source() == profile) {
            return kept.view();
        }
        List<Entry> entries = new ArrayList<>(profile.entries().stream().flatMap(entry -> Entry.of(entry).stream())
            .filter(entry -> profile.riding() || entry.kind() != Kind.HORSE_ARMOR).toList());
        if (profile.riding()) {
            UUID saddle = UUID.nameUUIDFromBytes((profile.id() + ":riding")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            entries.add(new Entry(saddle, Kind.MOUNT, ItemSpec.of(Items.SADDLE), Optional.empty(), 1, 1));
        }
        LoadoutProfile view = new LoadoutProfile(profile.id(), profile.name(), entries);
        if (READ.size() > 4096) {
            READ.clear();
        }
        READ.put(profile.id(), new Read(profile, view));
        return view;
    }

    /**
     * Writes this reading back, retaining nested items and accessories. Weapons remain inventory entries;
     * the internal saddle demand becomes the profile's riding flag.
     */
    EquipmentProfile into(EquipmentProfile profile) {
        List<EquipmentProfile.Entry> merged = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.kind() != Kind.MOUNT) {
                merged.add(entry.equipment());
            }
        }
        profile.entries().stream().filter(entry -> Entry.of(entry).isEmpty()).forEach(merged::add);
        return new EquipmentProfile(profile.id(), name, merged,
            entries.stream().anyMatch(entry -> entry.kind() == Kind.MOUNT));
    }

    public boolean accepts(ItemStack stack) {
        return entries.stream()
            .filter(entry -> entry.kind() == Kind.WEAPON)
            .anyMatch(entry -> Goods.matches(stack, entry.items()));
    }

    public static EquipmentSlot equipmentSlot(ItemStack stack) {
        return GearSlots.wornAt(stack);
    }

    static List<ItemStack> samples(ItemSpec spec) {
        return EquipmentProfile.samples(spec);
    }

    public boolean hasRanged() {
        return entries.stream()
            .filter(entry -> entry.kind() == Kind.WEAPON)
            .flatMap(entry -> samples(entry.items()).stream())
            .anyMatch(stack -> Loadouts.using(stack).filter(provider -> provider.ranged(stack)).isPresent());
    }
}
