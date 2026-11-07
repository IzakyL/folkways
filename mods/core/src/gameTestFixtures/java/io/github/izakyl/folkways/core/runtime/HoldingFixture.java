package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.resident.api.Conveyance;
import io.github.izakyl.folkways.core.resident.api.Going;
import io.github.izakyl.folkways.core.work.api.ActionServices;
import io.github.izakyl.folkways.core.work.api.Disposition;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.HoldingContext;
import io.github.izakyl.folkways.core.work.api.HoldingStep;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.PreemptReason;
import io.github.izakyl.folkways.core.work.api.Settlement;
import io.github.izakyl.folkways.core.work.api.SoftPreempt;
import io.github.izakyl.folkways.core.work.api.WorkAction;
import io.github.izakyl.folkways.core.work.api.WorkHolding;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.internal.Commit;
import io.github.izakyl.folkways.core.work.support.StandardHoldings;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

// Drives the public holding contract. Tests inspect each real settlement before stowing its
// products.
public final class HoldingFixture {
    private final Node node;
    private final HoldingContext context;
    private WorkHolding holding;
    private HoldingStep state = new HoldingStep.Running();
    private Outcome outcome;
    private int commits;
    private int delivered;
    private boolean walking;
    private boolean preservesHand;

    public HoldingFixture(ServerLevel level, Node node, Worker worker) {
        this.node = node;
        var body = Bodies.of(worker.body()).orElseThrow();
        ActionServices services =
                new TestActionServices() {
                    public Optional<Conveyance> approach(
                        HoldingContext at, WorkAction action, Set<WorldPos> footings) {
                        if (!walking || footings.isEmpty()) {
                            return Optional.empty();
                        }
                        return Optional.of(
                            new Conveyance() {
                            public Going step(
                                ServerLevel world,
                                net.minecraft.world.entity.Mob actor,
                                Set<WorldPos> cells) {
                            var feet = cells.iterator().next().block(world);
                            actor.setPos(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                            return new Going.Arrived();
                            }

                            public void release(
                                ServerLevel world,
                                net.minecraft.world.entity.Mob actor) {
                            actor.getNavigation().stop();
                            }
                            });
                    }

                    public void failed(WorkAction action, Throwable failure) {}
                };
        Settlement settlement =
                new Settlement() {
                    public Outcome commit(WorkAction action) {
                        ItemStack previous = worker.body().getMainHandItem();
                        outcome = Commit.run(level, action, worker);
                        if (preservesHand && worker.body().getMainHandItem() != previous) {
                            throw new AssertionError(
                                "the native operation did not restore the exact previous main"
                                + " hand");
                        }
                        commits++;
                        return outcome;
                    }

                    public void stow(List<ItemStack> made) {
                        // The test owns the returned products, just as a settlement implementation
                        // owns stowing.
                    }
                };
        context = new HoldingContext(level, body, worker, services, settlement, StandardHoldings.INSTANCE);
    }

    public HoldingFixture walking() {
        walking = true;
        return this;
    }

    public HoldingStep tick() {
        if (holding == null) {
            holding = node.acquire(context);
        }
        if (!(state instanceof HoldingStep.Done) && !(state instanceof HoldingStep.Failed)) {
            state = holding.tick(context);
        }
        return state;
    }

    public HoldingFixture prepare() {
        if (commits == delivered) {
            tick();
        }
        return this;
    }

    public HoldingFixture preservesHand() {
        preservesHand = true;
        return this;
    }

    public Optional<Outcome> settled() {
        if (commits == delivered) {
            return Optional.empty();
        }
        delivered = commits;
        return Optional.of(outcome);
    }

    public Outcome step() {
        if (commits != delivered) {
            delivered = commits;
            return outcome;
        }
        int before = commits;
        for (int tick = 0; tick < 4096; tick++) {
            HoldingStep next = tick();
            if (commits != before) {
                delivered = commits;
                return outcome;
            }
            if (next instanceof HoldingStep.Done) {
                return Outcome.done();
            }
            if (next instanceof HoldingStep.Failed failed) {
                return Outcome.failed(failed.why());
            }
        }
        return null;
    }

    public boolean running() {
        if (commits != delivered) {
            return false;
        }
        int before = commits;
        return tick() instanceof HoldingStep.Running && commits == before;
    }

    public java.util.UUID id() {
        return node.id();
    }

    public void release(Ending how) {
        if (holding != null) {
            holding.hardRelease(context, Disposition.END, PreemptReason.REVOKED);
        }
    }

    public boolean finished() {
        if (commits != delivered) {
            return state instanceof HoldingStep.Done || state instanceof HoldingStep.Failed;
        }
        HoldingStep next = tick();
        return next instanceof HoldingStep.Done || next instanceof HoldingStep.Failed;
    }

    public void suspend() {
        if (holding == null) {
            holding = node.acquire(context);
        }
        holding.hardRelease(context, Disposition.SUSPEND, PreemptReason.URGE);
        holding.suspended(context, PreemptReason.URGE);
    }

    public void resume() {
        holding.resumed(context);
        state = new HoldingStep.Running();
    }

    public HoldingFixture hardUrge() {
        var pack = context.worker().pack();
        List<ItemStack> before =
                java.util.stream.IntStream.range(0, pack.getContainerSize())
                        .mapToObj(slot -> pack.getItem(slot).copy())
                        .toList();
        int settled = commits;
        suspend();
        holding.hardRelease(context, Disposition.SUSPEND, PreemptReason.URGE);
        for (int slot = 0; slot < before.size(); slot++) {
            if (!ItemStack.matches(before.get(slot), pack.getItem(slot))) {
                throw new AssertionError("hard suspension changed physical cargo in slot " + slot);
            }
        }
        if (commits != settled) {
            throw new AssertionError("hard suspension committed another operation");
        }
        resume();
        return this;
    }

    public void softEnd() {
        holding.requestSoftPreempt(
            context,
            new SoftPreempt(Disposition.END, PreemptReason.CONTROLLER, context.level().getGameTime() + 20));
    }

    public void end(Ending how) {
        if (holding != null) {
            holding.hardRelease(context, Disposition.END, PreemptReason.REVOKED);
        }
        finish(how);
    }

    public void finish(Ending how) {
        node.released(context.level(), context.worker(), how);
        node.ended(context.level(), how);
    }
}
