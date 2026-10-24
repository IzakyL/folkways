package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Stand;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.api.work.Before;
import io.github.izakyl.folkways.core.api.work.Delivery;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workshop;
import io.github.izakyl.folkways.core.engine.labor.ColonyLabor;
import io.github.izakyl.folkways.core.engine.plan.haul.Haul;
import io.github.izakyl.folkways.core.engine.travel.Faring;
import io.github.izakyl.folkways.core.engine.travel.Journey;
import io.github.izakyl.folkways.core.engine.travel.Ways;
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
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

public final class PlanningFixture {
    public final WorldPos source;
    public final WorldPos target;
    public final Stands stands;
    public final Crew crew;
    public final Worker worker;
    public final Ways ways;

    public PlanningFixture(GameTestHelper helper) {
        this(helper, 6);
    }

    public PlanningFixture(GameTestHelper helper, int packCells) {
        source = WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 1)));
        target = source.at(source.cell().offset(6, 0, 0));
        stands = Stands.building()
            .container(new Stash(source), Set.of(new Stand(source)))
            .container(new Stash(target), Set.of(new Stand(target))).done();
        var body = PersonBody.RESIDENT.get().create(helper.getLevel());
        var licence = body.licences().of(Vocations.required(Vocations.HAULING)).orElseThrow();
        crew = new Crew(List.of(new Crew.Hand(body.resident(), source, packCells, packCells, 1,
            List.of(licence), List.of(), Optional.empty())));
        worker = new Worker() {
            final Container pack = new SimpleContainer(packCells);
            public Resident resident() { return body.resident(); }
            public Mob body() { return body; }
            public Container pack() { return pack; }
            public List<Store> within() { return List.of(); }
            public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
            public void spill(ItemStack stack) { throw new AssertionError("unexpected spill: " + stack); }
            public Effects effects() { return Effects.NONE; }
            public List<Node> route() { return List.of(); }
            public Placement placement() { return Placement.NOWHERE; }
        };
        ways = new Ways() {
            public Faring journey(Resident who, WorldPos from, Set<WorldPos> goals) {
                return Faring.yes(Journey.afoot(1, goals));
            }
            public Faring anyoneReaches(Set<WorldPos> goals) {
                return Faring.yes(Journey.afoot(1, goals));
            }
        };
    }

    public static Worker placed(Worker who, Placement placement, List<Worker.Store> stores) {
        return new Worker() {
            public Resident resident() { return who.resident(); }
            public Mob body() { return who.body(); }
            public Container pack() { return who.pack(); }
            public List<Store> within() { return stores; }
            public ItemStack held(ToolNeed need) { return who.held(need); }
            public void spill(ItemStack stack) { who.spill(stack); }
            public Effects effects() { return who.effects(); }
            public List<Node> route() { return who.route(); }
            public Placement placement() { return placement; }
        };
    }

    public Situation situation(Stock stock, List<Workshop> workshops) {
        return new Situation(ways, stock, workshops, stands);
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
            return complete() ? List.of() : List.of(Grown.of(Delivery.to(id, Haul.DOMAIN, into, stances, goods,
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
        woven(labor, level, batch, new Planner.Woven(delta, new Crew(List.of()), null, false, List.of(), List.of(),
            List.of(), Asks.NONE, new Planner.Timing(0, 0), delta.put().size()));
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
