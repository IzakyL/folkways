package io.github.izakyl.folkways.firefighting;

import com.mikoalopex.createfirefightingadd.CreateFireFightingAdd;
import com.mikoalopex.createfirefightingadd.content.equipment.extinguisher.FireExtinguisherItem;
import com.mikoalopex.createfirefightingadd.content.equipment.extinguisher.FireExtinguisherItemHandler;
import com.mikoalopex.createfirefightingadd.content.fluids.nozzle.FireExtinguisherSprayEffects;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.TaskTools;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.engine.inventory.InventoryObservations;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.front.engine.colony.ZoneDrafts;
import io.github.izakyl.folkways.plugins.damagecontrol.DamageControlContent;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysFirefightingMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FirefightingGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(FirefightingGameTests.class);
    }

    @GameTest(template = "empty")
    public static void aResidentUsesTheAddonSprayAndItsRealFiveMillibucketCost(GameTestHelper helper) {
        Rig rig = new Rig(helper, 100);
        rig.level.setBlock(rig.fire, Blocks.FIRE.defaultBlockState(), 2);
        var session = rig.adapter().open(rig.level, rig.worker, WorldPos.of(rig.level, rig.fire))
            .orElseThrow();
        session.suppress(rig.level, rig.fire);
        helper.assertTrue(rig.pack.getItem(0) == rig.extinguisher, "the physical borrowed stack is modified");
        session.close();
        ItemStack nativeStack = rig.extinguisher.copy();
        new FireExtinguisherItemHandler(CreateFireFightingAdd.FIRE_EXTINGUISHER_FLUID, nativeStack)
            .setStoredFluid(new FluidStack(Fluids.WATER, 100));
        var player = FakePlayerFactory.getMinecraft(rig.level);
        player.setPos(Vec3.atCenterOf(rig.fire.above(128)));
        player.tickCount = 0;
        helper.assertTrue(FireExtinguisherSprayEffects.spray(player, nativeStack, InteractionHand.MAIN_HAND),
            "the real player entry point supplies the consumption reference");
        helper.assertValueEqual(FireExtinguisherItem.getFluid(rig.extinguisher).getAmount(),
            FireExtinguisherItem.getFluid(nativeStack).getAmount(), "resident and native paid interval costs match");
        FireExtinguisherSprayEffects.stopSound(player);
        rig.close();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void oneExtinguisherPulseUsesTheNativeConeAndCost(GameTestHelper helper) {
        Rig rig = new Rig(helper, 100);
        BlockPos second = rig.fire.east();
        BlockPos outside = rig.fire.north(4);
        for (BlockPos at : List.of(rig.fire, second, outside)) {
            rig.level.setBlock(at.below(), Blocks.NETHERRACK.defaultBlockState(), 2);
            rig.level.setBlock(at, Blocks.FIRE.defaultBlockState(), 2);
        }
        var session = rig.adapter().open(rig.level, rig.worker, WorldPos.of(rig.level, rig.fire))
            .orElseThrow();
        helper.assertTrue(session.suppress(rig.level, rig.fire) instanceof Outcome.Done, "one paid pulse succeeds");
        helper.assertTrue(rig.level.getBlockState(rig.fire).isAir(), "the aimed fire is extinguished");
        helper.assertTrue(rig.level.getBlockState(second).isAir(), "the next fire in the cone is extinguished too");
        helper.assertTrue(rig.level.getBlockState(outside).is(Blocks.FIRE), "fire outside the cone is untouched");
        helper.assertValueEqual(FireExtinguisherItem.getFluid(rig.extinguisher).getAmount(), 95,
            "multiple cells share one native five mB charge");
        session.close();
        rig.level.setBlock(rig.fire, Blocks.FIRE.defaultBlockState(), 2);
        var reopened = rig.adapter().open(rig.level, rig.worker, WorldPos.of(rig.level, rig.fire))
            .orElseThrow();
        helper.assertTrue(reopened.suppress(rig.level, rig.fire) instanceof Outcome.Failed,
            "reopening cannot bypass the native interval");
        helper.startSequence().thenExecuteAfter(1, () -> {
            helper.assertTrue(reopened.suppress(rig.level, rig.fire) instanceof Outcome.Failed,
                "the second tick of a paid interval cannot grant another pulse");
            helper.assertValueEqual(FireExtinguisherItem.getFluid(rig.extinguisher).getAmount(), 95,
                "interval refusals spend no water");
        }).thenExecuteAfter(1, () -> {
            try {
                helper.assertTrue(reopened.suppress(rig.level, rig.fire) instanceof Outcome.Done,
                    "the next interval permits a new paid pulse");
                helper.assertValueEqual(FireExtinguisherItem.getFluid(rig.extinguisher).getAmount(), 90,
                    "two paid intervals consume ten mB");
            } finally {
                reopened.close();
                rig.close();
            }
        }).thenSucceed();
    }

    @GameTest(template = "empty")
    public static void theExtinguisherUsesItsLastNativePartialCharge(GameTestHelper helper) {
        Rig rig = new Rig(helper, 3);
        rig.level.setBlock(rig.fire, Blocks.FIRE.defaultBlockState(), 2);
        var session = rig.adapter().open(rig.level, rig.worker, WorldPos.of(rig.level, rig.fire))
            .orElseThrow();
        helper.assertTrue(session.suppress(rig.level, rig.fire) instanceof Outcome.Done,
            "the native handler permits a final partial charge");
        helper.assertTrue(rig.level.getBlockState(rig.fire).isAir(), "the partial charge still suppresses fire");
        helper.assertTrue(FireExtinguisherItem.getFluid(rig.extinguisher).isEmpty(),
            "only the remaining fluid is used");
        session.close();
        rig.close();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void waterBucketRefillConservesWaterAndReturnsTheBucket(GameTestHelper helper) {
        Rig rig = new Rig(helper, 0);
        rig.store.setItem(0, new ItemStack(Items.WATER_BUCKET));
        var refill = new RefillNode(rig.worker.resident().id(), rig.source,
            Stances.at(rig.level, rig.worker.body().blockPosition()), () -> true);
        helper.assertTrue(Commit.run(rig.level, refill, rig.worker) instanceof Outcome.Done,
            "refill uses real item fluid capabilities");
        helper.assertValueEqual(FireExtinguisherItem.getFluid(rig.extinguisher).getAmount(), 1000,
            "one bucket transfers a thousand mB");
        helper.assertTrue(rig.store.getItem(0).is(Items.BUCKET), "the same store receives the empty bucket");
        helper.assertTrue(rig.store.countItem(Items.WATER_BUCKET) == 0, "water is not duplicated");
        rig.close();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aFluidTankTransfersOnlyTheRemainingCapacity(GameTestHelper helper) {
        Rig rig = new Rig(helper, FireExtinguisherItem.CAPACITY - 120);
        FluidTank tank = new FluidTank(1000);
        tank.fill(new FluidStack(Fluids.WATER, 900), FluidAction.EXECUTE);
        helper.assertValueEqual(RefillNode.transfer(tank,
            rig.extinguisher.getCapability(Capabilities.FluidHandler.ITEM)), 120,
            "only the missing fluid is transferred");
        helper.assertValueEqual(tank.getFluidAmount(), 780, "the source loses exactly what the bottle gains");
        helper.assertValueEqual(FireExtinguisherItem.getFluid(rig.extinguisher).getAmount(),
            FireExtinguisherItem.CAPACITY,
            "the bottle cannot overfill");
        rig.close();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aPartialBottleDoesNotDestroyAnIndivisibleWaterBucket(GameTestHelper helper) {
        Rig rig = new Rig(helper, FireExtinguisherItem.CAPACITY - 120);
        ItemStack bucket = new ItemStack(Items.WATER_BUCKET);
        var fluid = bucket.getCapability(Capabilities.FluidHandler.ITEM);
        helper.assertValueEqual(RefillNode.transfer(fluid,
            rig.extinguisher.getCapability(Capabilities.FluidHandler.ITEM)), 0,
            "a bucket is kept when its whole contents cannot fit");
        helper.assertTrue(fluid.getContainer().is(Items.WATER_BUCKET), "water bucket is preserved");
        helper.assertValueEqual(FireExtinguisherItem.getFluid(rig.extinguisher).getAmount(),
            FireExtinguisherItem.CAPACITY - 120,
            "no partial free refill");
        rig.close();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aStoppedSessionCannotSprayOrSpendFluid(GameTestHelper helper) {
        Rig rig = new Rig(helper, 100);
        var session = rig.adapter().open(rig.level, rig.worker, WorldPos.of(rig.level, rig.fire))
            .orElseThrow();
        session.close();
        session.close();
        rig.level.setBlock(rig.fire, Blocks.FIRE.defaultBlockState(), 2);
        helper.assertTrue(session.suppress(rig.level, rig.fire) instanceof Outcome.Failed,
            "stopping closes the resident session");
        helper.assertValueEqual(FireExtinguisherItem.getFluid(rig.extinguisher).getAmount(), 100,
            "interruption does not consume fluid");
        helper.assertTrue(rig.level.getBlockState(rig.fire).is(Blocks.FIRE), "a closed session cannot mutate blocks");
        rig.close();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nativeObstructionAndRangeMissesReportThePaidAttempt(GameTestHelper helper) {
        Rig rig = new Rig(helper, 100);
        rig.level.setBlock(rig.fire, Blocks.FIRE.defaultBlockState(), 2);
        var origin = rig.worker.body().getEyePosition();
        BlockPos wall = rig.fire.west();
        rig.level.setBlock(wall, Blocks.STONE.defaultBlockState(), 2);
        rig.level.setBlock(wall.above(), Blocks.STONE.defaultBlockState(), 2);
        var session = rig.adapter().open(rig.level, rig.worker, WorldPos.of(rig.level, rig.fire))
            .orElseThrow();
        helper.assertTrue(session.suppress(rig.level, rig.fire) instanceof Outcome.Failed,
            "the native effect misses behind the wall");
        helper.assertTrue(session.performed(), "an emitted pulse is distinct from a refused attempt");
        session.close();
        helper.assertValueEqual(FireExtinguisherItem.getFluid(rig.extinguisher).getAmount(), 95,
            "the actual native attempt consumes one pulse even when the world changed after planning");
        helper.assertTrue(rig.level.getBlockState(rig.fire).is(Blocks.FIRE), "a native miss leaves the target burning");
        rig.level.removeBlock(wall, false);
        rig.level.removeBlock(wall.above(), false);
        Rig distant = new Rig(helper, 100);
        var far = origin.add(-8, 0, 0);
        helper.assertTrue(NativeSprayBridge.spray(distant.level, distant.worker.body(), far,
            Vec3.atCenterOf(distant.fire).subtract(far).normalize(), distant.fire, distant.extinguisher),
            "the native attempt reports emission even when the target is beyond four blocks");
        helper.assertValueEqual(FireExtinguisherItem.getFluid(distant.extinguisher).getAmount(), 95,
            "out-of-range native attempts also report their real consumed water");
        distant.close();
        rig.close();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void adjacentOccludedFireDoesNotOfferASprayStance(GameTestHelper helper) {
        Rig rig = new Rig(helper, 100, true);
        rig.level.setBlock(rig.fire, Blocks.FIRE.defaultBlockState(), 2);
        BlockPos feet = rig.fire.west().north();
        rig.level.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), 2);
        rig.worker.body().moveTo(Vec3.atBottomCenterOf(feet));
        rig.level.setBlock(rig.fire.west(), Blocks.STONE.defaultBlockState(), 2);
        rig.level.setBlock(rig.fire.west().above(), Blocks.STONE.defaultBlockState(), 2);
        var adapter = rig.adapter();
        helper.assertTrue(adapter.reaches(rig.level, rig.worker, WorldPos.of(rig.level, rig.fire)),
            "method availability checks the carried water, not a second shot ray");
        helper.assertFalse(adapter.footings(rig.level, rig.worker, WorldPos.of(rig.level, rig.fire)).contains(feet),
            "planning excludes the same blocked native ray before travelling");
        helper.assertValueEqual(FireExtinguisherItem.getFluid(rig.extinguisher).getAmount(), 100,
            "checking reach and footings never spends physical water");
        rig.level.removeBlock(rig.fire.west(), false);
        rig.level.removeBlock(rig.fire.west().above(), false);
        helper.assertTrue(adapter.reaches(rig.level, rig.worker, WorldPos.of(rig.level, rig.fire)),
            "the same physical tool remains available after the wall is removed");
        helper.assertTrue(adapter.footings(rig.level, rig.worker, WorldPos.of(rig.level, rig.fire)).contains(feet),
            "planning also recovers the visible footing");
        rig.close();
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void anOffCenterArrivalUsesOneNativeExtinguisherPulse(GameTestHelper helper) {
        Rig rig = new Rig(helper, 100, true);
        for (BlockPos at : BlockPos.betweenClosed(rig.fire.offset(-4, -1, -4), rig.fire.offset(4, -1, 4))) {
            rig.level.setBlock(at, Blocks.STONE.defaultBlockState(), 2);
        }
        rig.level.setBlock(rig.fire, Blocks.FIRE.defaultBlockState(), 2);
        BlockPos feet = rig.fire.west(2);
        var adapter = rig.adapter();
        var candidates = adapter.footings(rig.level, rig.worker, WorldPos.of(rig.level, rig.fire));
        helper.assertTrue(candidates.contains(feet), "a conservative reachable stance is offered");
        helper.assertTrue(!candidates.isEmpty(), "the conservative set is nonempty");
        helper.assertFalse(candidates.contains(rig.fire.west(3)),
            "being within the raw native range is insufficient without the full arrival margin");
        for (BlockPos cell : candidates) {
            Vec3 eye = Vec3.atBottomCenterOf(cell).add(0, rig.worker.body().getEyeHeight(), 0);
            double range = FireExtinguisherSprayEffects.RANGE - Reach.IN_THE_CELL - 0.05D;
            for (double dy : new double[] {-Reach.CELL_HEIGHT, 0, Reach.CELL_HEIGHT}) {
                helper.assertTrue(eye.add(0, dy, 0).distanceToSqr(Vec3.atCenterOf(rig.fire)) <= range * range,
                    "every planned stance reserves the full arrival tolerance and native range");
            }
        }
        rig.worker.body().setPos(Vec3.atBottomCenterOf(feet).add(-0.8D, 0, 0));
        helper.assertTrue(Reach.standingIn(rig.worker.body(), Stances.at(rig.level, feet)),
            "ordinary arrival accepts the body 0.8 blocks off center");
        var session = adapter.open(rig.level, rig.worker, WorldPos.of(rig.level, rig.fire)).orElseThrow();
        helper.assertTrue(session.suppress(rig.level, rig.fire) instanceof Outcome.Done,
            "the native effect extinguishes from the off-center actual eye in one attempt");
        helper.assertTrue(rig.level.getBlockState(rig.fire).isAir(), "the target really stopped burning");
        helper.assertValueEqual(FireExtinguisherItem.getFluid(rig.extinguisher).getAmount(), 95,
            "exactly one native pulse was paid");
        session.close();
        rig.close();
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void aClearCenterRayDoesNotHideAnOccludedArrivalOffset(GameTestHelper helper) {
        Rig rig = new Rig(helper, 100, true);
        BlockPos feet = rig.fire.west(2);
        rig.level.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), 2);
        rig.level.setBlock(rig.fire, Blocks.FIRE.defaultBlockState(), 2);
        var adapter = rig.adapter();
        helper.assertTrue(adapter.footings(rig.level, rig.worker, WorldPos.of(rig.level, rig.fire)).contains(feet),
            "the original arrival volume has no obstruction");
        BlockPos wall = rig.fire.west().north();
        rig.level.setBlock(wall, Blocks.STONE.defaultBlockState(), 2);
        rig.level.setBlock(wall.above(), Blocks.STONE.defaultBlockState(), 2);
        Vec3 eye = Vec3.atBottomCenterOf(feet).add(0, rig.worker.body().getEyeHeight(), 0);
        var hit = rig.level.clip(new ClipContext(eye, Vec3.atCenterOf(rig.fire), ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE, CollisionContext.empty()));
        helper.assertTrue(hit.getType() == HitResult.Type.MISS, "the exact center ray stays clear");
        var offset = rig.level.clip(new ClipContext(eye.add(0, 0, -0.8D), Vec3.atCenterOf(rig.fire),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        helper.assertTrue(offset.getType() == HitResult.Type.BLOCK,
            "a real ray from an ordinary arrival offset intersects the corner wall");
        helper.assertFalse(adapter.footings(rig.level, rig.worker, WorldPos.of(rig.level, rig.fire)).contains(feet),
            "planning rejects an obstacle inside the continuous arrival-ray envelope");
        rig.close();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anEmptyOrNonWaterBottleFallsBackWithoutBeingConsumed(GameTestHelper helper) {
        Rig rig = new Rig(helper, 0);
        var adapter = rig.adapter();
        helper.assertTrue(adapter.open(rig.level, rig.worker, WorldPos.of(rig.level, rig.fire)).isEmpty(),
            "empty bottle cannot spray");
        new FireExtinguisherItemHandler(CreateFireFightingAdd.FIRE_EXTINGUISHER_FLUID, rig.extinguisher)
            .setStoredFluid(new FluidStack(Fluids.LAVA, 100));
        helper.assertFalse(adapter.available(rig.worker), "damage control never sprays igniting fluid");
        helper.assertTrue(adapter.open(rig.level, rig.worker, WorldPos.of(rig.level, rig.fire)).isEmpty(),
            "lava does not open a session");
        helper.assertValueEqual(FireExtinguisherItem.getFluid(rig.extinguisher).getAmount(), 100,
            "unsupported fluid stays intact");
        rig.close();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aWaterSourceRefillUsesAndReturnsARealBucket(GameTestHelper helper) {
        Rig rig = new Rig(helper, 0);
        BlockPos water = rig.source.block(rig.level);
        rig.level.setBlock(water, Blocks.WATER.defaultBlockState(), 2);
        Stances stance = Stances.at(rig.level, rig.worker.body().blockPosition());
        RefillNode bare = new RefillNode(rig.worker.resident().id(), rig.source, stance, () -> true, true);
        helper.assertTrue(Commit.run(rig.level, bare, rig.worker) instanceof Outcome.Failed,
            "water gathering needs real equipment");
        helper.assertTrue(rig.level.getBlockState(water).is(Blocks.WATER), "no bucket means the source stays intact");
        helper.assertValueEqual(FireExtinguisherItem.getFluid(rig.extinguisher).getAmount(), 0, "no water is conjured");
        rig.store.setItem(0, new ItemStack(Items.BUCKET));
        RefillNode refill = new RefillNode(rig.worker.resident().id(), rig.source, stance, () -> true, true);
        Outcome outcome = Commit.run(rig.level, refill, rig.worker);
        helper.assertTrue(outcome instanceof Outcome.Done, "a real bucket picks up the source");
        helper.assertTrue(rig.level.getBlockState(water).isAir(), "the water source is consumed");
        helper.assertValueEqual(FireExtinguisherItem.getFluid(rig.extinguisher).getAmount(), 1000,
            "one source yields one bucket of water");
        var made = ((Outcome.Done) outcome).made();
        helper.assertTrue(made.size() == 1 && made.getFirst().is(Items.BUCKET),
            "one empty physical bucket is returned");
        helper.assertValueEqual(rig.store.countItem(Items.BUCKET), 0,
            "the returned bucket was actually taken from the store");
        rig.close();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void borrowingMovesOneRealExtinguisherAndClosingReleasesItsLoan(GameTestHelper helper) {
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        var body = PersonBody.RESIDENT.get().create(level);
        BlockPos chest = helper.absolutePos(new BlockPos(3, 1, 3));
        for (BlockPos pos : BlockPos.betweenClosed(chest.offset(-2, -1, -2), chest.offset(2, -1, 2))) {
            level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
        }
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 2);
        var store = (Container) level.getBlockEntity(chest);
        ItemStack bottle = new ItemStack(CreateFireFightingAdd.FIRE_EXTINGUISHER_ITEM.get());
        new FireExtinguisherItemHandler(CreateFireFightingAdd.FIRE_EXTINGUISHER_FLUID, bottle)
            .setStoredFluid(new FluidStack(Fluids.WATER, 375));
        store.setItem(0, bottle);
        WorldPos source = WorldPos.of(level, chest);
        colony.stores(FolkwaysFirefightingMod.OWNER, level.dimension(), List.of(source));
        body.setNoAi(true);
        body.moveTo(chest.getX() - .5, chest.getY(), chest.getZ() + .5);
        level.addFreshEntity(body);
        colony.hold(PersonContent.ID,
            Held.Entity.of(body));
        ZoneDrafts.zone(level, colony,
            DamageControlContent.ID,
            chest.offset(-2, 0, -2), chest.offset(2, 1, 2),
            ColonySettings.empty()).orElseThrow();
        var presence = new FirefightingPresence(colony, new ExtinguisherAdapter());
        try {
            InventoryObservations.publish(colony, level);
            presence.tick(level.getServer());
            Node borrow = presence.goals(colony.view(level)).getFirst().nodes().getFirst();
            Worker worker = TestWorker.of(body).within(new Worker.Store(source, store)).effects(Effects.NONE)
                .spill(stack -> { });
            Outcome outcome = Commit.run(level, borrow, worker);
            helper.assertTrue(outcome instanceof Outcome.Done, "the supply task borrows from a real store");
            ItemStack taken = ((Outcome.Done) outcome).made().getFirst();
            helper.assertTrue(store.getItem(0).isEmpty(), "exactly one extinguisher leaves the store");
            helper.assertValueEqual(FireExtinguisherItem.getFluid(taken).getAmount(), 375,
                "borrowing preserves its fluid component");
            helper.assertTrue(TaskTools.holds(body.getUUID(), taken),
                "the same equipment stays with its task");
            presence.closed(Closing.RAZED);
            helper.assertFalse(TaskTools.holds(body.getUUID(), taken),
                "closing releases task ownership without deleting the physical item");
        } finally {
            presence.closed(Closing.RAZED);
            body.discard();
            colony.raze();
        }
        helper.succeed();
    }

    private static final class Rig {
        final ServerLevel level;
        final BlockPos fire;
        final WorldPos source;
        final ItemStack extinguisher = new ItemStack(CreateFireFightingAdd.FIRE_EXTINGUISHER_ITEM.get());
        final SimpleContainer pack = new SimpleContainer(9);
        final SimpleContainer store = new SimpleContainer(9);
        final Worker worker;

        Rig(GameTestHelper helper, int water) {
            this(helper, water, false);
        }

        Rig(GameTestHelper helper, int water, boolean resident) {
            level = helper.getLevel();
            fire = helper.absolutePos(new BlockPos(4, 1, 3));
            source = WorldPos.of(level, fire.west(2));
            level.setBlock(fire.below(), Blocks.NETHERRACK.defaultBlockState(), 2);
            pack.setItem(0, extinguisher);
            if (water > 0) {
                new FireExtinguisherItemHandler(CreateFireFightingAdd.FIRE_EXTINGUISHER_FLUID, extinguisher)
                    .setStoredFluid(new FluidStack(Fluids.WATER, water));
            }
            var mob = resident ? PersonBody.RESIDENT.get().create(level) : EntityType.PIG.create(level);
            mob.setNoAi(true);
            mob.moveTo(fire.getX() - 2.5, fire.getY(), fire.getZ() + .5);
            level.addFreshEntity(mob);
            worker = TestWorker.of(mob, new Resident(mob.getUUID(),
                    resident ? PersonBody.ID : ResourceLocation.withDefaultNamespace("pig")), pack)
                .within(new Worker.Store(source, store)).spill(stack -> { });
        }

        ExtinguisherAdapter adapter() {
            return new ExtinguisherAdapter(id -> java.util.Optional.of(extinguisher.copy()));
        }

        void close() {
            worker.body().discard();
        }
    }
}
