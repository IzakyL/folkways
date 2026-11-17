package io.github.izakyl.folkways.core.combat.api;

import io.github.izakyl.folkways.core.work.api.Worker;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/** Shared squad and vehicle policy. An absent override is FREE, unlimited range, direct fire. */
public final class WeaponRules {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("folkways-combat");
    public enum Mode { FREE, ORDERED, DISABLED }

    public record Rule(Mode mode, double range, boolean indirect) {
        public Rule {
            if (mode == null || !Double.isFinite(range) || range < 0) {
                throw new IllegalArgumentException("invalid weapon rule");
            }
        }

        public boolean permits(boolean ordered, double distance, boolean indirectShot) {
            return mode != Mode.DISABLED && (mode != Mode.ORDERED || ordered)
                && (range == 0 || distance <= range) && (!indirectShot || indirect);
        }
    }

    public static final Rule DEFAULT = new Rule(Mode.FREE, 0, false);
    public static final String TAG = "folkways_core:weapon_rules";

    @FunctionalInterface
    public interface Policy {
        boolean permits(Worker who, Entity target, ItemStack weapon, double distance);
    }

    private static Policy policy = (who, target, weapon, distance) -> true;

    public static void policy(Policy value) {
        policy = java.util.Objects.requireNonNull(value);
    }

    private WeaponRules() {
    }

    public static String category(ItemStack weapon) {
        return Loadouts.entries().entrySet().stream().filter(entry -> entry.getValue().accepts(weapon))
            .map(entry -> entry.getKey().toString()).findFirst().orElse("minecraft:melee");
    }

    public static boolean permits(Worker who, Entity target, ItemStack weapon) {
        if (!(who.body().level() instanceof ServerLevel level)) {
            return false;
        }
        return policy.permits(who, target, weapon, target == null ? 0 : who.body().distanceTo(target));
    }

    public static boolean permitsApproach(Worker who, Entity target, ItemStack weapon) {
        return policy.permits(who, target, weapon, 0);
    }

    /** The rule that applies to a worker's weapon; supplied by whoever owns the rules, so the API stays below them. */
    @FunctionalInterface
    public interface Lookup {
        Rule rule(Worker who, ItemStack weapon);
    }

    private static Lookup lookup = (who, weapon) -> DEFAULT;

    public static void lookup(Lookup value) {
        lookup = java.util.Objects.requireNonNull(value);
    }

    /** The weapon's reach, capped by its rule's range. */
    public static double range(Worker who, ItemStack weapon, double reach) {
        if (!(who.body().level() instanceof ServerLevel)) {
            return reach;
        }
        Rule rule = lookup.rule(who, weapon);
        return rule.range() == 0 ? reach : Math.min(reach, rule.range());
    }

    public static CompoundTag write(Map<String, Rule> rules) {
        CompoundTag result = new CompoundTag();
        rules.forEach((name, rule) -> {
            CompoundTag row = new CompoundTag();
            row.putString("mode", rule.mode().name());
            row.putDouble("range", rule.range());
            row.putBoolean("indirect", rule.indirect());
            result.put(name, row);
        });
        return result;
    }

    public static Map<String, Rule> read(CompoundTag tag) {
        Map<String, Rule> rules = new LinkedHashMap<>();
        for (String name : tag.getAllKeys()) {
            try {
                CompoundTag row = tag.getCompound(name);
                rules.put(name, new Rule(Mode.valueOf(row.getString("mode")), row.getDouble("range"),
                    row.getBoolean("indirect")));
            } catch (RuntimeException invalid) {
                LOGGER.warn("Skipping invalid weapon rule {}", name, invalid);
            }
        }
        return Map.copyOf(rules);
    }
}
