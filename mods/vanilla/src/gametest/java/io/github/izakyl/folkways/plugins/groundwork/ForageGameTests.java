package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.labor.ColonyLabor;
import io.github.izakyl.folkways.core.engine.plan.PlanningFixture;
import io.github.izakyl.folkways.core.engine.colony.ColonyWays;
import io.github.izakyl.folkways.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.registries.RegisterEvent;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ForageGameTests {
    private static final ResourceLocation WILD_ID = ResourceLocation.fromNamespaceAndPath("folkways", "test_wild_food");
    private static Block wild;

    @SubscribeEvent
    public static void blocks(RegisterEvent event) {
        event.register(Registries.BLOCK, registry -> {
            wild = new Block(BlockBehaviour.Properties.ofFullCopy(Blocks.SHORT_GRASS));
            registry.register(WILD_ID, wild);
        });
        event.register(Registries.ITEM, registry -> registry.register(WILD_ID, new BlockItem(wild, new Item.Properties())));
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ForageGameTests.class);
    }

    @GameTest(template = "empty")
    public static void berryBushesAreBrokenAtEveryAge(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            for (int age = 0; age <= 3; age++) {
                f.place(berry(age));
                f.zone(GroundworkContent.FORAGE);
                Node node = f.only();
                Outcome.Done done = (Outcome.Done) node.commit(f.level, f.fixture.worker);
                helper.assertTrue(f.level.getBlockState(f.cell).isAir(), "a berry bush is removed at age " + age);
                helper.assertTrue(age < 2 ? done.made().isEmpty() : count(done, Items.SWEET_BERRIES) > 0,
                    "the bush pays its ordinary breaking loot at age " + age);
                helper.assertTrue(((Outcome.Done) node.commit(f.level, f.fixture.worker)).made().isEmpty(), "replay cannot pay again");
            }
            helper.assertTrue(f.level.getEntitiesOfClass(ItemEntity.class, new AABB(f.cell).inflate(2)).isEmpty(),
                "all forage loot enters the outcome");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void disabledForageLeavesBerryBushesAlone(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.place(berry(1));
            f.zone(GroundworkContent.WEED);
            helper.assertTrue(f.nodes().isEmpty() && f.level.getBlockState(f.cell).equals(berry(1)), "weeding does not forage");
            f.place(berry(3));
            f.zone();
            helper.assertTrue(f.nodes().isEmpty() && f.level.getBlockState(f.cell).equals(berry(3)), "disabled forage leaves plants alone");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void glowBerryColumnsIncludeFruitlessSegmentsAndStayWithinTheMark(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            place(f.level, f.cell.above(4), Blocks.STONE.defaultBlockState());
            for (int y = 0; y <= 3; y++) {
                Block vine = y == 0 ? Blocks.CAVE_VINES : Blocks.CAVE_VINES_PLANT;
                place(f.level, f.cell.above(y), vine.defaultBlockState().setValue(CaveVines.BERRIES, y != 1));
            }
            Set<BlockPos> marked = Set.of(f.cell, f.cell.above(), f.cell.above(2));
            f.zone(marked, GroundworkContent.FORAGE);
            helper.assertTrue(f.nodes().size() == 1, "one column is one clearing job");
            Node node = f.only();
            Outcome.Done done = (Outcome.Done) node.commit(f.level, f.fixture.worker);
            for (BlockPos cell : marked) helper.assertTrue(f.level.getBlockState(cell).isAir(), "every marked vine segment goes");
            helper.assertTrue(count(done, Items.GLOW_BERRIES) == 2, "both marked fruiting segments pay their loot");
            helper.assertTrue(Foraging.vine(f.level.getBlockState(f.cell.above(3)))
                && f.level.getBlockState(f.cell.above(3)).getValue(CaveVines.BERRIES), "the unmarked upper vine keeps its fruit");
            helper.assertTrue(((Outcome.Done) node.commit(f.level, f.fixture.worker)).made().isEmpty(), "a column cannot pay twice");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void protectedVineSegmentsPreventCuttingTheirColumn(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.place(Blocks.CAVE_VINES.defaultBlockState().setValue(CaveVines.BERRIES, true));
            place(f.level, f.cell.above(), Blocks.CAVE_VINES_PLANT.defaultBlockState());
            Set<BlockPos> marked = Set.of(f.cell, f.cell.above());
            helper.assertTrue(new Survey(f.level, marked).forage(f.cell.above()::equals, Set.of()).isEmpty(),
                "a protected segment keeps the complete column out of discovery");
            f.zone(marked, GroundworkContent.FORAGE);
            Node node = f.only();
            f.colony.hold(GroundworkContent.ID, new Held.Block(WorldPos.of(f.level, f.cell.above()))).orElseThrow();
            helper.assertTrue(node.commit(f.level, f.fixture.worker) instanceof Outcome.Failed,
                "protection is checked for the whole column again at commit");
            helper.assertTrue(f.level.getBlockState(f.cell).is(Blocks.CAVE_VINES), "the lower vine stays too");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void clearOverridesForageAndRemovesTheBush(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.place(berry(3));
            f.zone(GroundworkContent.CLEAR, GroundworkContent.FORAGE);
            helper.assertTrue(f.only() instanceof BreakNode, "clear owns this block when both switches are on");
            f.only().commit(f.level, f.fixture.worker);
            helper.assertTrue(f.level.getBlockState(f.cell).isAir(), "clear removes the bush");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void doublePlantsArePickedOnceAndProtectedHalvesStay(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.place(Blocks.SUNFLOWER.defaultBlockState());
            place(f.level, f.cell.above(), Blocks.SUNFLOWER.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER));
            helper.assertTrue(new Survey(f.level, Set.of(f.cell)).forage(f.cell.above()::equals, Set.of()).isEmpty(),
                "a protected upper half prevents picking its support");
            f.zone(GroundworkContent.FORAGE);
            Outcome.Done done = (Outcome.Done) f.only().commit(f.level, f.fixture.worker);
            helper.assertTrue(f.level.getBlockState(f.cell).isAir() && f.level.getBlockState(f.cell.above()).isAir(),
                "the complete plant is removed from its marked lower half");
            helper.assertTrue(count(done, Items.SUNFLOWER) == 1, "a double plant pays its normal loot once");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void pickingRechecksProtectionAndReplacement(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.place(berry(3));
            f.zone(GroundworkContent.FORAGE);
            Node node = f.only();
            f.place(berry(1));
            helper.assertTrue(((Outcome.Done) node.commit(f.level, f.fixture.worker)).made().isEmpty()
                && f.level.getBlockState(f.cell).isAir(), "a bush that became unripe is still removed with its current loot");
            f.place(Blocks.STONE.defaultBlockState());
            node.commit(f.level, f.fixture.worker);
            helper.assertTrue(f.level.getBlockState(f.cell).is(Blocks.STONE), "replacement blocks stay");
            f.place(berry(3));
            colonyPoint(f);
            helper.assertTrue(node.commit(f.level, f.fixture.worker) instanceof Outcome.Failed, "new protection is read again");
        }
        helper.succeed();
    }

    private static void colonyPoint(Fixture f) {
        // Block holdings use the same cell index that groundwork consults, regardless of their owner.
        f.colony.hold(GroundworkContent.ID, new Held.Block(WorldPos.of(f.level, f.cell))).orElseThrow();
    }

    @GameTest(template = "empty")
    public static void oldSavedConfigurationsKeepTheirDiscoveryBoundary(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.place(Blocks.DIRT.defaultBlockState());
            f.zone(GroundworkContent.CLEAR);
            Node node = f.only();
            node.planned(f.level);
            node.commit(f.level, f.fixture.worker);
            node.ended(f.level, Ending.DONE);
            f.presence.closed(Closing.SHUTDOWN);
            var saved = f.colony.kept(GroundworkContent.ID);
            var site = saved.getList("sites", 10).getCompound(0);
            site.putString("configuration", site.getString("configuration").replace(",forage=false", ""));
            f.colony.keep(GroundworkContent.ID, saved);
            f.place(Blocks.STONE.defaultBlockState());
            GroundPresence loaded = new GroundPresence(f.colony);
            loaded.refresh(f.level.getServer());
            helper.assertTrue(loaded.goals(f.colony.view(f.level)).isEmpty(), "migration does not rediscover completed work");
            helper.assertTrue(f.level.getBlockState(f.cell).is(Blocks.STONE), "new terrain stays outside the old targets");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void savedInPlaceTargetsMigrateWithoutRediscoveringOtherWork(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.place(berry(3));
            place(f.level, f.cell.east(), berry(0));
            place(f.level, f.cell.west(), Blocks.SHORT_GRASS.defaultBlockState());
            f.zone(Set.of(f.cell, f.cell.east(), f.cell.west()), GroundworkContent.FORAGE, GroundworkContent.WEED);
            f.presence.closed(Closing.SHUTDOWN);
            var saved = f.colony.kept(GroundworkContent.ID);
            var site = saved.getList("sites", 10).getCompound(0);
            site.remove("forage_version");
            var old = new net.minecraft.nbt.ListTag();
            old.add(net.minecraft.nbt.StringTag.valueOf(Job.keyOf(f.cell)));
            site.getCompound("targets").put(GroundworkContent.FORAGE, old);
            site.getCompound("targets").put(GroundworkContent.WEED, new net.minecraft.nbt.ListTag());
            f.colony.keep(GroundworkContent.ID, saved);
            GroundPresence loaded = new GroundPresence(f.colony);
            loaded.refresh(f.level.getServer());
            List<Node> nodes = loaded.goals(f.colony.view(f.level)).stream().flatMap(goal -> goal.nodes().stream()).toList();
            helper.assertTrue(nodes.size() == 2 && nodes.stream().allMatch(node -> node instanceof ForageNode),
                "migration discovers the formerly unripe bush but preserves completed weeding");
            for (Node node : nodes) node.commit(f.level, f.fixture.worker);
            helper.assertTrue(f.level.getBlockState(f.cell.east()).isAir(), "the migrated young bush is removed");
            helper.assertTrue(f.level.getBlockState(f.cell.west()).is(Blocks.SHORT_GRASS), "unrelated completed work stays spent");
            helper.assertTrue(f.colony.kept(GroundworkContent.ID).getList("sites", 10).getCompound(0)
                .getInt("forage_version") == 2, "the new target revision is saved");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 800)
    public static void brokenBerryBushLootReachesColonyStorage(GameTestHelper helper) throws ReflectiveOperationException {
        stored(helper, berry(3), false);
    }

    @GameTest(template = "empty", timeoutTicks = 800)
    public static void taggedWildFoodAndSeedsReachColonyStorage(GameTestHelper helper) throws ReflectiveOperationException {
        stored(helper, wild.defaultBlockState(), true);
    }

    @GameTest(template = "empty", timeoutTicks = 800)
    public static void wholeVineColumnLootReachesColonyStorage(GameTestHelper helper) throws ReflectiveOperationException {
        stored(helper, Blocks.CAVE_VINES.defaultBlockState().setValue(CaveVines.BERRIES, true), false);
    }

    private static void stored(GameTestHelper helper, BlockState plant, boolean picked) throws ReflectiveOperationException {
        Fixture f = new Fixture(helper);
        f.place(plant);
        boolean vines = Foraging.vine(plant);
        Set<BlockPos> marked = vines ? Set.of(f.cell, f.cell.above(), f.cell.above(2)) : Set.of(f.cell);
        if (vines) {
            place(f.level, f.cell.above(), Blocks.CAVE_VINES_PLANT.defaultBlockState());
            place(f.level, f.cell.above(2), Blocks.CAVE_VINES_PLANT.defaultBlockState().setValue(CaveVines.BERRIES, true));
            place(f.level, f.cell.above(3), Blocks.STONE.defaultBlockState());
        }
        f.zone(marked, GroundworkContent.FORAGE);
        BlockPos barrel = f.cell.east(3);
        place(f.level, barrel, Blocks.BARREL.defaultBlockState());
        Container stock = (Container) f.level.getBlockEntity(barrel);
        f.colony.stores(GroundworkContent.ID, f.level.dimension(), List.of(WorldPos.of(f.level, barrel)));
        var person = f.fixture.worker.body();
        person.moveTo(f.cell.getX() + 2.5, f.cell.getY(), f.cell.getZ() + 1.5);
        f.colony.hold(PersonContent.ID, Held.Entity.of(person)).orElseThrow();
        f.level.addFreshEntity(person);
        var body = io.github.izakyl.folkways.core.api.resident.body.Bodies.of(person).orElseThrow();
        body.joinColony(f.colony.id());
        f.colony.entered(body);
        var data = ColonyData.find(f.level.getServer(), f.colony.id()).orElseThrow();
        var labor = new ColonyLabor(data, f.level.dimension());
        Class<?> lanes = Class.forName("io.github.izakyl.folkways.core.engine.labor.Lanes");
        var inline = lanes.getDeclaredField("INLINE");
        inline.setAccessible(true);
        var run = ColonyLabor.class.getMethod("run", ServerLevel.class, lanes);
        var stop = ColonyLabor.class.getDeclaredMethod("close");
        stop.setAccessible(true);
        Set<Long> forced = new HashSet<>();
        for (BlockPos corner : List.of(f.cell.offset(-3, 0, -3), f.cell.offset(4, 0, -3),
                f.cell.offset(-3, 0, 3), f.cell.offset(4, 0, 3))) {
            ChunkPos chunk = new ChunkPos(corner);
            if (!f.level.getForcedChunks().contains(chunk.toLong()) && f.level.setChunkForced(chunk.x, chunk.z, true)) forced.add(chunk.toLong());
        }
        boolean[] closed = {false};
        Runnable close = () -> {
            if (closed[0]) return;
            closed[0] = true;
            try { stop.invoke(labor); }
            catch (ReflectiveOperationException broken) { throw new IllegalStateException(broken); }
            f.close();
            forced.forEach(chunk -> f.level.setChunkForced(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), false));
        };
        helper.onEachTick(() -> {
            if (closed[0]) return;
            f.colony.service(GroundworkContent.ID, GroundPresence.class).orElseThrow().tick(f.level.getServer());
            ColonyWays.advance(data, f.level);
            try { run.invoke(labor, f.level, inline.get(null)); }
            catch (ReflectiveOperationException broken) { throw new IllegalStateException(broken); }
        });
        helper.runAtTickTime(790, close);
        helper.startSequence().thenWaitUntil(() -> {
            for (BlockPos cell : marked) helper.assertTrue(f.level.getBlockState(cell).isAir(), "every marked forage block is destroyed");
            helper.assertTrue(stock.countItem(vines ? Items.GLOW_BERRIES : picked ? Items.WHEAT : Items.SWEET_BERRIES)
                >= (vines ? 2 : 1), "food reaches the colony barrel");
            if (picked) helper.assertTrue(stock.countItem(Items.WHEAT_SEEDS) == 1, "wild seeds are stored with the food");
        }).thenExecute(close).thenSucceed();
    }

    private static BlockState berry(int age) {
        return Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, age);
    }

    private static int count(Outcome.Done done, Item item) {
        return done.made().stream().filter(stack -> stack.is(item)).mapToInt(net.minecraft.world.item.ItemStack::getCount).sum();
    }

    private static void place(ServerLevel level, BlockPos cell, BlockState state) {
        level.setBlock(cell, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    private static final class Fixture implements AutoCloseable {
        final ServerLevel level;
        final BlockPos cell;
        final Colony colony;
        final PlanningFixture fixture;
        final GroundPresence presence;

        Fixture(GameTestHelper helper) {
            level = helper.getLevel();
            cell = helper.absolutePos(new BlockPos(4, 1, 4));
            for (BlockPos floor : BlockPos.betweenClosed(cell.offset(-3, -1, -3), cell.offset(4, -1, 3))) {
                ForageGameTests.place(level, floor, Blocks.DIRT.defaultBlockState());
                for (int y = 1; y <= 5; y++) ForageGameTests.place(level, floor.above(y), Blocks.AIR.defaultBlockState());
            }
            colony = Colonies.mint(level.getServer());
            fixture = new PlanningFixture(helper);
            presence = new GroundPresence(colony);
        }

        void place(BlockState state) { ForageGameTests.place(level, cell, state); }

        void zone(String... switches) {
            zone(Set.of(cell), switches);
        }

        void zone(Set<BlockPos> marked, String... switches) {
            ColonyFront front = ColonyFront.of(colony);
            front.zonesIn(level).forEach(zone -> front.erase(zone.id()));
            ColonySettings settings = ColonySettings.empty().with(GroundworkContent.CLEAR, new ColonySettings.Value.Flag(false));
            for (String key : switches) settings = settings.with(key, new ColonySettings.Value.Flag(true));
            front.addZone(level, GroundworkContent.ZONE, marked, settings).orElseThrow();
            presence.refresh(level.getServer());
        }

        List<Node> nodes() {
            return presence.goals(colony.view(level)).stream().flatMap(goal -> goal.nodes().stream()).toList();
        }

        Node only() {
            List<Node> nodes = nodes();
            if (nodes.size() != 1) throw new AssertionError("expected one forage/clear job, got " + nodes);
            return nodes.getFirst();
        }

        public void close() {
            fixture.worker.body().discard();
            colony.raze();
        }
    }
}
