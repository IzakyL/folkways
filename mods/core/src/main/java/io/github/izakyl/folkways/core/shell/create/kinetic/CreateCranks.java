package io.github.izakyl.folkways.core.shell.create.kinetic;

import com.simibubi.create.content.kinetics.crank.HandCrankBlockEntity;
import com.simibubi.create.content.kinetics.crank.ValveHandleBlockEntity;
import io.github.izakyl.folkways.core.facility.api.Controls;
import io.github.izakyl.folkways.core.facility.api.control.Reading;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Hand cranks and valve handles, worked by hand: {@code turn} takes a whole number of native uses, positive for a plain
 * use and negative for a sneaking one, which turns the other way. One use of a valve handle turns it through its set
 * angle; one use of a hand crank keeps it spinning for ten ticks, as a held right click does. The writer stays at the
 * handle until every use has run, as with any crank. Once the last use has stopped, the {@code turn} reading shows the
 * count for a few seconds, so the same request made again straight away is not turned twice; a turn is otherwise
 * relative, and the handle keeps no position of its own.
 */
public final class CreateCranks {

    public static final String PART = "turn";
    public static final int MAX_TURNS = 64;
    static final SignalType.Real TYPE = new SignalType.Real(-MAX_TURNS, MAX_TURNS, 1);
    // Long enough for a routine's closing check to see the finished count.
    private static final int SHOWN = 100;
    private static final ResourceLocation ID = ResourceLocation.parse("folkways_core:create_crank");

    private record Key(ResourceKey<Level> level, BlockPos pos) {
    }

    private static final class Job {
        private final int target;
        private int remaining;
        private long finishedAt = -1;

        private Job(int target) {
            this.target = target;
            remaining = Math.abs(target);
        }
    }

    // In memory, on the server thread; a turn cut short by a restart is asked again by its routine.
    private static final Map<Key, Job> JOBS = new HashMap<>();

    private CreateCranks() {
    }

    public static void install() {
        Controls.register(ID, Controls.SPECIFIC, (level, pos) -> crank(level, pos)
            .map(crank -> List.<Signal>of(new Turn(pos))).orElseGet(List::of));
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> JOBS.clear());
    }

    static Optional<HandCrankBlockEntity> crank(ServerLevel level, WorldPos pos) {
        if (!pos.in(level) || !level.isLoaded(pos.block(level))) {
            return Optional.empty();
        }
        return level.getBlockEntity(pos.block(level)) instanceof HandCrankBlockEntity crank && !crank.isRemoved()
            ? Optional.of(crank) : Optional.empty();
    }

    /** The speed one plain use gives the handle, in its network's terms, from Create's own definition. */
    public static float forwardSpeed(HandCrankBlockEntity crank) {
        boolean backwards = crank.backwards;
        int inUse = crank.inUse;
        try {
            crank.backwards = false;
            crank.inUse = Math.max(1, inUse);
            return crank.getGeneratedSpeed();
        } finally {
            crank.backwards = backwards;
            crank.inUse = inUse;
        }
    }

    private static void prune(long now) {
        if (JOBS.size() > 256) {
            JOBS.values().removeIf(job -> job.finishedAt >= 0 && now - job.finishedAt > SHOWN);
        }
    }

    record Turn(WorldPos anchor) implements Signal {

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
            return crank(level, anchor).isPresent();
        }

        @Override
        public Optional<Reading> feedback() {
            return Optional.of(level -> crank(level, anchor).flatMap(crank -> {
                Job job = JOBS.get(new Key(level.dimension(), crank.getBlockPos()));
                return job != null && job.finishedAt >= 0 && level.getGameTime() - job.finishedAt <= SHOWN
                    ? Optional.of(Value.of(job.target)) : Optional.empty();
            }));
        }

        @Override
        public Map<String, Reading> readings() {
            return Map.of("angle", level -> crank(level, anchor).filter(ValveHandleBlockEntity.class::isInstance)
                .map(crank -> Value.of(((ValveHandleBlockEntity) crank).angleInput.getValue())));
        }

        @Override
        public Write write(ServerLevel level, Mob writer, Value value) {
            if (!(value instanceof Value.Real(double number)) || TYPE.check(value).isPresent()) {
                return Write.REFUSED;
            }
            HandCrankBlockEntity crank = crank(level, anchor).orElse(null);
            if (crank == null) {
                return Write.REFUSED;
            }
            long now = level.getGameTime();
            prune(now);
            int turns = (int) Math.rint(number);
            Key key = new Key(level.dimension(), crank.getBlockPos());
            Job job = JOBS.get(key);
            if (job != null && (job.target != turns || job.finishedAt >= 0 && now - job.finishedAt > SHOWN)) {
                job = null;
            }
            if (job == null) {
                job = new Job(turns);
                JOBS.put(key, job);
            }
            if (job.finishedAt >= 0) {
                return Write.WRITTEN;
            }
            boolean back = turns < 0;
            if (job.remaining > 0) {
                if (crank instanceof ValveHandleBlockEntity valve) {
                    if (valve.inUse == 0 && valve.cooldown == 0) {
                        // A valve handle will not turn against a network some other source already drives.
                        if (valve.getTheoreticalSpeed() != 0) {
                            return Write.BUSY;
                        }
                        if (!valve.activate(back)) {
                            return Write.BUSY;
                        }
                        job.remaining--;
                    }
                } else if (crank.inUse <= 1) {
                    // Turned again before it runs down, so the network keeps one steady speed through the uses.
                    crank.turn(back);
                    job.remaining--;
                }
            }
            if (job.remaining == 0 && crank.inUse == 0) {
                job.finishedAt = now;
            }
            return Write.WRITTEN;
        }
    }
}
