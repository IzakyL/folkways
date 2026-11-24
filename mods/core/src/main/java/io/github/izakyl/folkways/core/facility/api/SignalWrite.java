package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.facility.api.control.Control;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.resident.api.Doings;
import io.github.izakyl.folkways.core.work.api.Disposition;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.HoldingContext;
import io.github.izakyl.folkways.core.work.api.HoldingStep;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.PreemptReason;
import io.github.izakyl.folkways.core.work.api.Recourse;
import io.github.izakyl.folkways.core.work.api.SoftPreempt;
import io.github.izakyl.folkways.core.work.api.WorkHolding;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

// Sets one signal. The writer takes the control's lease when the work is taken up and keeps it until the work is let
// go, so no one else writes it meanwhile; the control is resolved again at every step, never kept from the rig.
final class SignalWrite implements Node {

    // A busy or briefly missing control is tried again a few times before the run is given up.
    private static final int RETRY_TICKS = 40;
    private static final int RETRIES = 5;

    private final ResourceLocation owner;
    private final Rig.Part part;
    private final SignalType type;
    private final Value target;
    private final NodeSpec spec;
    private Lease lease;
    private boolean borrowed;
    private int retried;
    private final List<Routines.Expect> beforePulse;
    private final boolean presses;
    private long pressedAt = -1;
    private Signal.Write settled;

    SignalWrite(UUID id, ResourceLocation owner, Rig.Part part, SignalType type, Value target) {
        this(id, owner, part, type, target, List.of());
    }

    SignalWrite(UUID id, ResourceLocation owner, Rig.Part part, SignalType type, Value target,
                List<Routines.Expect> beforePulse) {
        this(id, owner, part, type, target, beforePulse, !beforePulse.isEmpty());
    }

    /**
     * A write that {@code presses} is a pulse held until the control settles it: the writer keeps the lease while a
     * native action it started is still under way, such as a turret's queued shot.
     */
    SignalWrite(UUID id, ResourceLocation owner, Rig.Part part, SignalType type, Value target,
                List<Routines.Expect> beforePulse, boolean presses) {
        this.presses = presses;
        this.beforePulse = List.copyOf(beforePulse);
        this.owner = owner;
        this.part = part;
        this.type = type;
        this.target = target;
        spec = NodeSpec.of(id, owner, part.site(), part.stances(), workload())
            .doing(Doings.OPERATING, Optional.of(part.name())).done();
    }

    private Workload workload() {
        if (presses) {
            return (Workload.Continuous) this::pressing;
        }
        return switch (part.cost()) {
            case Control.Cost.Instant ignored -> Workload.Once.of(0);
            case Control.Cost.Remote ignored -> Workload.Once.of(0);
            case Control.Cost.Ticks(int ticks) -> Workload.Once.of(ticks);
            // A crank is turned until what it drives shows the target.
            case Control.Cost.UntilRead ignored -> (Workload.Continuous) (level, who) ->
                signal(level).filter(signal -> signal.write(level, who.body(), target) == Signal.Write.WRITTEN)
                    .flatMap(Signal::commandFeedback).flatMap(reading -> reading.read(level))
                    .filter(read -> type.agrees(target, read)).isEmpty();
        };
    }

    private boolean pressing(ServerLevel level, Worker worker) {
        if (pressedAt >= 0) {
            return settled == null && level.getGameTime() - pressedAt < 200;
        }
        Signal pulse = signal(level).orElse(null);
        if (pulse == null || lease == null || !lease.open()
                || !lease.control().leaseKey().equals(pulse.leaseKey())) {
            settled = Signal.Write.REFUSED;
            return false;
        }
        for (Routines.Expect expect : beforePulse) {
            Signal prerequisite = Controls.resolve(level, expect.control()).filter(Signal.class::isInstance)
                .map(Signal.class::cast).orElse(null);
            if (prerequisite == null) {
                settled = Signal.Write.REFUSED;
                return false;
            }
            if (part.seated() && prerequisite.leaseKey().equals(pulse.leaseKey())
                    && prerequisite.write(level, worker.body(), expect.target()) != Signal.Write.WRITTEN) {
                settled = Signal.Write.REFUSED;
                return false;
            }
            if (prerequisite.commandFeedback().flatMap(reading -> reading.read(level))
                    .filter(read -> expect.type().agrees(expect.target(), read)).isEmpty()) {
                settled = Signal.Write.BUSY;
                return false;
            }
        }
        long attempt = level.getGameTime();
        pressedAt = attempt;
        Signal.Write written = pulse.press(level, worker.body(), result -> {
            if (pressedAt == attempt) {
                settled = result;
            }
        });
        if (written != Signal.Write.WRITTEN) {
            settled = written;
        }
        return settled == null;
    }

    boolean directPressing(ServerLevel level, Worker worker) {
        if (lease == null) {
            lease = signal(level).filter(signal -> signal.admits(worker.body()))
                .flatMap(signal -> {
                    borrowed = Lease.held(signal).isPresent();
                    return Lease.take(signal, worker);
                }).orElse(null);
            if (lease == null) {
                settled = Signal.Write.BUSY;
                return false;
            }
        }
        return pressing(level, worker);
    }

    boolean simple() {
        return !presses;
    }

    boolean remote() {
        return simple() && part.cost() instanceof Control.Cost.Remote;
    }

    Outcome writeRemote(ServerLevel level) {
        var live = signal(level).orElse(null);
        if (live == null || !(live.cost() instanceof Control.Cost.Remote)) {
            return Outcome.failed(FacilityRefusal.GONE);
        }
        if (Lease.held(live).isPresent()) {
            return Outcome.failed(FacilityRefusal.BUSY);
        }
        return write(level, live, null);
    }

    boolean untilRead() {
        return part.cost() instanceof Control.Cost.UntilRead;
    }

    String partName() {
        return part.name();
    }

    WriteSequence.Write step() {
        return new WriteSequence.Write(part.ref(), target, 0);
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    @Override
    public boolean mountedWork() {
        return part.seated();
    }

    // The control says who may write it; for a seat, that is asked before the writer sits down.
    @Override
    public boolean admits(ServerLevel level, Mob body) {
        return signal(level).filter(signal -> signal.admits(body)).isPresent();
    }

    private Optional<Signal> signal(ServerLevel level) {
        return Controls.resolve(level, part.ref()).filter(Signal.class::isInstance).map(Signal.class::cast)
            .filter(signal -> signal.valid(level));
    }

    @Override
    public WorkHolding acquire(HoldingContext context) {
        Optional<Signal> signal = signal(context.level());
        if (signal.isEmpty()) {
            return refused(FacilityRefusal.GONE);
        }
        Optional<Lease> taken = Lease.take(signal.get(), context.worker());
        if (taken.isEmpty()) {
            return refused(Lease.held(signal.get()).isPresent() ? FacilityRefusal.BUSY : FacilityRefusal.GONE);
        }
        lease = taken.get();
        return context.holdings().standard(context, this);
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        if (presses) {
            return switch (settled) {
                case WRITTEN -> Outcome.done();
                case BUSY -> Outcome.failed(FacilityRefusal.BUSY);
                case REFUSED -> Outcome.failed(FacilityRefusal.REFUSED);
                case null -> Outcome.failed(FacilityRefusal.UNSETTLED);
            };
        }
        Optional<Signal> signal = signal(level);
        if (signal.isEmpty()) {
            return Outcome.failed(FacilityRefusal.GONE);
        }
        if (lease == null || !lease.open() || !lease.control().leaseKey().equals(signal.get().leaseKey())) {
            return Outcome.failed(FacilityRefusal.BUSY);
        }
        return write(level, signal.get(), who.body());
    }

    private Outcome write(ServerLevel level, Signal signal, Mob writer) {
        if (!signal.type().equals(type) || type.check(target).isPresent()) {
            return Outcome.failed(FacilityRefusal.REFUSED);
        }
        return switch (signal.write(level, writer, target)) {
            case WRITTEN -> Outcome.done();
            case BUSY -> Outcome.failed(FacilityRefusal.BUSY);
            case REFUSED -> Outcome.failed(FacilityRefusal.REFUSED);
        };
    }

    @Override
    public Recourse failing(ServerLevel level, RefusalKind why) {
        close();
        if ((why == FacilityRefusal.BUSY || why == FacilityRefusal.GONE) && retried++ < RETRIES) {
            return Recourse.waitFor(RETRY_TICKS);
        }
        return Recourse.FAIL;
    }

    @Override
    public void released(ServerLevel level, Worker who, Ending how) {
        close();
    }

    @Override
    public void ended(ServerLevel level, Ending how) {
        close();
    }

    private void close() {
        pressedAt = -1;
        settled = null;
        if (lease != null) {
            if (!borrowed) {
                lease.close();
            }
            borrowed = false;
            lease = null;
        }
    }

    // Work that cannot start: it fails at once, and the node says whether to try again.
    private static WorkHolding refused(RefusalKind why) {
        return new WorkHolding() {
            @Override
            public HoldingStep tick(HoldingContext context) {
                return new HoldingStep.Failed(why);
            }

            @Override
            public void requestSoftPreempt(HoldingContext context, SoftPreempt request) {
            }

            @Override
            public void hardRelease(HoldingContext context, Disposition result, PreemptReason reason) {
            }

            @Override
            public void suspended(HoldingContext context, PreemptReason reason) {
            }

            @Override
            public void resumed(HoldingContext context) {
            }
        };
    }

    @Override
    public String toString() {
        return "SignalWrite[" + part.name() + " := " + target + " for " + owner + "]";
    }
}
