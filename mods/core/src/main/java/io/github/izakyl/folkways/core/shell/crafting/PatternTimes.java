package io.github.izakyl.folkways.core.shell.crafting;

import io.github.izakyl.folkways.core.colony.api.Colony;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/** Per-colony exponentially weighted observations, persisted with the colony's extension keeps. */
public final class PatternTimes {

    // The old 200 + count * 20 model gives 220 ticks for a cold single execution.
    public static final double DEFAULT_TICKS = 220;
    private static final double ALPHA = 0.25;
    private final Colony colony;
    private final ResourceLocation owner;
    private final Map<String, Map<String, Double>> times = new HashMap<>();

    public PatternTimes(Colony colony, ResourceLocation owner) {
        this.colony = colony;
        this.owner = owner;
        CompoundTag saved = colony.kept(owner);
        for (String network : saved.getAllKeys()) {
            CompoundTag patterns = saved.getCompound(network);
            Map<String, Double> values = new HashMap<>();
            for (String pattern : patterns.getAllKeys()) {
                double value = patterns.getDouble(pattern);
                if (Double.isFinite(value) && value > 0) {
                    values.put(pattern, value);
                }
            }
            times.put(network, values);
        }
    }

    public static String identity(String definition) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(definition.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public int estimate(String network, Map<String, Long> runs) {
        double total = runs.entrySet().stream()
            .mapToDouble(entry -> entry.getValue() * single(network, entry.getKey())).sum();
        return (int) Math.clamp(Math.ceil(total), 1, Integer.MAX_VALUE);
    }

    private double single(String network, String pattern) {
        return times.getOrDefault(network, Map.of()).getOrDefault(pattern, DEFAULT_TICKS);
    }

    public void completed(String network, Map<String, Long> runs, long elapsed) {
        if (runs.isEmpty() || elapsed <= 0) {
            return;
        }
        double predicted = runs.entrySet().stream()
            .mapToDouble(entry -> entry.getValue() * single(network, entry.getKey())).sum();
        Map<String, Double> values = times.computeIfAbsent(network, ignored -> new HashMap<>());
        // Native APIs expose a completed job's elapsed time, not timings for its individual patterns. Apportion
        // that observation by the previous per-pattern weights; a single-pattern job supplies a direct sample.
        for (var entry : runs.entrySet()) {
            double before = values.getOrDefault(entry.getKey(), DEFAULT_TICKS);
            double sample = Math.max(1, elapsed * before / predicted);
            values.put(entry.getKey(), before * (1 - ALPHA) + sample * ALPHA);
        }
        save();
    }

    public void retain(Map<String, Set<String>> present) {
        boolean changed = times.keySet().removeIf(network -> !present.containsKey(network));
        for (var entry : times.entrySet()) {
            changed |= entry.getValue().keySet().removeIf(pattern -> !present.get(entry.getKey()).contains(pattern));
        }
        if (changed) {
            save();
        }
    }

    private void save() {
        CompoundTag saved = new CompoundTag();
        times.forEach((network, values) -> {
            CompoundTag patterns = new CompoundTag();
            values.forEach(patterns::putDouble);
            saved.put(network, patterns);
        });
        colony.keep(owner, saved);
    }
}
