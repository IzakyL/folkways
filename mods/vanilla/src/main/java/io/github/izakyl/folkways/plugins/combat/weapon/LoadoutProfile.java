package io.github.izakyl.folkways.plugins.combat.weapon;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

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
                || target > 4096
                || threshold < 0
                || threshold > target
                || kind != Kind.SUPPLY && (target != 1 || threshold != 1)
                || (kind == Kind.ARMOR || kind == Kind.HORSE_ARMOR) != slot.isPresent()
                || kind == Kind.ARMOR && slot.filter(value ->
                    value.getType() != EquipmentSlot.Type.HUMANOID_ARMOR).isPresent()
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
                    case WEAPON -> LoadoutRegistry.using(stack).isPresent();
                    case ARMOR -> equipmentSlot(stack) == slot.orElseThrow();
                    case MOUNT -> stack.is(Items.SADDLE);
                    case HORSE_ARMOR -> stack.is(Items.LEATHER_HORSE_ARMOR) || stack.is(Items.IRON_HORSE_ARMOR)
                        || stack.is(Items.GOLDEN_HORSE_ARMOR) || stack.is(Items.DIAMOND_HORSE_ARMOR);
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
    }

    public LoadoutProfile {
        Objects.requireNonNull(id);
        Objects.requireNonNull(name);
        name = name.strip();
        entries = List.copyOf(entries);
        if (name.isEmpty()
            || name.length() > 80
            || entries.size() > 128
            || new HashSet<>(entries.stream().map(Entry::id).toList()).size() != entries.size()
            || entries.stream().flatMap(entry -> entry.slot().stream()).distinct().count()
            != entries.stream().filter(entry -> entry.kind() == Kind.ARMOR || entry.kind() == Kind.HORSE_ARMOR).count()
            || entries.stream().filter(entry -> entry.kind() == Kind.MOUNT).count() > 1) {
            throw new IllegalArgumentException("invalid profile");
        }
    }

    public boolean accepts(ItemStack stack) {
        return entries.stream()
            .filter(entry -> entry.kind() == Kind.WEAPON)
            .anyMatch(entry -> Goods.matches(stack, entry.items()));
    }

    public static EquipmentSlot equipmentSlot(ItemStack stack) {
        EquipmentSlot custom = stack.getEquipmentSlot();
        if (custom != null) {
            return custom;
        }
        Equipable equipable = Equipable.get(stack);
        return equipable == null ? EquipmentSlot.MAINHAND : equipable.getEquipmentSlot();
    }

    static List<ItemStack> samples(ItemSpec spec) {
        if (!spec.anyOf().isEmpty()) {
            return spec.anyOf().stream().flatMap(one -> samples(one).stream()).toList();
        }
        return Goods.members(spec).stream()
            .map(
            item ->
                Goods.display(
                    new ItemSpec(
                    ItemSpec.of(item).item(),
                    Optional.empty(),
                    Set.of(),
                    spec.components())))
            .toList();
    }

    public boolean hasRanged() {
        return entries.stream()
            .filter(entry -> entry.kind() == Kind.WEAPON)
            .flatMap(entry -> samples(entry.items()).stream())
            .anyMatch(stack -> LoadoutRegistry.using(stack).filter(provider -> provider.ranged(stack)).isPresent());
    }
}
