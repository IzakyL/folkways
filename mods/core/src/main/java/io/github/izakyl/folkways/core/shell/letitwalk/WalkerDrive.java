package io.github.izakyl.folkways.core.shell.letitwalk;

import dev.letitwalk.content.WalkerCoreBlockEntity;
import dev.letitwalk.walker.WalkerBrain;
import io.github.izakyl.folkways.core.facility.api.control.Reading;
import io.github.izakyl.folkways.core.facility.api.control.Readings;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/** Drives the walker for one tick per write until its position reading reports arrival. */
record WalkerDrive(WorldPos anchor) implements Signal {

    static final String PART = "destination";
    static final double ARRIVED = 1;
    private static final double FAR = 8;
    private static final double WALK_ANGLE = Math.toRadians(15);
    private static final double YAW_DEADBAND = Math.toRadians(.25);
    private static final double TURN_GAIN = .08;
    private static final double MAX_TURN = .06;
    private static final double AXIS_DEADBAND = .25;
    private static final double EASE_DISTANCE = 2.5;
    private static final double CRAB_SPEED = .7;
    private static final double SPAN = 30_000_000;
    static final SignalType TYPE = new SignalType.Vector(List.of(new SignalType.Real(-SPAN, SPAN, 0),
        new SignalType.Real(-4096, 4096, 0), new SignalType.Real(-SPAN, SPAN, 0)));

    @Override
    public String part() {
        return PART;
    }

    @Override
    public SignalType type() {
        return TYPE;
    }

    @Override
    public Cost cost() {
        return new Cost.UntilRead();
    }

    @Override
    public boolean valid(ServerLevel level) {
        return Walkers.core(level, anchor).filter(core -> core.isPowered() && !core.parked).isPresent();
    }

    @Override
    public Optional<Vec3> travelPosition(ServerLevel level) {
        return Walkers.core(level, anchor).flatMap(Walkers::position);
    }

    @Override
    public Optional<Reading> feedback() {
        return Readings.of(LetItWalkIntegration.id("position"), anchor);
    }

    @Override
    public Write write(ServerLevel level, Mob writer, Value value) {
        if (!(value instanceof Value.Vector(List<Double> target)) || TYPE.check(value).isPresent()) {
            return Write.REFUSED;
        }
        WalkerCoreBlockEntity core = Walkers.core(level, anchor).orElse(null);
        if (core == null) {
            return Write.REFUSED;
        }
        if (!core.isPowered() || core.parked) {
            return Write.BUSY;
        }
        Vec3 destination = new Vec3(target.get(0), target.get(1), target.get(2));
        Optional<Vec3> at = Walkers.position(core);
        Optional<Vec3> heading = Walkers.heading(core);
        if (at.isEmpty() || heading.isEmpty()) {
            return Write.BUSY;
        }
        Vec3 facing = heading.get();
        Vec3 offset = destination.subtract(at.get()).multiply(1, 0, 1);
        double distance = offset.horizontalDistance();
        Optional<Vec3> approach = Walkers.approachHeading(core, destination,
            distance <= FAR ? heading : Optional.empty());
        Vec3 aim = approach.orElse(offset);
        // Positive yaw turns the front from (x, z) towards (z, -x).
        double yawError = Math.atan2(aim.x * facing.z - aim.z * facing.x, aim.dot(facing));
        double turn = Math.abs(yawError) > YAW_DEADBAND
            ? Mth.clamp(yawError * TURN_GAIN, -MAX_TURN, MAX_TURN) : 0;

        int keys = 0;
        if (distance > ARRIVED) {
            if (approach.isPresent()) {
                double ahead = offset.dot(facing);
                double left = offset.x * facing.z - offset.z * facing.x;
                int side = movement(left, CRAB_SPEED, -core.strafe, WalkerBrain.KEY_LEFT, WalkerBrain.KEY_RIGHT);
                keys = movement(ahead, 1, core.throttle, WalkerBrain.KEY_FORWARD, WalkerBrain.KEY_BACK)
                    | (side == 0 ? 0 : WalkerBrain.KEY_CRAB | side) | 1 << WalkerBrain.GEAR_SHIFT;
            } else {
                keys = 2 << WalkerBrain.GEAR_SHIFT;
                if (Math.abs(yawError) < WALK_ANGLE) {
                    keys |= WalkerBrain.KEY_FORWARD;
                }
            }
        }
        core.receiveInput(keys, level.getGameTime());
        core.receiveTurn(turn);
        int shown = keys | (turn > 0 ? WalkerBrain.KEY_LEFT : turn < 0 ? WalkerBrain.KEY_RIGHT : 0);
        Walkers.station(level, anchor).ifPresent(station -> Walkers.showKeys(level, station, shown));
        return Write.WRITTEN;
    }

    /** The native input ramps by .08 per tick. Release it as soon as it reaches the requested fraction. */
    private static int movement(double offset, double speed, double current, int positive, int negative) {
        double distance = Math.abs(offset);
        double demand = Mth.clamp(distance / (EASE_DISTANCE * speed), 0, 1);
        if (distance <= AXIS_DEADBAND || current * Math.signum(offset) >= demand) {
            return 0;
        }
        return offset > 0 ? positive : negative;
    }
}
