package io.github.izakyl.folkways.plugins.cookery.seeds;

import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.colony.Release;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GrassSourcesGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(GrassSourcesGameTests.class); }

    @GameTest(template = "empty_large")
    public static void realShortGrassNeedsTheRealHeadSlotAndDeliversNativeModSeeds(GameTestHelper helper) {
        try (Fixture rig = new Fixture(helper)) {
            rig.body.pack().setItem(0, new ItemStack(ModItems.STRAW_HAT.get()));
            rig.level.setBlock(rig.center.above(), Blocks.SHORT_GRASS.defaultBlockState(), Block.UPDATE_ALL);
            helper.assertTrue(GrassSources.gather(rig.level, rig, rig.center.above(), rig.allowed) instanceof Outcome.Failed,
                "a pack-only hat does not satisfy the native HEAD condition");
            helper.assertTrue(rig.level.getBlockState(rig.center.above()).is(Blocks.SHORT_GRASS), "refused work leaves grass intact");
            rig.wearHat();
            int modSeeds = 0;
            int harvested = 0;
            for (BlockPos ground : rig.allowed) {
                rig.level.setBlock(ground.above(), Blocks.SHORT_GRASS.defaultBlockState(), Block.UPDATE_ALL);
                Outcome.Done done = (Outcome.Done) GrassSources.gather(rig.level, rig, ground.above(), rig.allowed);
                modSeeds += done.made().stream().filter(stack -> stack.is(TagMod.COOKERY_MOD_SEEDS)).mapToInt(ItemStack::getCount).sum();
                if (++harvested >= 128) break;
            }
            helper.assertTrue(modSeeds > 0, "full native short-grass loot and GLM actually produce Cookery seeds");
            helper.assertTrue(rig.body.getItemBySlot(EquipmentSlot.HEAD).is(TagMod.STRAW_HAT)
                && Goods.countIn(rig.body.pack(), Goods.specOf(ModItems.STRAW_HAT.get().getDefaultInstance())) == 0,
                "one real worn hat supplies the effect without a cargo copy");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void nativeBonemealSpendsOneActualItemAndOnlyRestoresTheAuthorizedPatch(GameTestHelper helper) {
        try (Fixture rig = new Fixture(helper)) {
            helper.assertTrue(GrassSources.safeRestore(rig.level, rig.center, rig.allowed), "the native random-walk footprint is fully authorized");
            rig.body.pack().setItem(0, new ItemStack(Items.BONE_MEAL));
            helper.assertTrue(Commit.run(rig.level, rig.restore(), rig) instanceof Outcome.Done, "real BoneMealItem API completes");
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), Goods.specOf(Items.BONE_MEAL.getDefaultInstance())), 0L,
                "native growth spends exactly one supplied bone meal");
            helper.assertTrue(rig.allowed.stream().anyMatch(cell -> rig.level.getBlockState(cell.above()).is(Blocks.SHORT_GRASS)),
                "the original bonemeal feature creates real collectible grass");
            for (int offset = -8; offset <= 8; offset++) {
                helper.assertTrue(rig.level.getBlockState(rig.center.offset(8, 1, offset)).isAir()
                    && rig.level.getBlockState(rig.center.offset(-8, 1, offset)).isAir()
                    && rig.level.getBlockState(rig.center.offset(offset, 1, 8)).isAir()
                    && rig.level.getBlockState(rig.center.offset(offset, 1, -8)).isAir(), "the unregistered ring stays untouched");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void missingBoundaryOrNonFlatSoilRefusesGrowthAndReturnsBoneMeal(GameTestHelper helper) {
        try (Fixture rig = new Fixture(helper)) {
            rig.body.pack().setItem(0, new ItemStack(Items.BONE_MEAL));
            rig.allowed.remove(rig.center.offset(7, 0, 7));
            helper.assertTrue(Commit.run(rig.level, rig.restore(), rig) instanceof Outcome.Failed, "one unregistered cell makes native growth unsafe");
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), Goods.specOf(Items.BONE_MEAL.getDefaultInstance())), 1L,
                "refusal returns exactly the original bone meal");
            rig.allowed.add(rig.center.offset(7, 0, 7));
            rig.level.setBlock(rig.center.offset(2, -1, 2), Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
            helper.assertTrue(!GrassSources.safeRestore(rig.level, rig.center, rig.allowed), "downward grass paths cannot escape the authorized plane");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void realColonyGrassZonePublishesOnlyHatBoundNativeCollection(GameTestHelper helper) {
        try (Fixture rig = new Fixture(helper)) {
            rig.mark(false);
            rig.level.setBlock(rig.center.above(), Blocks.SHORT_GRASS.defaultBlockState(), Block.UPDATE_ALL);
            rig.body.pack().setItem(0, new ItemStack(ModItems.STRAW_HAT.get()));
            rig.presence.refresh(rig.level.getServer());
            helper.assertTrue(rig.presence.goals(rig.colony.view(rig.level)).isEmpty(), "pack-only hats do not activate collection");
            rig.wearHat();
            Node gather = rig.source();
            helper.assertTrue(gather.worker().orElseThrow().equals(rig.body.id()), "only the real hat-wearing farmer is assigned");
            helper.assertTrue(gather.planned(rig.level).isEmpty(), "the registered zone takes its exclusive turn");
            helper.assertTrue(Commit.run(rig.level, gather, rig) instanceof Outcome.Done, "published source executes native collection");
            helper.assertTrue(rig.level.getBlockState(rig.center.above()).isAir(), "collection consumes the actual grass block");
            gather.ended(rig.level, Ending.DONE);
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void unmarkAndRemarkCannotReviveAnOldGrassTask(GameTestHelper helper) {
        try (Fixture rig = new Fixture(helper)) {
            UUID zone = rig.mark(false);
            rig.wearHat();
            rig.level.setBlock(rig.center.above(), Blocks.SHORT_GRASS.defaultBlockState(), Block.UPDATE_ALL);
            rig.presence.refresh(rig.level.getServer());
            Node old = rig.source();
            helper.assertTrue(old.planned(rig.level).isEmpty(), "the old source turn is planned");
            rig.colony.release(zone, Release.LET_GO);
            rig.mark(false);
            rig.presence.refresh(rig.level.getServer());
            helper.assertTrue(Commit.run(rig.level, old, rig) instanceof Outcome.Failed, "real unmarking rejects the old action after remarking");
            helper.assertTrue(rig.level.getBlockState(rig.center.above()).is(Blocks.SHORT_GRASS), "stale work leaves terrain untouched");
            old.ended(rig.level, Ending.REVOKED);
            Node next = rig.source();
            helper.assertTrue(next.planned(rig.level).isEmpty(), "the new generation has no permanent old lock");
            helper.assertTrue(Commit.run(rig.level, next, rig) instanceof Outcome.Done, "newly registered work can collect");
            next.ended(rig.level, Ending.DONE);
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void stockRecoveredDuringTravelReturnsBoneMealWithoutGrowingGrass(GameTestHelper helper) {
        try (Fixture rig = new Fixture(helper)) {
            rig.mark(true);
            rig.wearHat();
            rig.body.pack().setItem(1, new ItemStack(Items.BONE_MEAL));
            rig.presence.refresh(rig.level.getServer());
            Node restore = rig.source();
            helper.assertTrue(restore.planned(rig.level).isEmpty(), "a real restoration turn is planned");
            rig.body.pack().setItem(2, new ItemStack(ModItems.TOMATO_SEED.get(), 64));
            helper.assertTrue(Commit.run(rig.level, restore, rig) instanceof Outcome.Done, "obsolete work settles without consuming input");
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), Goods.specOf(Items.BONE_MEAL.getDefaultInstance())), 1L,
                "the actual unspent bone meal is returned exactly once");
            helper.assertTrue(rig.allowed.stream().noneMatch(cell -> rig.level.getBlockState(cell.above()).is(Blocks.SHORT_GRASS)),
                "no growth occurs after the seed purpose is satisfied");
            restore.ended(rig.level, Ending.DONE);
        }
        helper.succeed();
    }

    private static final class Fixture implements Worker, AutoCloseable {
        final ServerLevel level;
        final BlockPos center;
        final Set<BlockPos> allowed = new LinkedHashSet<>();
        final Colony colony;
        final ResidentEntity body;
        final SeedPresence presence;
        Fixture(GameTestHelper helper) {
            level = helper.getLevel();
            center = helper.absolutePos(new BlockPos(10, 1, 10));
            for (int dx = -8; dx <= 8; dx++) for (int dz = -8; dz <= 8; dz++) {
                BlockPos at = center.offset(dx, 0, dz);
                level.setBlock(at.below(), Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(at, Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(at.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(at.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                if (Math.abs(dx) <= 7 && Math.abs(dz) <= 7) allowed.add(at);
            }
            colony = Colonies.mint(level.getServer());
            body = PersonBody.RESIDENT.get().create(level);
            body.setNoAi(true);
            body.setPos(center.getX() + 1.5, center.getY() + 1, center.getZ() + 0.5);
            level.addFreshEntity(body);
            colony.hold(PersonContent.ID, Held.Entity.of(body)).orElseThrow();
            body.joinColony(colony.id());
            colony.entered(body);
            SeedBudget budget = new SeedBudget(() -> new SeedBudget.Snapshot(List.of(new SeedBudget.Stock("real source pack",
                SeedBudget.Location.pack(body.id()), IntStream.range(0, body.pack().getContainerSize()).mapToObj(body.pack()::getItem).toList())),
                List.of(), List.of(), Map.of()), () -> Set.of(ModItems.TOMATO_SEED.get()));
            presence = new SeedPresence(colony, ignored -> budget, () -> true);
        }
        UUID mark(boolean restore) {
            return ColonyFront.of(colony).addZone(level, SeedPresence.GRASS, allowed,
                ColonySettings.empty().with(SeedPresence.RESTORE_GRASS, new ColonySettings.Value.Flag(restore))).orElseThrow().id();
        }
        void wearHat() {
            body.setItemSlot(EquipmentSlot.HEAD, body.pack().getItem(0).is(ModItems.STRAW_HAT.get())
                ? body.pack().removeItem(0, 1) : new ItemStack(ModItems.STRAW_HAT.get()));
        }
        Node source() {
            return presence.goals(colony.view(level)).stream().flatMap(goal -> goal.nodes().stream())
                .filter(node -> node.worker().isPresent()).findFirst().orElseThrow();
        }
        Node restore() {
            NodeSpec spec = NodeSpec.of(UUID.randomUUID(), SeedPresence.ID, new WorkSite.AtBlock(WorldPos.of(level, center)),
                Stances.WHEREVER, Workload.Once.of(1)).needs(new Need(Goods.specOf(Items.BONE_MEAL.getDefaultInstance()), 1, true)).done();
            return new Node() {
                public NodeSpec spec() { return spec; }
                public Outcome commit(ServerLevel world, Worker who) { return GrassSources.restore(world, who, center, allowed); }
            };
        }
        public Resident resident() { return body.resident(); }
        public Mob body() { return body; }
        public Container pack() { return body.pack(); }
        public List<Store> within() { return List.of(); }
        public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
        public void spill(ItemStack stack) { if (!stack.isEmpty()) throw new AssertionError("test pack has room"); }
        public int rankOf(String perk) { return 0; }
        public List<Node> route() { return List.of(); }
        public Placement placement() { return Placement.NOWHERE; }
        public void close() { presence.closed(Closing.SHUTDOWN); body.discard(); colony.raze(); }
    }
}
