package io.github.izakyl.folkways.core.combat.api;

import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** A position last reported by a source, in world coordinates. */
public final class SpottedTargets {

    public record Spot(UUID target, int entityId, ResourceKey<Level> dimension, Vec3 position, long tick) {

        /** A positional target has no runtime entity id. */
        public boolean entity() {
            return entityId >= 0;
        }
    }

    private SpottedTargets() {
    }
}
