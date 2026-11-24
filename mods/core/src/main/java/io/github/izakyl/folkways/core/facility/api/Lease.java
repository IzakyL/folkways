package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.facility.api.control.Control;
import io.github.izakyl.folkways.core.work.api.Seating;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.world.api.Stances;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * One resident's exclusive use of a control. A control has at most one lease at a time, keyed by
 * {@link Control#leaseKey}. A lease does nothing but keep others off: the writer reaches the control's stance, and
 * sits in its seat when the stance is one, through the engine's arrival step before it takes the lease. The lease ends
 * when it is closed, when its resident dies or leaves the level, or when another taker preempts it. Leases live only
 * in memory.
 *
 * <p>All of it runs on the server thread.
 */
public final class Lease implements AutoCloseable {

    private static final Map<Object, Lease> LEASES = new LinkedHashMap<>();

    private final Control control;
    private final Worker worker;
    private final Object key;
    // The seat the control is written from, when its stance is one.
    private final Optional<Stances.Seat> seat;
    // Whether enter was called: leave answers every enter, whether it admitted, refused or threw.
    private boolean entered;
    private boolean closed;

    private Lease(Control control, Worker worker, Optional<Stances.Seat> seat) {
        this.control = control;
        this.worker = worker;
        this.seat = seat;
        key = control.leaseKey();
    }

    /**
     * Takes the control for the worker, if no one else holds it and it is valid now. The worker's own lease on the same
     * control is handed back as it is.
     */
    public static Optional<Lease> take(Control control, Worker worker) {
        return take(control, worker, false);
    }

    /** As {@link #take(Control, Worker)}; with `preempt`, another resident's lease on the control is ended first. */
    public static Optional<Lease> take(Control control, Worker worker, boolean preempt) {
        if (!(worker.body().level() instanceof ServerLevel level)
            || !level.getServer().isSameThread()
            || !worker.body().isAlive()
            || worker.body().isRemoved()
            || !control.valid(level)) {
            return Optional.empty();
        }
        Lease held = LEASES.get(control.leaseKey());
        if (held != null) {
            if (held.worker.resident().id().equals(worker.resident().id())) {
                return Optional.of(held);
            }
            if (!preempt) {
                return Optional.empty();
            }
            held.close();
        }
        Lease lease = new Lease(control, worker, control.stance(level)
            .filter(Stances.Seat.class::isInstance).map(Stances.Seat.class::cast));
        LEASES.put(lease.key, lease);
        boolean admitted = false;
        try {
            lease.entered = true;
            admitted = control.enter(worker.body());
            return admitted ? Optional.of(lease) : Optional.empty();
        } finally {
            if (!admitted) {
                lease.close();
            }
        }
    }

    /** Who holds the control now, if anyone. */
    public static Optional<Lease> held(Control control) {
        return Optional.ofNullable(LEASES.get(control.leaseKey()));
    }

    public Control control() {
        return control;
    }

    public Worker worker() {
        return worker;
    }

    public boolean open() {
        return !closed;
    }

    /** The seat the control is written from, when its stance is one. */
    public Optional<Stances.Seat> seat() {
        return seat;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        LEASES.remove(key, this);
        if (entered) {
            control.leave(worker.body());
        }
    }

    /** Whether the body sits in the seat of a control it holds under an open lease. */
    public static boolean ownsSeat(Mob body) {
        return LEASES.values().stream().anyMatch(lease -> !lease.closed && lease.worker.body() == body
            && lease.seat.filter(seat -> Seating.in(body, seat)).isPresent());
    }

    /** Ends every lease held by this resident. */
    public static void release(UUID entity) {
        for (Lease lease : List.copyOf(LEASES.values())) {
            if (lease.worker.resident().id().equals(entity) || lease.worker.body().getUUID().equals(entity)) {
                lease.close();
            }
        }
    }

    /** Core only. */
    public static void install() {
        NeoForge.EVENT_BUS.addListener(Lease::leaving);
        NeoForge.EVENT_BUS.addListener(Lease::dying);
    }

    private static void leaving(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            release(event.getEntity().getUUID());
        }
    }

    private static void dying(LivingDeathEvent event) {
        if (!event.getEntity().level().isClientSide()) {
            release(event.getEntity().getUUID());
        }
    }
}
