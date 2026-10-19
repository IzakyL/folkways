package io.github.izakyl.folkways.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

// Everything bearing on one resident at once, each change kept with what it came from so it can be told apart.
// Whatever holds a resident's effects answers its questions with one of these, never with where they came from.
public final class Effects {

    public static final Effects NONE = new Effects(List.of());

    // One change, and the id of what gave it: a perk, a mood, a place.
    public record Part(String source, Modifier modifier) {

        public Part {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(modifier, "modifier");
        }
    }

    private final List<Part> parts;

    private Effects(List<Part> parts) {
        this.parts = parts;
    }

    public static Effects of(List<Part> parts) {
        return parts.isEmpty() ? NONE : new Effects(List.copyOf(parts));
    }

    public List<Part> parts() {
        return parts;
    }

    public Effects with(Effects more) {
        if (more.parts.isEmpty()) {
            return this;
        }
        if (parts.isEmpty()) {
            return more;
        }
        List<Part> both = new ArrayList<>(parts);
        both.addAll(more.parts);
        return new Effects(List.copyOf(both));
    }

    public double value(Stat stat) {
        return value(stat, Optional.empty());
    }

    public double value(Stat stat, ResourceLocation trade) {
        return value(stat, Optional.of(trade));
    }

    public double value(Stat stat, Optional<ResourceLocation> trade) {
        double sum = stat.base();
        double factor = 1.0;
        for (Part part : parts) {
            Modifier modifier = part.modifier();
            if (!modifier.scope().covers(trade)) {
                continue;
            }
            if (modifier instanceof Modifier.Add add && add.stat().equals(stat)) {
                sum += add.amount();
            } else if (modifier instanceof Modifier.Multiply multiply && multiply.stat().equals(stat)) {
                factor *= multiply.factor();
            }
        }
        return stat.clamp(sum * factor);
    }

    public boolean has(Flag flag) {
        return has(flag, Optional.empty());
    }

    public boolean has(Flag flag, ResourceLocation trade) {
        return has(flag, Optional.of(trade));
    }

    public boolean has(Flag flag, Optional<ResourceLocation> trade) {
        boolean granted = false;
        for (Part part : parts) {
            Modifier modifier = part.modifier();
            if (!modifier.scope().covers(trade)) {
                continue;
            }
            if (modifier instanceof Modifier.Forbid forbid && forbid.flag().equals(flag)) {
                return false;
            }
            if (modifier instanceof Modifier.Grant grant && grant.flag().equals(flag)) {
                granted = true;
            }
        }
        return granted;
    }
}
