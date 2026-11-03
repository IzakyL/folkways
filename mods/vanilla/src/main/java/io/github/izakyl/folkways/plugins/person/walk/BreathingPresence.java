package io.github.izakyl.folkways.plugins.person.walk;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.core.api.work.Urge;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;

public final class BreathingPresence {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways", "breathing");

    private final Map<Mob, Breathing> bodies = new WeakHashMap<>();

    public static void declare(Declaring declaring) {
        BreathingPresence presence = new BreathingPresence();
        declaring.urges(ID, (colony, who, view) -> {
            Breathing breathing = presence.bodies.computeIfAbsent(who.body(), body -> new Breathing());
            if (!breathing.needed(who.body())) {
                return List.of();
            }
            // Retreat commands use CONTROL / 4. Immediate loss of air takes precedence over them.
            return List.of(new Urge(ID, Urge.DIRE + 3, true,
                () -> new BreathingAction(who.body(), breathing), Urge.Priority.CONTROL));
        });
    }
}
