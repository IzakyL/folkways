package io.github.izakyl.folkways.core.shell.warfare;

import java.util.Collection;
import net.minecraft.world.phys.Vec3;

/** Optional traffic adjustments to native vehicle motion. */
public interface VehicleTraffic {
    VehicleTraffic NONE = new VehicleTraffic() {};

    default Vec3 destination(Vec3 goal) {
        return goal;
    }

    default Vec3 steer(Vec3 goal) {
        return goal;
    }

    default Vec3 limit(Vec3 velocity, double braking, boolean vertical) {
        return velocity;
    }

    default Vec3 flight(Vec3 goal) {
        return goal;
    }

    default void reserve(Vec3 goal, Collection<VehicleTraffic> traffic) {
    }

    default void clear() {
    }
}
