package io.github.izakyl.folkways.front.engine.colony;

import com.mojang.authlib.GameProfile;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.front.api.Delegation;
import io.github.izakyl.folkways.front.api.Enrollment;
import io.github.izakyl.folkways.front.api.Registering;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.Shape;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BlockSweepGameTests {
    private static final ResourceLocation COMPOST = id("compost");
    private static final ResourceLocation STORE = id("store");
    private static final ResourceLocation ANCHORED_POINT = id("test/sweep_anchored_point");
    private static final ResourceLocation BLOCK_WITHOUT_ENTITY = id("test/sweep_block_without_entity");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(BlockSweepGameTests.class); }

    @SubscribeEvent
    public static void front(Registering event) {
        event.enrollment(id("test/sweep"), new Enrollment(List.of(
            new Delegation(ANCHORED_POINT, new Shape.Point(
                (level, cell) -> level.getBlockState(cell).is(Blocks.LAPIS_BLOCK),
                (level, cell) -> level.getBlockState(cell.below()).is(Blocks.LAPIS_BLOCK) ? cell.below() : cell)),
            new Delegation(BLOCK_WITHOUT_ENTITY, new Shape.Block(state -> state.is(Blocks.EMERALD_BLOCK)))),
            Schema.none(), List.of()));
    }

    @GameTest(template = "empty")
    public static void mixedNativePointsAndContainerFallbackAreDiscovered(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos point = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos chest = point.east(2);
        level.setBlockAndUpdate(point, Blocks.COMPOSTER.defaultBlockState());
        level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        helper.assertTrue(level.getBlockEntity(point) == null, "native composters have no block entity");
        helper.assertTrue(ColonySites.accepting(level, point).orElseThrow().shape() instanceof Shape.Point,
            "the real compost enrollment must use Point");
        var container = ColonySites.accepting(level, chest).orElseThrow();
        helper.assertTrue(container.id().equals(STORE) && container.shape() instanceof Shape.Block block && block.fallback(),
            "the real chest must still use the generic container fallback");
        var found = BlockSweep.found(level, point, chest);
        helper.assertTrue(sites(found, COMPOST).equals(List.of(point)), "Point must appear beside a Block without a cast");
        helper.assertTrue(sites(found, STORE).equals(List.of(chest)), "the container fallback remains discoverable");
        helper.assertValueEqual(found.size(), 2, "both real enrollment kinds share the sweep preview");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void pointsWithoutAnyBlockEntitiesAreAllDiscovered(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos first = helper.absolutePos(new BlockPos(1, 1, 1));
        List<BlockPos> points = List.of(first, first.east(2), first.east(4));
        for (BlockPos point : points) level.setBlockAndUpdate(point, Blocks.COMPOSTER.defaultBlockState());
        var found = BlockSweep.found(level, first, points.getLast());
        helper.assertTrue(sites(found, COMPOST).equals(points), "every native Point is retained with its own immutable anchor");
        helper.assertValueEqual(found.size(), 1, "a pure Point sweep needs no Block delegation");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void pointAnchorsAreCanonicalizedBeforeDeduplication(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos lower = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(lower, Blocks.LAPIS_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(lower.above(), Blocks.LAPIS_BLOCK.defaultBlockState());
        helper.assertTrue(ColonySites.anchor(level, lower.above()).equals(lower), "the point gesture resolves the lower anchor");
        var found = BlockSweep.found(level, lower, lower.above());
        helper.assertTrue(sites(found, ANCHORED_POINT).equals(List.of(lower)),
            "the sweep uses the same Point anchor as a single click and deduplicates both cells");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void blocksStillRequireTheirBlockEntity(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos block = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(block, Blocks.EMERALD_BLOCK.defaultBlockState());
        helper.assertTrue(ColonySites.accepting(level, block).orElseThrow().id().equals(BLOCK_WITHOUT_ENTITY),
            "fixture must really match a Block declaration even without an entity");
        helper.assertTrue(BlockSweep.found(level, block, block).isEmpty(), "the Point fix must not admit entityless Blocks");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void doubleChestPreviewAndTakeShareOneCanonicalMember(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos left = helper.absolutePos(new BlockPos(2, 1, 2));
        var state = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH)
            .setValue(ChestBlock.TYPE, ChestType.LEFT);
        BlockPos right = left.relative(ChestBlock.getConnectedDirection(state));
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        level.setBlock(left, state, flags);
        level.setBlock(right, state.setValue(ChestBlock.TYPE, ChestType.RIGHT), flags);
        BlockPos anchor = ColonySites.anchor(level, left);
        helper.assertTrue(ColonySites.anchor(level, right).equals(anchor), "both chest halves resolve to one member");
        BlockPos min = new BlockPos(Math.min(left.getX(), right.getX()), left.getY(), Math.min(left.getZ(), right.getZ()));
        BlockPos max = new BlockPos(Math.max(left.getX(), right.getX()), left.getY(), Math.max(left.getZ(), right.getZ()));
        var found = BlockSweep.found(level, min, max);
        helper.assertTrue(sites(found, STORE).equals(List.of(anchor)), "preview counts the canonical double chest once");
        var colony = Colonies.mint(level.getServer());
        try {
            var player = observer(helper);
            helper.assertTrue(!BlockSweep.take(level, colony, min, max, Set.of(STORE), player).refused(), "take accepts the double chest");
            helper.assertValueEqual(colony.holdings().size(), 1, "take registers exactly the previewed member");
            helper.assertTrue(colony.holdingAt(WorldPos.of(level, anchor)).orElseThrow().what() instanceof Held.Block,
                "container registration still uses Held.Block");
            helper.assertTrue(BlockSweep.take(level, colony, min, max, Set.of(STORE), player).refused(),
                "a repeated sweep cannot toggle or duplicate the double chest");
        } finally {
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void takeRegistersSelectedPointsWithoutTogglingOrStealing(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos first = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos foreign = first.east(2);
        BlockPos chest = first.east(4);
        level.setBlockAndUpdate(first, Blocks.COMPOSTER.defaultBlockState());
        level.setBlockAndUpdate(foreign, Blocks.COMPOSTER.defaultBlockState());
        level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        var colony = Colonies.mint(level.getServer());
        var other = Colonies.mint(level.getServer());
        try {
            var existing = ColonyGround.hold(level, other, COMPOST, foreign).orElseThrow();
            var player = observer(helper);
            helper.assertTrue(!BlockSweep.take(level, colony, first, chest, Set.of(COMPOST), player).refused(),
                "a Point-only selection works inside a mixed box");
            var holding = colony.holdingAt(WorldPos.of(level, first)).orElseThrow();
            helper.assertTrue(holding.owner().equals(COMPOST) && holding.what() instanceof Held.Point,
                "the native composter is really registered as Held.Point");
            helper.assertTrue(colony.holdingAt(WorldPos.of(level, chest)).isEmpty(), "unselected container kinds are not taken");
            helper.assertTrue(colony.holdingAt(WorldPos.of(level, foreign)).isEmpty()
                && other.holding(existing.id()).isPresent(), "a foreign Point keeps its original colony and identity");
            helper.assertTrue(BlockSweep.take(level, colony, first, foreign, Set.of(COMPOST), player).refused(),
                "repeating a sweep of already held points makes no change");
            helper.assertTrue(colony.holding(holding.id()).isPresent(), "a repeated take never toggles off the existing point");
            helper.assertTrue(BlockSweep.take(level, other, first, first, Set.of(COMPOST), player).refused(),
                "the other colony cannot steal the newly registered point either");
            helper.assertValueEqual(colony.holdings().size(), 1, "there is only one new member");
            helper.assertValueEqual(other.holdings().size(), 1, "foreign registration was neither duplicated nor moved");
        } finally {
            colony.raze();
            other.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void absentChunksRemainUnloadedDuringDiscovery(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos absent = new BlockPos(25_000_000, 80, 25_000_000);
        helper.assertTrue(!level.isLoaded(absent), "fixture starts in an absent chunk");
        helper.assertTrue(BlockSweep.found(level, absent, absent.east(3)).isEmpty(), "absent cells provide no candidates");
        helper.assertTrue(!level.isLoaded(absent), "sweeping must not load a remote chunk");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void oversizedBoxesAreRefusedBeforeTakingAnyPoint(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos first = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(first, Blocks.COMPOSTER.defaultBlockState());
        BlockPos last = first.east(BlockSweep.MOST_CELLS);
        helper.assertTrue(BlockSweep.fits(first, last.west()), "the inclusive cell limit remains accepted");
        helper.assertTrue(!BlockSweep.fits(first, last), "one additional cell exceeds the limit");
        helper.assertTrue(BlockSweep.found(level, first, last).isEmpty(), "an oversized preview does not enumerate candidates");
        var colony = Colonies.mint(level.getServer());
        try {
            var result = BlockSweep.take(level, colony, first, last, Set.of(COMPOST), observer(helper));
            helper.assertTrue(result.refusal().orElseThrow().key().equals(BlockSweep.SweepRefusal.TOO_LARGE.translationKey()),
                "server take reports the size limit before attempting a Point");
            helper.assertTrue(colony.holdings().isEmpty(), "size rejection leaves no partial enrollment");
        } finally {
            colony.raze();
        }
        helper.succeed();
    }

    private static List<BlockPos> sites(Map<Delegation, List<BlockPos>> found, ResourceLocation kind) {
        return found.entrySet().stream().filter(entry -> entry.getKey().id().equals(kind))
            .flatMap(entry -> entry.getValue().stream()).toList();
    }

    // Exercise take without registering a networked player or leaving one in the shared test server.
    private static ServerPlayer observer(GameTestHelper helper) {
        return new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
            new GameProfile(UUID.randomUUID(), "sweep-test"), ClientInformation.createDefault()) {
            @Override
            public void displayClientMessage(Component message, boolean actionBar) { }
        };
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("folkways", path);
    }
}
