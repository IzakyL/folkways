package io.github.izakyl.folkways.firearms.warfare;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Local traffic planning only; native physics remains responsible for moving the commanded hull. */
final class VehicleSeparation {
    private final VehicleEntity vehicle;
    private Vec3 requested;
    private Vec3 parking;
    private Vec3 parkingAxis;
    private Vec3 detour;
    private double flightHeight;
    private long flightUntil;

    VehicleSeparation(VehicleEntity vehicle) {
        this.vehicle = vehicle;
    }

    void clear() {
        requested = null;
        parking = null;
        detour = null;
        flightUntil = 0;
    }

    void reserve(Vec3 goal, Collection<VehicleSeparation> traffic) {
        clear();
        requested = goal;
        parking = goal;
        Vec3 forward = goal.subtract(vehicle.position()).multiply(1, 0, 1).normalize();
        Vec3 sideways = new Vec3(-forward.z, 0, forward.x);
        var peers = traffic.stream().filter(other -> other != this && other.requested != null
            && !other.vehicle.isRemoved() && other.vehicle.level() == vehicle.level()
            && other.requested.distanceTo(goal) < 4)
            .sorted(Comparator.comparingInt(other -> other.vehicle.getId())).toList();
        if (!peers.isEmpty()) {
            // Share the first reservation's arrival axis, so later orders cannot rotate the formation.
            sideways = peers.getFirst().parkingAxis;
        }
        parkingAxis = sideways;
        double step = Math.max(parkingSize(), peers.stream().mapToDouble(VehicleSeparation::parkingSize)
            .max().orElse(0)) + 4;
        double side = vehicle.position().subtract(goal).dot(sideways) < 0 ? -1 : 1;
        // Keep earlier reservations fixed; prefer the side this hull is already approaching from.
        for (int slot = 0; slot < traffic.size() * 2 + 3; slot++) {
            int rank = (slot + 1) / 2;
            double offset = rank * step * (slot % 2 == 1 ? side : -side);
            Vec3 candidate = goal.add(sideways.scale(offset));
            boolean free = true;
            for (var other : traffic) {
                if (other == this || other.parking == null || other.vehicle.isRemoved()
                    || other.vehicle.level() != vehicle.level()) {
                    continue;
                }
                double space = clearance(other.vehicle) + (vehicle.getBbWidth() + other.vehicle.getBbWidth()) / 2;
                if (candidate.subtract(other.parking).horizontalDistance() < space) {
                    free = false;
                    break;
                }
            }
            if (free) {
                parking = candidate;
                return;
            }
        }
    }

    private double parkingSize() {
        var bounds = vehicle.getBoundingBox();
        // Include arrival tolerance on both hulls, as well as the native collision bounds.
        return Math.max(bounds.getXsize(), bounds.getZsize()) + vehicle.getBbWidth();
    }

    Vec3 destination(Vec3 goal) {
        return requested != null && requested.equals(goal) ? parking : goal;
    }

    private List<VehicleEntity> nearby() {
        return vehicle.level().getEntitiesOfClass(VehicleEntity.class, vehicle.getBoundingBox().inflate(128),
            other -> other != vehicle && other.isAlive() && !other.isWreck())
            .stream().sorted(Comparator.comparingInt(VehicleEntity::getId)).toList();
    }

    private double clearance(VehicleEntity other) {
        return (vehicle.getBbWidth() + other.getBbWidth()) / 2 + 2;
    }

    private boolean sameHeight(VehicleEntity other) {
        var own = vehicle.getBoundingBox();
        var bounds = other.getBoundingBox();
        return own.minY - 2 < bounds.maxY && own.maxY + 2 > bounds.minY;
    }

    Vec3 steer(Vec3 goal) {
        if (detour != null) {
            if (detour.subtract(vehicle.position()).horizontalDistance() > Math.max(2, vehicle.getBbWidth() / 2)) {
                return new Vec3(detour.x, goal.y, detour.z);
            }
            detour = null;
        }
        Vec3 direction = goal.subtract(vehicle.position()).multiply(1, 0, 1).normalize();
        double distance = goal.subtract(vehicle.position()).horizontalDistance();
        double ahead = Math.min(distance, Math.max(24, vehicle.getDeltaMovement().horizontalDistance() * 60));
        for (var other : nearby()) {
            if (!sameHeight(other)) {
                continue;
            }
            Vec3 relative = other.position().subtract(vehicle.position()).multiply(1, 0, 1);
            double along = relative.dot(direction);
            double across = relative.subtract(direction.scale(along)).length();
            double space = clearance(other);
            boolean following = other.getDeltaMovement().multiply(1, 0, 1).dot(direction) > 0.03;
            if (along > 0 && along < ahead + space && across < space * 1.5 && !following) {
                // Both oncoming drivers pass on their right. At crossings the larger id also slows below.
                Vec3 right = new Vec3(-direction.z, 0, direction.x);
                detour = other.position().add(right.scale(space * 2));
                return new Vec3(detour.x, goal.y, detour.z);
            }
        }
        return goal;
    }

    Vec3 limit(Vec3 velocity, double braking, boolean vertical) {
        Vec3 result = velocity;
        for (var other : nearby()) {
            if (!vertical && !sameHeight(other)) {
                continue;
            }
            Vec3 relative = other.position().subtract(vehicle.position());
            Vec3 otherVelocity = other.getDeltaMovement();
            Vec3 closing = result.subtract(otherVelocity).multiply(1, 0, 1);
            double time = Mth.clamp(relative.multiply(1, 0, 1).dot(closing)
                / Math.max(1e-6, closing.horizontalDistanceSqr()), 0, 40);
            Vec3 closest = relative.subtract(closing.scale(time));
            double height = relative.y + (otherVelocity.y - result.y) * time;
            if (vertical && Math.abs(height) > Math.max(vehicle.getBbHeight(), other.getBbHeight()) + 3) {
                continue;
            }
            double space = clearance(other);
            double gap = relative.horizontalDistance() - space;
            if (closest.horizontalDistance() < space * 1.5 && time > 0) {
                double forward = Math.max(0, otherVelocity.multiply(1, 0, 1)
                    .dot(result.multiply(1, 0, 1).normalize()));
                double safe = Math.sqrt(2 * braking * Math.max(0, gap - 2)) + forward;
                if (vehicle.getId() > other.getId() && otherVelocity.horizontalDistance() > 0.03
                    && otherVelocity.normalize().dot(result.normalize()) < 0.5) {
                    safe *= 0.5;
                }
                double speed = result.horizontalDistance();
                if (speed > safe) {
                    result = new Vec3(result.x * safe / speed, result.y, result.z * safe / speed);
                }
            }
            // Check the swept next few ticks as well: steering may still be catching up with the detour.
            for (int attempt = 0; attempt < 8; attempt++) {
                Vec3 motion = result.subtract(otherVelocity);
                double t = Mth.clamp(relative.multiply(1, 0, 1).dot(motion.multiply(1, 0, 1))
                    / Math.max(1e-6, motion.horizontalDistanceSqr()), 0, 8);
                Vec3 separation = relative.subtract(motion.scale(t));
                if (separation.horizontalDistance() >= space || vertical
                    && Math.abs(separation.y) > Math.max(vehicle.getBbHeight(), other.getBbHeight()) + 3) {
                    break;
                }
                result = vertical ? result.scale(0.5) : result.multiply(0.5, 1, 0.5);
            }
        }
        return result;
    }

    Vec3 flight(Vec3 goal) {
        long now = vehicle.level().getGameTime();
        for (var other : nearby()) {
            if (VehicleController.commanded(other) && vehicle.getId() < other.getId()) {
                continue;
            }
            Vec3 relative = other.position().subtract(vehicle.position());
            Vec3 closing = vehicle.getDeltaMovement().subtract(other.getDeltaMovement());
            double time = Mth.clamp(relative.dot(closing) / Math.max(1e-6, closing.lengthSqr()), 0, 80);
            Vec3 closest = relative.subtract(closing.scale(time));
            double height = Math.max(vehicle.getBbHeight(), other.getBbHeight()) + 6;
            if (closest.horizontalDistance() < clearance(other) * 3 && Math.abs(closest.y) < height) {
                flightHeight = other.getY() + height + 4;
                flightUntil = now + 100;
            }
        }
        return now < flightUntil ? new Vec3(goal.x, Math.max(goal.y, flightHeight), goal.z) : goal;
    }
}
