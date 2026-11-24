package io.github.izakyl.folkways.core.shell.warfare;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import io.github.izakyl.folkways.core.combat.api.Vehicles;
import java.util.Optional;
import net.minecraft.util.Mth;

/** Engine boundary used by the mixin; all coefficients below come from SW 0.8.9.2's aircraft engine. */
public final class DirectVehicleMotion {
    private DirectVehicleMotion() {
    }

    public interface Controlled {
        boolean folkways$controlled();

        void folkways$controlled(boolean controlled);
    }

    public static void controlled(VehicleEntity vehicle, boolean controlled) {
        if (((Controlled) vehicle).folkways$controlled() != controlled) {
            ((Controlled) vehicle).folkways$controlled(controlled);
        }
    }

    public static void work(EngineInfo engine, VehicleEntity vehicle) {
        if (vehicle.level().isClientSide()) {
            if (((Controlled) vehicle).folkways$controlled()) {
                // SW subtracts gravity and moves after travel. Leave only handleClientSync's interpolation.
                vehicle.setDeltaMovement(0, vehicle.computed().getGravity(), 0);
                return;
            }
        } else {
            if (VehicleController.drive(vehicle, engine)) {
                return;
            }
            controlled(vehicle, false);
        }
        engine.work(vehicle);
    }

    public static Optional<Vehicles.RunwayNeeds> runway(VehicleEntity vehicle) {
        if (!(vehicle.getEngineInfo() instanceof EngineInfo.Aircraft engine)) {
            return Optional.empty();
        }
        double minimum = minimumSpeed(engine, vehicle.computed().getGravity());
        double maximum = maximumSpeed(engine, 1);
        double acceleration = Math.max(Math.ulp(1f), maximum * .006 * engine.getIncrement());
        double braking = Math.max(Math.ulp(1f), maximum * .006 * engine.getDecrement());
        double left = Double.POSITIVE_INFINITY;
        double right = Double.NEGATIVE_INFINITY;
        vehicle.updateOBB();
        var axis = vehicle.getRightVec(1);
        for (var obb : vehicle.getOBBs()) {
            for (var vertex : obb.getVertices()) {
                double lateral = vertex.x * axis.x + vertex.y * axis.y + vertex.z * axis.z;
                left = Math.min(left, lateral);
                right = Math.max(right, lateral);
            }
        }
        double span = Math.max(vehicle.getBbWidth(), right - left);
        // One control tick of travel plus one yaw step of lateral tracking error on each side.
        double margin = maximum + vehicle.getBbWidth();
        double width = Math.ceil(span + 2 * maximum * Math.sin(Math.toRadians(engine.getYawSpeed())));
        // Approach turns occupy airspace, not pavement. Allow one control step of touchdown speed error.
        double touchdownSpeed = minimum + Math.max(acceleration, braking);
        double stop = 0;
        for (double speed = touchdownSpeed; speed > 0;) {
            speed = Math.max(0, speed - braking);
            stop += speed;
        }
        var needs = new Vehicles.RunwayNeeds(0, Math.ceil(stop + margin), width,
            minimum, acceleration, margin);
        return Optional.of(new Vehicles.RunwayNeeds(needs.takeoff(0), needs.landing(), width,
            minimum, acceleration, margin, VehicleCatapults.slack(vehicle)));
    }

    static double shipSpeed(double rate, double submerged) {
        // SW's aligned immersed drag is clamp(.99 - 4v² - .02*submerged, 0, .99), after adding .04v.
        double low = 0;
        double high = Math.max(Math.sqrt(0.99 / 4), 0.11 * Math.abs(rate));
        for (int step = 0; step < 53; step++) {
            double middle = (low + high) / 2;
            double drag = Mth.clamp(0.99 - 4 * middle * middle - 0.02 * submerged, 0, 0.99);
            if (middle * (1 - 1.04 * drag) < 0.11 * Math.abs(rate)) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return high;
    }

    static double airshipSpeed(double rate) {
        double thrust = 0.01 * Math.abs(rate);
        double low = 0;
        double high = thrust / (1 - 0.99);
        for (int step = 0; step < 53; step++) {
            double middle = (low + high) / 2;
            double drag = Mth.clamp(0.91499 + 0.07 + 0.031 - 0.01 * middle * middle, 0.01, 0.99);
            if (middle * (1 - drag) < thrust) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return high;
    }

    public static double minimumSpeed(EngineInfo.Aircraft engine, double gravity) {
        // Level, aligned flight: lift = v * .008 * LiftSpeed * clamp(4 - .25v, 1, 4).
        double low = 0;
        double high = gravity / Math.max(Math.ulp(1f), 0.008 * engine.getLiftSpeed());
        for (int step = 0; step < 53; step++) {
            double middle = (low + high) / 2;
            double lift = middle * 0.008 * engine.getLiftSpeed() * Mth.clamp(4 - 0.25 * middle, 1, 4);
            if (lift < gravity) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return high;
    }

    static double maximumSpeed(EngineInfo.Aircraft engine, double power) {
        // Aligned airborne drag and .047 * power * SpeedRate thrust, at equilibrium.
        double thrust = 0.047 * power * engine.getSpeedRate();
        double low = 0;
        double high = thrust / (1 - 1.04 * 0.96);
        for (int step = 0; step < 53; step++) {
            double middle = (low + high) / 2;
            double drag = Mth.clamp(0.96 - 0.0017 * engine.getResistance() * middle * middle, 0.01, 0.99);
            if (middle * (1 - 1.04 * drag) < thrust) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return high;
    }
}
