package io.github.izakyl.folkways.cookery.seeds;

import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.effect.Effects;
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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_cookery", bus = EventBusSubscriber.Bus.MOD)
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
    public static void realColonyGrassZonePublishesDemandBeforeBorrowingAndStillRequiresANativeHatAtCommit(GameTestHelper helper) {
        try (Fixture rig = new Fixture(helper)) {
            rig.mark(false);
            rig.level.setBlock(rig.center.above(), Blocks.SHORT_GRASS.defaultBlockState(), Block.UPDATE_ALL);
            rig.body.pack().setItem(0, new ItemStack(ModItems.STRAW_HAT.get()));
            rig.presence.refresh(rig.level.getServer());
            Node waiting = rig.source();
            helper.assertTrue(waiting.planned(rig.level).isEmpty(), "a real grass task can request its first shared hat before one is worn");
            helper.assertTrue(Commit.run(rig.level, waiting, rig) instanceof Outcome.Failed,
                "publishing demand does not bypass the native HEAD requirement at execution");
            waiting.ended(rig.level, Ending.REVOKED);
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

    @GameTest(template = "empty_large", timeoutTicks = 300)
    public static void anAdoptedGrassSourceRemainsWantedWhileItsResidentTravelsAcrossRepeatedSweeps(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos travellingFeet = helper.absolutePos(new BlockPos(10, 1, 10)).offset(-41, 1, 0);
        ChunkPos travellingChunk = new ChunkPos(travellingFeet);
        // The 24-block template does not load this distant resident. Keep the real colony view live.
        boolean forced = !level.getForcedChunks().contains(travellingChunk.toLong())
            && level.setChunkForced(travellingChunk.x, travellingChunk.z, true);
        long readyBy = level.getGameTime() + 200;
        boolean[] started = { false };
        helper.onEachTick(() -> {
            if (started[0]) return;
            if (!level.isPositionEntityTicking(travellingFeet) && level.getGameTime() < readyBy) return;
            started[0] = true;
            try {
                helper.assertTrue(level.isPositionEntityTicking(travellingFeet), "the travelling resident's real chunk must become entity-ticking within 200 ticks before the sweep starts: " + travellingChunk);
                for (boolean restore : List.of(false, true)) {
                    try (Fixture rig = new Fixture(helper)) {
                        rig.mark(restore); rig.wearHat();
                        if (restore) rig.body.pack().setItem(1, new ItemStack(Items.BONE_MEAL));
                        else rig.level.setBlock(rig.center.above(), Blocks.SHORT_GRASS.defaultBlockState(), Block.UPDATE_ALL);
                        rig.presence.refresh(rig.level.getServer());
                        var submitted = new java.util.ArrayList<Node>();
                        var withdrawn = new java.util.ArrayList<UUID>();
                        var colony = (io.github.izakyl.folkways.core.engine.colony.ColonyWorks) rig.colony;
                        colony.listen(rig.level.dimension(), new io.github.izakyl.folkways.core.engine.colony.ColonyWorks.Listening() {
                            public void submitted(net.minecraft.resources.ResourceLocation owner, io.github.izakyl.folkways.core.api.work.Grown work) { submitted.addAll(work.nodes()); }
                            public void withdrawn(net.minecraft.resources.ResourceLocation owner, UUID node) { withdrawn.add(node); }
                            public void offered() { }
                        });
                        var asking = new io.github.izakyl.folkways.core.api.colony.Asking(rig.colony, SeedPresence.ID, () -> { });
                        var offered = rig.presence.goals(rig.colony.view(rig.level));
                        Node source = offered.getFirst().nodes().getFirst();
                        asking.offer(Map.of(rig.level.dimension(), offered));
                        helper.assertValueEqual(submitted.size(), 1, "one grass operation is submitted through the real colony root contract");
                        Node original = submitted.getFirst();
                        helper.assertTrue(original.planned(rig.level).isEmpty(), "the original source takes its exclusive turn");
                        rig.body.moveTo(travellingFeet.getX() + 0.5, travellingFeet.getY(), travellingFeet.getZ() + 0.5);
                        helper.assertTrue(rig.colony.view(rig.level).residents().stream().anyMatch(resident -> resident.id().equals(rig.body.id())),
                            "the far resident remains present in the real loaded colony view");
                        for (int period = 0; period < 10; period++) {
                            rig.presence.refresh(rig.level.getServer());
                            var wanted = rig.presence.goals(rig.colony.view(rig.level));
                            helper.assertTrue(wanted.size() == 1 && wanted.getFirst().nodes().getFirst() == source
                                    && source.id().equals(original.id()),
                                "the same adopted source stays wanted under its decorated root's id while its resident is still far away");
                            asking.offer(Map.of(rig.level.dimension(), wanted));
                        }
                        helper.assertTrue(withdrawn.isEmpty() && submitted.size() == 1, "repeated sweeps neither withdraw nor resubmit the active source");
                        helper.assertValueEqual(Goods.countIn(rig.body.pack(), Goods.specOf(Items.BONE_MEAL.getDefaultInstance())), restore ? 1L : 0L,
                            "travelling never consumes a future restoration's real bone meal");
                        // Clothing borrowing wraps the source in a WHEREVER root; the native action
                        // still executes at the original source's real offered work stance.
                        var feet = source.spec().stances().cells().iterator().next().cell();
                        rig.body.moveTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                        rig.finish(original);
                        colony.ended(rig.level.dimension(), original.id(), Ending.DONE);
                        if (restore) {
                            helper.assertValueEqual(Goods.countIn(rig.body.pack(), Goods.specOf(Items.BONE_MEAL.getDefaultInstance())), 0L,
                                "one actual bone meal is spent only after arrival");
                            helper.assertTrue(rig.allowed.stream().anyMatch(cell -> rig.level.getBlockState(cell.above()).is(Blocks.SHORT_GRASS)),
                                "the retained original task performs the actual native growth");
                        } else helper.assertTrue(rig.level.getBlockState(rig.center.above()).isAir(), "the retained task removes the real original grass once");
                    }
                }
                helper.succeed();
            } finally {
                if (forced) level.setChunkForced(travellingChunk.x, travellingChunk.z, false);
            }
        });
    }

    @GameTest(template = "empty_large")
    public static void disablingRestorationWithdrawsABookedGrassSourceAndPreservesItsBoneMeal(GameTestHelper helper) {
        try (Fixture rig = new Fixture(helper)) {
            UUID zone = rig.mark(true); rig.wearHat(); rig.body.pack().setItem(1, new ItemStack(Items.BONE_MEAL));
            rig.presence.refresh(rig.level.getServer()); Node old = rig.source();
            helper.assertTrue(old.planned(rig.level).isEmpty(), "the native restore task takes its exclusive turn");
            helper.assertTrue(ColonyFront.of(rig.colony).setDrawn(zone,
                ColonySettings.empty().with(SeedPresence.RESTORE_GRASS, new ColonySettings.Value.Flag(false))), "the player disables restoration on the real zone");
            rig.presence.refresh(rig.level.getServer());
            helper.assertTrue(rig.presence.goals(rig.colony.view(rig.level)).isEmpty() && old.refusal(rig.level).isPresent(),
                "the changed setting withdraws the active restore root instead of retaining unwanted work");
            helper.assertTrue(Commit.run(rig.level, old, rig) instanceof Outcome.Failed, "old restoration cannot spend input after the setting changes");
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), Goods.specOf(Items.BONE_MEAL.getDefaultInstance())), 1L,
                "the rejected native action returns its one actual bone meal");
            old.ended(rig.level, Ending.REVOKED);
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void aBookedSourceLosingTaskBorrowingPermissionOrFarmingLicenceCannotSpendBoneMeal(GameTestHelper helper) {
        for (boolean revokeLicence : List.of(false, true)) {
            try (Fixture rig = new Fixture(helper)) {
                rig.mark(true); rig.wearHat(); rig.body.pack().setItem(1, new ItemStack(Items.BONE_MEAL));
                rig.presence.refresh(rig.level.getServer()); Node old = rig.source();
                helper.assertTrue(old.planned(rig.level).isEmpty(), "the real source is adopted before the resident changes");
                if (revokeLicence) {
                    var trade = rig.body.licences().trade(io.github.izakyl.folkways.plugins.farming.FarmingContent.trade()).orElseThrow();
                    rig.body.licences().allow(trade, false);
                } else {
                    ColonyFront.of(rig.colony).setSetting(io.github.izakyl.folkways.cookery.CookeryContent.ID, "wear_hat", new ColonySettings.Value.Flag(false));

                }
                // The global supplier stays true: execution must still recheck its own task permission.
                helper.assertTrue(rig.presence.goals(rig.colony.view(rig.level)).isEmpty(), "the ineligible worker's root is no longer wanted");
                helper.assertTrue(Commit.run(rig.level, old, rig) instanceof Outcome.Failed, "the already adopted action rechecks its own actual worker");
                helper.assertValueEqual(Goods.countIn(rig.body.pack(), Goods.specOf(Items.BONE_MEAL.getDefaultInstance())), 1L,
                    "cancelling the worker leaves its one real bone meal unspent");
                helper.assertTrue(rig.allowed.stream().noneMatch(cell -> rig.level.getBlockState(cell.above()).is(Blocks.SHORT_GRASS)),
                    "no terrain changes after task borrowing or farming permission is withdrawn");
                old.ended(rig.level, Ending.REVOKED);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void aKeepReservationStillLetsNativeGrassFillAnActualSeedDeficit(GameTestHelper helper) {
        try (Fixture rig = new Fixture(helper)) {
            rig.mark(false); rig.wearHat();
            rig.body.pack().setItem(0, new ItemStack(ModItems.TOMATO_SEED.get(), 63));
            BlockPos storeCell = rig.center.offset(8, 1, 8);
            rig.level.setBlock(storeCell, Blocks.BARREL.defaultBlockState(), Block.UPDATE_ALL);
            rig.colony.stores(SeedPresence.ID, rig.level.dimension(), List.of(WorldPos.of(rig.level, storeCell)));
            for (BlockPos ground : rig.allowed) rig.level.setBlock(ground.above(), Blocks.SHORT_GRASS.defaultBlockState(), Block.UPDATE_ALL);
            rig.presence.refresh(rig.level.getServer());
            Node keep = rig.presence.goals(rig.colony.view(rig.level)).stream().flatMap(goal -> goal.nodes().stream())
                .filter(node -> node.worker().isEmpty()).findFirst().orElseThrow();
            rig.claims = List.of(new SeedBudget.Claim(keep.id(), SeedBudget.Location.pack(rig.body.id()),
                Goods.specOf(ModItems.TOMATO_SEED.get().getDefaultInstance()), 64));
            rig.level.random.setSeed(0L);
            long count = 63;
            for (int attempt = 0; attempt < rig.allowed.size() && count < 64; attempt++) {
                Node source = rig.source();
                var feet = source.spec().stances().cells().iterator().next().cell();
                rig.body.moveTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                helper.assertTrue(source.planned(rig.level).isEmpty(), "the real grass source owns its exclusive turn");
                var done = (Outcome.Done) Commit.run(rig.level, source, rig);
                done.made().forEach(stack -> helper.assertTrue(io.github.izakyl.folkways.core.api.terms.Containers.insert(rig.body.pack(), stack).isEmpty(), "native grass loot fits actual cargo"));
                source.ended(rig.level, Ending.DONE);
                count = Goods.countIn(rig.body.pack(), Goods.specOf(ModItems.TOMATO_SEED.get().getDefaultInstance()));
            }
            helper.assertTrue(count >= 64, "real native grass loot supplies the missing planting seed");
            helper.assertTrue(rig.presence.goals(rig.colony.view(rig.level)).isEmpty(),
                "the fulfilled reserve stops both grass gathering and Keep despite its old outstanding self claim");
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
        List<SeedBudget.Claim> claims = List.of();
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
                claims, List.of(), Map.of()), () -> Set.of(ModItems.TOMATO_SEED.get()));
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
        void finish(Node node) {
            if (node.spec().workload() instanceof io.github.izakyl.folkways.core.api.work.batch.BatchSession) {
                var batch = new io.github.izakyl.folkways.core.engine.batch.BatchExecution(node);
                boolean complete = false;
                for (int index = 0; index < 16; index++) {
                    var next = batch.next(level, this);
                    if (next instanceof io.github.izakyl.folkways.core.api.work.batch.StepResult.Complete) {
                        complete = true;
                        break;
                    }
                    if (!(next instanceof io.github.izakyl.folkways.core.api.work.batch.StepResult.Step)
                            && !(next instanceof io.github.izakyl.folkways.core.api.work.batch.StepResult.Auxiliary)) {
                        throw new AssertionError("the arrived real source makes progress: " + next);
                    }
                    Node action = next instanceof io.github.izakyl.folkways.core.api.work.batch.StepResult.Step step
                        ? step.node() : ((io.github.izakyl.folkways.core.api.work.batch.StepResult.Auxiliary) next).node();
                    if (action.planned(level).isPresent()) throw new AssertionError("the retained action can be planned");
                    Outcome result = next instanceof io.github.izakyl.folkways.core.api.work.batch.StepResult.Step
                        ? Commit.run(level, action, this, batch) : Commit.run(level, action, this);
                    if (!(result instanceof Outcome.Done done)) throw new AssertionError("the original source action commits after arrival: " + result);
                    for (ItemStack made : done.made()) {
                        if (!io.github.izakyl.folkways.core.api.terms.Containers.insert(body.pack(), made).isEmpty()) throw new AssertionError("native seed output fits real cargo");
                    }
                    action.released(level, this, Ending.DONE);
                    action.ended(level, Ending.DONE);
                    batch.notifySettled(level);
                    batch.ended(level, action, Ending.DONE);
                }
                if (!complete) throw new AssertionError("the retained decorated source must finish its native action and returns");
                if (!(batch.finish(level, this) instanceof Outcome.Done)) throw new AssertionError("the arrived source completes its real batch");
                node.released(level, this, Ending.DONE);
                node.ended(level, Ending.DONE);
                return;
            } else if (node.spec().workload() instanceof Workload.Within within) {
                boolean complete = false;
                for (int step = 0; step < 16; step++) {
                    var turn = within.next(level, this);
                    if (turn instanceof Workload.Within.Turn.Leave) {
                        complete = true;
                        break;
                    }
                    if (!(turn instanceof Workload.Within.Turn.Piece piece)) throw new AssertionError("the arrived real source makes progress: " + turn);
                    if (piece.node().planned(level).isPresent()) throw new AssertionError("the retained action can be planned");
                    finish(piece.node());
                    within.ended(level, piece.node(), Ending.DONE);
                }
                if (!complete) throw new AssertionError("the retained decorated source must finish its native action and returns");
            }
            Outcome result = Commit.run(level, node, this);
            if (!(result instanceof Outcome.Done done)) throw new AssertionError("the original adopted source commits after arrival: " + result);
            for (ItemStack made : done.made()) {
                if (!io.github.izakyl.folkways.core.api.terms.Containers.insert(body.pack(), made).isEmpty()) throw new AssertionError("native seed output fits real cargo");
            }
            node.released(level, this, Ending.DONE);
            node.ended(level, Ending.DONE);
        }
        public Resident resident() { return body.resident(); }
        public Mob body() { return body; }
        public Container pack() { return body.pack(); }
        public List<Store> within() { return List.of(); }
        public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
        public void spill(ItemStack stack) { if (!stack.isEmpty()) throw new AssertionError("test pack has room"); }
        public Effects effects() { return Effects.NONE; }
        public List<Node> route() { return List.of(); }
        public Placement placement() { return Placement.NOWHERE; }
        public void close() { presence.closed(Closing.SHUTDOWN); body.discard(); colony.raze(); }
    }
}
