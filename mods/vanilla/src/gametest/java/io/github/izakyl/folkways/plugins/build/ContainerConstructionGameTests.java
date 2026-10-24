package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.StoreRule;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Delivery;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.core.engine.plan.Crew;
import io.github.izakyl.folkways.core.engine.plan.Message;
import io.github.izakyl.folkways.core.engine.plan.PlanningFixture;
import io.github.izakyl.folkways.core.engine.plan.Stock;
import io.github.izakyl.folkways.core.engine.plan.haul.TransferNode;
import io.github.izakyl.folkways.front.api.panel.Board;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.wares.WaresContent;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ContainerConstructionGameTests {

    private static final Map<BlockPos, IItemHandler> HANDLERS = new LinkedHashMap<>();

    @SubscribeEvent
    public static void capabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, BlockEntityType.BARREL,
            (barrel, side) -> HANDLERS.get(barrel.getBlockPos()));
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ContainerConstructionGameTests.class);
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void aBlueprintClearsAFilledChestIntoTheColonyStore(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.place(f.at, Blocks.CHEST.defaultBlockState());
            f.contents(f.at).setItem(0, new ItemStack(Items.DIAMOND, 7));
            f.file(Map.of(f.at, Blocks.AIR.defaultBlockState()));
            f.blocked(BuildRefusal.EMPTYING_CONTAINER);
            f.finish();
            helper.assertTrue(f.level.isEmptyBlock(f.at), "the chest is cleared");
            f.stored(Items.DIAMOND, 7);
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void aDoubleChestIsEmptiedBeforeEitherHalfGoes(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            BlockState left = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.LEFT);
            BlockPos right = f.at.relative(ChestBlock.getConnectedDirection(left));
            f.place(f.at, left);
            f.place(right, left.setValue(ChestBlock.TYPE, ChestType.RIGHT));
            Container chest = f.contents(f.at);
            chest.setItem(0, new ItemStack(Items.DIAMOND, 7));
            chest.setItem(27, new ItemStack(Items.EMERALD, 5));
            f.file(Map.of(f.at, Blocks.AIR.defaultBlockState(), right, Blocks.AIR.defaultBlockState()));
            f.haul();
            helper.assertTrue(f.level.getBlockState(f.at).is(Blocks.CHEST)
                && f.level.getBlockState(right).is(Blocks.CHEST), "both halves remain while contents are being moved");
            f.finish();
            helper.assertTrue(f.level.isEmptyBlock(f.at) && f.level.isEmptyBlock(right), "both halves are cleared");
            f.stored(Items.DIAMOND, 7);
            f.stored(Items.EMERALD, 5);
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void replacingAFilledBarrelDeliversItsContentsFirst(GameTestHelper helper) {
        SchematicPlacement.Adapter installed = SchematicPlacement.adapter();
        try {
            for (SchematicPlacement.Adapter adapter : List.of(installed, new VanillaSchematics())) {
                SchematicPlacement.install(adapter);
                try (Fixture f = new Fixture(helper)) {
                    f.place(f.at, Blocks.BARREL.defaultBlockState());
                    f.contents(f.at).setItem(0, new ItemStack(Items.DIAMOND, 7));
                    f.file(Map.of(f.at, Blocks.DIRT.defaultBlockState()));
                    f.finish();
                    helper.assertTrue(f.level.getBlockState(f.at).is(Blocks.DIRT),
                        "both placement adapters replace the barrel after delivery");
                    f.stored(Items.DIAMOND, 7);
                }
            }
        } finally {
            SchematicPlacement.install(installed);
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void diggingOutAColonyStoreWithholdsThenReleasesIt(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.place(f.at, Blocks.BARREL.defaultBlockState());
            f.contents(f.at).setItem(0, new ItemStack(Items.DIAMOND, 7));
            WorldPos at = WorldPos.of(f.level, f.at);
            UUID holding = f.real.hold(WaresContent.STORE, new Held.Block(at)).orElseThrow().id();
            f.file(Map.of(f.at, Blocks.AIR.defaultBlockState()));
            helper.assertTrue(f.real.holding(holding).isPresent(), "the store is still held before its block goes");
            helper.assertFalse(StoreRule.takes(f.real.storeRules(at)), "ordinary work cannot draw from the retiring store");
            helper.assertFalse(StoreRule.stores(f.real.storeRules(at)), "goods cannot be routed back into it");
            f.finish();
            helper.assertTrue(f.real.holding(holding).isEmpty(), "removal releases the holding");
            helper.assertFalse(f.real.storesIn(f.level.dimension()).contains(at), "its owner withdraws the store");
            f.stored(Items.DIAMOND, 7);
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void noRoomWaitsWithARefusalAndNeverSpills(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.place(f.at, Blocks.CHEST.defaultBlockState());
            f.contents(f.at).setItem(0, new ItemStack(Items.DIAMOND, 7));
            for (int slot = 0; slot < f.store.getContainerSize(); slot++) {
                f.store.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            }
            f.file(Map.of(f.at, Blocks.AIR.defaultBlockState()));
            f.blocked(BuildRefusal.NO_ROOM_TO_EMPTY);
            helper.assertTrue(f.deliveries().isEmpty(), "nothing is lifted without somewhere to put it");
            helper.assertTrue(f.real.holdingAt(WorldPos.of(f.level, f.at)).isEmpty(), "an outside chest need not be joined");
            for (int turn = 0; turn < 3; turn++) {
                helper.assertTrue(Commit.run(f.level, f.site().pieces().getFirst().node(), f.plan.worker)
                    instanceof Outcome.Failed failed && failed.why() == BuildRefusal.NO_ROOM_TO_EMPTY,
                    "even a direct commit cannot spill the full chest");
            }
            helper.assertValueEqual(f.contents(f.at).getItem(0).getCount(), 7, "every item stays in the chest");
            f.noSpills();
            f.store.setItem(0, ItemStack.EMPTY);
            f.finish();
            f.stored(Items.DIAMOND, 7);
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void noStoreLeavesTheContainerUntouched(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.real.holdingAt(WorldPos.of(f.level, f.storeAt)).ifPresent(holding ->
                f.real.release(holding.id(), io.github.izakyl.folkways.core.api.colony.Release.LET_GO));
            f.real.hold(BuildContent.ID, new Held.Block(WorldPos.of(f.level, f.storeAt))).orElseThrow();
            f.place(f.at, Blocks.CHEST.defaultBlockState());
            f.contents(f.at).setItem(0, new ItemStack(Items.DIAMOND, 7));
            f.file(Map.of(f.at, Blocks.AIR.defaultBlockState()));
            f.blocked(BuildRefusal.NO_ROOM_TO_EMPTY);
            f.noSpills();
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void anEmptyContainerNeedsNoCollectionStep(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.place(f.at, Blocks.CHEST.defaultBlockState());
            f.file(Map.of(f.at, Blocks.AIR.defaultBlockState()));
            helper.assertTrue(f.deliveries().isEmpty(), "there is no emptying step for an empty chest");
            helper.assertValueEqual(f.site().pieces().size(), 1, "only the original strip piece is needed");
            f.finish();
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void capabilityOnlyContentsAreCarriedWithTheirComponents(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.place(f.at, Blocks.BARREL.defaultBlockState());
            ItemStackHandler handler = new ItemStackHandler(1);
            ItemStack named = new ItemStack(Items.DIAMOND, 7);
            named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("kept intact"));
            handler.setStackInSlot(0, named);
            HANDLERS.put(f.at, handler);
            f.level.invalidateCapabilities(f.at);
            f.file(Map.of(f.at, Blocks.AIR.defaultBlockState()));
            f.finish();
            helper.assertTrue(handler.getStackInSlot(0).isEmpty(), "the capability's own contents were extracted");
            helper.assertTrue(ItemStack.matches(f.store.getItem(0), named), "all components survive delivery");
            f.stored(Items.DIAMOND, 7);
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void anUnextractableCapabilityWaitsSafely(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.place(f.at, Blocks.BARREL.defaultBlockState());
            ItemStackHandler handler = new ItemStackHandler(1) {
                @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
                    return ItemStack.EMPTY;
                }
            };
            handler.setStackInSlot(0, new ItemStack(Items.DIAMOND, 7));
            HANDLERS.put(f.at, handler);
            f.level.invalidateCapabilities(f.at);
            f.file(Map.of(f.at, Blocks.AIR.defaultBlockState()));
            f.blocked(BuildRefusal.UNREADABLE_CONTAINER);
            helper.assertTrue(f.deliveries().isEmpty(), "nothing tries to force inaccessible contents out");
            helper.assertValueEqual(handler.getStackInSlot(0).getCount(), 7, "the items stay intact");
            f.noSpills();
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void aStoreFilledDuringTheCarryWaitsWithoutSpilling(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.place(f.at, Blocks.CHEST.defaultBlockState());
            f.contents(f.at).setItem(0, new ItemStack(Items.DIAMOND, 7));
            f.file(Map.of(f.at, Blocks.AIR.defaultBlockState()));
            f.haul(true);
            f.finish();
            f.stored(Items.DIAMOND, 7);
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void aContainerAddedAfterPlanningIsEmptiedToo(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.file(Map.of(f.at, Blocks.DIRT.defaultBlockState()));
            f.place(f.at, Blocks.BARREL.defaultBlockState());
            f.contents(f.at).setItem(0, new ItemStack(Items.DIAMOND, 7));
            f.finish();
            f.stored(Items.DIAMOND, 7);
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void aContainerAddedInPlaceOfWaterIsEmptiedBeforeDraining(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.place(f.at, Blocks.WATER.defaultBlockState());
            f.file(Map.of(f.at, Blocks.AIR.defaultBlockState()));
            helper.assertTrue(f.site().pieces().stream().anyMatch(piece -> piece.work().step().orElseThrow().kind()
                == BuildJob.Kind.DRAIN), "the original work drains water");
            f.place(f.at, Blocks.BARREL.defaultBlockState());
            f.contents(f.at).setItem(0, new ItemStack(Items.DIAMOND, 7));
            f.finish();
            f.stored(Items.DIAMOND, 7);
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void anAlreadyCorrectContainerKeepsItsContentsAndHolding(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.place(f.at, Blocks.CHEST.defaultBlockState());
            Container original = f.contents(f.at);
            original.setItem(0, new ItemStack(Items.DIAMOND, 7));
            WorldPos at = WorldPos.of(f.level, f.at);
            UUID holding = f.real.hold(WaresContent.STORE, new Held.Block(at)).orElseThrow().id();
            f.file(Map.of(f.at, Blocks.CHEST.defaultBlockState(), f.at.east(2), Blocks.DIRT.defaultBlockState()));
            helper.assertTrue(f.deliveries().isEmpty(), "completed cells produce no collection work");
            f.finish();
            helper.assertTrue(f.level.getBlockEntity(f.at) == original, "the completed block entity is untouched");
            helper.assertValueEqual(original.getItem(0).getCount(), 7, "completed contents stay where they were");
            helper.assertTrue(f.real.holding(holding).isPresent(), "the completed holding is kept");
            helper.assertTrue(StoreRule.stores(f.real.storeRules(at)), "the completed store stays available");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void aQueuedCollectionCannotEmptyACompletedReplacement(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.place(f.at, Blocks.BARREL.defaultBlockState());
            f.contents(f.at).setItem(0, new ItemStack(Items.DIAMOND, 7));
            f.file(Map.of(f.at, Blocks.CHEST.defaultBlockState()));
            Delivery delivery = (Delivery) f.deliveries().getFirst().nodes().getFirst();
            Grown trip = io.github.izakyl.folkways.core.engine.plan.haul.Haul.DELIVERIES.expand(delivery).orElseThrow();
            f.contents(f.at).clearContent();
            f.place(f.at, Blocks.CHEST.defaultBlockState());
            Container correct = f.contents(f.at);
            correct.setItem(0, new ItemStack(Items.DIAMOND, 7));
            helper.assertTrue(Commit.run(f.level, trip.nodes().getFirst(), f.plan.worker) instanceof Outcome.Failed,
                "even the stale pickup cannot read a replacement container");
            f.presence.settle(f.level.getServer());
            helper.assertTrue(f.presence.site(f.order).isEmpty(), "the correct replacement completes the order");
            helper.assertValueEqual(correct.getItem(0).getCount(), 7, "its contents remain untouched");
            f.noSpills();
        }
        helper.succeed();
    }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final ServerLevel level;
        final Colony real;
        final PlanningFixture plan;
        final BlockPos at;
        final BlockPos storeAt;
        final Container store;
        final BuildPresence presence;
        final Map<UUID, Grown> asked = new LinkedHashMap<>();
        final Map<UUID, BiConsumer<UUID, Ending>> hear = new LinkedHashMap<>();
        UUID order;

        @SuppressWarnings("unchecked")
        Fixture(GameTestHelper helper) {
            this.helper = helper;
            level = helper.getLevel();
            plan = new PlanningFixture(helper);
            at = helper.absolutePos(new BlockPos(5, 2, 5));
            storeAt = at.south(5);
            for (BlockPos cell : BlockPos.betweenClosed(at.offset(-5, -1, -5), at.offset(6, -1, 9))) {
                place(cell, Blocks.STONE.defaultBlockState());
                place(cell.above(), Blocks.AIR.defaultBlockState());
                place(cell.above(2), Blocks.AIR.defaultBlockState());
            }
            place(storeAt, Blocks.BARREL.defaultBlockState());
            store = contents(storeAt);
            real = Colonies.mint(level.getServer());
            real.hold(WaresContent.STORE, new Held.Block(WorldPos.of(level, storeAt))).orElseThrow();
            Colony colony = (Colony) Proxy.newProxyInstance(Colony.class.getClassLoader(), new Class<?>[] {Colony.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("submit")) {
                        Grown work = (Grown) args[2];
                        work.nodes().forEach(node -> {
                            asked.put(node.id(), work);
                            hear.put(node.id(), (BiConsumer<UUID, Ending>) args[3]);
                        });
                        return null;
                    }
                    if (method.getName().equals("withdraw")) {
                        asked.remove(args[1]);
                        return null;
                    }
                    return method.invoke(real, args);
                });
            presence = new BuildPresence(colony, level.getServer());
        }

        void place(BlockPos at, BlockState state) {
            level.setBlock(at, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }

        Container contents(BlockPos at) { return Containers.at(level, at).orElseThrow(); }

        void file(Map<BlockPos, BlockState> cells) {
            List<BlueprintBlock> blocks = cells.entrySet().stream()
                .map(cell -> new BlueprintBlock(cell.getKey().subtract(at), cell.getValue())).toList();
            Blueprint drawing = new Blueprint(UUID.randomUUID(), "emptying", blocks, List.of(), List.of(),
                BlueprintVolume.around(blocks.stream().map(BlueprintBlock::offset).toList()).orElseThrow(), 0, true);
            order = presence.fileOnce(level, drawing, at).orElseThrow().id();
            presence.settle(level.getServer());
        }

        BuildSite site() { return presence.site(order).orElseThrow(); }

        List<Grown> deliveries() {
            return asked.values().stream().distinct().filter(work -> work.nodes().getFirst() instanceof Delivery).toList();
        }

        void blocked(BuildRefusal why) {
            helper.assertTrue(site().refusals().containsValue(why), "the site reports " + why);
            Board board = presence.board(BuildContent.PAGE.id(), real.view(level)).orElseThrow();
            helper.assertTrue(board.toString().contains(why.translationKey()), "the building page names the refusal");
        }

        void haul() { haul(false); }

        void haul(boolean congested) {
            Grown goal = deliveries().getFirst();
            UUID id = goal.nodes().getFirst().id();
            PlanningFixture.Rounds solver = new PlanningFixture.Rounds();
            try {
                var solved = solver.solve(plan.situation(new Stock(List.of(), Map.of(new Stash(WorldPos.of(level, storeAt)), 27)),
                    List.of()), plan.crew, List.of(goal), List.of(), 0);
                var tour = solved.schedule().tours().get(plan.worker.resident().id());
                helper.assertTrue(tour != null && tour.nodes().size() == 2,
                    "core hauling plans one take and one put for the same resident: " + solved.diagnosis());
                List<UUID> nodes = tour.nodes();
                Node take = solved.weave().vertices().get(nodes.getFirst()).node();
                Node put = solved.weave().vertices().get(nodes.getLast()).node();
                helper.assertTrue(take instanceof TransferNode && put instanceof TransferNode,
                    "both steps use the existing hauling machinery");
                helper.assertTrue(Commit.run(level, take, plan.worker) instanceof Outcome.Done, "the resident collects the stack");
                take.ended(level, Ending.DONE);
                // Replan after pickup: the remaining put must survive without another withdrawal.
                Crew.Hand hand = plan.crew.hands().getFirst();
                List<ItemStack> carried = new java.util.ArrayList<>();
                for (int slot = 0; slot < plan.worker.pack().getContainerSize(); slot++) {
                    if (!plan.worker.pack().getItem(slot).isEmpty()) carried.add(plan.worker.pack().getItem(slot).copy());
                }
                Crew carrying = new Crew(List.of(new Crew.Hand(hand.who(), hand.at(), 6 - carried.size(), 6,
                    hand.pace(), hand.licences(), hand.tools(), Optional.empty(), carried)));
                var remaining = solver.solve(plan.situation(new Stock(List.of(), Map.of(new Stash(WorldPos.of(level, storeAt)), 27)),
                    List.of()), carrying, List.of(goal), List.of(new Message.Finished(take.id())), 1);
                helper.assertTrue(remaining.weave().vertices().size() == 1
                    && remaining.weave().vertices().containsKey(put.id()), "pickup is never repeated after replanning");
                helper.assertTrue(level.getBlockEntity(at) != null, "the block stays while delivery is outstanding");
                if (congested) {
                    for (int slot = 0; slot < store.getContainerSize(); slot++) {
                        store.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
                    }
                    helper.assertTrue(Commit.run(level, put, plan.worker) instanceof Outcome.Failed,
                        "a full destination refuses the put");
                    site().tick(level);
                    blocked(BuildRefusal.NO_ROOM_TO_EMPTY);
                    helper.assertTrue(!plan.worker.pack().isEmpty(), "the undelivered items stay in the pack");
                    noSpills();
                    store.setItem(0, ItemStack.EMPTY);
                }
                helper.assertTrue(Commit.run(level, put, plan.worker) instanceof Outcome.Done, "the resident stores the exact stack");
                put.ended(level, Ending.DONE);
                hear.get(id).accept(id, Ending.DONE);
                asked.remove(id);
            } finally {
                solver.closed();
            }
            presence.settle(level.getServer());
        }

        void finish() {
            plan.worker.pack().setItem(4, new ItemStack(Items.DIRT, 16));
            for (int turn = 0; turn < 64 && presence.site(order).isPresent(); turn++) {
                presence.settle(level.getServer());
                if (!deliveries().isEmpty()) {
                    haul();
                    continue;
                }
                for (BuildSite.Piece piece : site().pieces()) {
                    Outcome outcome = Commit.run(level, piece.node(), plan.worker);
                    if (outcome instanceof Outcome.Failed failed && failed.why() == BuildRefusal.EMPTYING_CONTAINER) {
                        helper.assertTrue(!deliveries().isEmpty(), "a late container requests collection at commit time");
                        continue;
                    }
                    helper.assertTrue(outcome instanceof Outcome.Done, "the build proceeds after delivery: " + outcome);
                    site().ended(piece.node().id(), Ending.DONE);
                }
                presence.settle(level.getServer());
            }
            helper.assertTrue(presence.site(order).isEmpty(), "the filed blueprint completes");
            noSpills();
        }

        void stored(net.minecraft.world.item.Item item, int count) {
            helper.assertValueEqual((int) Goods.countIn(store, io.github.izakyl.folkways.core.api.terms.ItemSpec.of(
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item))), count, "every item reaches the colony store");
            noSpills();
        }

        void noSpills() {
            var lying = level.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(7));
            helper.assertTrue(lying.isEmpty(), "no items spill onto the ground: "
                + lying.stream().map(ItemEntity::getItem).toList());
        }

        public void close() {
            presence.closed(io.github.izakyl.folkways.core.api.colony.Closing.SHUTDOWN);
            store.clearContent();
            HANDLERS.remove(at);
            level.invalidateCapabilities(at);
            real.raze();
            plan.worker.body().discard();
        }
    }
}
