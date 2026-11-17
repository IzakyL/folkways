package io.github.izakyl.folkways.core.combat.api;

import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** A named, type-specific crew, stock and weapon policy. */
public record VehicleProfile(UUID id, String name, ResourceLocation type, Set<Integer> seats,
                             Optional<UUID> equipment, List<Stock> stock, long energy,
                             Map<String, WeaponRules.Rule> rules) {
    public record Stock(ItemSpec items, int target, int threshold) {
        public Stock {
            Objects.requireNonNull(items);
            if (target < 1 || target > 65536 || threshold < 0 || threshold > target) {
                throw new IllegalArgumentException("invalid vehicle stock");
            }
        }
    }

    public VehicleProfile {
        Objects.requireNonNull(id);
        Objects.requireNonNull(type);
        Objects.requireNonNull(equipment);
        name = name.strip();
        seats = Set.copyOf(seats);
        stock = List.copyOf(stock);
        rules = Map.copyOf(rules);
        if (name.isEmpty() || name.length() > 80 || seats.size() > 128
            || seats.stream().anyMatch(seat -> seat < 0 || seat >= 128)
            || stock.size() > 192 || rules.size() > 256 || energy < 0) {
            throw new IllegalArgumentException("invalid vehicle profile");
        }
    }

    public Component displayName() {
        return Component.literal(name);
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putString("name", name);
        tag.putString("type", type.toString());
        tag.putIntArray("seats", seats.stream().sorted().mapToInt(Integer::intValue).toArray());
        equipment.ifPresent(value -> tag.putUUID("equipment", value));
        ListTag entries = new ListTag();
        for (Stock entry : stock) {
            CompoundTag row = new CompoundTag();
            row.put("items", entry.items().save(registries));
            row.putInt("target", entry.target());
            row.putInt("threshold", entry.threshold());
            entries.add(row);
        }
        tag.put("stock", entries);
        tag.putLong("energy", energy);
        tag.put("rules", WeaponRules.write(rules));
        return tag;
    }

    public static VehicleProfile load(CompoundTag tag, HolderLookup.Provider registries) {
        List<Stock> stock = new ArrayList<>();
        for (Tag value : tag.getList("stock", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) value;
            stock.add(new Stock(ItemSpec.load(Reader.of(row.getCompound("items")), registries).orElseThrow(),
                row.getInt("target"), row.getInt("threshold")));
        }
        Set<Integer> seats = Arrays.stream(tag.getIntArray("seats")).boxed()
            .collect(Collectors.toSet());
        return new VehicleProfile(tag.getUUID("id"), tag.getString("name"),
            ResourceLocation.parse(tag.getString("type")), seats,
            tag.hasUUID("equipment") ? Optional.of(tag.getUUID("equipment")) : Optional.empty(),
            stock, tag.getLong("energy"), WeaponRules.read(tag.getCompound("rules")));
    }
}
