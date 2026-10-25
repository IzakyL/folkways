package io.github.izakyl.folkways.core.api.resident.body;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;

/** Mod integrations can temporarily reserve a body's actions without coupling Core to their runtime. */
public final class BodyActions {
    private record Gate(ResourceLocation id, Predicate<Body> allowed) { }

    private static volatile List<Gate> gates = List.of();

    private BodyActions() { }

    public static synchronized void register(ResourceLocation id, Predicate<Body> allowed) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(allowed);
        if (gates.stream().anyMatch(gate -> gate.id().equals(id))) {
            throw new IllegalArgumentException("body action gate already registered: " + id);
        }
        List<Gate> next = new ArrayList<>(gates);
        next.add(new Gate(id, allowed));
        gates = List.copyOf(next);
    }

    /** Returns whether every registered integration currently permits this body's ordinary actions. */
    public static boolean allowed(Body body) {
        for (Gate gate : gates) {
            if (!gate.allowed().test(body)) {
                return false;
            }
        }
        return true;
    }
}
