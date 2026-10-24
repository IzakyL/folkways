package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.core.api.ground.Ground;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Function;
import net.minecraft.server.MinecraftServer;

// One soft computation allowance belongs to the whole server, not each colony or dimension.
// The elected site retains its ticket for this tick even when it does not call or cannot progress.
final class ServerFillBudget {
    private static final Registry PRODUCTION = new Registry(FillPlan.Limits.NORMAL.work(), FillPlan.Limits.NORMAL.nanos());

    private ServerFillBudget() { }

    static void register(MinecraftServer server, FillPlan plan) {
        PRODUCTION.register(server, server.getTickCount(), plan);
    }

    static void close(MinecraftServer server, FillPlan plan) {
        PRODUCTION.close(server, server.getTickCount(), plan);
    }

    static boolean registered(MinecraftServer server, FillPlan plan) {
        return PRODUCTION.registered(server, plan);
    }

    static <T> T allotted(MinecraftServer server, FillPlan plan, Function<Ground.Budget, T> call) {
        return PRODUCTION.allotted(server, server.getTickCount(), plan, call);
    }

    // The explicit tick and work-only constructor also let deterministic fixtures exercise the
    // production allocation algorithm without depending on wall-clock speed or other test sites.
    static final class Registry {
        private final int work;
        private final long nanos;
        private final Map<MinecraftServer, Pool> pools = new WeakHashMap<>();
        // Closing the last site releases its deque and all FillPlan references. This primitive-only
        // tombstone prevents close/reopen in the same tick from creating a second allowance.
        private final Map<MinecraftServer, Balance> last = new WeakHashMap<>();

        Registry(int work, long nanos) {
            if (work < 0 || nanos < 0) throw new IllegalArgumentException("negative server fill allowance");
            this.work = work;
            this.nanos = nanos;
        }

        void register(MinecraftServer server, long tick, FillPlan plan) {
            Pool pool = pools.get(server);
            if (pool == null) {
                Balance previous = last.remove(server);
                pool = new Pool(work, nanos, previous != null && previous.tick() == tick ? previous : null);
                pools.put(server, pool);
            }
            pool.register(plan);
        }

        void close(MinecraftServer server, long tick, FillPlan plan) {
            Pool pool = pools.get(server);
            if (pool == null || !pool.remove(plan)) return;
            if (pool.live.isEmpty()) {
                pools.remove(server);
                if (pool.tick == tick) last.put(server, new Balance(tick, pool.leftWork, pool.leftNanos));
                else last.remove(server);
            }
        }

        boolean registered(MinecraftServer server, FillPlan plan) {
            Pool pool = pools.get(server);
            return pool != null && pool.live.contains(plan);
        }

        int liveCount(MinecraftServer server) {
            Pool pool = pools.get(server);
            return pool == null ? 0 : pool.live.size();
        }

        boolean hasPool(MinecraftServer server) {
            return pools.containsKey(server);
        }

        <T> T allotted(MinecraftServer server, long tick, FillPlan plan, Function<Ground.Budget, T> call) {
            Pool pool = pools.get(server);
            return pool == null ? call.apply(new Ground.Budget(0, 0)) : pool.allotted(tick, plan, call);
        }
    }

    private record Balance(long tick, int work, long nanos) { }

    private static final class Pool {
        private final int work;
        private final long nanos;
        private final ArrayDeque<FillPlan> turns = new ArrayDeque<>();
        private final Set<FillPlan> live = new HashSet<>();
        private long tick = Long.MIN_VALUE;
        private FillPlan owner;
        private int leftWork;
        private long leftNanos;
        private Ground.Budget active;

        Pool(int work, long nanos, Balance previous) {
            this.work = work;
            this.nanos = nanos;
            if (previous != null) {
                tick = previous.tick();
                leftWork = previous.work();
                leftNanos = previous.nanos();
                // The previous ticket died with its owner. Do not elect a replacement this tick.
            }
        }

        void register(FillPlan plan) {
            if (live.add(plan)) turns.addLast(plan);
        }

        boolean remove(FillPlan plan) {
            if (!live.remove(plan)) return false;
            turns.remove(plan);
            if (owner == plan) owner = null;
            return true;
        }

        <T> T allotted(long now, FillPlan plan, Function<Ground.Budget, T> call) {
            if (tick != now) {
                tick = now;
                owner = turns.pollFirst();
                if (owner != null) turns.addLast(owner);
                leftWork = work;
                leftNanos = nanos;
            }
            if (owner != plan || !live.contains(plan)) return call.apply(new Ground.Budget(0, 0));
            // A nested callback shares the in-flight allowance as well. Only the outer scope settles
            // it, so its actual work and elapsed time cannot be refunded by re-entering the gate.
            if (active != null) return call.apply(active);
            Ground.Budget budget = new Ground.Budget(Math.max(0, leftWork), nanos == 0 ? 0 : Math.max(1L, leftNanos));
            active = budget;
            long started = System.nanoTime();
            try {
                return call.apply(budget);
            } finally {
                active = null;
                leftWork = Math.max(0, leftWork - budget.used());
                if (nanos > 0) {
                    leftNanos = Math.max(0L, leftNanos - (System.nanoTime() - started));
                    if (leftNanos == 0) leftWork = 0;
                }
            }
        }
    }
}
