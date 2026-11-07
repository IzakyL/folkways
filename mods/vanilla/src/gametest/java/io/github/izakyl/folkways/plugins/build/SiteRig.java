package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.Conveyance;
import io.github.izakyl.folkways.core.resident.api.Resident;
import io.github.izakyl.folkways.core.resident.api.ResidentKind;
import io.github.izakyl.folkways.core.runtime.TestActionServices;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.work.api.ActionServices;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.HoldingContext;
import io.github.izakyl.folkways.core.work.api.HoldingStep;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Settlement;
import io.github.izakyl.folkways.core.work.api.WorkAction;
import io.github.izakyl.folkways.core.work.api.WorkHolding;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.internal.Commit;
import io.github.izakyl.folkways.core.work.support.StandardHoldings;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** A build site asking a colony that only writes down what it is asked, for the game tests to answer by hand. */
final class SiteRig implements BuildSite.Asks {

    final List<Grown> asked = new ArrayList<>();
    final List<UUID> withdrawn = new ArrayList<>();
    private final Map<UUID, BiConsumer<UUID, Ending>> heard = new HashMap<>();
    final BuildSite site;
    private final Map<UUID, UUID> holders = new HashMap<>();
    private final Map<UUID, Shift> shifts = new HashMap<>();
    private final Colony colony = (Colony) Proxy.newProxyInstance(SiteRig.class.getClassLoader(),
        new Class<?>[] {Colony.class}, (proxy, method, args) -> switch (method.getName()) {
            case "holder" -> Optional.ofNullable(holders.get((UUID) args[0]));
            case "isHeld" -> holders.containsKey((UUID) args[0]);
            case "isSuspended", "isApproaching" -> false;
            case "hold", "claimAt", "claimOf", "claim", "store" -> Optional.empty();
            case "storesIn", "storeRules", "claims" -> List.of();
            default -> method.isDefault()
                ? java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args) : null;
        });

    @Override
    public Optional<Colony> colony() {
        return Optional.of(colony);
    }

    Shift shift() {
        Node node = asked.getFirst().nodes().getFirst();
        return shifts.computeIfAbsent(node.id(), ignored -> new Shift(node, holders));
    }

    private SiteRig(UUID order, BuildTarget target, TemporaryScaffolds temporary) {
        site = new BuildSite(order, this, target, temporary);
    }

    static SiteRig start(ServerLevel level, UUID order, BuildTarget target) {
        SiteRig rig = new SiteRig(order, target, new TemporaryScaffolds(() -> { }));
        rig.site.start(level, Set.of(), Set.of());
        return rig;
    }

    static SiteRig start(ServerLevel level, BuildTarget target) {
        return start(level, target, new TemporaryScaffolds(() -> { }), Set.of());
    }

    static SiteRig start(ServerLevel level, BuildTarget target, TemporaryScaffolds temporary, Set<BlockPos> met) {
        return start(level, target, temporary, met, Set.of());
    }

    static SiteRig start(ServerLevel level, BuildTarget target, TemporaryScaffolds temporary, Set<BlockPos> met,
            Set<io.github.izakyl.folkways.plugins.build.draft.Joint> joined) {
        SiteRig rig = new SiteRig(UUID.randomUUID(), target, temporary);
        rig.site.start(level, met, joined);
        return rig;
    }

    // The stone ground the site tests build on.
    static void floor(GameTestHelper helper) {
        for (BlockPos cell : BlockPos.betweenClosed(helper.absolutePos(new BlockPos(1, 0, 1)),
                helper.absolutePos(new BlockPos(21, 0, 21)))) {
            helper.getLevel().setBlock(cell, Blocks.STONE.defaultBlockState(), 3);
        }
    }

    static BuildTarget target(ServerLevel level, Map<BlockPos, BlockState> cells) {
        return new BuildTarget(WorldPos.of(level, cells.keySet().iterator().next()).realm(), cells);
    }

    // A body carrying stone and scaffolding enough for any one site.
    static Worker worker(Mob body) {
        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.STONE, 64), new ItemStack(Items.SCAFFOLDING, 64));
        return TestWorker.of(body, new Resident(body.getUUID(), ResourceLocation.withDefaultNamespace("pig")), pack)
            .spill(stack -> { });
    }

    @Override
    public Optional<List<ItemStack>> pack(Worker who) {
        List<ItemStack> items = new ArrayList<>();
        for (int slot = 0; slot < who.pack().getContainerSize(); slot++) {
            items.add(who.pack().getItem(slot).copy());
        }
        return Optional.of(items);
    }

    @Override
    public Optional<List<ItemStack>> pack(UUID worker) {
        return Optional.empty();
    }

    @Override
    public void submit(ResourceKey<Level> dimension, Grown work, BiConsumer<UUID, Ending> ended) {
        asked.add(work);
        work.nodes().forEach(node -> heard.put(node.id(), ended));
    }

    @Override
    public void withdraw(UUID node) {
        withdrawn.add(node);
    }

    Optional<BuildSite.Piece> at(BlockPos cell) {
        return site.pieces().stream()
            .filter(piece -> piece.work().step().map(step -> step.after().containsKey(cell)).orElse(false))
            .findFirst();
    }

    BuildSite.Piece pieceAt(BlockPos cell) {
        return at(cell).orElseThrow(() -> new AssertionError("no piece of work at " + cell.toShortString()));
    }

    BuildAction action(BlockPos cell) {
        return pieceAt(cell).action();
    }

    Step step(BlockPos cell) {
        return pieceAt(cell).work().step().orElseThrow();
    }

    /** Whether the piece with the node {@code first} goes in before the one with {@code then}. */
    boolean ordered(UUID first, UUID then) {
        return site.pieces().stream().filter(piece -> piece.id().equals(then))
            .anyMatch(piece -> site.before(piece).contains(first));
    }

    /** Whether the piece at {@code first} goes in before the piece at {@code then}. */
    boolean before(BlockPos first, BlockPos then) {
        return site.before(pieceAt(then)).contains(pieceAt(first).id());
    }

    /** The cells of every piece that goes in straight before the piece at {@code cell}. */
    List<BlockPos> after(BlockPos cell) {
        Map<UUID, BlockPos> focus = new HashMap<>();
        site.pieces().forEach(piece -> focus.put(piece.id(), piece.work().focus()));
        return site.before(pieceAt(cell)).stream().map(focus::get).filter(java.util.Objects::nonNull).toList();
    }

    /** Whether nothing has to go in before the piece at {@code cell}. */
    boolean free(BlockPos cell) {
        return site.before(pieceAt(cell)).isEmpty();
    }

    void end(Node node, Ending how) {
        site.ended(node.id(), how);
    }

    void end(WorkAction action, Ending how) {
        site.pieces().stream().filter(piece -> piece.action() == action).findFirst()
            .ifPresent(piece -> site.ended(piece.id(), how));
    }

    /** Does the piece at {@code cell} as a worker would, tells the site it ended, and lets the site hear it. */
    Outcome work(GameTestHelper helper, BlockPos cell, Worker worker) {
        BuildAction node = action(cell);
        Outcome outcome = Commit.run(helper.getLevel(), node, worker);
        if (outcome instanceof Outcome.Done) {
            end(node, Ending.DONE);
            site.tick(helper.getLevel());
        }
        return outcome;
    }

    /** Does every piece still out, in the order asked, until none is left or one fails. */
    void workAll(GameTestHelper helper, Worker worker) {
        for (int round = 0; round < 64 && !site.pieces().isEmpty(); round++) {
            for (BuildSite.Piece piece : site.pieces()) {
                Outcome outcome = Commit.run(helper.getLevel(), piece.action(), worker);
                helper.assertTrue(outcome instanceof Outcome.Done, "work asked for can be done, not " + outcome);
                end(piece.action(), Ending.DONE);
            }
            site.tick(helper.getLevel());
        }
    }

    // Observe real settlements while driving the public holding contract.
    static final class Shift {
        private final Node node;
        private final Map<UUID, UUID> holders;
        private WorkHolding holding;
        WorkAction operation;
        Outcome outcome;
        int commits;

        Shift(Node node) {
            this(node, new HashMap<>());
        }

        Shift(Node node, Map<UUID, UUID> holders) {
            this.node = node;
            this.holders = holders;
        }

        HoldingStep tick(ServerLevel level, Worker worker) {
            HoldingContext context = context(level, worker);
            if (holding == null) {
                holding = node.acquire(context);
            }
            holders.put(node.id(), worker.resident().id());
            return holding.tick(context);
        }

        Outcome step(ServerLevel level, Worker worker) {
            int before = commits;
            for (int tick = 0; tick < 4096 && commits == before; tick++) {
                if (tick(level, worker) instanceof HoldingStep.Done) {
                    throw new AssertionError("holding ended before its next settlement");
                }
            }
            if (commits == before) {
                throw new AssertionError("holding did not settle within its work budget");
            }
            return outcome;
        }

        private HoldingContext context(ServerLevel level, Worker worker) {
            Body body = new Body() {
                public Mob mob() {
                    return worker.body();
                }

                public ResidentKind kind() {
                    return PersonBody.person();
                }
            };
            ActionServices services = new TestActionServices() {
                public Optional<Conveyance> approach(HoldingContext context, WorkAction action,
                        Set<WorldPos> footings) {
                    return Optional.of(body.kind().locomotion().along(action.spec().pace(), noWay()));
                }

            };
            Settlement settlement = new Settlement() {
                public Outcome commit(WorkAction action) {
                    operation = action;
                    outcome = Commit.run(level, action, worker);
                    commits++;
                    return outcome;
                }

                public void stow(List<ItemStack> made) {
                    made.forEach(stack -> worker.spill(Containers.insert(worker.pack(), stack.copy())));
                }
            };
            return new HoldingContext(level, body, worker, services, settlement, StandardHoldings.INSTANCE);
        }
    }

}
