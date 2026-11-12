package io.github.izakyl.folkways.core.equipment.api;

import io.github.izakyl.folkways.core.combat.api.Loadouts;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Named entries are worn; unnamed roots are personal inventory. Only worn parents own child entries. */
public record EquipmentProfile(UUID id, String name, List<Entry> entries, boolean riding) {

    public EquipmentProfile(UUID id, String name, List<Entry> entries) {
        this(id, name, entries, false);
    }

    public static final int MAX_ENTRIES = 192;
    public static final int MAX_TARGET = 4096;

    public record Entry(UUID id, Optional<ResourceLocation> slot, ItemSpec items, int target, int threshold,
                        Optional<UUID> parent) {
        public Entry {
            Objects.requireNonNull(id);
            Objects.requireNonNull(slot);
            Objects.requireNonNull(parent);
            if (slot.isPresent() && parent.isPresent()) {
                throw new IllegalArgumentException("slotted entry cannot have a parent");
            }
            Objects.requireNonNull(items);
            if (slot.isPresent() ? target != 1 || threshold != 1
                : target < 1 || target > MAX_TARGET || threshold < 0 || threshold > target) {
                throw new IllegalArgumentException("invalid equipment entry");
            }
        }

        public Entry(UUID id, Optional<ResourceLocation> slot, ItemSpec items, int target, int threshold) {
            this(id, slot, items, target, threshold, Optional.empty());
        }

        public Entry under(UUID parent) {
            return new Entry(id, slot, items, target, threshold, Optional.of(parent));
        }

        public static Entry slotted(ResourceLocation slot, ItemSpec items) {
            return new Entry(UUID.randomUUID(), Optional.of(slot), items, 1, 1);
        }

        public static Entry carried(ItemSpec items, int target, int threshold) {
            return new Entry(UUID.randomUUID(), Optional.empty(), items, target, threshold);
        }

        public boolean slotted() {
            return slot.isPresent();
        }

        public Optional<GearSlot> gear() {
            return slot.flatMap(GearSlots::slot);
        }

        public boolean matches(ItemStack stack) {
            return Goods.matches(stack, items)
                && (slot.isEmpty() || gear().filter(gear -> gear.fits(stack)).isPresent())
                && (!weapon() || Loadouts.using(stack).isPresent());
        }

        /** Single carried weapons use the same physical acceptance for supply, retention and combat. */
        public boolean weapon() {
            if (slot.isPresent() || parent.isPresent() || target != 1 || threshold != 1) {
                return false;
            }
            List<ItemStack> samples = samples(items);
            return !samples.isEmpty() && samples.stream().allMatch(stack -> Loadouts.using(stack).isPresent());
        }

        /** Whether some actual item satisfies the entry; a slot no installed module declares never does. */
        public boolean available() {
            if (slot.isPresent() && gear().isEmpty()) {
                return false;
            }
            return samples(items).stream().anyMatch(this::matches);
        }

        public Entry withItems(ItemSpec replaced) {
            return new Entry(id, slot, replaced, target, threshold, parent);
        }

        public Entry withAmounts(int newTarget, int newThreshold) {
            return new Entry(id, slot, items, newTarget, newThreshold, parent);
        }
    }

    public EquipmentProfile {
        Objects.requireNonNull(id);
        name = Objects.requireNonNull(name).strip();
        entries = List.copyOf(entries);
        if (name.isEmpty() || name.length() > 80 || entries.size() > MAX_ENTRIES
            || new HashSet<>(entries.stream().map(Entry::id).toList()).size() != entries.size()
            || entries.stream().flatMap(entry -> entry.slot().stream()).distinct().count()
            != entries.stream().filter(Entry::slotted).count()) {
            throw new IllegalArgumentException("invalid equipment profile");
        }
    }

    public Optional<Entry> in(ResourceLocation slot) {
        return entries.stream().filter(entry -> entry.slot().filter(slot::equals).isPresent()).findFirst();
    }

    public Optional<Entry> entry(UUID entry) {
        return entries.stream().filter(each -> each.id().equals(entry)).findFirst();
    }

    public List<Entry> carried() {
        return entries.stream().filter(entry -> !entry.slotted() && entry.parent().isEmpty()).toList();
    }

    public EquipmentProfile renamed(String newName) {
        return new EquipmentProfile(id, newName, entries, riding);
    }

    public EquipmentProfile with(List<Entry> newEntries) {
        Set<UUID> removed = new HashSet<>(entries.stream().map(Entry::id).toList());
        newEntries.forEach(entry -> removed.remove(entry.id()));
        boolean changed;
        do {
            changed = false;
            for (Entry entry : newEntries) {
                if (entry.parent().filter(removed::contains).isPresent() && removed.add(entry.id())) {
                    changed = true;
                }
            }
        } while (changed);
        return new EquipmentProfile(id, name, newEntries.stream().filter(entry -> !removed.contains(entry.id()))
            .toList(), riding);
    }

    public EquipmentProfile riding(boolean enabled) {
        return new EquipmentProfile(id, name, entries, enabled);
    }

    /** One display stack per member item of the goods, carrying the goods' component filter. */
    public static List<ItemStack> samples(ItemSpec spec) {
        if (!spec.anyOf().isEmpty()) {
            return spec.anyOf().stream().flatMap(one -> samples(one).stream()).toList();
        }
        return Goods.members(spec).stream()
            .map(item -> Goods.display(new ItemSpec(ItemSpec.of(item).item(), Optional.empty(), Set.of(),
                spec.components())))
            .toList();
    }
}
