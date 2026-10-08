package io.github.izakyl.folkways.plugins.wares;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;

// Which cookers work has spoken for, for what, and in what turn. A cooker holds one stack of input and one of what it
// makes, so it cooks in batches, one after another: a booking for the product of the last batch joins it while every
// load in it still fits in the one input stack together - the loads go in side by side and cook as one, and what
// comes out is the same goods whoever takes it - and any other booking starts a batch of its own behind the rest.
// Each booking is known by the step that takes its goods out, and work booked into a batch starts only after the
// steps that empty every batch before it.
final class Bookings {

    private record Load(long count, long ticks) {
    }

    private record Batch(ItemSpec makes, Map<UUID, Load> loads, Set<UUID> out) {

        Batch(ItemSpec makes) {
            this(makes, new LinkedHashMap<>(), new LinkedHashSet<>());
        }

        boolean emptied() {
            return out.containsAll(loads.keySet());
        }

        long load() {
            return loads.values().stream().mapToLong(Load::count).sum();
        }

        long ticks() {
            return loads.values().stream().mapToLong(Load::ticks).sum();
        }
    }

    private final Map<WorldPos, List<Batch>> booked = new HashMap<>();

    synchronized boolean taken(WorldPos at) {
        return booked.containsKey(at);
    }

    // How long a booking for `makes` would wait on the batches before its own: their cooking, end to end.
    synchronized long ahead(WorldPos at, ItemSpec makes, long most) {
        List<Batch> batches = booked.get(at);
        if (batches == null) {
            return 0;
        }
        Batch last = batches.getLast();
        boolean joins = last.makes().equals(makes) && last.load() < most;
        long ticks = 0;
        for (Batch batch : batches) {
            if (joins && batch == last) {
                break;
            }
            ticks += batch.ticks();
        }
        return ticks;
    }

    // The most of `makes` one booking takes now: what still fits beside the last batch if it makes the same, or a
    // whole batch of its own.
    synchronized long room(WorldPos at, ItemSpec makes, long most) {
        List<Batch> batches = booked.get(at);
        if (batches == null) {
            return most;
        }
        Batch last = batches.getLast();
        long left = most - last.load();
        return last.makes().equals(makes) && left > 0 ? left : most;
    }

    // Answers the steps the booking waits for, or nothing if it is turned away.
    synchronized Optional<Set<UUID>> book(WorldPos at, UUID taker, ItemSpec makes, long load, long ticks,
                                       long most) {
        if (load > most) {
            return Optional.empty();
        }
        List<Batch> batches = booked.computeIfAbsent(at, key -> new ArrayList<>());
        Batch last = batches.isEmpty() ? null : batches.getLast();
        if (last == null || !last.makes().equals(makes) || last.load() + load > most) {
            last = new Batch(makes);
            batches.add(last);
        }
        last.loads().put(taker, new Load(load, ticks));
        Set<UUID> before = new LinkedHashSet<>();
        for (Batch batch : batches) {
            if (batch == last) {
                break;
            }
            before.addAll(batch.loads().keySet());
        }
        return Optional.of(before);
    }

    // The booking's goods are out of the cooker: the batch after it goes next once all of its own are.
    synchronized void out(WorldPos at, UUID taker) {
        for (Batch batch : booked.getOrDefault(at, List.of())) {
            if (batch.loads().containsKey(taker)) {
                batch.out().add(taker);
            }
        }
    }

    // Whether what the cooker holds cooked is what the batch in it now makes - the first not yet taken out. Anything
    // else there is nobody's: left by work since dropped, it would keep the batch in turn from ever cooking.
    synchronized boolean expects(WorldPos at, ItemStack held) {
        for (Batch batch : booked.getOrDefault(at, List.of())) {
            if (!batch.emptied()) {
                return Goods.matches(held, batch.makes());
            }
        }
        return false;
    }

    // Answers whether that was the last of its bookings, so the cooker is free again.
    synchronized boolean release(WorldPos at, UUID taker) {
        List<Batch> batches = booked.get(at);
        if (batches == null) {
            return false;
        }
        batches.removeIf(batch -> {
            batch.out().remove(taker);
            return batch.loads().remove(taker) != null && batch.loads().isEmpty();
        });
        if (!batches.isEmpty()) {
            return false;
        }
        booked.remove(at);
        return true;
    }

    synchronized void clear() {
        booked.clear();
    }
}
