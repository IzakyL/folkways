package io.github.izakyl.folkways.core.plan.internal;

import io.github.izakyl.folkways.core.work.api.ReservationPool;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Claims belonging to a run, including the stock temporarily lent to material expansions. */
final class PoolClaims {

    record Input(int need, Resource.Lot lot) {
    }

    static final class Loan implements Runnable {
        final PoolClaims pool;
        final Input input;
        final Claim draw;
        private boolean settled;

        Loan(PoolClaims pool, Input input, Claim draw) {
            this.pool = pool;
            this.input = input;
            this.draw = draw;
        }

        @Override
        public synchronized void run() {
            if (!settled) {
                settled = true;
                pool.budget.drawn(input.need(), draw.amount());
            }
        }

        synchronized boolean returnable() {
            if (settled) {
                return false;
            }
            settled = true;
            return true;
        }
    }

    final ReservationPool budget;
    final Set<UUID> consumers = new LinkedHashSet<>();
    final Map<Input, Claim> inputs = new LinkedHashMap<>();
    final Map<UUID, Expansion> hauling = new LinkedHashMap<>();
    final Map<UUID, Claim> packed = new LinkedHashMap<>();
    private final UUID outputOwner = UUID.randomUUID();
    private int received;
    boolean open = true;

    PoolClaims(ReservationPool budget) {
        this.budget = budget;
    }

    void adjust(Claims claims, Input key, long change) {
        Claim old = inputs.remove(key);
        long count = change;
        if (old != null) {
            claims.drop(old);
            count += old.amount();
        }
        if (count > 0) {
            Claim next = new Claim(new Resource.Transit(key.lot().where(), key.lot().spec()), budget.owner(), count);
            inputs.put(key, next);
            claims.take(next);
        }
    }

    void receive(Claims claims) {
        List<ReservationPool.Stored> arrivals = budget.storedSince(received);
        for (var put : arrivals) {
            claims.take(new Claim(new Resource.Transit(put.where(), put.spec()), outputOwner, put.count()));
        }
        received += arrivals.size();
    }

    void close(Claims claims) {
        open = false;
        claims.release(budget.owner());
        claims.release(outputOwner);
        packed.values().forEach(claims::drop);
        packed.clear();
        inputs.clear();
    }
}
