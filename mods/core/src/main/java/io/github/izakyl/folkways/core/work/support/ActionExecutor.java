package io.github.izakyl.folkways.core.work.support;

import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.Doing;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.resident.api.ResidentKinds;
import io.github.izakyl.folkways.core.work.api.ActionHandle;
import io.github.izakyl.folkways.core.work.api.ActionServices;
import io.github.izakyl.folkways.core.work.api.ActionSpec;
import io.github.izakyl.folkways.core.work.api.Disposition;
import io.github.izakyl.folkways.core.work.api.HoldingContext;
import io.github.izakyl.folkways.core.work.api.HoldingStep;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Placement;
import io.github.izakyl.folkways.core.work.api.PreemptReason;
import io.github.izakyl.folkways.core.work.api.SoftPreempt;
import io.github.izakyl.folkways.core.work.api.ToolUse;
import io.github.izakyl.folkways.core.work.api.WorkAction;
import io.github.izakyl.folkways.core.work.api.WorkEffort;
import io.github.izakyl.folkways.core.work.api.WorkExertion;
import io.github.izakyl.folkways.core.work.api.WorkNoise;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.WorkYield;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

// Executes one action without registering work, phases or a second holding.
public final class ActionExecutor implements ActionHandle {

    private static final int BEAT_TICKS = 10;
    private record Beat(Optional<BlockPos> at, boolean store) {
    }

    private final ActionServices services;
    private final WorkAction current;
    private final Placement placement;
    private final Walking walking;
    private final Workload.Continuous ongoing;
    private final boolean joining;
    private int windup;
    private int windupTicks;
    private List<Beat> beats = List.of();
    private boolean committing;
    private boolean released;
    private boolean softPreempt;
    private boolean gestureReleased;
    private HoldingStep result;

    public ActionExecutor(HoldingContext context, WorkAction node, Placement placement, boolean joining) {
        if (!(node.spec().workload() instanceof Workload.Once)
                && !(node.spec().workload() instanceof Workload.Continuous)) {
            throw new IllegalArgumentException("an action must be finite or continuous");
        }
        this.services = context.actions();
        this.current = node;
        this.placement = placement;
        this.walking = new Walking(node, placement);
        this.joining = joining;
        this.ongoing = node.spec().workload() instanceof Workload.Continuous continuous ? continuous : null;
        this.windupTicks = node.spec().workload() instanceof Workload.Once once
            ? Math.max(0, once.ticksFor(context.worker())) : 0;
        setOff(context);
    }

    @Override
    public Optional<String> debugStage() {
        return Optional.of(stage());
    }

    public String stage() {
        return committing ? "committing" : walking.travelling() ? "approaching"
            : beats.size() > 1 ? "beats" : "winding_up";
    }

    @Override
    public HoldingStep tick(HoldingContext context) {
        if (result != null) {
            return result;
        }
        if (softPreempt) {
            // Abort an unfinished wind-up; resumption starts it again without committing partial work.
            releaseBody(context);
            return new HoldingStep.Yielded();
        }
        if (released) {
            return new HoldingStep.Yielded();
        }
        if (walking.travelling()) {
            carry(context);
        } else if (ongoing != null) {
            hold(context);
        } else {
            windUp(context);
        }
        if (result != null) {
            return result;
        }
        return new HoldingStep.Running();
    }

    @Override
    public void requestSoftPreempt(HoldingContext context, SoftPreempt request) {
        softPreempt = true;
    }

    @Override
    public void hardRelease(HoldingContext context, Disposition disposition, PreemptReason reason) {
        if (released) {
            return;
        }
        releaseBody(context);
    }

    private void releaseBody(HoldingContext context) {
        if (released) {
            return;
        }
        released = true;
        if (!gestureReleased) {
            current.spec().gesture().release(context.body().mob());
            gestureReleased = true;
        }
        walking.release(context.body());
        context.body().clearDoing();
        context.body().clearHandPresentation();
    }

    @Override
    public void suspended(HoldingContext context, PreemptReason reason) {
    }

    @Override
    public void resumed(HoldingContext context) {
        released = false;
        softPreempt = false;
        gestureReleased = false;
        setOff(context);
    }

    private void setOff(HoldingContext context) {
        windup = 0;
        switch (walking.start(context, services)) {
            case HoldingStep.Done ignored -> {
                if (joining) {
                    windupTicks = 0;
                }
                startWork(context.level(), context.body());
            }
            case HoldingStep.Failed failure -> result = failure;
            default -> { }
        }
    }

    private void carry(HoldingContext context) {
        switch (walking.tick(context, services)) {
            case HoldingStep.Done ignored -> startWork(context.level(), context.body());
            case HoldingStep.Failed failure -> result = failure;
            default -> { }
        }
    }

    private void startWork(ServerLevel level, Body body) {
        windup = 0;
        body.mob().getNavigation().stop();
        beats = beatsOf(level, body);
        present(body);
        ActionSpec spec = current.spec();
        if (ongoing != null) {
            body.setDoing(Doing.open(spec.doing()).about(spec.about()).with(current.wares()));
            return;
        }
        long from = level.getGameTime();
        body.setDoing(Doing.over(spec.doing(), from, from + windupTicks).about(spec.about()).with(current.wares()));
    }

    private void windUp(HoldingContext context) {
        Body body = context.body();
        perform(body);
        if (windup >= windupTicks) {
            commit(context);
            return;
        }
        windup++;
    }

    private void hold(HoldingContext context) {
        ServerLevel level = context.level();
        Body body = context.body();
        if (current.staysAtWorkSite() && !walking.standing(body)) {
            setOff(context);
            return;
        }
        perform(body);
        if (!ongoing.holds(level, context.worker())) {
            commit(context);
            return;
        }
        windup++;
    }

    @Override
    public void reportEffort(Body body) {
        WorkEffort effort = current.spec().effort();
        if (effort instanceof WorkEffort.None) {
            return;
        }
        double done = switch (effort) {
            case WorkEffort.Total total -> total.amount() * (windupTicks > 0
                ? Math.min(1.0D, (double) windup / windupTicks) : committing ? 1.0D : 0.0D);
            case WorkEffort.PerTick rate -> rate.amount() * windup;
            case WorkEffort.None ignored -> 0;
        };
        WorkExertion.performed(body.mob(), done, windup, 0);
    }

    // Work that reaches into stores where it stands, rather than having goods carried to it, shows each reach in
    // the one wind-up: the body turns to a store it takes from, back to the work, and on to a store it puts into,
    // reaching each time. Work without stores at hand plays its gesture once, as it always has.
    private void perform(Body body) {
        if (beats.size() <= 1) {
            current.spec().gesture().perform(body.mob(), windup, cellOf(body));
            focusOf(body).ifPresent(target -> body.mob().getLookControl().setLookAt(Vec3.atCenterOf(target)));
            return;
        }
        int length = ongoing != null ? BEAT_TICKS : Math.max(1, (windupTicks + 1) / beats.size());
        int index = windup / length;
        Beat beat = beats.get(ongoing != null ? index % beats.size() : Math.min(index, beats.size() - 1));
        int into = ongoing != null || index < beats.size() ? windup % length : windup - (beats.size() - 1) * length;
        beat.at().ifPresent(target -> body.mob().getLookControl().setLookAt(Vec3.atCenterOf(target)));
        if (!beat.store()) {
            current.spec().gesture().perform(body.mob(), into, cellOf(body));
        } else if (into == 0) {
            body.mob().swing(InteractionHand.MAIN_HAND);
        }
    }

    private Optional<BlockPos> focusOf(Body body) {
        return current.spec().focus()
            .flatMap(cell -> WorldSpaces.world(body.mob().level(), current.spec().site().where().at(cell)))
            .map(BlockPos::containing);
    }

    // The stores the work takes from and puts into where it stands, with the work between each: from one store to
    // the work, to the next store, back to the work, and on to the store what it makes goes into.
    private List<Beat> beatsOf(ServerLevel level, Body body) {
        Set<BlockPos> from = new LinkedHashSet<>();
        for (Placement.Draw draw : placement.draws()) {
            draw.from().flatMap(stash -> cellOf(level, stash)).ifPresent(from::add);
        }
        Set<BlockPos> into = new LinkedHashSet<>();
        for (Stash stash : placement.into()) {
            cellOf(level, stash).ifPresent(into::add);
        }
        Optional<BlockPos> focus = focusOf(body);
        Optional<BlockPos> work = focus.isPresent() ? focus : Optional.of(cellOf(body))
            .filter(cell -> current.spec().site() instanceof WorkSite.AtBlock);
        work.ifPresent(cell -> {
            from.remove(cell);
            into.remove(cell);
        });
        Beat working = new Beat(work, false);
        List<Beat> beats = new ArrayList<>();
        for (BlockPos store : from) {
            beats.add(new Beat(Optional.of(store), true));
            beats.add(working);
        }
        if (beats.isEmpty()) {
            beats.add(working);
        }
        for (BlockPos store : into) {
            if (beats.getLast().store()) {
                beats.add(working);
            }
            beats.add(new Beat(Optional.of(store), true));
        }
        // Work over before every turn could be shown, such as one joined onto the last, shows only its own.
        if (ongoing == null && windupTicks + 1 < beats.size()) {
            return List.of(working);
        }
        return List.copyOf(beats);
    }

    private static Optional<BlockPos> cellOf(ServerLevel level, Stash stash) {
        return stash.pos().in(level) ? WorldSpaces.world(level, stash.pos()).map(BlockPos::containing)
            : Optional.empty();
    }

    private void commit(HoldingContext context) {
        committing = true;
        Body body = context.body();
        Outcome outcome;
        try {
            outcome = context.settlement().commit(current);
        } catch (RuntimeException | LinkageError broken) {
            services.failed(current, broken);
            result = new HoldingStep.Failed(services.broken());
            return;
        }
        current.spec().gesture().release(body.mob());
        gestureReleased = true;
        for (WorkNoise noise : outcome.noises()) {
            noise.sound(context.level(), body.mob());
        }
        switch (outcome) {
            case Outcome.Done done -> {
                Wear.from(context.level(), body, current.spec(), context.worker(),
                    item -> services.toolBroke(context, item));
                if (current instanceof Node node) {
                    WorkYield.gave(body.mob(), node, done.made());
                }
                context.settlement().stow(done.made());
                done.xp().ifPresent(gained ->
                    Bodies.worked(body, gained.vocation(), windup, context.level().getRandom()));
                result = new HoldingStep.Done();
            }
            case Outcome.Failed(List<WorkNoise> ignored, RefusalKind why) -> result = new HoldingStep.Failed(why);
        }
        releaseBody(context);
    }

    private void present(Body body) {
        ActionSpec spec = current.spec();
        List<ToolUse> uses = ResidentKinds.barehanded(body.kind().id(), spec.vocation()) ? List.of() : spec.tools();
        for (ToolUse use : uses) {
            ItemStack tool = Tools.from(body.pack().contents(), use.need()).orElse(ItemStack.EMPTY);
            if (!tool.isEmpty()) {
                body.presentHeldItem(tool.copy());
                break;
            }
        }
        spec.gesture().present(body, spec.focus());
    }

    private BlockPos cellOf(Body body) {
        return current.spec().site() instanceof WorkSite.AtBlock(WorldPos pos)
            ? WorldSpaces.world(body.mob().level(), pos)
                .map(BlockPos::containing).orElse(body.mob().blockPosition())
            : body.mob().blockPosition();
    }
}
