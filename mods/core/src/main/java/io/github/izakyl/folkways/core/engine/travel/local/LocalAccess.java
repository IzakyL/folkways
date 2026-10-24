package io.github.izakyl.folkways.core.engine.travel.local;

import io.github.izakyl.folkways.core.api.ground.Ground;
import io.github.izakyl.folkways.core.api.terms.Reach;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

// Main-thread, resumable breadth-first proof. A cache eviction drops only its lookup reference: a planner
// holding this object continues exactly where it yielded, including partway through surveying a section.
final class LocalAccess implements Ground.Access {

    private static final int MOST_VISITED = 32768;
    private static final int MOST_TARGETS = 128;
    private static final long[] NO_STEPS = new long[0];

    private final LocalWatch watch;
    private final LocalGround ground;
    @Nullable
    private final Walker walker;
    private final List<BlockPos> exits;
    private final Long2IntOpenHashMap distances = new Long2IntOpenHashMap();
    private final Long2LongOpenHashMap toward = new Long2LongOpenHashMap();
    private final LongArrayFIFOQueue open = new LongArrayFIFOQueue();
    private final Long2ObjectLinkedOpenHashMap<List<BlockPos>> workStances = new Long2ObjectLinkedOpenHashMap<>();
    private int exitAt;
    private boolean current;
    private long cell;
    @Nullable
    private long[] steps;
    private int stepAt;
    private boolean done;
    private boolean truncated;
    @Nullable
    private Survey.Read reading;
    private long readingKey;

    LocalAccess(LocalWatch watch, LocalGround ground, @Nullable Walker walker, List<BlockPos> exits) {
        this.watch = watch;
        this.ground = ground;
        this.walker = walker;
        this.exits = exits;
        distances.defaultReturnValue(-1);
        // Different movement kinds cannot be combined into a single body's escape route.
        done = walker == null || exits.isEmpty();
    }

    @Override
    public boolean valid() {
        return ground.terrainValid() && (done || ground.valid());
    }

    @Override
    public boolean complete() {
        return done && valid();
    }

    @Override
    public boolean exhaustive() {
        return complete() && !truncated;
    }

    @Override
    public int visited() {
        return distances.size();
    }

    @Override
    public boolean advance(Ground.Budget budget) {
        if (!valid()) {
            return false;
        }
        if (done) {
            return true;
        }
        int before = budget.used();
        try {
            while (exitAt < exits.size()) {
                if (budget.exhausted()) {
                    return false;
                }
                BlockPos exit = exits.get(exitAt);
                long next = exit.asLong();
                Section there = null;
                if (ground.known(exit) && !distances.containsKey(next)) {
                    there = section(Section.keyOf(next), budget);
                    if (there == null) {
                        return false;
                    }
                }
                if (!budget.tryStep()) {
                    return false;
                }
                if (there != null && there.stands().contains(next)) {
                    distances.put(next, 0);
                    open.enqueue(next);
                }
                exitAt++;
                if (distances.size() >= MOST_VISITED) {
                    finish(true);
                    return true;
                }
            }
            while (current || !open.isEmpty()) {
                if (!current) {
                    if (!budget.tryStep()) {
                        return false;
                    }
                    cell = open.dequeueLong();
                    current = true;
                    steps = null;
                    stepAt = 0;
                }
                if (steps == null) {
                    Section here = section(Section.keyOf(cell), budget);
                    if (here == null) {
                        return false;
                    }
                    steps = here.steps().getOrDefault(cell, NO_STEPS);
                }
                while (stepAt < steps.length) {
                    if (budget.exhausted()) {
                        return false;
                    }
                    long next = steps[stepAt];
                    Section there = null;
                    if (!distances.containsKey(next)
                            && watch.covers(BlockPos.getX(next), BlockPos.getY(next), BlockPos.getZ(next))) {
                        there = section(Section.keyOf(next), budget);
                        if (there == null) {
                            return false;
                        }
                    }
                    if (!budget.tryStep()) {
                        return false;
                    }
                    if (there != null && there.stands().contains(next) && there.stepsTo(next, cell)) {
                        distances.put(next, distances.get(cell) + 1);
                        toward.put(next, cell);
                        open.enqueue(next);
                        if (distances.size() >= MOST_VISITED) {
                            finish(true);
                            return true;
                        }
                    }
                    stepAt++;
                }
                current = false;
            }
            finish(false);
            return true;
        } finally {
            watch.worked(budget.used() - before);
        }
    }

    private void finish(boolean limited) {
        done = true;
        truncated = limited;
        current = false;
        steps = null;
        open.clear();
    }

    @Nullable
    private Section section(long key, Ground.Budget budget) {
        if (reading == null || readingKey != key) {
            if (budget.exhausted()) {
                return null;
            }
            reading = ground.read(walker, key);
            readingKey = key;
        }
        // The pending read is pinned by this search even if another query evicts its section lookup.
        reading.use(walker);
        reading.advance(budget);
        Section result = reading.result();
        if (result != null) {
            reading = null;
        }
        return result;
    }

    @Override
    public int distance(BlockPos feet) {
        return valid() ? distances.get(feet.asLong()) : -1;
    }

    @Override
    public List<BlockPos> path(BlockPos feet) {
        if (!valid() || !distances.containsKey(feet.asLong())) {
            return List.of();
        }
        List<BlockPos> path = new ArrayList<>();
        long at = feet.asLong();
        path.add(BlockPos.of(at));
        while (distances.get(at) > 0) {
            at = toward.get(at);
            path.add(BlockPos.of(at));
        }
        return List.copyOf(path);
    }

    @Override
    public List<BlockPos> stancesFor(BlockPos target) {
        if (!complete()) {
            return List.of();
        }
        List<BlockPos> saved = workStances.getAndMoveToLast(target.asLong());
        if (saved != null) {
            return saved;
        }
        List<BlockPos> found = new ArrayList<>();
        int span = Mth.ceil(Reach.PLAYER_BLOCK_REACH);
        for (BlockPos feet : BlockPos.betweenClosed(target.offset(-span, -span, -span),
                target.offset(span, span, span))) {
            if (canWorkFrom(feet, target)) {
                found.add(feet.immutable());
            }
        }
        found.sort(Comparator.<BlockPos>comparingDouble(feet -> feet.distSqr(target))
            .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX)
            .thenComparingInt(BlockPos::getZ));
        List<BlockPos> answer = List.copyOf(found);
        workStances.putAndMoveToLast(target.asLong(), answer);
        if (workStances.size() > MOST_TARGETS) {
            workStances.removeFirst();
        }
        return answer;
    }

    @Override
    public boolean canWorkFrom(BlockPos feet, BlockPos target) {
        return !feet.equals(target) && !feet.equals(target.below()) && distance(feet) >= 0
            && Reach.withinReach(feet, target) && ground.inSight(feet, target);
    }
}
