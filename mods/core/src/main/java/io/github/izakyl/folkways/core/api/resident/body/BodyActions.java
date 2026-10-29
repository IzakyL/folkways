package io.github.izakyl.folkways.core.api.resident.body;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;

/** Optional, temporary execution gates; revocation and resource cleanup must run before these gates. */
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

    public static boolean allowed(Body body) {
        for (Gate gate : gates) {
            if (!gate.allowed().test(body)) { return false; }
        }
        return true;
    }
}
