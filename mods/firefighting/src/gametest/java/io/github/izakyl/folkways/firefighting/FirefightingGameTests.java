package io.github.izakyl.folkways.firefighting;

import com.mikoalopex.createfirefightingadd.CreateFireFightingAdd;
import com.mikoalopex.createfirefightingadd.content.equipment.extinguisher.FireExtinguisherItem;
import com.mikoalopex.createfirefightingadd.content.equipment.extinguisher.FireExtinguisherItemHandler;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.TaskTools;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.front.engine.colony.ZoneDrafts;
import io.github.izakyl.folkways.plugins.damagecontrol.DamageControlContent;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import java.lang.reflect.Proxy;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
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
    public static void register(RegisterGameTestsEvent event) { event.register(FirefightingGameTests.class); }

    @GameTest(template = "empty")
    public static void aResidentUsesTheAddonSprayAndItsRealFiveMillibucketCost(GameTestHelper helper) {
        Rig rig = new Rig(helper, 100);
        rig.level.setBlock(rig.fire, Blocks.FIRE.defaultBlockState(), 2);
        var session = new ExtinguisherAdapter().open(rig.level, rig.worker, WorldPos.of(rig.level, rig.fire)).orElseThrow();
        helper.assertTrue(session.suppress(rig.level, rig.fire) instanceof Outcome.Done, "resident spray succeeds without a player");
        helper.assertTrue(rig.level.getBlockState(rig.fire).isAir(), "the native block effect extinguishes fire");
        helper.assertValueEqual(FireExtinguisherItem.getFluid(rig.extinguisher).getAmount(), 95, "one native consumption interval costs five mB");
        helper.assertTrue(rig.pack.getItem(0) == rig.extinguisher, "the physical borrowed stack is modified");
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
        helper.assertTrue(Commit.run(rig.level, refill, rig.worker) instanceof Outcome.Done, "refill uses real item fluid capabilities");
        helper.assertValueEqual(FireExtinguisherItem.getFluid(rig.extinguisher).getAmount(), 1000, "one bucket transfers a thousand mB");
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
        helper.assertValueEqual(RefillNode.transfer(tank, rig.extinguisher.getCapability(Capabilities.FluidHandler.ITEM)), 120,
            "only the missing fluid is transferred");
        helper.assertValueEqual(tank.getFluidAmount(), 780, "the source loses exactly what the bottle gains");
        helper.assertValueEqual(FireExtinguisherItem.getFluid(rig.extinguisher).getAmount(), FireExtinguisherItem.CAPACITY,
            "the bottle cannot overfill");
        rig.close();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aPartialBottleDoesNotDestroyAnIndivisibleWaterBucket(GameTestHelper helper) {
        Rig rig = new Rig(helper, FireExtinguisherItem.CAPACITY - 120);
        ItemStack bucket = new ItemStack(Items.WATER_BUCKET);
        var fluid = bucket.getCapability(Capabilities.FluidHandler.ITEM);
        helper.assertValueEqual(RefillNode.transfer(fluid, rig.extinguisher.getCapability(Capabilities.FluidHandler.ITEM)), 0,
            "a bucket is kept when its whole contents cannot fit");
        helper.assertTrue(fluid.getContainer().is(Items.WATER_BUCKET), "water bucket is preserved");
        helper.assertValueEqual(FireExtinguisherItem.getFluid(rig.extinguisher).getAmount(), FireExtinguisherItem.CAPACITY - 120,
            "no partial free refill");
        rig.close();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aStoppedSessionCannotSprayOrSpendFluid(GameTestHelper helper) {
        Rig rig = new Rig(helper, 100);
        var session = new ExtinguisherAdapter().open(rig.level, rig.worker, WorldPos.of(rig.level, rig.fire)).orElseThrow();
        session.close();
        session.close();
        rig.level.setBlock(rig.fire, Blocks.FIRE.defaultBlockState(), 2);
        helper.assertTrue(session.suppress(rig.level, rig.fire) instanceof Outcome.Failed, "stopping closes the resident session");
        helper.assertValueEqual(FireExtinguisherItem.getFluid(rig.extinguisher).getAmount(), 100, "interruption does not consume fluid");
        helper.assertTrue(rig.level.getBlockState(rig.fire).is(Blocks.FIRE), "a closed session cannot mutate blocks");
        rig.close();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void obstructionAndRangeRefuseWithoutSpendingFluid(GameTestHelper helper) {
        Rig rig = new Rig(helper, 100);
        rig.level.setBlock(rig.fire, Blocks.FIRE.defaultBlockState(), 2);
        var origin = rig.worker.body().getEyePosition();
        var direction = Vec3.atCenterOf(rig.fire).subtract(origin).normalize();
        BlockPos wall = rig.fire.west();
        rig.level.setBlock(wall, Blocks.STONE.defaultBlockState(), 2);
        rig.level.setBlock(wall.above(), Blocks.STONE.defaultBlockState(), 2);
        helper.assertFalse(NativeSprayBridge.spray(rig.level, rig.worker.body(), origin, direction, rig.fire, rig.extinguisher),
            "spray cannot pass a wall");
        rig.level.removeBlock(wall, false);
        rig.level.removeBlock(wall.above(), false);
        var far = origin.add(-8, 0, 0);
        helper.assertFalse(NativeSprayBridge.spray(rig.level, rig.worker.body(), far,
            Vec3.atCenterOf(rig.fire).subtract(far).normalize(), rig.fire, rig.extinguisher),
            "the addon's four-block limit is honoured");
        helper.assertValueEqual(FireExtinguisherItem.getFluid(rig.extinguisher).getAmount(), 100, "refusals spend no water");
        rig.close();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anEmptyOrNonWaterBottleFallsBackWithoutBeingConsumed(GameTestHelper helper) {
        Rig rig = new Rig(helper, 0);
        var adapter = new ExtinguisherAdapter();
        helper.assertTrue(adapter.open(rig.level, rig.worker, WorldPos.of(rig.level, rig.fire)).isEmpty(), "empty bottle cannot spray");
        new FireExtinguisherItemHandler(CreateFireFightingAdd.FIRE_EXTINGUISHER_FLUID, rig.extinguisher)
            .setStoredFluid(new FluidStack(Fluids.LAVA, 100));
        helper.assertFalse(adapter.available(rig.worker), "damage control never sprays igniting fluid");
        helper.assertTrue(adapter.open(rig.level, rig.worker, WorldPos.of(rig.level, rig.fire)).isEmpty(), "lava does not open a session");
        helper.assertValueEqual(FireExtinguisherItem.getFluid(rig.extinguisher).getAmount(), 100, "unsupported fluid stays intact");
        rig.close();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aWaterSourceRefillUsesAndReturnsARealBucket(GameTestHelper helper) {
        Rig rig = new Rig(helper, 0);
        BlockPos water = rig.source.block(rig.level);
        rig.level.setBlock(water, Blocks.WATER.defaultBlockState(), 2);
        rig.store.setItem(0, new ItemStack(Items.BUCKET));
        RefillNode refill = new RefillNode(rig.worker.resident().id(), rig.source,
            Stances.at(rig.level, rig.worker.body().blockPosition()), () -> true, true);
        Outcome outcome = Commit.run(rig.level, refill, rig.worker);
        helper.assertTrue(outcome instanceof Outcome.Done, "a real bucket picks up the source");
        helper.assertTrue(rig.level.getBlockState(water).isAir(), "the water source is consumed");
        helper.assertValueEqual(FireExtinguisherItem.getFluid(rig.extinguisher).getAmount(), 1000, "one source yields one bucket of water");
        var made = ((Outcome.Done) outcome).made();
        helper.assertTrue(made.size() == 1 && made.getFirst().is(Items.BUCKET), "one empty physical bucket is returned");
        helper.assertValueEqual(rig.store.countItem(Items.BUCKET), 0, "the returned bucket was actually taken from the store");
        rig.close();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aWaterSourceCannotRefillWithoutABucket(GameTestHelper helper) {
        Rig rig = new Rig(helper, 0);
        BlockPos water = rig.source.block(rig.level);
        rig.level.setBlock(water, Blocks.WATER.defaultBlockState(), 2);
        RefillNode refill = new RefillNode(rig.worker.resident().id(), rig.source,
            Stances.at(rig.level, rig.worker.body().blockPosition()), () -> true, true);
        helper.assertTrue(Commit.run(rig.level, refill, rig.worker) instanceof Outcome.Failed, "water gathering needs real equipment");
        helper.assertTrue(rig.level.getBlockState(water).is(Blocks.WATER), "no bucket means the source stays intact");
        helper.assertValueEqual(FireExtinguisherItem.getFluid(rig.extinguisher).getAmount(), 0, "no water is conjured");
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
            presence.tick(level.getServer());
            Node borrow = presence.goals(colony.view(level)).getFirst().nodes().getFirst();
            Worker worker = (Worker) Proxy.newProxyInstance(Worker.class.getClassLoader(), new Class<?>[] {Worker.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "body" -> body;
                    case "resident" -> body.resident();
                    case "pack" -> body.pack();
                    case "within" -> List.of(new Worker.Store(source, store));
                    case "effects" -> Effects.NONE;
                    case "placement" -> Placement.NOWHERE;
                    case "held" -> ItemStack.EMPTY;
                    case "supplied", "route" -> List.of();
                    default -> null;
                });
            Outcome outcome = Commit.run(level, borrow, worker);
            helper.assertTrue(outcome instanceof Outcome.Done, "the supply task borrows from a real store");
            ItemStack taken = ((Outcome.Done) outcome).made().getFirst();
            helper.assertTrue(store.getItem(0).isEmpty(), "exactly one extinguisher leaves the store");
            helper.assertValueEqual(FireExtinguisherItem.getFluid(taken).getAmount(), 375, "borrowing preserves its fluid component");
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
            level = helper.getLevel();
            fire = helper.absolutePos(new BlockPos(4, 1, 3));
            source = WorldPos.of(level, fire.west(2));
            level.setBlock(fire.below(), Blocks.NETHERRACK.defaultBlockState(), 2);
            pack.setItem(0, extinguisher);
            if (water > 0) {
                new FireExtinguisherItemHandler(CreateFireFightingAdd.FIRE_EXTINGUISHER_FLUID, extinguisher)
                    .setStoredFluid(new FluidStack(Fluids.WATER, water));
            }
            var mob = EntityType.PIG.create(level);
            mob.setNoAi(true);
            mob.moveTo(fire.getX() - 2.5, fire.getY(), fire.getZ() + .5);
            level.addFreshEntity(mob);
            worker = (Worker) Proxy.newProxyInstance(Worker.class.getClassLoader(), new Class<?>[] {Worker.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "body" -> mob;
                    case "resident" -> new Resident(mob.getUUID(), ResourceLocation.withDefaultNamespace("pig"));
                    case "pack" -> pack;
                    case "within" -> List.of(new Worker.Store(source, store));
                    case "effects" -> Effects.NONE;
                    case "placement" -> Placement.NOWHERE;
                    case "held" -> ItemStack.EMPTY;
                    case "supplied", "route" -> List.of();
                    default -> null;
                });
        }
        void close() { worker.body().discard(); }
    }
}
