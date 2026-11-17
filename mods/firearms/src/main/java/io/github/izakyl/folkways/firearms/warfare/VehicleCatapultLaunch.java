package io.github.izakyl.folkways.firearms.warfare;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo;
import com.atsuishio.superbwarfare.entity.misc.CatapultShuttleEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import io.github.izakyl.folkways.core.combat.api.LandingSites;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

final class VehicleCatapultLaunch {
    private final VehicleEntity vehicle;
    private final LandingSites.Site site;
    private final LandingSites.Catapult catapult;
    private CatapultShuttleEntity shuttle;
    private boolean attached;
    private boolean triggered;
    private boolean released;
    private boolean prepared;

    VehicleCatapultLaunch(VehicleEntity vehicle, LandingSites.Site site, LandingSites.Catapult catapult) {
        this.vehicle = vehicle;
        this.site = site;
        this.catapult = catapult;
    }

    boolean tick(EngineInfo.Aircraft engine) {
        if (released) {
            return false;
        }
        if (VehicleAirports.airborne(vehicle, site)
            && (shuttle == null || shuttle.getTowingEntity() != vehicle)) {
            cancel();
            VehicleRunwayTraffic.release(vehicle);
            released = true;
            return false;
        }
        ServerLevel level = (ServerLevel) vehicle.level();
        var pose = site.pose(level).orElse(null);
        if (pose == null) {
            return true;
        }
        if (shuttle == null || shuttle.isRemoved()) {
            shuttle = VehicleCatapults.shuttle(level, catapult);
        }
        double gravity = vehicle.computed().getGravity();
        if (shuttle == null) {
            vehicle.setDeltaMovement(pose.velocity().add(0, gravity, 0));
            return true;
        }
        if (attached) {
            Vec3 end = WorldSpaces.world(level, catapult.start()).orElseThrow()
                .add(pose.forward().scale(catapult.length()));
            if (shuttle.position().subtract(end).dot(pose.forward()) >= 0
                || shuttle.getTowingEntity() != vehicle) {
                cancel();
                released = true;
                return false;
            }
            // SW's shuttle adds the towing force. Preserve it through travel and compensate only native gravity.
            vehicle.setDeltaMovement(vehicle.getDeltaMovement().add(0, gravity, 0));
            vehicle.setYRot((float) Math.toDegrees(Math.atan2(-pose.forward().x, pose.forward().z)));
            vehicle.setXRot(0);
            vehicle.setZRot(0);
            vehicle.setPower(0);
            return true;
        }
        if (!prepared) {
            prepared = true;
            if (VehicleCatapults.powered(level, catapult)) {
                VehicleCatapults.trigger(level, catapult, false);
                vehicle.setDeltaMovement(pose.velocity().add(0, gravity, 0));
                vehicle.setPower(0);
                return true;
            }
        }
        // A stopped server can leave the levers powered and the shuttle at the far end. Return it before hooking.
        Vec3 start = WorldSpaces.world(level, catapult.start()).orElse(null);
        if (start == null) {
            return true;
        }
        if (shuttle.position().subtract(start).dot(pose.forward()) > 1
            || level.getBlockState(catapult.controller().block(level))
                .getValue(com.atsuishio.superbwarfare.block.CatapultControllerBlock.POWERED)) {
            VehicleCatapults.trigger(level, catapult, false);
            vehicle.setDeltaMovement(pose.velocity().add(0, gravity, 0));
            vehicle.setPower(0);
            return true;
        }
        double slack = VehicleCatapults.slack(vehicle);
        double vertical = vehicle.getY() - shuttle.getY();
        double separation = Math.max(0, Math.sqrt(Math.max(0, slack * slack - vertical * vertical)) - .1);
        Vec3 target = shuttle.position().subtract(pose.forward().scale(separation));
        Vec3 delta = target.subtract(vehicle.position()).multiply(1, 0, 1);
        double maximum = DirectVehicleMotion.maximumSpeed(engine, 1);
        double acceleration = Math.max(Math.ulp(1f), maximum * .006 * engine.getIncrement());
        double braking = Math.max(Math.ulp(1f), maximum * .006 * engine.getDecrement());
        double reach = Math.max(acceleration, braking);
        double desired = Math.min(DirectVehicleMotion.minimumSpeed(engine, gravity) / 2,
            Math.sqrt(2 * braking * Math.max(0, delta.length() - reach)));
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        vehicle.setYRot(Mth.approachDegrees(vehicle.getYRot(), yaw, engine.getYawSpeed()));
        Vec3 current = vehicle.getDeltaMovement().subtract(pose.velocity()).multiply(1, 0, 1);
        Vec3 wanted = delta.normalize().scale(desired);
        Vec3 change = wanted.subtract(current);
        double limit = desired < current.length() ? braking : acceleration;
        Vec3 velocity = current.add(change.length() > limit ? change.normalize().scale(limit) : change);
        vehicle.setDeltaMovement(velocity.add(pose.velocity()).add(0, gravity, 0));
        vehicle.setXRot(0);
        vehicle.setZRot(0);
        vehicle.setPower((float) (desired / maximum));
        if (delta.length() <= reach * 2 && current.length() <= braking * 2) {
            // Same pair of native links written by TowBarItem, after its distance and rear-side constraints.
            if (vehicle.position().subtract(shuttle.position()).dot(shuttle.getLookAngle()) <= 0
                && vehicle.distanceTo(shuttle) <= VehicleCatapults.slack(vehicle)) {
                shuttle.setTowingUUID(vehicle.getStringUUID());
                vehicle.setTowedByUUID(shuttle.getStringUUID());
                attached = true;
                triggered = VehicleCatapults.trigger(level, catapult, true);
                if (!triggered) {
                    cancel();
                }
            }
        }
        return true;
    }

    boolean seated() {
        return shuttle != null && shuttle.getTowingEntity() == vehicle;
    }

    void cancel() {
        if (shuttle != null && shuttle.getTowingEntity() == vehicle) {
            shuttle.clearTowingInfo();
        }
        if (triggered) {
            VehicleCatapults.trigger((ServerLevel) vehicle.level(), catapult, false);
            triggered = false;
        }
        attached = false;
    }
}
