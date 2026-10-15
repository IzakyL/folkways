package io.github.izakyl.folkways.plugins.cookery.tools;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.ChoppingBoardBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Delivery;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Hold;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Produce;
import io.github.izakyl.folkways.core.api.work.Refinement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.ToolUse;
import io.github.izakyl.folkways.core.api.work.Tools;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Workshop;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.colony.ColonyRegistry;
import io.github.izakyl.folkways.core.engine.colony.ColonyWorks;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.core.engine.plan.Crew;
import io.github.izakyl.folkways.core.engine.plan.Known;
import io.github.izakyl.folkways.core.engine.plan.Message;
import io.github.izakyl.folkways.core.engine.plan.PlanningFixture;
import io.github.izakyl.folkways.core.engine.plan.Solving;
import io.github.izakyl.folkways.core.engine.plan.Stock;
import io.github.izakyl.folkways.plugins.cookery.CookeryContent;
import io.github.izakyl.folkways.plugins.cookery.KitchenBookings;
import io.github.izakyl.folkways.plugins.cookery.board.ChoppingBoardWorkshops;
import io.github.izakyl.folkways.plugins.cookery.racks.KitchenRacks;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.wares.WaresContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TaskToolsGameTests {
    private static final ToolNeed KNIFE = new ToolNeed.Tagged(TagMod.KITCHEN_KNIFE);
    private static final ItemSpec KNIVES = ItemSpec.of(TagMod.KITCHEN_KNIFE);

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(TaskToolsGameTests.class);
    }

    @GameTest(template = "empty")
    public static void twoRealBoardOperationsBorrowOneNamedKnifeAndReturnItToTheRack(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            ItemStack knife = rig.namedKnife();
            rig.rack.setItem(0, knife.copy());
            rig.body.pack().setItem(0, new ItemStack(Items.CHICKEN, 2));
            Node first = rig.board();
            Node second = rig.board();
            helper.assertTrue(first.spec().tools().isEmpty() && second.spec().tools().isEmpty(),
                "food parents can be assigned before borrowing, without giving every crafting worker a knife");
            helper.assertTrue(first.spec().needs().stream().noneMatch(need -> Goods.overlap(need.spec(), KNIVES)),
                "the reusable knife is never a consumed food ingredient");
            rig.finish(first);
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), KNIVES), 0L, "the first task physically returns its knife");
            helper.assertTrue(rig.rack.getItem(0).getHoverName().getString().equals("Only shared knife"),
                "the real rack receives the original named knife");
            helper.assertTrue(second.ready(rig.level), "the second task can now borrow the same physical knife");
            rig.finish(second);
            helper.assertValueEqual(Goods.countIn(rig.rack, KNIVES) + Goods.countIn(rig.chest, KNIVES)
                + Goods.countIn(rig.body.pack(), KNIVES), 1L, "two operations conserve exactly one physical knife");
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), Goods.specOf(ModItems.RAW_CUT_SMALL_MEATS.get().getDefaultInstance())),
                6L, "two real native board batches produce six meat pieces");
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), Goods.specOf(Items.BONE.getDefaultInstance())),
                2L, "both native secondary products are delivered once");
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), Goods.specOf(Items.CHICKEN.getDefaultInstance())),
                0L, "both real chicken inputs are spent once");
            helper.assertTrue(rig.rack.getItem(0).getDamageValue() >= knife.getDamageValue(),
                "native wear is retained by the returned tool");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aWholeTwoOperationFoodPlanDoesNotBudgetTheOnlyKnifeTwice(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            var fixture = new PlanningFixture(helper, 36);
            var source = new Stash(fixture.source);
            var stock = new Stock(List.of(new Stock.Holding(source, new ItemStack(Items.CHICKEN, 2)),
                new Stock.Holding(source, rig.namedKnife())), Map.of(source, 27));
            var crafting = rig.body.licences().of(WaresContent.trade()).orElseThrow();
            var hauling = rig.body.licences().of(Vocations.required(Vocations.HAULING)).orElseThrow();
            var hand = new Crew.Hand(rig.body.resident(), fixture.source, 36, 36, 1,
                List.of(crafting, hauling), List.of(), Optional.empty());
            Known known = new Known(fixture.situation(stock, List.of(rig.tools.decorate(rig.workshop()))), List.of(hand));
            ItemSpec meat = Goods.specOf(ModItems.RAW_CUT_SMALL_MEATS.get().getDefaultInstance());
            NodeSpec spec = NodeSpec.of(UUID.randomUUID(), CookeryContent.ID, new WorkSite.AtBlock(fixture.target),
                Stances.at(fixture.target), Workload.Once.of(1)).needs(new Need(meat, 6)).done();
            Node consumer = new Node() {
                public NodeSpec spec() { return spec; }
                public Outcome commit(ServerLevel level, Worker who) { return Outcome.done(); }
            };
            Solving solver = new Solving();
            try {
                var result = solver.handle(known, List.of(new Message.Submitted(CookeryContent.ID, Grown.of(consumer))), 0);
                helper.assertTrue(result.diagnosis().shortfalls().isEmpty() && result.diagnosis().unassigned().isEmpty()
                    && result.diagnosis().refused().isEmpty(), "the real complete food plan is feasible: " + result.diagnosis());
                List<Node> tasks = result.woven().delta().put().values().stream().map(vertex -> vertex.node())
                    .filter(node -> node instanceof ToolWork).toList();
                helper.assertValueEqual(tasks.size(), 2, "the real planner schedules two physical board batches");
                helper.assertTrue(tasks.stream().flatMap(node -> node.spec().needs().stream())
                    .noneMatch(need -> Goods.overlap(need.spec(), KNIVES)), "neither batch consumes a fictional copy of the knife");
            } finally {
                solver.closed();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void missingToolsKeepTheOnlyCraftingWorkerFreeAndMergeOneBootstrapOrder(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            List<Node> submitted = new ArrayList<>();
            rig.colony.works().listen(rig.level.dimension(), new ColonyWorks.Listening() {
                public void submitted(ResourceLocation owner, Grown work) { submitted.addAll(work.nodes()); }
                public void withdrawn(ResourceLocation owner, UUID node) { }
                public void offered() { }
            });
            Node first = rig.simple(new AtomicInteger(), false);
            Node second = rig.simple(new AtomicInteger(), false);
            helper.assertTrue(!first.ready(rig.level) && !second.ready(rig.level),
                "a tool-less food task never occupies the sole crafting worker inside Borrow");
            for (int tick = 0; tick < 60; tick++) {
                rig.tools.tick(rig.level.getServer());
            }
            List<Delivery> orders = submitted.stream().filter(node -> node instanceof Delivery)
                .map(node -> (Delivery) node).toList();
            helper.assertValueEqual(orders.size(), 1, "two waiting tasks produce one stable real bootstrap demand");
            helper.assertValueEqual(orders.getFirst().goods().count(), 1L, "the merged demand asks for one knife only");
            helper.assertTrue(Goods.overlap(orders.getFirst().goods().spec(), KNIVES), "the bootstrap asks for a permitted knife");
            helper.assertTrue(orders.getFirst().spec().tools().isEmpty(), "the knife's delivery does not itself require a knife");
            helper.assertTrue(rig.body.pack().isEmpty() && rig.rack.isEmpty() && rig.chest.isEmpty(),
                "planning a missing tool creates no physical inventory");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void cancellationAndReloadRecoverOnlyTheActualBorrowedTool(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            rig.rack.setItem(0, rig.namedKnife());
            AtomicInteger executions = new AtomicInteger();
            Node work = rig.simple(executions, false);
            rig.step(work);
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), KNIVES), 1L, "the borrow really moved the rack's knife");
            helper.assertTrue(io.github.izakyl.folkways.core.api.resident.body.TaskTools.holds(rig.body.id(), rig.body.pack().getItem(0)),
                "the actual borrowed stack is protected from ordinary cargo unloading");
            work.ended(rig.level, Ending.REVOKED);
            rig.tools.closed(Closing.SHUTDOWN);
            TaskTools loaded = new TaskTools(rig.colony.works(), rig.level.getServer());
            try {
                helper.assertTrue(io.github.izakyl.folkways.core.api.resident.body.TaskTools.holds(rig.body.id(), rig.body.pack().getItem(0)),
                    "reload protects the saved physical return before the next inventory survey");
                List<Grown> recovery = loaded.goals(rig.colony.works().view(rig.level));
                helper.assertValueEqual(recovery.size(), 1, "one saved loan has one return task");
                rig.finish(recovery.getFirst().nodes().getFirst());
                helper.assertValueEqual(executions.get(), 0, "a cancelled food operation is never replayed by tool recovery");
                helper.assertValueEqual(Goods.countIn(rig.rack, KNIVES), 1L, "the same named knife returns to the native rack");
                helper.assertTrue(rig.body.pack().isEmpty(), "the recovery leaves no duplicated knife in the pack");
                helper.assertTrue(loaded.goals(rig.colony.works().view(rig.level)).isEmpty(), "a completed return does not replay after another scan");
            } finally {
                loaded.closed(Closing.SHUTDOWN);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aBusyToolWaitsAndAFullRackReturnsToOrdinaryStorage(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            rig.rack.setItem(0, rig.namedKnife());
            Node first = rig.simple(new AtomicInteger(), false);
            Node second = rig.simple(new AtomicInteger(), false);
            rig.step(first);
            helper.assertTrue(!second.ready(rig.level), "a borrowed knife cannot be borrowed by another task");
            var state = rig.tools.pool.request(rig.colony.colonyId(), second.id(), ((ToolWork) second).families().getFirst(),
                rig.tools.stock.read(rig.level, KNIVES).inventory());
            helper.assertTrue(state.replenishment().isEmpty(), "a busy real knife never triggers a second manufacture");
            rig.rack.setItem(0, new ItemStack(ModItems.KITCHEN_SHOVEL.get()));
            rig.rack.setItem(1, new ItemStack(ModItems.SICKLE.get()));
            rig.finish(first);
            helper.assertValueEqual(Goods.countIn(rig.chest, KNIVES), 1L, "a full native rack returns the original knife to an ordinary store");
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), KNIVES), 0L, "fallback really removes the borrowed pack stack");
            helper.assertTrue(second.ready(rig.level), "the returned fallback knife becomes borrowable again");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aPlayerChangingTheReservedToolDoesNotMoveOrDuplicateEitherStack(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            rig.rack.setItem(0, rig.namedKnife());
            Node work = rig.simple(new AtomicInteger(), false);
            var turn = ((Workload.Within) work.spec().workload()).next(rig.level, rig.worker);
            Node borrow = ((Workload.Within.Turn.Piece) turn).node();
            rig.rack.getItem(0).set(DataComponents.CUSTOM_NAME, Component.literal("Player changed this knife"));
            rig.stand(borrow);
            Outcome result = Commit.run(rig.level, borrow, rig.worker);
            helper.assertTrue(result instanceof Outcome.Failed, "the borrowed source is rechecked including components at commit");
            ((Workload.Within) work.spec().workload()).ended(rig.level, borrow, Ending.DROPPED);
            helper.assertTrue(rig.body.pack().isEmpty(), "a stale reservation does not inject a knife into the worker");
            helper.assertTrue(rig.rack.getItem(0).getHoverName().getString().equals("Player changed this knife"),
                "the player's real changed tool stays in its native rack");
            helper.assertValueEqual(Goods.countIn(rig.rack, KNIVES), 1L, "there is still exactly one real tool");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aDroppedBorrowedToolRemainsPhysicalStockAndDoesNotTriggerManufacture(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            rig.rack.setItem(0, rig.namedKnife());
            Node work = rig.simple(new AtomicInteger(), false);
            rig.step(work);
            ItemStack taken = Containers.extract(rig.body.pack(), stack -> stack.is(TagMod.KITCHEN_KNIFE), 1);
            var drop = new net.minecraft.world.entity.item.ItemEntity(rig.level, rig.body.mob().getX(),
                rig.body.mob().getY(), rig.body.mob().getZ(), taken);
            rig.level.addFreshEntity(drop);
            work.ended(rig.level, Ending.REVOKED);
            Node recovery = rig.tools.goals(rig.colony.works().view(rig.level)).getFirst().nodes().getFirst();
            rig.finish(recovery);
            Node next = rig.simple(new AtomicInteger(), false);
            var state = rig.tools.pool.request(rig.colony.colonyId(), next.id(), ((ToolWork) next).families().getFirst(),
                rig.tools.stock.read(rig.level, KNIVES).inventory());
            helper.assertTrue(state.status() == KitchenTools.Status.BUSY && state.replenishment().isEmpty(),
                "the observed physical drop is unavailable stock, never proof that the tool was destroyed");
            helper.assertValueEqual(drop.getItem().getCount(), 1, "waiting does not consume or copy the real dropped knife");
            drop.discard();
        }
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void nativeBreakageReleasesTheLoanAndAllowsOneReplacement(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            ItemStack lastUse = rig.namedKnife();
            lastUse.setDamageValue(lastUse.getMaxDamage() - 1);
            rig.rack.setItem(0, lastUse);
            AtomicInteger executions = new AtomicInteger();
            Node work = rig.simple(executions, true);
            rig.finish(work);
            helper.assertValueEqual(executions.get(), 1, "the last real tool use is executed exactly once");
            helper.assertTrue(rig.tools.pool.leases(rig.colony.colonyId()).isEmpty(), "confirmed native breakage releases the physical loan");
            helper.assertValueEqual(Goods.countIn(rig.rack, KNIVES) + Goods.countIn(rig.chest, KNIVES)
                + Goods.countIn(rig.body.pack(), KNIVES), 0L, "native destruction does not return a fresh replacement");
            Node next = rig.simple(new AtomicInteger(), false);
            helper.assertTrue(!next.ready(rig.level), "the next task waits outside the worker until replacement exists");
            var state = rig.tools.pool.request(rig.colony.colonyId(), next.id(), ((ToolWork) next).families().getFirst(),
                rig.tools.stock.read(rig.level, KNIVES).inventory());
            helper.assertValueEqual(state.replenishment().orElseThrow().count(), 1L, "only a confirmed lost tool permits one replacement");
        }
        helper.succeed();
    }

    private static final class Rig implements AutoCloseable {
        final GameTestHelper helper;
        final ServerLevel level;
        final BlockPos boardCell;
        final WorldPos boardAt;
        final WorldPos rackAt;
        final WorldPos chestAt;
        final Container rack;
        final Container chest;
        final Body body;
        final ColonyData colony;
        final TaskTools tools;
        final KitchenBookings bookings = new KitchenBookings();
        final Map<UUID, List<Hold>> holds = new java.util.LinkedHashMap<>();
        final Worker worker;

        Rig(GameTestHelper helper) {
            this.helper = helper;
            level = helper.getLevel();
            boardCell = helper.absolutePos(new BlockPos(3, 1, 3));
            for (BlockPos floor : BlockPos.betweenClosed(boardCell.offset(-4, -1, -4), boardCell.offset(4, -1, 4))) {
                level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
                for (int up = 1; up <= 3; up++) {
                    level.setBlockAndUpdate(floor.above(up), Blocks.AIR.defaultBlockState());
                }
            }
            level.setBlockAndUpdate(boardCell, ModBlocks.CHOPPING_BOARD.get().defaultBlockState());
            BlockPos rackCell = boardCell.west(3);
            BlockPos chestCell = boardCell.east(3);
            level.setBlockAndUpdate(rackCell, ModBlocks.KITCHENWARE_RACKS.get().defaultBlockState());
            level.setBlockAndUpdate(chestCell, Blocks.CHEST.defaultBlockState());
            KitchenRacks.register();
            TaskTools.register();
            rack = Containers.at(level, rackCell).orElseThrow();
            chest = Containers.at(level, chestCell).orElseThrow();
            boardAt = WorldPos.of(level, boardCell);
            rackAt = WorldPos.of(level, rackCell);
            chestAt = WorldPos.of(level, chestCell);
            body = PersonBody.RESIDENT.get().create(level);
            body.mob().setNoAi(true);
            body.mob().moveTo(boardCell.getX() + 0.5, boardCell.getY(), boardCell.getZ() + 1.5);
            level.addFreshEntity(body.mob());
            colony = ColonyRegistry.get(level.getServer()).mint(level.getServer());
            colony.works().hold(body.kind().id(), Held.Entity.of(body.mob())).orElseThrow();
            colony.works().stores(CookeryContent.ID, level.dimension(), List.of(chestAt, rackAt));
            tools = new TaskTools(colony.works(), level.getServer());
            worker = new Worker() {
                public Resident resident() { return body.resident(); }
                public Mob body() { return Rig.this.body.mob(); }
                public Container pack() { return Rig.this.body.pack(); }
                public List<Store> within() { return List.of(new Store(rackAt, rack), new Store(chestAt, chest)); }
                public ItemStack held(ToolNeed need) { return Tools.from(Rig.this.body.pack().contents(), need).orElse(ItemStack.EMPTY); }
                public void spill(ItemStack stack) { if (!stack.isEmpty()) { throw new AssertionError("unexpected spill: " + stack); } }
                public int rankOf(String perk) { return 0; }
                public List<Node> route() { return List.of(); }
                public Placement placement() { return Placement.NOWHERE; }
            };
        }

        ItemStack namedKnife() {
            ItemStack knife = new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get());
            knife.setDamageValue(23);
            knife.set(DataComponents.CUSTOM_NAME, Component.literal("Only shared knife"));
            return knife;
        }

        Workshop workshop() {
            return ChoppingBoardWorkshops.freeze(UUID.randomUUID(), colony.colonyId(), level, boardAt,
                Stances.at(level, boardCell.south()), bookings, () -> { }).orElseThrow();
        }

        Node board() {
            Produce intent = Produce.of(CookeryContent.ID, new WorkSite.AtBlock(boardAt),
                Goods.specOf(ModItems.RAW_CUT_SMALL_MEATS.get().getDefaultInstance()), 1, Produce.UNBOUNDED);
            Refinement.Change change = tools.decorate(workshop()).options(intent, Grown.of(intent)).stream()
                .filter(option -> option.replacement().nodes().getFirst().spec().needs().stream()
                    .allMatch(need -> Goods.countIn(body.pack(), need.spec()) >= need.count()))
                .findFirst().orElseThrow();
            for (Hold hold : change.holds()) {
                helper.assertTrue(hold.take().isEmpty(), "the actual board turn is booked");

            }
            Node work = change.replacement().nodes().getFirst();
            holds.put(work.id(), change.holds());
            helper.assertTrue(work.planned(level).isEmpty(), "the actual shared-tool task is adopted");
            return work;
        }

        Node simple(AtomicInteger executions, boolean breaks) {
            NodeSpec spec = NodeSpec.of(UUID.randomUUID(), CookeryContent.ID, new WorkSite.AtBlock(boardAt),
                Stances.at(level, boardCell.south()), Workload.Once.of(1)).tools(new ToolUse(KNIFE, 0))
                .gives(new Amount(Goods.specOf(Items.PAPER.getDefaultInstance()), 1)).done();
            Node action = new Node() {
                public NodeSpec spec() { return spec; }
                public Outcome commit(ServerLevel level, Worker who) {
                    ItemStack tool = who.held(KNIFE);
                    helper.assertTrue(!tool.isEmpty(), "the original action sees a physical held shared knife");
                    if (breaks) {
                        tool.hurtAndBreak(1, level, who.body(), ignored -> { });
                    }
                    executions.incrementAndGet();
                    return new Outcome.Done(List.of(), List.of(new ItemStack(Items.PAPER)), Optional.empty());
                }
            };
            Node wrapped = tools.decorate(Grown.of(action)).nodes().getFirst();
            helper.assertTrue(wrapped.planned(level).isEmpty(), "the tool-requiring action is adopted");
            return wrapped;
        }

        void stand(Node piece) {
            piece.spec().stances().cells().stream().findFirst().ifPresent(at ->
                body.mob().moveTo(at.cell().getX() + 0.5, at.cell().getY(), at.cell().getZ() + 0.5));
        }

        boolean step(Node work) {
            Workload.Within within = (Workload.Within) work.spec().workload();
            var turn = within.next(level, worker);
            if (turn instanceof Workload.Within.Turn.Leave) {
                helper.assertTrue(work.commit(level, worker) instanceof Outcome.Done, "the completed parent settles without replaying its action");
                work.ended(level, Ending.DONE);
                for (Hold hold : holds.getOrDefault(work.id(), List.of())) {
                    hold.release(Ending.DONE);
                }
                return true;
            }
            helper.assertTrue(turn instanceof Workload.Within.Turn.Piece, "the real shared-tool task does not stall: " + turn);
            Node piece = ((Workload.Within.Turn.Piece) turn).node();
            stand(piece);
            helper.assertTrue(piece.planned(level).isEmpty(), "the real preparation/action piece can start");
            Outcome outcome = Commit.run(level, piece, worker);
            helper.assertTrue(outcome instanceof Outcome.Done, "the actual physical commit succeeds: " + outcome);
            for (ItemStack product : ((Outcome.Done) outcome).made()) {
                helper.assertTrue(Containers.insert(body.pack(), product).isEmpty(), "all native food products fit in reserved pack space");
            }
            piece.released(level, worker, Ending.DONE);
            piece.ended(level, Ending.DONE);
            within.ended(level, piece, Ending.DONE);
            return false;
        }

        void finish(Node work) {
            for (int step = 0; step < 32; step++) {
                if (step(work)) {
                    return;
                }
            }
            throw new AssertionError("the shared-tool task never completed");
        }

        @Override
        public void close() {
            tools.closed(Closing.SHUTDOWN);
            holds.values().stream().flatMap(List::stream).forEach(hold -> hold.release(Ending.REVOKED));
            ColonyRegistry.get(level.getServer()).raze(level.getServer(), colony.colonyId());
            body.mob().discard();
        }
    }
}
