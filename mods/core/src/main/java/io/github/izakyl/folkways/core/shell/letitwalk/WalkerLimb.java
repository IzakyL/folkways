package io.github.izakyl.folkways.core.shell.letitwalk;

import dev.letitwalk.content.WalkerCoreBlockEntity;
import dev.letitwalk.network.WalkerControlPayload;
import dev.letitwalk.walker.LegRuntime;
import dev.letitwalk.walker.WalkerLimbs;
import dev.ryanhcode.sable.Sable;
import io.github.izakyl.folkways.core.facility.api.control.Reading;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/** Selects a native pilot limb and steers its aim with the same counters as mouse input. */
record WalkerLimb(WorldPos anchor, int number) implements Signal {

    private static final SignalType.Real AXIS = new SignalType.Real(-1, 1, 0);
    private static final SignalType.Vector TYPE = new SignalType.Vector(List.of(AXIS, AXIS));
    private static final Value.Vector RELEASE = new Value.Vector(List.of());
    // countsMoved sign-extends by shifting 18 bits, leaving 14 counter bits.
    private static final int COUNT_MASK = (1 << (Integer.SIZE - 18)) - 1;
    private static final int MAX_COUNTS = 30;
    private static final double INNER_RADIUS = .25 / .95;
    private static final Field AIM = aimField();
    private static final Map<WalkerCoreBlockEntity, Target> TARGETS = new WeakHashMap<>();

    private static final class Target {
        final int number;
        final double fwd;
        final double up;
        final long started;
        int upCounter;
        int fwdCounter;
        double[] previousAim;

        Target(WalkerCoreBlockEntity core, int number, List<Double> axes, long started) {
            this.number = number;
            double radius = Math.hypot(axes.get(0), axes.get(1));
            double clamped = Math.clamp(radius, INNER_RADIUS, 1);
            this.fwd = radius < 1e-6 ? 0 : axes.get(0) * clamped / radius;
            this.up = radius < 1e-6 ? -INNER_RADIUS : axes.get(1) * clamped / radius;
            this.started = started;
            upCounter = core.manualUp;
            fwdCounter = core.manualFwd;
        }

        boolean advance(WalkerCoreBlockEntity core, long now) {
            if (core.manualLimb != number || WalkerControlPayload.countsMoved(core.manualUp, upCounter) != 0
                    || WalkerControlPayload.countsMoved(core.manualFwd, fwdCounter) != 0) {
                return false;
            }
            var leg = WalkerLimbs.limb(core, number);
            if (leg == null) {
                core.receiveLimb(0, upCounter, fwdCounter);
                return false;
            }
            if (leg.manualAim == null) {
                return now - started <= 10;
            }
            double[] aim = aim(leg.manualAim);
            if (previousAim != null && Math.hypot(aim[0] - previousAim[0], aim[1] - previousAim[1]) < 1e-6) {
                return false;
            }
            double step = Math.clamp(.06 / Math.max(.5, .95 * leg.built.chain.reach()), .004, .01);
            int upDelta = counts((up - aim[1]) / step);
            int fwdDelta = counts((fwd - aim[0]) / step) * leg.manualSide;
            if (upDelta == 0 && fwdDelta == 0) {
                return false;
            }
            previousAim = aim;
            upCounter = (upCounter + upDelta) & COUNT_MASK;
            fwdCounter = (fwdCounter + fwdDelta) & COUNT_MASK;
            core.receiveLimb(number, upCounter, fwdCounter);
            return true;
        }
    }

    @Override
    public String part() {
        return "limb/" + number;
    }

    @Override
    public SignalType type() {
        return TYPE;
    }

    @Override
    public boolean valid(ServerLevel level) {
        return Walkers.core(level, anchor).filter(core -> WalkerLimbs.limb(core, number) != null).isPresent();
    }

    @Override
    public Optional<Reading> feedback() {
        return Optional.of(level -> Walkers.core(level, anchor).flatMap(core -> readEnd(level, core)));
    }

    @Override
    public Optional<Reading> commandFeedback() {
        // A probe releases the selected limb when it returns the control to rest.
        return Optional.of(level -> Optional.of(RELEASE));
    }

    @Override
    public Write write(ServerLevel level, Mob writer, Value value) {
        var core = Walkers.core(level, anchor).orElse(null);
        if (core == null || WalkerLimbs.limb(core, number) == null) {
            return Write.REFUSED;
        }
        if (value == null || RELEASE.equals(value)) {
            if (core.manualLimb == number) {
                TARGETS.remove(core);
                core.receiveLimb(0, core.manualUp, core.manualFwd);
            }
            return Write.WRITTEN;
        }
        if (!(value instanceof Value.Vector vector) || TYPE.check(value).isPresent()) {
            return Write.REFUSED;
        }
        var target = new Target(core, number, vector.values(), level.getGameTime());
        core.receiveLimb(number, target.upCounter, target.fwdCounter);
        TARGETS.put(core, target);
        return Write.WRITTEN;
    }

    static void tick(ServerLevel level) {
        TARGETS.entrySet().removeIf(entry -> {
            var core = entry.getKey();
            return core.isRemoved() || !core.isAssembled()
                || core.getLevel() == level && !entry.getValue().advance(core, level.getGameTime());
        });
    }

    private Optional<Value> readEnd(ServerLevel level, WalkerCoreBlockEntity core) {
        var leg = WalkerLimbs.limb(core, number);
        var hull = Sable.HELPER.getContaining(level, core.getBlockPos());
        if (leg == null || hull == null) {
            return Optional.empty();
        }
        double[][] points = leg.built.chain.forward(leg.built.angles, null);
        double[] tip = points[points.length - 1];
        // Where the joints have actually carried the end, not where the pilot input asks it to go: a limb that cannot
        // reach its aim reads short of it.
        Vec3 end = Vec3.atLowerCornerOf(core.getBlockPos()).add(tip[0], tip[1], tip[2]);
        Vec3 world = hull.logicalPose().transformPosition(end);
        var data = new CompoundTag();
        data.putDouble("x", round(world.x));
        data.putDouble("y", round(world.y));
        data.putDouble("z", round(world.z));
        data.putBoolean("arm", leg.arm);
        // The aim the native pilot holds, after it pushed the requested one into the ring it takes aims within.
        if (core.manualLimb == number && leg.manualAim != null) {
            double[] held = aim(leg.manualAim);
            var aim = new ListTag();
            aim.add(DoubleTag.valueOf(round(held[0])));
            aim.add(DoubleTag.valueOf(round(held[1])));
            data.put("aim", aim);
        }
        var ring = new ListTag();
        ring.add(DoubleTag.valueOf(round(INNER_RADIUS)));
        ring.add(DoubleTag.valueOf(1.0));
        data.put("ring", ring);
        // The same point in the hull's own space, which stays put however the hull walks or tilts.
        WorldSpaces.containing(level, core.getBlockPos()).ifPresent(frame -> {
            Vec3 local = frame.toLocal(world);
            var at = new ListTag();
            at.add(DoubleTag.valueOf(round(local.x)));
            at.add(DoubleTag.valueOf(round(local.y)));
            at.add(DoubleTag.valueOf(round(local.z)));
            data.put("local", at);
        });
        // The last link's world direction, from its joint to the end: a tool carried on it points this way.
        if (points.length >= 2) {
            double[] joint = points[points.length - 2];
            Vec3 link = hull.logicalPose().transformNormal(
                new Vec3(tip[0] - joint[0], tip[1] - joint[1], tip[2] - joint[2]));
            if (link.length() > 1e-6) {
                link = link.normalize();
                var axis = new ListTag();
                axis.add(DoubleTag.valueOf(round(link.x)));
                axis.add(DoubleTag.valueOf(round(link.y)));
                axis.add(DoubleTag.valueOf(round(link.z)));
                data.put("axis", axis);
            }
        }
        return Optional.of(new Value.Data(data));
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    private static int counts(double delta) {
        return (int) Math.clamp(Math.round(delta), -MAX_COUNTS, MAX_COUNTS);
    }

    private static Field aimField() {
        try {
            Field field = WalkerLimbs.Track.class.getDeclaredField("aim");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot read the native walker limb aim", failure);
        }
    }

    private static double[] aim(WalkerLimbs.Track track) {
        try {
            return (double[]) AIM.get(track);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot read the native walker limb aim", failure);
        }
    }
}
