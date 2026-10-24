package io.github.izakyl.folkways.core.api.resident.body;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

// Only departures from the usual work settings are kept, including trades temporarily absent from the game.
public record WorkPriorities(Map<ResourceLocation, Rule> overrides) {

    public record Rule(boolean allowed, Keenness priority) {
        public Rule {
            Objects.requireNonNull(priority, "priority");
        }
    }

    public static final Rule DEFAULT = new Rule(true, Keenness.DEFAULT);

    public WorkPriorities {
        Map<ResourceLocation, Rule> sparse = new LinkedHashMap<>(overrides);
        sparse.values().removeIf(DEFAULT::equals);
        overrides = Map.copyOf(sparse);
    }

    public Rule of(ResourceLocation trade) {
        return overrides.getOrDefault(trade, DEFAULT);
    }

    public WorkPriorities with(ResourceLocation trade, Rule rule) {
        Map<ResourceLocation, Rule> changed = new LinkedHashMap<>(overrides);
        changed.put(trade, rule);
        return new WorkPriorities(changed);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        overrides.forEach((trade, rule) -> {
            CompoundTag entry = new CompoundTag();
            entry.putBoolean("allowed", rule.allowed());
            entry.putInt("priority", rule.priority().number());
            tag.put(trade.toString(), entry);
        });
        return tag;
    }

    public static WorkPriorities from(CompoundTag tag) {
        Map<ResourceLocation, Rule> overrides = new LinkedHashMap<>();
        for (String key : tag.getAllKeys()) {
            ResourceLocation trade = ResourceLocation.tryParse(key);
            if (trade == null || !tag.contains(key, Tag.TAG_COMPOUND)) {
                continue;
            }
            CompoundTag entry = tag.getCompound(key);
            overrides.put(trade, new Rule(!entry.contains("allowed", Tag.TAG_BYTE) || entry.getBoolean("allowed"),
                Keenness.ofNumber(entry.getInt("priority")).orElse(Keenness.DEFAULT)));
        }
        return new WorkPriorities(overrides);
    }
}
