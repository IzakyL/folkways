package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.front.api.PastDay;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ClearOrderGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ClearOrderGameTests.class);
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void sideAttachmentsOutrankHigherOrdinaryWorkAndKeepTheirSupports(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos wall = origin(helper);
        floor(level, wall);
        BlockPos attached = wall.east();
        BlockPos ordinary = wall.offset(3, 2, 0);
        List<BlockState> hanging = List.of(
            Blocks.WALL_TORCH.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST),
            Blocks.REDSTONE_WALL_TORCH.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST),
            Blocks.LADDER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST),
            Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST),
            Blocks.WHITE_WALL_BANNER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST),
            Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST),
            Blocks.STONE_BUTTON.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST),
            Blocks.TRIPWIRE_HOOK.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
        for (BlockState state : hanging) {
            place(level, wall, Blocks.DIRT.defaultBlockState());
            place(level, attached, state);
            place(level, ordinary, Blocks.DIRT.defaultBlockState());
            Set<BlockPos> cells = Set.of(wall, attached, ordinary);
            helper.assertTrue(state.canSurvive(level, attached), "fixture has real side support: " + state);
            List<Job> ready = new Survey(level, cells).clear(cell -> false);
            helper.assertTrue(ready.size() == 1 && ready.getFirst().cell().equals(attached),
                "side attachment precedes even higher unrelated work: " + state + ", got " + ready);
            helper.assertTrue(new Survey(level, cells).discovering().clear(cell -> false).size() == 3,
                "blocked support and lower priorities stay in the initial snapshot");
            Outcome outcome = clear(level, ready.getFirst());
            helper.assertTrue(outcome instanceof Outcome.Done done && count(done, state.getBlock().asItem()) == 1,
                "the attachment's real item is delivered once: " + state);
            helper.assertTrue(level.getBlockState(wall).is(Blocks.DIRT), "the support still stands");
            helper.assertTrue(new Survey(level, cells).clear(cell -> false).stream()
                .anyMatch(job -> job.cell().equals(wall)), "the cleared attachment releases its support");
            helper.assertTrue(lying(level, wall).isEmpty(), "explicit detachment creates no loose item");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void verticalAndFallingDependentsPrecedeTheirSupports(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos support = origin(helper);
        floor(level, support);
        BlockPos above = support.above();
        for (Block block : List.of(Blocks.WHITE_CARPET, Blocks.RAIL, Blocks.STONE_PRESSURE_PLATE,
                Blocks.REDSTONE_WIRE, Blocks.TORCH, Blocks.SAND, Blocks.GRAVEL, Blocks.ANVIL)) {
            place(level, support, Blocks.STONE.defaultBlockState());
            place(level, above, block.defaultBlockState());
            Set<BlockPos> cells = Set.of(support, above);
            List<Job> ready = new Survey(level, cells).clear(cell -> false);
            helper.assertTrue(ready.size() == 1 && ready.getFirst().cell().equals(above),
                "the supported or falling block goes before its floor: " + block);
            helper.assertTrue(new ClearOrder(level).blocked(List.of(support)), "its floor cannot be removed");
            ItemStack tool = new ItemStack(Items.DIAMOND_PICKAXE);
            if (block == Blocks.GRAVEL) {
                tool = new ItemStack(Items.DIAMOND_SHOVEL);
                // Ordinary gravel loot can be flint. Use a legal real tool so this order test
                // retains its exact one-gravel delivery assertion without controlling RNG.
                tool.enchant(level.registryAccess().registryOrThrow(Registries.ENCHANTMENT)
                    .getHolderOrThrow(Enchantments.SILK_TOUCH), 1);
            }
            Outcome outcome = node(level, ready.getFirst(), (cell, state) -> false).commit(level, worker(level, tool));
            helper.assertTrue(outcome instanceof Outcome.Done done && count(done, block.asItem()) == 1,
                "the dependent's actual item is delivered once: " + block);
            helper.assertFalse(new ClearOrder(level).blocked(List.of(support)), "its floor is released");
        }
        place(level, support, Blocks.STONE.defaultBlockState());
        BlockPos below = support.below();
        place(level, below, Blocks.LANTERN.defaultBlockState().setValue(BlockStateProperties.HANGING, true));
        List<Job> ready = new Survey(level, Set.of(support, below)).clear(cell -> false);
        helper.assertTrue(ready.size() == 1 && ready.getFirst().cell().equals(below),
            "a hanging lantern overrides ordinary top-down clearing of its ceiling");
        helper.assertTrue(clear(level, ready.getFirst()) instanceof Outcome.Done done && count(done, Items.LANTERN) == 1,
            "the hanging lantern is delivered once");
        helper.assertTrue(level.getBlockState(support).is(Blocks.STONE), "the lantern's ceiling survives detachment");
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void anAttachmentAddedAfterPlanningStopsAStaleSupportCommit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos support = origin(helper);
        floor(level, support);
        place(level, support, Blocks.DIRT.defaultBlockState());
        Job job = new Survey(level, Set.of(support)).clear(cell -> false).getFirst();
        BreakNode stale = node(level, job, (cell, state) -> false);
        helper.assertTrue(stale.ready(level), "the original support job starts ready");
        BlockPos attached = support.east();
        place(level, attached, Blocks.LADDER.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
        helper.assertFalse(stale.ready(level), "a late side attachment blocks an already assigned job");
        helper.assertTrue(stale.commit(level, worker(level)) instanceof Outcome.Failed,
            "commit independently rejects stale work, even if its worker bypasses ready");
        helper.assertTrue(level.getBlockState(support).is(Blocks.DIRT)
            && level.getBlockState(attached).is(Blocks.LADDER), "both blocks survive the rejected commit");
        place(level, attached, Blocks.AIR.defaultBlockState());
        helper.assertTrue(stale.ready(level) && stale.commit(level, worker(level)) instanceof Outcome.Done,
            "another worker finishing the attachment releases the same support job");
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void protectedAndOutOfZoneDependentsBlockSupportsWithoutExpandingTheBox(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos support = origin(helper);
        floor(level, support);
        BlockPos attached = support.east();
        place(level, support, Blocks.DIRT.defaultBlockState());
        place(level, attached, Blocks.LADDER.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
        helper.assertTrue(new Survey(level, Set.of(support)).clear(cell -> false).isEmpty(),
            "an attachment outside the box blocks its support");
        helper.assertTrue(new Survey(level, Set.of(support)).discovering().clear(cell -> false).size() == 1,
            "blocked work remains pending instead of completing the zone");
        Set<BlockPos> both = Set.of(support, attached);
        helper.assertTrue(new Survey(level, both).clear(attached::equals).isEmpty(),
            "a colony member attachment protects its support too");
        helper.assertTrue(new Survey(level, both).clear(cell -> false, state -> state.is(Blocks.LADDER), Set.of()).isEmpty(),
            "an attachment kept as the fill material protects its support");
        helper.assertTrue(new Survey(level, both).clear(cell -> false, state -> false, Set.of(attached)).isEmpty(),
            "a dependent claimed by other work still blocks clearing its support");
        helper.assertTrue(lying(level, support).isEmpty(), "all planning probes leave no drops");
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void reciprocalPartsAreRemovedTogetherAndTheirLootIsDeliveredOnce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos root = origin(helper);
        floor(level, root);
        List<List<BlockState>> pairs = List.of(
            List.of(Blocks.OAK_DOOR.defaultBlockState(), Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)),
            List.of(Blocks.SUNFLOWER.defaultBlockState(), Blocks.SUNFLOWER.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)));
        for (List<BlockState> pair : pairs) {
            place(level, root.below(), Blocks.DIRT.defaultBlockState());
            place(level, root, pair.getFirst());
            place(level, root.above(), pair.getLast());
            assertPair(helper, root, root.above(), pair.getFirst().getBlock().asItem());
        }
        place(level, root, Blocks.RED_BED.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
        place(level, root.east(), Blocks.RED_BED.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST).setValue(BlockStateProperties.BED_PART, BedPart.HEAD));
        assertPair(helper, root, root.east(), Items.RED_BED);
        place(level, root.below(), Blocks.FARMLAND.defaultBlockState());
        place(level, root, Blocks.PITCHER_CROP.defaultBlockState().setValue(BlockStateProperties.AGE_4, 4));
        place(level, root.above(), Blocks.PITCHER_CROP.defaultBlockState().setValue(BlockStateProperties.AGE_4, 4)
            .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
        assertPair(helper, root, root.above(), Items.PITCHER_PLANT);
        place(level, root, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST)
            .setValue(PistonBaseBlock.EXTENDED, true));
        place(level, root.east(), Blocks.PISTON_HEAD.defaultBlockState().setValue(PistonHeadBlock.FACING, Direction.EAST));
        assertPair(helper, root, root.east(), Items.PISTON);
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 240)
    public static void tallScaffoldsAndTheirDeckComeDownOneCellAtATimeWithoutDelayedDrops(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = origin(helper);
        int height = 128;
        place(level, base.below(), Blocks.STONE.defaultBlockState());
        List<BlockPos> removal = new ArrayList<>();
        for (int up = 0; up < height; up++) {
            place(level, base.above(up), Blocks.SCAFFOLDING.defaultBlockState()
                .setValue(ScaffoldingBlock.DISTANCE, 0));
        }
        BlockPos top = base.above(height - 1);
        for (int distance = 1; distance <= 6; distance++) {
            place(level, top.east(distance), Blocks.SCAFFOLDING.defaultBlockState()
                .setValue(ScaffoldingBlock.DISTANCE, distance).setValue(ScaffoldingBlock.BOTTOM, true));
        }
        for (int distance = 6; distance >= 1; distance--) removal.add(top.east(distance));
        for (int up = height - 1; up >= 0; up--) removal.add(base.above(up));
        helper.assertTrue(new ClearOrder(level).blocked(List.of(base.below())), "the tower protects its ground support");
        helper.assertTrue(new ClearOrder(level).blocked(List.of(top)), "the side deck blocks the tower top");
        int[] next = {0};
        int[] recovered = {0};
        Worker worker = worker(level);
        helper.onEachTick(() -> {
            if (next[0] >= removal.size()) return;
            BlockPos cell = removal.get(next[0]);
            helper.assertTrue(level.getBlockState(cell).is(Blocks.SCAFFOLDING), "no earlier removal collapsed " + cell);
            Job job = Job.at(cell, new WorkSite.AtBlock(WorldPos.of(level, cell)), Stances.WHEREVER);
            BreakNode node = node(level, job, (part, state) -> false);
            helper.assertTrue(node.ready(level), "the next exposed cell has no remaining dependents");
            Outcome outcome = node.commit(level, worker);
            helper.assertTrue(outcome instanceof Outcome.Done, "one scaffold removal commits");
            recovered[0] += count((Outcome.Done) outcome, Items.SCAFFOLDING);
            next[0]++;
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertValueEqual(next[0], removal.size(), "all cells removed"))
            .thenExecuteAfter(8, () -> {
                helper.assertValueEqual(recovered[0], height + 6, "each scaffold is returned exactly once");
                helper.assertTrue(level.getEntitiesOfClass(ItemEntity.class,
                    new AABB(base.below()).minmax(new AABB(top.east(7).above(2)))).isEmpty(),
                    "later physics produces no loose scaffold");
                helper.assertFalse(new ClearOrder(level).blocked(List.of(base.below())), "the ground support is released last");
            }).thenSucceed();
    }

    private static void assertPair(GameTestHelper helper, BlockPos root, BlockPos other, Item item) {
        ServerLevel level = helper.getLevel();
        helper.assertTrue(new Survey(level, Set.of(other)).clear(cell -> false).isEmpty(),
            "a partial selection never dismantles an unselected partner");
        List<Job> ready = new Survey(level, Set.of(root, other)).clear(cell -> false);
        helper.assertTrue(ready.size() == 1 && new HashSet<>(ready.getFirst().cells()).equals(Set.of(root, other)),
            "the reciprocal pair is one bounded operation");
        Outcome outcome = clear(level, ready.getFirst());
        helper.assertTrue(outcome instanceof Outcome.Done done && count(done, item) == 1,
            "the whole pair returns its single actual item: " + item + ", got " + outcome);
        helper.assertTrue(level.getBlockState(root).isAir() && level.getBlockState(other).isAir(), "both parts are gone");
        helper.assertTrue(lying(level, root).isEmpty(), "the paired item is not also left on the ground");
    }

    private static BreakNode node(ServerLevel level, Job job, java.util.function.BiPredicate<BlockPos, BlockState> spared) {
        Chores chores = new Chores(UUID.randomUUID(), "clear", (at, chore) -> null, () -> {}, new PastDay());
        return new BreakNode(new Chores.Chore(UUID.randomUUID(), job, chores), BreakNode.Way.CLEAR,
            level.getBlockState(job.cell()), state -> true, spared);
    }

    private static Outcome clear(ServerLevel level, Job job) {
        return node(level, job, (cell, state) -> false).commit(level, worker(level));
    }

    private static Worker worker(ServerLevel level) {
        return worker(level, new ItemStack(Items.DIAMOND_PICKAXE));
    }

    private static Worker worker(ServerLevel level, ItemStack tool) {
        var body = PersonBody.RESIDENT.get().create(level);
        return (Worker) Proxy.newProxyInstance(Worker.class.getClassLoader(), new Class<?>[] {Worker.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "body" -> body.mob();
                case "held" -> tool;
                case "effects" -> io.github.izakyl.folkways.effect.Effects.NONE;
                default -> throw new AssertionError(method.getName());
            });
    }

    private static int count(Outcome.Done outcome, Item item) {
        return outcome.made().stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    private static List<ItemEntity> lying(ServerLevel level, BlockPos cell) {
        return level.getEntitiesOfClass(ItemEntity.class, new AABB(cell).inflate(3));
    }

    private static BlockPos origin(GameTestHelper helper) {
        return helper.absolutePos(new BlockPos(5, 2, 5));
    }

    private static void floor(ServerLevel level, BlockPos origin) {
        for (BlockPos cell : BlockPos.betweenClosed(origin.offset(-3, -1, -3), origin.offset(7, -1, 4))) {
            place(level, cell, Blocks.STONE.defaultBlockState());
        }
    }

    private static void place(ServerLevel level, BlockPos cell, BlockState state) {
        level.setBlock(cell, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }
}
