package io.github.izakyl.folkways.core.resident.api;

import io.github.izakyl.folkways.core.base.persist.Required;
import io.github.izakyl.folkways.core.vocation.api.Keenness;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
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
        sparse.entrySet().removeIf(entry -> defaults(entry.getKey()).equals(entry.getValue()));
        overrides = Map.copyOf(sparse);
    }

    public Rule of(ResourceLocation trade) {
        return overrides.getOrDefault(trade, defaults(trade));
    }

    public static Rule defaults(ResourceLocation trade) {
        return Vocations.of(trade)
            .map(vocation -> new Rule(vocation.defaultAllowed(), vocation.defaultPriority())).orElse(DEFAULT);
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
            if (trade == null) {
                throw new IllegalArgumentException("Invalid work-priority trade id: " + key);
            }
            CompoundTag entry = Required.compound(tag, key);
            Keenness priority = Keenness.ofNumber(Required.integer(entry, "priority")).orElseThrow();
            overrides.put(trade, new Rule(Required.flag(entry, "allowed"), priority));
        }
        return new WorkPriorities(overrides);
    }
}
