package io.github.izakyl.folkways.effect;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

// A number about a resident that effects move: what it is with nothing on it, and the bounds it is held to.
public record Stat(ResourceLocation id, double base, double min, double max) {

    public Stat {
        Objects.requireNonNull(id, "id");
        if (!(min <= base && base <= max)) {
            throw new IllegalArgumentException("stat " + id + " starts at " + base + ", outside its " + min + ".." + max);
        }
    }

    public double clamp(double value) {
        return Math.max(min, Math.min(max, value));
    }
}
