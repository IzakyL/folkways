package io.github.izakyl.folkways.cookery;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.ChoppingBoardBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.StockpotBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Hold;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Produce;
import io.github.izakyl.folkways.core.api.work.Refinement;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Workshop;
import io.github.izakyl.folkways.core.engine.colony.ColonyWorks;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.engine.Enrollments;
import io.github.izakyl.folkways.front.engine.colony.MemberToggle;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_cookery", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CookeryRegistrationGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CookeryRegistrationGameTests.class);
    }

    @GameTest(template = "empty")
    public static void realKitchenRegistrationMarksBlocksPublishesWorkshopsAndSuppliesItsPage(GameTestHelper helper) {
        try (Kitchen rig = new Kitchen(helper)) {
            helper.assertTrue(Enrollments.of(CookeryContent.ID).pages().stream()
                .anyMatch(page -> page.id().equals(CookeryContent.ID)), "the actual front registers the kitchen page");
            rig.mark(rig.boardCell);
            rig.mark(rig.potCell);
            rig.presence.refresh(rig.level.getServer());
            helper.assertTrue(rig.colony.view(rig.level).blocks(CookeryContent.STATION)
                .containsAll(List.of(rig.boardCell, rig.potCell)), "book marking uses the real kitchen delegation");
            helper.assertValueEqual(rig.workshops().size(), 2, "both real block types publish executable workshops");
            helper.assertTrue(!rig.options("raw_cut_small_meats", 3).isEmpty(),
                "the published board can expand a meat demand");
            helper.assertTrue(!rig.options("cooked_rice", 3).isEmpty(),
                "the published pot can expand a rice demand");
            var page = Facing.of(rig.colony, CookeryContent.ID).orElseThrow()
                .board(CookeryContent.ID, rig.colony.view(rig.level)).orElseThrow();
            helper.assertValueEqual(page.rows().stream().filter(row -> row.acts().stream().anyMatch(act -> act instanceof io.github.izakyl.folkways.front.api.panel.Board.Act.Ping)).count(), 2L, "the real facing provider lists both stations");
            helper.assertValueEqual(page.rows().stream().flatMap(row -> row.acts().stream()).filter(act -> act instanceof io.github.izakyl.folkways.front.api.panel.Board.Act.Edit).count(), 3L, "all three clothing settings can actually be edited through the kitchen page");
            helper.assertTrue(page.rows().stream().filter(row -> row.acts().stream().anyMatch(act -> act instanceof io.github.izakyl.folkways.front.api.panel.Board.Act.Ping)).allMatch(row -> row.detail().getContents() instanceof TranslatableContents),
                "the page keeps translated status components until the client");
            rig.mark(rig.boardCell);
            rig.mark(rig.potCell);
            rig.presence.refresh(rig.level.getServer());
            helper.assertTrue(rig.workshops().isEmpty(), "removing the last marked station withdraws old workshops");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anEnclosedMarkedStationStaysVisibleWithAnUnreachableStatus(GameTestHelper helper) {
        try (Kitchen rig = new Kitchen(helper)) {
            rig.mark(rig.boardCell);
            for (BlockPos cell : BlockPos.betweenClosed(rig.boardCell.offset(-1, -1, -1), rig.boardCell.offset(1, 2, 1))) {
                if (!cell.equals(rig.boardCell)) rig.level.setBlockAndUpdate(cell, Blocks.STONE.defaultBlockState());
            }
            rig.presence.refresh(rig.level.getServer());
            var page = rig.presence.board(CookeryContent.ID, rig.colony.view(rig.level)).orElseThrow();
            helper.assertValueEqual(page.rows().stream().filter(row -> row.acts().stream().anyMatch(act -> act instanceof io.github.izakyl.folkways.front.api.panel.Board.Act.Ping)).count(), 1L, "being enclosed does not erase the marked station");
            helper.assertTrue(page.rows().getFirst().detail().getContents() instanceof TranslatableContents text
                && text.getKey().equals("folkways.page.cookery.unreachable"), "the page explains the missing work position");
            helper.assertTrue(rig.workshops().isEmpty(), "an unreachable kitchen cannot dispatch production");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unmarkingAnInvestedPotPreservesGoodsAndLetsANewColonyReuseIt(GameTestHelper helper) {
        try (Kitchen rig = new Kitchen(helper)) {
            rig.mark(rig.potCell);
            rig.prepareRice();
            CompoundTag contents = nativeContents(rig.pot, rig.level);
            rig.mark(rig.potCell);
            helper.assertTrue(contents.equals(nativeContents(rig.pot, rig.level)),
                "unmarking preserves invested food, soup, lid and progress");
            helper.assertTrue(rig.presence.goals(rig.colony.view(rig.level)).isEmpty(),
                "the removed kitchen has no recovery task");
            rig.emptyPot();
            Colony next = Colonies.mint(rig.level.getServer());
            try {
                helper.assertTrue(MemberToggle.toggleAt(rig.level, next, rig.potCell, rig.player).refusal().isEmpty(),
                    "a different colony can mark the manually emptied pot");
                CookeryPresence presence = next.service(CookeryContent.ID, CookeryPresence.class).orElseThrow();
                presence.refresh(rig.level.getServer());
                helper.assertTrue(((ColonyWorks) next).workshopsIn(rig.level.dimension()).stream()
                    .anyMatch(workshop -> !options(workshop, "cooked_rice", 3).isEmpty()),
                    "the prior owner's persistent claim cannot lock the empty pot forever");
            } finally {
                next.raze();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void razingAColonyReleasesItsLoadedPotWithoutDestroyingTheBatch(GameTestHelper helper) {
        try (Kitchen rig = new Kitchen(helper)) {
            rig.mark(rig.potCell);
            rig.prepareRice();
            CompoundTag contents = nativeContents(rig.pot, rig.level);
            rig.colony.raze();
            helper.assertTrue(contents.equals(nativeContents(rig.pot, rig.level)),
                "real colony teardown preserves the native batch");
            rig.emptyPot();
            Colony next = Colonies.mint(rig.level.getServer());
            try {
                helper.assertTrue(MemberToggle.toggleAt(rig.level, next, rig.potCell, rig.player).refusal().isEmpty(),
                    "the released block can join a new colony");
                CookeryPresence presence = next.service(CookeryContent.ID, CookeryPresence.class).orElseThrow();
                presence.refresh(rig.level.getServer());
                helper.assertTrue(((ColonyWorks) next).workshopsIn(rig.level.dimension()).stream()
                    .anyMatch(workshop -> !options(workshop, "cooked_rice", 3).isEmpty()),
                    "raze cannot leave a stale ownership lock");
            } finally {
                next.raze();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void serverShutdownKeepsTheNativeBatchAndItsRecoveryOwnership(GameTestHelper helper) {
        try (Kitchen rig = new Kitchen(helper)) {
            rig.mark(rig.potCell);
            rig.prepareRice();
            CompoundTag before = rig.pot.saveCustomOnly(rig.level.registryAccess());
            rig.presence.closed(Closing.SHUTDOWN);
            helper.assertTrue(before.equals(rig.pot.saveCustomOnly(rig.level.registryAccess())),
                "shutdown does not discard the persisted ownership or native batch");
            CookeryPresence resumed = new CookeryPresence(rig.colony, rig.level.getServer());
            try {
                resumed.refresh(rig.level.getServer());
                helper.assertValueEqual(resumed.goals(rig.colony.view(rig.level)).size(), 1,
                    "a newly constructed presence recovers the same batch after shutdown");
            } finally {
                resumed.closed(Closing.RAZED);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unmarkingABoardPreservesTheIngredientAndDoesNotAdoptItOnRemark(GameTestHelper helper) {
        try (Kitchen rig = new Kitchen(helper)) {
            rig.mark(rig.boardCell);
            rig.put(new ItemStack(Items.CHICKEN));
            rig.put(ModItems.IRON_KITCHEN_KNIFE.get().getDefaultInstance());
            Node work = rig.plan("raw_cut_small_meats", 3);
            rig.firstPiece(work);
            ChoppingBoardBlockEntity board = (ChoppingBoardBlockEntity) rig.level.getBlockEntity(rig.boardCell);
            helper.assertTrue(board.getCurrentCutStack().is(Items.CHICKEN), "real production invested the chicken");
            CompoundTag contents = nativeContents(board, rig.level);
            rig.mark(rig.boardCell);
            helper.assertTrue(contents.equals(nativeContents(board, rig.level)), "unmarking preserves input and cut progress");
            rig.mark(rig.boardCell);
            rig.presence.refresh(rig.level.getServer());
            helper.assertTrue(rig.presence.goals(rig.colony.view(rig.level)).isEmpty(),
                "remarking cannot adopt the now unowned ingredient");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theRealBookRegistersANativeComposterPointAndTheKitchenPagePublishesIt(GameTestHelper helper) {
        try (Kitchen rig = new Kitchen(helper)) {
            BlockPos cell = helper.absolutePos(new BlockPos(4, 1, 4)); WorldPos at = WorldPos.of(rig.level, cell);
            rig.level.setBlockAndUpdate(cell, Blocks.COMPOSTER.defaultBlockState()); rig.mark(cell);
            helper.assertTrue(Enrollments.delegation(CookeryContent.COMPOST).orElseThrow().shape() instanceof Shape.Point,
                "the actual front declares native composters as ledger points");
            helper.assertTrue(rig.colony.holdingAt(at).filter(holding -> holding.owner().equals(CookeryContent.COMPOST)
                && holding.what() instanceof Held.Point).isPresent(), "real book membership persists its distinct compost delegation");
            helper.assertTrue(rig.level.getBlockEntity(cell) == null, "the real native composter keeps no block entity");
            helper.assertTrue(rig.colony.view(rig.level).blocks(CookeryContent.COMPOST).contains(cell), "the actual loaded point view reaches the kitchen service");
            rig.presence.refresh(rig.level.getServer());
            var page = rig.presence.board(CookeryContent.ID, rig.colony.view(rig.level)).orElseThrow();
            helper.assertTrue(page.rows().stream().anyMatch(row -> row.icon().is(Blocks.COMPOSTER.asItem())), "the kitchen board lists the actual native point and its status");
            rig.level.setBlockAndUpdate(cell, Blocks.COMPOSTER.defaultBlockState().setValue(ComposterBlock.LEVEL, 8));
            helper.assertValueEqual(rig.presence.goals(rig.colony.view(rig.level)).size(), 1, "the real marked point exposes its native ready-compost task");
            rig.mark(cell); rig.presence.refresh(rig.level.getServer());
            helper.assertTrue(rig.colony.holdingAt(at).isEmpty() && rig.presence.goals(rig.colony.view(rig.level)).isEmpty(), "a second real book click unmarks and withdraws the native point");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unmarkingANativeComposterRejectsItsOldCollectionAndRemarkingRecoversOnlyOneBoneMeal(GameTestHelper helper) {
        try (Kitchen rig = new Kitchen(helper)) {
            BlockPos cell = helper.absolutePos(new BlockPos(4, 1, 4));
            rig.level.setBlockAndUpdate(cell, Blocks.COMPOSTER.defaultBlockState().setValue(ComposterBlock.LEVEL, 8));
            rig.mark(cell); rig.presence.refresh(rig.level.getServer());
            Node old = rig.presence.goals(rig.colony.view(rig.level)).getFirst().nodes().getFirst();
            helper.assertTrue(old.planned(rig.level).isEmpty(), "the native collect action obtains the real kitchen turn");
            rig.mark(cell);
            helper.assertTrue(Commit.run(rig.level, old, rig.worker) instanceof Outcome.Failed, "unmarking withdraws the old point generation before any native mutation");
            old.ended(rig.level, Ending.REVOKED);
            helper.assertValueEqual(rig.level.getBlockState(cell).getValue(ComposterBlock.LEVEL), 8, "the original physical compost remains ready");
            rig.mark(cell); rig.presence.refresh(rig.level.getServer());
            Node recovered = rig.presence.goals(rig.colony.view(rig.level)).getFirst().nodes().getFirst();
            helper.assertTrue(recovered.planned(rig.level).isEmpty(), "remarked native contents obtain one new valid generation");
            var feet = recovered.spec().stances().cells().iterator().next().cell();
            rig.body.moveTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
            Outcome result = Commit.run(rig.level, recovered, rig.worker);
            helper.assertTrue(result instanceof Outcome.Done, "the new point executes real native compost extraction");
            var made = ((Outcome.Done) result).made();
            helper.assertValueEqual(made.stream().filter(stack -> stack.is(Items.BONE_MEAL)).mapToInt(ItemStack::getCount).sum(), 1,
                "only one actual native bone meal is delivered");
            helper.assertValueEqual(rig.level.getBlockState(cell).getValue(ComposterBlock.LEVEL), 0, "native extraction empties the same composter");
            recovered.ended(rig.level, Ending.DONE);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nativeComposterPointsExcludeOtherColoniesAndRazeReleasesThemWithoutChangingCompost(GameTestHelper helper) {
        try (Kitchen rig = new Kitchen(helper)) {
            BlockPos cell = helper.absolutePos(new BlockPos(4, 1, 4)); WorldPos at = WorldPos.of(rig.level, cell);
            var nativeState = Blocks.COMPOSTER.defaultBlockState().setValue(ComposterBlock.LEVEL, 6);
            rig.level.setBlockAndUpdate(cell, nativeState); rig.mark(cell);
            Colony next = Colonies.mint(rig.level.getServer());
            try {
                var original = rig.colony.holdingAt(at).orElseThrow();
                helper.assertTrue(MemberToggle.toggleAt(rig.level, next, cell, rig.player).refusal()
                        .filter(notice -> notice.key().equals(MemberToggle.Refusal.ANOTHER_COLONY.translationKey())).isPresent(),
                    "a different colony's real book cannot steal an owned native point");
                helper.assertTrue(rig.colony.holdingAt(at).filter(original::equals).isPresent()
                        && next.holdingAt(at).isEmpty(), "the refusal preserves the same original point and creates no second owner");
                rig.colony.raze();
                helper.assertTrue(rig.level.getBlockState(cell).equals(nativeState), "raze does not change native compost progress or contents");
                helper.assertTrue(MemberToggle.toggleAt(rig.level, next, cell, rig.player).refusal().isEmpty(), "the released position can join another colony");
                helper.assertTrue(next.holdingAt(at).filter(holding -> holding.what() instanceof Held.Point).isPresent(), "the next owner holds exactly one native point");
            } finally { next.raze(); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void removingANativeComposterReconcilesItsRealPointAndWithdrawsTheKitchenRow(GameTestHelper helper) {
        try (Kitchen rig = new Kitchen(helper)) {
            BlockPos cell = helper.absolutePos(new BlockPos(4, 1, 4)); WorldPos at = WorldPos.of(rig.level, cell);
            rig.level.setBlockAndUpdate(cell, Blocks.COMPOSTER.defaultBlockState()); rig.mark(cell);
            rig.level.setBlockAndUpdate(cell, Blocks.AIR.defaultBlockState());
            ColonyData.find(rig.level.getServer(), rig.colony.id()).orElseThrow().reconcile(rig.level.getServer());
            rig.presence.refresh(rig.level.getServer());
            helper.assertTrue(rig.colony.holdingAt(at).isEmpty(), "loaded native point validation releases a removed composter");
            helper.assertTrue(rig.presence.board(CookeryContent.ID, rig.colony.view(rig.level)).orElseThrow().rows().stream()
                .noneMatch(row -> row.icon().is(Blocks.COMPOSTER.asItem())), "the actual kitchen page does not retain a ghost native point");
        }
        helper.succeed();
    }

    private static CompoundTag nativeContents(BlockEntity entity, ServerLevel level) {
        CompoundTag contents = entity.saveCustomOnly(level.registryAccess());
        contents.remove("NeoForgeData");
        contents.remove("neoforge:attachments");
        return contents;
    }

    private static List<Refinement.Change> options(Workshop workshop, String product, int count) {
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(
            ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", product)));
        Produce demand = Produce.of(CookeryContent.ID, workshop.site(), Goods.specOf(stack), count, Produce.UNBOUNDED);
        return workshop.options(demand, Grown.of(demand));
    }

    private static final class Kitchen implements AutoCloseable {
        final GameTestHelper helper;
        final ServerLevel level;
        final BlockPos boardCell;
        final BlockPos potCell;
        final Colony colony;
        final CookeryPresence presence;
        final FakePlayer player;
        final ResidentEntity body;
        final StockpotBlockEntity pot;
        final SimpleContainer pack = new SimpleContainer(36);
        final Container sharedTools;
        final List<Hold> holds = new ArrayList<>();
        final Worker worker;

        Kitchen(GameTestHelper helper) {
            this.helper = helper;
            level = helper.getLevel();
            boardCell = helper.absolutePos(new BlockPos(2, 1, 2));
            potCell = helper.absolutePos(new BlockPos(4, 2, 2));
            for (BlockPos cell : BlockPos.betweenClosed(helper.absolutePos(BlockPos.ZERO),
                    helper.absolutePos(new BlockPos(6, 0, 6)))) level.setBlockAndUpdate(cell, Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(boardCell, ModBlocks.CHOPPING_BOARD.get().defaultBlockState());
            level.setBlockAndUpdate(potCell.below(), Blocks.CAMPFIRE.defaultBlockState());
            level.setBlockAndUpdate(potCell, ModBlocks.STOCKPOT.get().defaultBlockState());
            pot = (StockpotBlockEntity) level.getBlockEntity(potCell);
            player = FakePlayerFactory.getMinecraft(level);
            colony = Colonies.mint(level.getServer());
            presence = colony.service(CookeryContent.ID, CookeryPresence.class).orElseThrow();
            BlockPos toolsCell = helper.absolutePos(new BlockPos(1, 1, 5));
            level.setBlockAndUpdate(toolsCell, Blocks.CHEST.defaultBlockState());
            sharedTools = Containers.at(level, toolsCell).orElseThrow();
            colony.stores(CookeryContent.ID, level.dimension(), List.of(WorldPos.of(level, toolsCell)));
            body = PersonBody.RESIDENT.get().create(level);
            body.setNoAi(true);
            body.moveTo(potCell.getX() - 0.5, potCell.getY() - 1, potCell.getZ() + 1.5, 0, 0);
            worker = new Worker() {
                public Resident resident() { return body.resident(); }
                public Mob body() { return body; }
                public Container pack() { return pack; }
                public List<Store> within() { return List.of(); }
                public ItemStack held(ToolNeed need) {
                    for (int slot = 0; slot < pack.getContainerSize(); slot++) {
                        ItemStack stack = pack.getItem(slot);
                        if (stack.is(ModItems.IRON_KITCHEN_KNIFE.get())) return stack;
                    }
                    return ItemStack.EMPTY;
                }
                public void spill(ItemStack stack) { if (!stack.isEmpty()) throw new AssertionError("the test pack has room"); }
                public io.github.izakyl.folkways.effect.Effects effects() { return body.effects(); }
                public List<Node> route() { return List.of(); }
                public Placement placement() { return Placement.NOWHERE; }
            };
        }

        void mark(BlockPos cell) {
            helper.assertTrue(MemberToggle.toggleAt(level, colony, cell, player).refusal().isEmpty(),
                "the actual book membership operation succeeds");
        }

        List<Workshop> workshops() {
            return ((ColonyWorks) colony).workshopsIn(level.dimension()).stream()
                .filter(workshop -> workshop.id().equals(CookeryContent.ID)).toList();
        }

        List<Refinement.Change> options(String product, int count) {
            return workshops().stream().flatMap(workshop -> CookeryRegistrationGameTests.options(workshop, product, count).stream()).toList();
        }

        Node plan(String product, int count) {
            presence.refresh(level.getServer());
            ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", product)));
            Refinement.Change change = options(product, count).stream()
                .filter(option -> option.replacement().nodes().stream().anyMatch(node -> node.spec().gives().stream()
                    .anyMatch(amount -> amount.count() == count && Goods.matches(stack, amount.spec()))))
                .findFirst().orElseThrow();
            change.holds().forEach(hold -> {
                helper.assertTrue(hold.take().isEmpty(), "published production obtains its real kitchen booking");
                holds.add(hold);
            });
            Node work = change.replacement().nodes().getFirst();
            helper.assertTrue(work.planned(level).isEmpty(), "production adopts the planned workshop");
            return work;
        }

        void firstPiece(Node work) {
            // A published kitchen task can first borrow its shared tool. Continue to the actual food load.
            for (int step = 0; step < 8; step++) {
                Node piece = work;
                Workload.Within within = work.spec().workload() instanceof Workload.Within nested ? nested : null;
                if (within != null) {
                    var turn = within.next(level, worker);
                    helper.assertTrue(turn instanceof Workload.Within.Turn.Piece,
                        "production prepares or loads a physical batch: " + turn);
                    piece = ((Workload.Within.Turn.Piece) turn).node();
                }
                piece.spec().stances().cells().stream().findFirst().ifPresent(at ->
                    body.moveTo(at.cell().getX() + 0.5, at.cell().getY(), at.cell().getZ() + 0.5));
                Outcome outcome = Commit.run(level, piece, worker);
                helper.assertTrue(outcome instanceof Outcome.Done, "the physical production piece commits: " + outcome);
                ((Outcome.Done) outcome).made().forEach(this::put);
                piece.released(level, worker, Ending.DONE);
                piece.ended(level, Ending.DONE);
                if (within != null) within.ended(level, piece, Ending.DONE);
                if (level.getBlockEntity(boardCell) instanceof ChoppingBoardBlockEntity board
                        && !board.getCurrentCutStack().isEmpty() || !pot.isEmpty()) return;
            }
            throw new AssertionError("production never reached its actual food loading action");
        }

        void prepareRice() {
            put(new ItemStack(ModItems.RICE_SEED.get(), 3));
            put(new ItemStack(Items.WATER_BUCKET));
            put(new ItemStack(Items.BOWL, 3));
            put(ModItems.STOCKPOT_LID.get().getDefaultInstance());
            firstPiece(plan("cooked_rice", 3));
            helper.assertTrue(!pot.isEmpty() && pot.hasLid(), "the published workshop invests a real pot batch");
        }

        void put(ItemStack stack) {
            Container into = stack.is(com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod.KITCHEN_KNIFE)
                ? sharedTools : pack;
            helper.assertTrue(Containers.insert(into, stack).isEmpty(), "fixture output fits in its physical store or pack");
        }

        void operate(ItemStack hand, Supplier<Boolean> action) {
            KitchenDelivery.Result result = KitchenDelivery.run(level, potCell, worker, hand, action);
            helper.assertTrue(result.success(), "the real manual pot interaction succeeds");
            result.outputs().forEach(this::put);
        }

        void emptyPot() {
            operate(ItemStack.EMPTY, () -> pot.onLitClick(level, body, ItemStack.EMPTY));
            while (!pot.isEmpty()) operate(ItemStack.EMPTY, () -> pot.removeIngredient(level, body));
            ItemStack bucket = Containers.extract(pack, stack -> stack.is(Items.BUCKET), 1);
            helper.assertTrue(!bucket.isEmpty(), "the batch returned its empty bucket");
            operate(bucket, () -> pot.removeSoupBase(level, body, bucket));
            helper.assertTrue(pot.isEmpty(), "the player can empty the preserved native contents");
        }

        @Override
        public void close() {
            holds.forEach(hold -> hold.release(Ending.DROPPED));
            colony.raze();
            body.discard();
        }
    }
}
