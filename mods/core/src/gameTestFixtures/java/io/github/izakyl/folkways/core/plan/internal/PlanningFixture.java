package io.github.izakyl.folkways.core.plan.internal;

import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.inventory.internal.Stands;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Before;
import io.github.izakyl.folkways.core.plan.api.Delivery;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.resident.api.Resident;
import io.github.izakyl.folkways.core.runtime.ColonyLabor;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.travel.internal.Faring;
import io.github.izakyl.folkways.core.travel.internal.Journey;
import io.github.izakyl.folkways.core.travel.internal.Ways;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.internal.HaulWork;
import io.github.izakyl.folkways.core.world.api.Stand;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.level.block.Blocks;

public final class PlanningFixture {
    public final WorldPos source;
    public final WorldPos target;
    public final Stands stands;
    public final Crew crew;
    public final Worker worker;
    public final Ways ways;
    private final ServerLevel level;

    public PlanningFixture(GameTestHelper helper) {
        this(helper, 6);
    }

    public PlanningFixture(GameTestHelper helper, int packCells) {
        level = helper.getLevel();
        source = WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 1)));
        target = source.at(source.cell().offset(6, 0, 0));
        stands = Stands.building()
            .container(new Stash(source), Set.of(new Stand(source)))
            .container(new Stash(target), Set.of(new Stand(target))).done();
        var body = PersonBody.RESIDENT.get().create(helper.getLevel());
        var licence = body.licences().of(Vocations.required(Vocations.HAULING)).orElseThrow();
        crew = new Crew(List.of(new Crew.Hand(body.resident(), source, packCells, packCells, 1,
            List.of(licence), List.of(), Optional.empty())));
        worker = TestWorker.of(body, body.resident(), new SimpleContainer(packCells));
        ways = afoot(1);
    }

    // Ways that reach every goal from anywhere on foot, in the same number of ticks.
    public static Ways afoot(int ticks) {
        return new Ways() {
            public Faring journey(Resident who, WorldPos from, Set<WorldPos> goals) {
                return Faring.yes(Journey.afoot(ticks, goals));
            }

            public Faring anyoneReaches(Set<WorldPos> goals) {
                return Faring.yes(Journey.afoot(ticks, goals));
            }
            public io.github.izakyl.folkways.core.transport.api.TransportTravel estimates() {
                return (kind, from, to, departure) -> from.sameRealm(to)
                    ? new io.github.izakyl.folkways.core.transport.api.TransportTravel.Yes(
                        Transfer.walk(from, new io.github.izakyl.folkways.core.work.api.WorkSite.AtBlock(to)), 0)
                    : io.github.izakyl.folkways.core.transport.api.TransportTravel.LATER;
            }
        };
    }

    public record Barrels(Container source, Container target) {
    }

    // Barrels stood at the source and the target, for stock the plan really takes from and puts into.
    public Barrels barrels() {
        level.setBlockAndUpdate(source.cell(), Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(target.cell(), Blocks.BARREL.defaultBlockState());
        return new Barrels(Containers.at(level, source.cell()).orElseThrow(),
            Containers.at(level, target.cell()).orElseThrow());
    }

    public Situation situation(Stock stock, List<Workshop> workshops) {
        return new Situation(ways, stock, workshops, stands);
    }

    // With transports published as they would be by the colony, at the given time.
    public Situation situation(Stock stock, List<Workshop> workshops,
                               io.github.izakyl.folkways.core.transport.api.TransportPublication transports,
                               long at) {
        return new Situation(ways, stock, workshops, stands, () -> transports, at);
    }

    public static final class Owed {

        private final UUID id = UUID.randomUUID();
        private final WorkSite into;
        private final Stances stances;
        private final ItemSpec goods;
        private final AtomicLong remaining;

        public Owed(WorkSite into, Stances stances, ItemSpec goods, long count) {
            this.into = into;
            this.stances = stances;
            this.goods = goods;
            this.remaining = new AtomicLong(count);
        }

        public UUID id() {
            return id;
        }

        public List<Grown> goals() {
            return complete() ? List.of() : List.of(Grown.of(Delivery.to(id, HaulWork.DOMAIN, into, stances, goods,
                remaining.get(), count -> remaining.addAndGet(-count))));
        }

        public long remaining() {
            return remaining.get();
        }

        public boolean complete() {
            return remaining.get() <= 0;
        }
    }

    public static final class Rounds {

        public static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_test", "rounds");

        private final Solving planner;
        private final Map<UUID, Grown> standing = new LinkedHashMap<>();
        private final Map<UUID, UUID> told = new LinkedHashMap<>();
        private final Set<UUID> planted = new LinkedHashSet<>();

        public Rounds() {
            this(new Claims(), new Cooldowns());
        }

        public Rounds(Claims claims, Cooldowns cooldowns) {
            this.planner = new Solving(claims, cooldowns);
        }

        public Planned solve(Situation now, Crew crew, List<Grown> goals, List<Message> events, long tick) {
            List<Message> inbox = new ArrayList<>();
            for (Crew.Hand hand : crew.hands()) {
                UUID held = hand.holding().orElse(null);
                if (held != null && !held.equals(told.get(hand.id()))) {
                    inbox.add(new Message.Started(held, hand.id()));
                }
                if (held == null) {
                    told.remove(hand.id());
                } else {
                    told.put(hand.id(), held);
                }
            }
            for (Message event : events) {
                inbox.add(event);
                if (event instanceof Message.Finished(UUID node) && standing.remove(node) != null) {
                    planted.remove(node);
                }
            }
            Map<UUID, Grown> offered = new LinkedHashMap<>();
            for (Grown goal : goals) {
                offered.put(goal.nodes().getFirst().id(), goal);
            }
            for (UUID id : List.copyOf(standing.keySet())) {
                if (!offered.containsKey(id)) {
                    standing.remove(id);
                    planted.remove(id);
                    inbox.add(new Message.Withdrawn(OWNER, id));
                }
            }
            offered.forEach((id, goal) -> {
                if (standing.putIfAbsent(id, goal) == null) {
                    inbox.add(new Message.Submitted(OWNER, goal));
                }
            });
            inbox.add(Message.ANSWERED);
            Planned solved = planner.handle(new Known(now, crew.hands()), inbox, tick);
            // What the plan kept to itself ends as the colony's labor ends it, and its id with it.
            for (Gone gone : solved.weave().gone()) {
                gone.node().ended(null, gone.how());
                if (standing.remove(gone.node().id()) != null) {
                    planted.remove(gone.node().id());
                }
            }
            for (UUID id : List.copyOf(standing.keySet())) {
                if (solved.weave().vertices().containsKey(id)) {
                    planted.add(id);
                } else if (planted.remove(id)) {
                    standing.remove(id);
                }
            }
            return solved;
        }

        public Solving planner() {
            return planner;
        }

        public void closed() {
            planner.closed();
        }
    }

    // What one round of planning made, handed to the colony's labor as its own stages hand it: the weave's change,
    // then the routes laid for it.
    public static void apply(ColonyLabor labor, ServerLevel level, Planned planned) {
        woven(labor, level, List.of(), planned.woven());
        laid(labor, planned.laid());
    }

    // A change to the plan made by hand, handed to the colony's labor as if the weave had made it out of `batch`.
    public static void apply(ColonyLabor labor, ServerLevel level, List<Message> batch, WeaveDelta delta) {
        apply(labor, level, batch, delta, Map.of());
    }

    public static void apply(ColonyLabor labor, ServerLevel level, List<Message> batch, WeaveDelta delta,
            Map<UUID, List<Claim>> claims) {
        woven(labor, level, batch, new Planner.Woven(delta, new Crew(List.of()), null, false, List.of(), List.of(),
            List.of(), Asks.NONE, new Planner.Timing(0, 0), delta.put().size(), claims));
    }

    // A whole plan handed to the colony's labor, as tests once handed it one: the change from the plan it holds now.
    // Work not among `vertices` goes, the rest is put, `fresh` among it grown afresh; `held` is what the owner now
    // keeps off the plan.
    public static void applyWhole(ColonyLabor labor, ServerLevel level, List<Message> batch,
                                  Map<UUID, Vertex> vertices, List<Before> links, List<Set<UUID>> groups,
                                  Set<UUID> fresh, List<Gone> gone, Set<UUID> held) {
        WeaveView now = graphOf(labor);
        Set<UUID> removed = new LinkedHashSet<>(now.vertices().keySet());
        removed.removeAll(vertices.keySet());
        WeaveDelta whole = Shown.delta(vertices, links, groups, Map.of(), fresh, gone, Set.of());
        apply(labor, level, batch, new WeaveDelta(whole.put(), removed, whole.linked(), Set.of(), whole.groups(),
            whole.pins(), whole.fresh(), whole.gone(), Optional.of(held)));
    }

    public static void applyWhole(ColonyLabor labor, ServerLevel level, List<Message> batch,
                                  Map<UUID, Vertex> vertices) {
        applyWhole(labor, level, batch, vertices, List.of(), List.of(), Set.of(), List.of(), Set.of());
    }

    private static WeaveView graphOf(ColonyLabor labor) {
        try {
            var field = ColonyLabor.class.getDeclaredField("graph");
            field.setAccessible(true);
            return (WeaveView) field.get(labor);
        } catch (ReflectiveOperationException broken) {
            throw new AssertionError(broken);
        }
    }

    private static void woven(ColonyLabor labor, ServerLevel level, List<Message> batch, Planner.Woven woven) {
        try {
            var method = ColonyLabor.class.getDeclaredMethod("woven", ServerLevel.class, List.class,
                Planner.Woven.class);
            method.setAccessible(true);
            method.invoke(labor, level, batch, woven);
        } catch (ReflectiveOperationException broken) {
            throw new AssertionError(broken);
        }
    }

    private static void laid(ColonyLabor labor, Scheduler.Scheduled laid) {
        try {
            var method = ColonyLabor.class.getDeclaredMethod("laid", Scheduler.Scheduled.class);
            method.setAccessible(true);
            method.invoke(labor, laid);
        } catch (ReflectiveOperationException broken) {
            throw new AssertionError(broken);
        }
    }
}
