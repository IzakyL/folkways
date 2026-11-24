package io.github.izakyl.folkways.core.shell.letitwalk;

import dev.letitwalk.content.WalkerCoreBlockEntity;
import dev.letitwalk.walker.WalkerBrain;
import dev.letitwalk.walker.WalkerLimbs;
import io.github.izakyl.folkways.core.facility.api.control.Reading;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

record WalkerPosture(WorldPos anchor, boolean height, SignalType type) implements Signal {

    private static final int RAISE = 16;
    private static final int LOWER = 32;
    private static final int LATCH = 1 << 19;
    private static final double HEIGHT_STEP = .05;
    private static final SignalType.Real ANGLE = new SignalType.Real(-WalkerBrain.MAX_LEAN, WalkerBrain.MAX_LEAN, 0);
    private static final SignalType.Vector LEAN = new SignalType.Vector(List.of(ANGLE, ANGLE));

    private static final Map<WalkerCoreBlockEntity, Target> TARGETS = new WeakHashMap<>();

    private static final class Target {
        Double height;
        Value.Vector lean;
        long sentAt = Long.MIN_VALUE;
        int sentKeys;
        long pilotAt = Long.MIN_VALUE;
        int pilotKeys;
        int pilotLatch;

        boolean advance(WalkerCoreBlockEntity core, long now) {
            if (core.lastInputTime != sentAt || core.input != sentKeys) {
                pilotAt = core.lastInputTime;
                pilotKeys = core.input;
                pilotLatch = core.latchedInput;
            }
            int keys = pilotAt != Long.MIN_VALUE && now - pilotAt <= 10 ? pilotKeys : pilotLatch;
            boolean adjusting = height != null;
            if (adjusting) {
                keys &= ~(RAISE | LOWER);
                double error = height - core.heightOffset;
                if (core.parked || !core.isPowered() || Math.abs(error) <= HEIGHT_STEP) {
                    height = null;
                } else {
                    keys |= error > 0 ? RAISE : LOWER;
                }
            }
            if (lean != null && (core.leanRollTarget != lean.values().get(0)
                    || core.leanPitchTarget != lean.values().get(1))) {
                lean = null;
            }
            if (adjusting || lean != null) {
                if (pilotLatch != 0 && height == null) {
                    keys |= LATCH;
                }
                send(core, keys, now);
            }
            return height != null || lean != null;
        }

        void send(WalkerCoreBlockEntity core, int keys, long now) {
            core.receiveInput(keys, now);
            sentAt = now;
            sentKeys = core.input;
        }
    }

    static void install() {
        NeoForge.EVENT_BUS.addListener(WalkerPosture::tick);
    }

    static List<Signal> at(ServerLevel level, WorldPos anchor) {
        return Walkers.core(level, anchor).map(core -> {
            var controls = new ArrayList<Signal>();
            controls.add(new WalkerPosture(anchor, true, heightRange(core)));
            controls.add(new WalkerPosture(anchor, false, LEAN));
            int count = WalkerLimbs.order(core).size();
            for (int i = 1; i <= count; i++) {
                controls.add(new WalkerLimb(anchor, i));
            }
            return controls;
        }).map(List::copyOf).orElseGet(List::of);
    }

    private static SignalType.Real heightRange(WalkerCoreBlockEntity core) {
        double stand = core.walking().stream().mapToDouble(leg -> leg.gait().standHeight()).min().orElse(0);
        return new SignalType.Real(-.6 * stand, .15 * stand, 0);
    }

    @Override
    public String part() {
        return height ? "height" : "lean";
    }

    @Override
    public boolean valid(ServerLevel level) {
        return Walkers.core(level, anchor).isPresent();
    }

    @Override
    public Optional<Reading> feedback() {
        return Optional.of(level -> Walkers.core(level, anchor).map(core -> height
            ? Value.of(core.heightOffset) : Value.of(core.leanRoll, core.leanPitch)));
    }

    @Override
    public Write write(ServerLevel level, Mob writer, Value value) {
        var core = Walkers.core(level, anchor).orElse(null);
        if (core == null || (height ? !(value instanceof Value.Real)
                : !(value instanceof Value.Vector vector) || vector.values().size() != 2)) {
            return Write.REFUSED;
        }
        if (core.parked || !core.isPowered()) {
            return Write.BUSY;
        }
        var target = TARGETS.computeIfAbsent(core, ignored -> new Target());
        if (height) {
            var range = heightRange(core);
            target.height = Math.clamp(((Value.Real) value).value(), range.min(), range.max());
        } else {
            var axes = ((Value.Vector) value).values();
            double roll = Math.clamp(axes.get(0), ANGLE.min(), ANGLE.max());
            double pitch = Math.clamp(axes.get(1), ANGLE.min(), ANGLE.max());
            core.receiveLean(roll, pitch);
            target.lean = roll == 0 && pitch == 0 ? null : new Value.Vector(List.of(roll, pitch));
            // A native lean packet also refreshes the pilot input timestamp.
            long now = level.getGameTime();
            if (target.lean != null && now - core.lastInputTime > 10) {
                target.pilotLatch = core.latchedInput;
                target.send(core, target.pilotLatch == 0 ? 0 : target.pilotLatch | LATCH, now);
            }
        }
        return Write.WRITTEN;
    }

    private static void tick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        TARGETS.entrySet().removeIf(entry -> {
            var core = entry.getKey();
            return core.isRemoved() || !core.isAssembled()
                || core.getLevel() == level && !entry.getValue().advance(core, level.getGameTime());
        });
        WalkerLimb.tick(level);
    }
}
