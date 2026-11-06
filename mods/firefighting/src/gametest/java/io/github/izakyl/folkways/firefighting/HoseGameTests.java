package io.github.izakyl.folkways.firefighting;

import com.mikoalopex.createfirefightingadd.Config;
import com.mikoalopex.createfirefightingadd.CreateFireFightingAdd;
import com.mikoalopex.createfirefightingadd.api.handheld.HandheldNozzleBindingApi;
import com.mikoalopex.createfirefightingadd.content.equipment.extinguisher.FireExtinguisherItemHandler;
import com.mikoalopex.createfirefightingadd.content.equipment.handheld.FireHydrantCabinetBlockEntity;
import com.mikoalopex.createfirefightingadd.content.equipment.handheld.HandheldNozzleControllerItem;
import com.mikoalopex.createfirefightingadd.content.equipment.handheld.HandheldNozzleType;
import com.mikoalopex.createfirefightingadd.content.fluids.nozzle.HandheldNozzleSprayEffects;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Closures;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.Keepouts;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.engine.labor.LaborFixture;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.plugins.damagecontrol.DamageControlContent;
import io.github.izakyl.folkways.plugins.damagecontrol.SuppressionMethods;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysFirefightingMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HoseGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(HoseGameTests.class);
    }

    @GameTest(template = "empty")
    public static void aHoseBindsTheRealCabinetAndConsumesItsWater(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            int before = HoseSessions.size();
            HoseSession session = rig.open();
            helper.assertValueEqual(HoseSessions.size(), before + 1, "one resident owns one live session");
            helper.assertTrue(rig.cabinet.isBoundTo(rig.body.id()), "the native cabinet names its resident owner");
            helper.assertValueEqual(session.hydrantId(), rig.cabinet.getHydrantId(),
                "the session keeps the real hydrant identity");
            helper.assertTrue(session.suppress(rig.level, rig.fire) instanceof Outcome.Done,
                "the resident sprays without a player");
            helper.assertTrue(rig.level.getBlockState(rig.fire).isAir(),
                "the native ray callback extinguishes the selected fire");
            helper.assertValueEqual(rig.cabinet.getFluid().getAmount(), 90, "one native spray tick consumes ten mB");
            session.close();
            helper.assertFalse(rig.cabinet.hasActiveBinding(), "stop releases the native binding");
            helper.assertValueEqual(HoseSessions.size(), before, "stop also removes the runtime session");
            var player = FakePlayerFactory.getMinecraft(rig.level);
            rig.cabinet.bindTo(player);
            ItemStack controller = new ItemStack(CreateFireFightingAdd.HANDHELD_NOZZLE_CONTROLLER_ITEM.get());
            HandheldNozzleControllerItem.bind(controller, rig.level, rig.cabinet.getBlockPos(),
                rig.cabinet.getHydrantId(), rig.cabinet.getNozzleType());
            helper.assertTrue(HandheldNozzleSprayEffects.spray(player, controller),
                "the real player entry point supplies the consumption reference");
            helper.assertValueEqual(rig.cabinet.getFluid().getAmount(), 80,
                "native and resident pulses consume the same ten mB");
            HandheldNozzleSprayEffects.stopSound(player);
            rig.cabinet.clearBinding(player.getUUID());
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 500)
    public static void hoseWorkKeepsItsConnectionFromPickupUntilTheRemoteFire(GameTestHelper helper) {
        hoseWork(helper, false, 128);
    }

    @GameTest(template = "empty", timeoutTicks = 500)
    public static void cancellingConnectedHoseTravelReturnsTheNozzleWithoutSpraying(GameTestHelper helper) {
        hoseWork(helper, true, 256);
    }

    private static void hoseWork(GameTestHelper helper, boolean cancel, int height) {
        Rig rig = new Rig(helper, height);
        BlockPos cabinet = rig.cabinet.getBlockPos();
        int range = NativeSprayBridge.hoseRange(rig.cabinet.getNozzleType());
        BlockPos fire = cabinet.east(range + 8);
        for (BlockPos at : BlockPos.betweenClosed(cabinet.offset(-8, -1, -2), fire.offset(1, -1, 2))) {
            rig.level.setBlock(at, Blocks.STONE.defaultBlockState(), 2);
        }
        rig.level.removeBlock(rig.fire, false);
        rig.level.setBlock(fire.below(), Blocks.NETHERRACK.defaultBlockState(), 2);
        rig.level.setBlock(fire, Blocks.FIRE.defaultBlockState(), 2);
        rig.body.pack().clearContent();
        rig.body.mob().setPos(Vec3.atBottomCenterOf(cabinet.west(6)));
        rig.body.mob().setNoAi(false);
        ColonyFront.of(rig.colony).addZone(rig.level, DamageControlContent.ID,
            Set.of(cabinet.west(6), cabinet.east(), fire), ColonySettings.empty()).orElseThrow();
        LaborFixture simulation = new LaborFixture(rig.colony, rig.level);
        boolean[] pickedUp = {false};
        boolean[] travelled = {false};
        boolean[] cancelled = {false};
        double[] closest = {Double.MAX_VALUE};
        Runnable[] reopen = {null};
        helper.onEachTick(() -> {
            if (!cancelled[0]) {
                Vec3 before = rig.body.mob().position();
                simulation.tick();
                helper.assertTrue(before.distanceToSqr(rig.body.mob().position()) < 1,
                    "cabinet approach and connected travel never use construction-site relocation");
            }
            closest[0] = Math.min(closest[0], rig.body.mob().position().distanceTo(Vec3.atCenterOf(fire)));
            helper.assertTrue(closest[0] >= 20, "the entire real work lifecycle stays at least twenty blocks away");
            if (rig.cabinet.getFluid().getAmount() < 100) {
                helper.assertTrue(rig.body.mob().position().distanceTo(Vec3.atCenterOf(fire)) >= 25,
                    "the paid native pulse is emitted at least twenty-five blocks from the fire");
            }
            if (rig.cabinet.hasActiveBinding()) {
                if (!pickedUp[0]) {
                    helper.assertTrue(rig.body.mob().distanceToSqr(Vec3.atCenterOf(cabinet)) <= 6.25,
                        "the work binds only when its resident has reached the cabinet");
                    helper.assertTrue(rig.body.mob().swinging, "pickup swings the resident's arm");
                    pickedUp[0] = true;
                    reopen[0] = Closures.close(rig.level, new BoundingBox(cabinet.getX() - 1, cabinet.getY() - 1,
                        cabinet.getZ() - 2, fire.getX() + 1, fire.getY() + 2, fire.getZ() + 2));
                }
                helper.assertTrue(rig.body.mob().distanceToSqr(Vec3.atCenterOf(cabinet))
                    <= HandheldNozzleBindingApi.MAX_BINDING_DISTANCE_SQR, "the connected walk respects hose length");
                if (rig.body.mob().distanceToSqr(Vec3.atCenterOf(cabinet)) > 9) {
                    travelled[0] = true;
                    helper.assertValueEqual(rig.cabinet.getFluid().getAmount(), 100,
                        "walking with the connected nozzle spends no spray water");
                    helper.assertTrue(rig.body.mob().getMainHandItem()
                        .is(CreateFireFightingAdd.HANDHELD_NOZZLE_CONTROLLER_ITEM.get()),
                        "the native controller stays visible while the hose is carried");
                    if (cancel) {
                        simulation.close();
                        cancelled[0] = true;
                        helper.assertFalse(rig.cabinet.hasActiveBinding(),
                            "cancellation immediately returns the nozzle");
                        helper.assertTrue(rig.body.mob().getNavigation().isDone(),
                            "cancellation stops connected travel");
                    }
                }
            }
        });
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(cancel ? cancelled[0] : rig.level.getBlockState(fire).isAir(),
                "ordinary damage-control work finishes its connected travel: doing=" + rig.body.doing()
                    + " at=" + rig.body.mob().blockPosition() + " bound=" + pickedUp[0]
                    + " travelled=" + travelled[0] + " fire=" + fire + " water=" + rig.cabinet.getFluid()
                    + " refusal=" + rig.body.balk());
        }).thenExecute(() -> {
            try {
                helper.assertTrue(pickedUp[0] && travelled[0], "one work lifecycle picks up and carries the hose");
                helper.assertValueEqual(rig.cabinet.getFluid().getAmount(), cancel ? 100 : 90,
                    "only completed suppression spends one native pulse");
                helper.assertTrue(cancel == rig.level.getBlockState(fire).is(Blocks.FIRE),
                    "cancelled travel leaves the distant fire for another responder");
                helper.assertFalse(rig.cabinet.hasActiveBinding(), "completed work returns the nozzle");
            } finally {
                simulation.close();
                if (reopen[0] != null) {
                    reopen[0].run();
                }
                rig.level.removeBlock(fire, false);
                rig.close();
            }
        }).thenSucceed();
    }

    @GameTest(template = "empty")
    public static void theConnectionAllowsThirtyTwoBlocksButNoMore(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            HoseSession session = rig.open();
            Vec3 cabinet = Vec3.atCenterOf(rig.cabinet.getBlockPos());
            rig.body.mob().setPos(cabinet.add(HandheldNozzleBindingApi.MAX_BINDING_DISTANCE, 0, 0));
            helper.assertTrue(session.valid(), "the native thirty-two block boundary is inclusive");
            rig.body.mob().setPos(cabinet.add(HandheldNozzleBindingApi.MAX_BINDING_DISTANCE + .01, 0, 0));
            helper.assertFalse(session.valid(), "even a small step beyond the boundary disconnects");
            HoseSessions.tick(new ServerTickEvent.Post(() -> true, rig.level.getServer()));
            helper.assertFalse(rig.cabinet.hasActiveBinding(), "the invalid connection is released on the server tick");
            helper.assertValueEqual(rig.cabinet.getFluid().getAmount(), 100, "out-of-range cleanup spends no fluid");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void bothNozzlesPlanConservativeFootingsAndSprayFromAnOffCenterArrival(GameTestHelper helper) {
        for (HandheldNozzleType type : List.of(HandheldNozzleType.CONE, HandheldNozzleType.FLAT)) {
            try (Rig rig = new Rig(helper)) {
                BlockPos cabinet = rig.cabinet.getBlockPos();
                for (BlockPos at : BlockPos.betweenClosed(cabinet.offset(-2, -1, -2), cabinet.offset(12, -1, 2))) {
                    rig.level.setBlock(at, Blocks.STONE.defaultBlockState(), 2);
                }
                rig.nozzle(type);
                rig.level.setBlock(rig.fire, Blocks.FIRE.defaultBlockState(), 2);
                rig.body.mob().setPos(Vec3.atBottomCenterOf(cabinet.east()));
                var feet = HoseAdapter.sprayFootings(rig.level, rig.worker, rig.at(), rig.cabinet);
                helper.assertFalse(feet.isEmpty(), "each native nozzle offers conservative spray positions");
                double limit = NativeSprayBridge.hoseRange(type) - Reach.IN_THE_CELL - 0.05D;
                for (BlockPos cell : feet) {
                    Vec3 eye = Vec3.atBottomCenterOf(cell).add(0, rig.body.mob().getEyeHeight(), 0);
                    for (double dy : new double[] {-Reach.CELL_HEIGHT, 0, Reach.CELL_HEIGHT}) {
                        helper.assertTrue(eye.add(0, dy, 0).distanceToSqr(Vec3.atCenterOf(rig.fire)) <= limit * limit,
                            "all nozzle stances reserve the full arrival tolerance within the native range");
                    }
                }
                BlockPos stance = feet.iterator().next();
                HoseSession session = rig.open();
                rig.body.mob().setPos(Vec3.atBottomCenterOf(stance).add(0, 0, 0.8D));
                helper.assertTrue(Reach.standingIn(rig.body.mob(), Stances.at(rig.level, stance)),
                    "ordinary arrival accepts the 0.8-block offset");
                int before = rig.cabinet.getFluid().getAmount();
                helper.assertTrue(session.suppress(rig.level, rig.fire) instanceof Outcome.Done,
                    "one native pulse suppresses from the off-center stance: " + type);
                helper.assertTrue(rig.level.getBlockState(rig.fire).isAir(), "the native target was actually removed");
                helper.assertValueEqual(rig.cabinet.getFluid().getAmount(), before - 10,
                    "exactly one native hose pulse was spent");
                session.close();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void sprayPlanningChoosesOneReachablePositionNearTheCabinet(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, 24)) {
            BlockPos cabinet = rig.cabinet.getBlockPos();
            BlockPos fire = cabinet.east(30);
            for (BlockPos cell : BlockPos.betweenClosed(cabinet.offset(-3, -1, -3), fire.offset(1, -1, 3))) {
                rig.level.setBlock(cell, Blocks.STONE.defaultBlockState(), 2);
            }
            rig.level.removeBlock(rig.fire, false);
            rig.level.setBlock(fire, Blocks.FIRE.defaultBlockState(), 2);
            rig.body.mob().setOnGround(true);
            var navigation = rig.body.mob().getNavigation();
            var original = navigation.createPath(cabinet.south(2), 0);
            navigation.moveTo(original, 1);
            BlockPos originalTarget = navigation.getTargetPos();
            var feet = HoseAdapter.sprayFootings(rig.level, rig.worker, WorldPos.of(rig.level, fire), rig.cabinet);
            helper.assertValueEqual(feet.size(), 1, "planning commits one preferred destination before walking");
            BlockPos chosen = feet.iterator().next();
            helper.assertTrue(chosen.distSqr(cabinet) <= 9, "the preferred reachable stance stays near the cabinet");
            helper.assertTrue(chosen.distSqr(fire) >= 25 * 25, "the chosen stance uses the native long range");
            helper.assertTrue(navigation.getPath() == original && navigation.getTargetPos().equals(originalTarget),
                "planning cannot mutate the resident's current navigation");
            rig.body.mob().setPos(Vec3.atBottomCenterOf(chosen).add(0, 0, 0.8D));
            helper.assertTrue(Reach.stanceHolding(rig.body.mob().position(), HoseAdapter.sprayFootings(
                rig.level, rig.worker, WorldPos.of(rig.level, fire), rig.cabinet)).isPresent(),
                "a safe off-center current position is retained without another approach");
            rig.level.removeBlock(fire, false);
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void anUnreachableNearCabinetStanceFallsBackToAReachableOne(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, 48)) {
            BlockPos cabinet = rig.cabinet.getBlockPos();
            BlockPos fire = cabinet.east(30);
            BlockPos isolated = cabinet.east(2);
            for (BlockPos cell : BlockPos.betweenClosed(cabinet.offset(-3, -1, -4), fire.offset(1, -1, 4))) {
                rig.level.setBlock(cell, Blocks.STONE.defaultBlockState(), 2);
            }
            rig.level.removeBlock(rig.fire, false);
            rig.level.setBlock(fire, Blocks.FIRE.defaultBlockState(), 2);
            rig.body.mob().setPos(Vec3.atBottomCenterOf(cabinet.west()));
            Runnable remove = Keepouts.install((level, x, y, z) -> level == rig.level
                && Math.abs(x - isolated.getX()) <= 1 && Math.abs(z - isolated.getZ()) <= 1
                && (x != isolated.getX() || z != isolated.getZ()));
            try {
                helper.assertTrue(SprayFootings.serves(rig.level, rig.body.mob(), isolated, fire,
                    NativeSprayBridge.hoseRange(rig.cabinet.getNozzleType()), true),
                    "the first preference is a safe spray stance surrounded by forbidden travel cells");
                var blocked = rig.body.mob().getNavigation().createPath(isolated, 0);
                helper.assertTrue(blocked == null || !blocked.canReach(), "the first candidate has no complete path");
                var feet = HoseAdapter.sprayFootings(rig.level, rig.worker, WorldPos.of(rig.level, fire), rig.cabinet);
                helper.assertValueEqual(feet.size(), 1, "an unreachable first preference does not hide alternatives");
                BlockPos chosen = feet.iterator().next();
                helper.assertFalse(chosen.equals(isolated), "a partial path cannot win stance selection");
                var path = rig.body.mob().getNavigation().createPath(chosen, 0);
                helper.assertTrue(path != null && path.canReach(), "the fallback has a complete resident path");
            } finally {
                remove.run();
                rig.level.removeBlock(fire, false);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void aFireBeyondTheNativeRangeRequiresAnotherStanceOrIsRefused(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, 72)) {
            BlockPos cabinet = rig.cabinet.getBlockPos();
            int range = NativeSprayBridge.hoseRange(rig.cabinet.getNozzleType());
            BlockPos fire = cabinet.east(range + 20);
            for (BlockPos cell : BlockPos.betweenClosed(cabinet.offset(-3, -1, -16), fire.offset(1, -1, 16))) {
                rig.level.setBlock(cell, Blocks.STONE.defaultBlockState(), 2);
            }
            rig.level.removeBlock(rig.fire, false);
            rig.level.setBlock(fire, Blocks.FIRE.defaultBlockState(), 2);
            var feet = HoseAdapter.sprayFootings(rig.level, rig.worker, WorldPos.of(rig.level, fire), rig.cabinet);
            helper.assertValueEqual(feet.size(), 1, "a complete path can reach a new stance inside native range");
            BlockPos chosen = feet.iterator().next();
            helper.assertFalse(chosen.equals(rig.body.mob().blockPosition()), "the old out-of-range stance is refused");
            helper.assertTrue(SprayFootings.within(Vec3.atBottomCenterOf(chosen)
                .add(0, rig.body.mob().getEyeHeight(), 0), Vec3.atCenterOf(fire), range),
                "the new stance reserves ordinary arrival within the actual native range");
            BlockPos remote = fire.east((int) HandheldNozzleBindingApi.MAX_BINDING_DISTANCE + 10);
            rig.level.getChunkAt(remote);
            rig.level.setBlock(remote.below(), Blocks.NETHERRACK.defaultBlockState(), 2);
            rig.level.setBlock(remote, Blocks.FIRE.defaultBlockState(), 2);
            helper.assertTrue(HoseAdapter.sprayFootings(rig.level, rig.worker,
                WorldPos.of(rig.level, remote), rig.cabinet).isEmpty(),
                "a target beyond both hose binding and nozzle range is refused");
            helper.assertValueEqual(rig.cabinet.getFluid().getAmount(), 100, "planning spends no spray water");
            rig.level.removeBlock(remote, false);
            rig.level.removeBlock(remote.below(), false);
            rig.level.removeBlock(fire, false);
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void aDistantResidentCanPlanCabinetPickupBeforePlanningItsSprayJourney(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, 144)) {
            rig.body.mob().setPos(Vec3.atBottomCenterOf(rig.cabinet.getBlockPos().west(80)));
            helper.assertFalse(rig.adapter.footings(rig.level, rig.worker, rig.at()).isEmpty(),
                "pickup planning is not limited by the live pathfinder's range to the later spray stance");
            helper.assertTrue(rig.adapter.open(rig.level, rig.worker, rig.at()).isEmpty(),
                "remote discovery still cannot bind or spray before walking to the cabinet");
            helper.assertValueEqual(rig.cabinet.getFluid().getAmount(), 100, "remote pickup planning spends no water");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void bothNozzlesHitFromTheDistantOrdinaryArrivalEnvelope(GameTestHelper helper) {
        for (HandheldNozzleType nozzle : List.of(HandheldNozzleType.CONE, HandheldNozzleType.FLAT)) {
            try (Rig rig = new Rig(helper, 120)) {
                BlockPos feet = rig.cabinet.getBlockPos().east(2);
                int range = NativeSprayBridge.hoseRange(nozzle);
                BlockPos fire = feet.east(range - 3);
                rig.nozzle(nozzle);
                rig.level.setBlock(fire.below(), Blocks.NETHERRACK.defaultBlockState(), 2);
                rig.level.setBlock(fire, Blocks.FIRE.defaultBlockState(), 2);
                helper.assertTrue(SprayFootings.serves(rig.level, rig.body.mob(), feet, fire, range, true),
                    "a distant stance passes conservative planning for each nozzle");
                for (double dy : new double[] {-Reach.CELL_HEIGHT, 0, Reach.CELL_HEIGHT}) {
                    for (int step = 0; step < 8; step++) {
                        double angle = step * Math.PI / 4;
                        Vec3 eye = Vec3.atBottomCenterOf(feet).add(Reach.IN_THE_CELL * Math.cos(angle),
                            rig.body.mob().getEyeHeight() + dy, Reach.IN_THE_CELL * Math.sin(angle));
                        Vec3 direction = Vec3.atCenterOf(fire).subtract(eye).normalize();
                        rig.level.setBlock(fire, Blocks.FIRE.defaultBlockState(), 2);
                        NativeSprayBridge.hose(rig.level, rig.body.mob(), rig.cabinet,
                            NativeSprayBridge.hoseOrigin(eye, direction), direction, new FluidStack(Fluids.WATER, 10));
                        helper.assertTrue(rig.level.getBlockState(fire).isAir(),
                            "one real native effect hits at the distant arrival boundary: " + nozzle
                                + " offset=" + step + " height=" + dy);
                    }
                }
                Vec3 beyond = Vec3.atCenterOf(fire).add(0, 0, -(range + 3));
                rig.level.setBlock(fire, Blocks.FIRE.defaultBlockState(), 2);
                NativeSprayBridge.hose(rig.level, rig.body.mob(), rig.cabinet, beyond, new Vec3(0, 0, 1),
                    new FluidStack(Fluids.WATER, 10));
                helper.assertTrue(rig.level.getBlockState(fire).is(Blocks.FIRE),
                    "the native nozzle itself rejects targets beyond its range: " + nozzle);
                rig.level.removeBlock(fire, false);
                rig.level.removeBlock(fire.below(), false);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nativeEffectiveRangesAndCenterSamplesRemainThePlanningContract(GameTestHelper helper) {
        Vec3 direction = new Vec3(1, -0.05, 0.3).normalize();
        for (HandheldNozzleType nozzle : List.of(HandheldNozzleType.CONE, HandheldNozzleType.FLAT)) {
            int effective = nozzle == HandheldNozzleType.CONE
                ? Config.coneNozzleMaxDistance : Config.flatNozzleMaxDistance;
            helper.assertValueEqual(NativeSprayBridge.hoseRange(nozzle), effective,
                "the bridge reads the native effective range without a resident-specific cap");
            for (long tick = 0; tick < 32; tick++) {
                var rays = NativeSprayBridge.hoseShape(nozzle).stratifiedDirections(direction,
                    Math.max(8, Math.min(28, effective / 2)), tick, new java.util.Random(tick * 37));
                helper.assertValueEqual(rays.getFirst(), direction,
                    "both native spray shapes always sample the aimed center before their rotating spread");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 500)
    public static void aFlatHoseSuppressesThirtyBlocksAwayWithoutApproachingTheFire(GameTestHelper helper) {
        int height = 192;
        int offset = 1024;
        BlockPos cabinet = helper.absolutePos(new BlockPos(2, 1 + height, 3 + offset));
        BlockPos fire = cabinet.east(30);
        Set<Long> forced = new LinkedHashSet<>();
        for (int x = (cabinet.getX() - 8) >> 4; x <= (cabinet.getX() + 70) >> 4; x++) {
            for (int z = (cabinet.getZ() - 4) >> 4; z <= (cabinet.getZ() + 4) >> 4; z++) {
                long chunk = ChunkPos.asLong(x, z);
                if (!helper.getLevel().getForcedChunks().contains(chunk)
                    && helper.getLevel().setChunkForced(x, z, true)) {
                    forced.add(chunk);
                }
                helper.getLevel().getChunk(x, z);
            }
        }
        Rig[] scene = {null};
        LaborFixture[] simulation = {null};
        double[] closest = {Double.MAX_VALUE};
        boolean[] pickedUp = {false};
        helper.onEachTick(() -> {
            if (scene[0] == null) {
                if (!helper.getLevel().isPositionEntityTicking(cabinet)) {
                    return;
                }
                Rig rig = new Rig(helper, height, offset);
                scene[0] = rig;
                rig.forced.addAll(forced);
                for (BlockPos cell : BlockPos.betweenClosed(cabinet.offset(-6, -1, -3), fire.offset(1, -1, 3))) {
                    rig.level.setBlock(cell, Blocks.STONE.defaultBlockState(), 2);
                }
                rig.nozzle(HandheldNozzleType.FLAT);
                rig.level.removeBlock(rig.fire, false);
                rig.level.setBlock(fire.below(), Blocks.NETHERRACK.defaultBlockState(), 2);
                rig.level.setBlock(fire, Blocks.FIRE.defaultBlockState(), 2);
                rig.body.pack().clearContent();
                rig.body.mob().setPos(Vec3.atBottomCenterOf(cabinet.west(5)));
                rig.body.mob().setNoAi(false);
                ColonyFront.of(rig.colony).addZone(rig.level, DamageControlContent.ID,
                    Set.of(cabinet.west(5), cabinet.east(2), fire), ColonySettings.empty()).orElseThrow();
                simulation[0] = new LaborFixture(rig.colony, rig.level);
            }
            Rig rig = scene[0];
            closest[0] = Math.min(closest[0], rig.body.mob().position().distanceTo(Vec3.atCenterOf(fire)));
            simulation[0].tick();
            closest[0] = Math.min(closest[0], rig.body.mob().position().distanceTo(Vec3.atCenterOf(fire)));
            helper.assertTrue(closest[0] >= 20, "the entire real work lifecycle stays at least twenty blocks away");
            if (rig.cabinet.hasActiveBinding() || rig.cabinet.getFluid().getAmount() < 100) {
                pickedUp[0] = true;
            }
            if (rig.cabinet.getFluid().getAmount() < 100) {
                helper.assertTrue(rig.body.mob().position().distanceTo(Vec3.atCenterOf(fire)) >= 25,
                    "the paid native pulse is emitted at least twenty-five blocks from the fire");
            }
        });
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(scene[0] != null, "the isolated scenario's chunks begin entity ticking");
            Rig rig = scene[0];
            helper.assertTrue(rig.level.getBlockState(fire).isAir(),
                "real distant hose work extinguishes: at=" + rig.body.mob().position()
                    + " doing=" + rig.body.doing() + " water=" + rig.cabinet.getFluid()
                    + " refusal=" + rig.body.balk());
        }).thenExecute(() -> {
            Rig rig = scene[0];
            try {
                helper.assertTrue(pickedUp[0], "the resident picked up the real cabinet nozzle");
                helper.assertTrue(closest[0] >= 20, "recorded minimum distance never entered the fire approach");
                helper.assertValueEqual(rig.cabinet.getFluid().getAmount(), 90, "one native pulse extinguishes");
                helper.assertFalse(rig.cabinet.hasActiveBinding(), "completion returns the nozzle");
            } finally {
                simulation[0].close();
                rig.level.removeBlock(fire, false);
                rig.close();
            }
        }).thenSucceed();
    }

    @GameTest(template = "empty")
    public static void aPlayerCanTakeOverAndClosesTheResidentSession(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            HoseSession session = rig.open();
            var player = FakePlayerFactory.getMinecraft(rig.level);
            ItemStack controller = new ItemStack(CreateFireFightingAdd.HANDHELD_NOZZLE_CONTROLLER_ITEM.get());
            ((HandheldNozzleControllerItem) controller.getItem())
                .tryBindToCabinet(controller, rig.level, rig.cabinet.getBlockPos(), player);
            helper.assertTrue(rig.cabinet.isBoundTo(player.getUUID()),
                "native player binding takes over through forceClearBinding");
            helper.assertFalse(session.valid(), "the resident session is closed before the player takes over");
            helper.assertTrue(session.suppress(rig.level, rig.fire) instanceof Outcome.Failed,
                "the old owner cannot spray");
            session.close();
            helper.assertTrue(rig.cabinet.isBoundTo(player.getUUID()),
                "late resident cleanup cannot clear the new player");
            helper.assertValueEqual(rig.cabinet.getFluid().getAmount(), 100, "takeover does not lose water");
            helper.assertTrue(rig.adapter.open(rig.level, rig.worker, rig.at()).isEmpty(),
                "an active player keeps exclusive ownership");
            helper.assertTrue(rig.cabinet.isBoundTo(player.getUUID()), "the player's binding survives the refusal");
            HandheldNozzleControllerItem.clearBinding(rig.level, controller, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void everyReleaseTriggerFreesTheCabinet(GameTestHelper helper) {
        Map<String, BiConsumer<Rig, HoseSession>> triggers = new LinkedHashMap<>();
        triggers.put("an equipment change", (rig, session) -> {
            rig.cabinet.getItemStackHandler()
                .setStackInSlot(FireHydrantCabinetBlockEntity.SLOT_NOZZLE, ItemStack.EMPTY);
            helper.assertTrue(session.suppress(rig.level, rig.fire) instanceof Outcome.Failed,
                "a disconnected hose cannot draw water");
            helper.assertValueEqual(rig.cabinet.getFluid().getAmount(), 100, "equipment changes conserve fluid");
        });
        triggers.put("a replaced cabinet", (rig, session) -> {
            BlockPos pos = rig.cabinet.getBlockPos();
            rig.level.removeBlock(pos, false);
            rig.level.setBlock(pos, CreateFireFightingAdd.FIRE_HYDRANT_CABINET.get().defaultBlockState(), 3);
            helper.assertFalse(((FireHydrantCabinetBlockEntity) rig.level.getBlockEntity(pos)).getHydrantId()
                .equals(session.hydrantId()), "a new cabinet has a different identity");
            HoseSessions.tick(new ServerTickEvent.Post(() -> true, rig.level.getServer()));
        });
        triggers.put("death", (rig, session) -> rig.body.mob().kill());
        triggers.put("entity unload", (rig, session) ->
            HoseSessions.left(new EntityLeaveLevelEvent(rig.body.mob(), rig.level)));
        triggers.put("chunk unload", (rig, session) ->
            HoseSessions.chunkUnloaded(new ChunkEvent.Unload(rig.level.getChunkAt(rig.cabinet.getBlockPos()))));
        triggers.put("colony close", (rig, session) -> HoseSessions.closeColony(rig.colony.id()));
        triggers.forEach((trigger, release) -> {
            try (Rig rig = new Rig(helper)) {
                HoseSession session = rig.open();
                release.accept(rig, session);
                helper.assertFalse(session.valid(), trigger + " closes the resident session");
                helper.assertFalse(((FireHydrantCabinetBlockEntity) rig.level.getBlockEntity(rig.cabinet.getBlockPos()))
                    .hasActiveBinding(), trigger + " immediately leaves no resident binding");
            }
        });
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void transientResidentBindingsAreNotSavedAsPlayers(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            rig.open();
            CompoundTag saved = rig.cabinet.saveWithoutMetadata(rig.level.registryAccess());
            helper.assertFalse(saved.hasUUID("BoundPlayer"),
                "a crash or reload must not restore a resident as an absent player");
            helper.assertTrue(rig.cabinet.isBoundTo(rig.body.id()), "saving does not disturb the live task");
            rig.cabinet.forceClearBinding();
            var player = FakePlayerFactory.getMinecraft(rig.level);
            rig.cabinet.bindTo(player);
            helper.assertTrue(rig.cabinet.saveWithoutMetadata(rig.level.registryAccess()).hasUUID("BoundPlayer"),
                "existing player binding persistence remains intact");
            rig.cabinet.clearBinding(player.getUUID());
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aNativeHoseMissReportsFailureAfterSpendingThePulse(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            HoseSession session = rig.open();
            BlockPos wall = rig.fire.west();
            rig.level.setBlock(wall, Blocks.STONE.defaultBlockState(), 2);
            rig.level.setBlock(wall.above(), Blocks.STONE.defaultBlockState(), 2);
            helper.assertTrue(session.suppress(rig.level, rig.fire) instanceof Outcome.Failed,
                "the native ray cannot pass a wall");
            helper.assertTrue(session.performed(), "the paid hose pulse authorizes the node's settle window");
            helper.assertValueEqual(rig.cabinet.getFluid().getAmount(), 90,
                "a native attempt spends real water and reports the actual miss");
            helper.assertTrue(rig.level.getBlockState(rig.fire).is(Blocks.FIRE), "the obstructed fire remains");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aLastPartialSprayUsesOnlyTheFluidActuallyPresent(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            rig.cabinet.getFluidHandler(null).drain(1000, FluidAction.EXECUTE);
            rig.cabinet.getFluidHandler(null).fill(new FluidStack(Fluids.WATER, 3), FluidAction.EXECUTE);
            HoseSession session = rig.open();
            helper.assertTrue(session.suppress(rig.level, rig.fire) instanceof Outcome.Done,
                "the addon allows the last partial tick");
            helper.assertTrue(rig.cabinet.getFluid().isEmpty(), "exactly the three remaining mB are consumed");
            helper.assertTrue(rig.level.getBlockState(rig.fire).isAir(), "the last partial spray still has its effect");
            session.close();
            helper.assertTrue(rig.adapter.open(rig.level, rig.worker, rig.at()).isEmpty(),
                "empty cabinets cannot start another session");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aHoseTakesPrecedenceAndOffersCabinetPickupFootings(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            var chosen = SuppressionMethods.all().stream()
                .filter(method -> method.reaches(rig.level, rig.worker, rig.at()))
                .findFirst()
                .orElseThrow();
            helper.assertTrue(chosen instanceof HoseAdapter, "an available hydrant precedes the portable extinguisher");
            helper.assertTrue(rig.adapter.footings(rig.level, rig.worker, rig.at())
                .contains(rig.body.mob().blockPosition()),
                "an adjacent safe footing can take the nozzle");
            Vec3 far = Vec3.atBottomCenterOf(rig.cabinet.getBlockPos().east(10));
            rig.level.setBlock(rig.cabinet.getBlockPos().east(10).below(), Blocks.STONE.defaultBlockState(), 2);
            rig.body.mob().setPos(far);
            helper.assertFalse(rig.adapter.footings(rig.level, rig.worker, rig.at())
                .contains(rig.body.mob().blockPosition()),
                "pickup footings remain at the cabinet to the hand tool's four-block reach: body=" + rig.body.mob()
                .blockPosition()
                + " footing=" + Footing.withFeetAt(rig.level, rig.body.mob().blockPosition())
                + " reaches=" + rig.adapter.reaches(rig.level, rig.worker, rig.at()));
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void oneSessionCannotSprayTwiceOnTheSameTick(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        HoseSession session = rig.open();
        helper.assertTrue(session.suppress(rig.level, rig.fire) instanceof Outcome.Done, "the first pulse succeeds");
        rig.level.setBlock(rig.fire, Blocks.FIRE.defaultBlockState(), 2);
        helper.assertTrue(session.suppress(rig.level, rig.fire) instanceof Outcome.Failed,
            "a repeated call cannot overdraw in the same native tick");
        helper.assertValueEqual(rig.cabinet.getFluid().getAmount(), 90, "the second call leaves fluid unchanged");
        helper.startSequence().thenExecuteAfter(1, () -> {
            try {
                helper.assertTrue(session.suppress(rig.level, rig.fire) instanceof Outcome.Done,
                    "the next server tick permits another pulse");
                helper.assertValueEqual(rig.cabinet.getFluid().getAmount(), 80, "two ticks consume exactly twenty mB");
            } finally {
                rig.close();
            }
        }).thenSucceed();
    }

    @GameTest(template = "empty")
    public static void oneHosePulseSuppressesSeveralNativeSamples(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            BlockPos second = rig.fire.east();
            BlockPos outside = rig.fire.north(5);
            rig.level.setBlock(second.below(), Blocks.NETHERRACK.defaultBlockState(), 2);
            rig.level.setBlock(second, Blocks.FIRE.defaultBlockState(), 2);
            rig.level.setBlock(outside.below(), Blocks.NETHERRACK.defaultBlockState(), 2);
            rig.level.setBlock(outside, Blocks.FIRE.defaultBlockState(), 2);
            HoseSession session = rig.open();
            session.suppress(rig.level, rig.fire);
            helper.assertTrue(rig.level.getBlockState(rig.fire).isAir(), "the selected fire is suppressed");
            helper.assertTrue(rig.level.getBlockState(second).isAir(), "the native rays suppress another fire too");
            helper.assertValueEqual(rig.cabinet.getFluid().getAmount(), 90, "the area pulse costs one native tick");
            helper.assertTrue(rig.level.getBlockState(outside).is(Blocks.FIRE),
                "cells outside the native rays are untouched");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void stoppingAlsoStopsTheAddonsLoopSound(GameTestHelper helper) throws ReflectiveOperationException {
        try (Rig rig = new Rig(helper)) {
            HoseSession session = rig.open();
            session.suppress(rig.level, rig.fire);
            var sounds = Class.forName("com.mikoalopex.createfirefightingadd.content.fluids.nozzle.NozzleSpraySounds")
                .getDeclaredField("ACTIVE");
            sounds.setAccessible(true);
            var map = (Map<?, ?>) ((Map<?, ?>) sounds.get(null)).get(rig.level);
            String key = "handheld:" + rig.body.id();
            helper.assertTrue(map.containsKey(key), "the resident uses the addon's looping sound");
            session.close();
            helper.assertFalse(map.containsKey(key), "stopping removes the native sound immediately");
            session.close();
            helper.assertFalse(map.containsKey(key), "repeated cleanup is harmless");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aCloseFlatSprayHitsTheFireBlockWithoutItsCentreInsideTheFan(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            rig.nozzle(HandheldNozzleType.FLAT);
            rig.body.mob().setPos(Vec3.atBottomCenterOf(rig.fire.west()));
            HoseSession session = rig.open();
            helper.assertTrue(session.suppress(rig.level, rig.fire) instanceof Outcome.Done,
                "native rays test the fire's block volume");
            helper.assertTrue(rig.level.getBlockState(rig.fire).isAir(),
                "a nearby fire can still receive a flat spray");
            helper.assertValueEqual(rig.cabinet.getFluid().getAmount(), 90, "a close shot has the same native cost");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aSameTickStopKeepsTheLastBurstUntilItFinishes(GameTestHelper helper) {
        long now = helper.getLevel().getGameTime();
        SprayTail tail = SprayTail.start(now).stop(now);
        helper.assertTrue(tail.hose(now), "start and stop received in one frame still show the hose");
        helper.assertFalse(tail.finished(now, true), "even a burst without projectiles gets a short visible hose");
        helper.assertFalse(tail.hose(now + 4), "the disconnected hose disappears after four client ticks");
        helper.assertFalse(tail.finished(now + 4, false), "already emitted native projectiles keep ticking");
        helper.assertTrue(tail.finished(now + 40, true), "the view retires only after its projectiles finish");
        helper.assertValueEqual(tail.stop(now + 3), tail, "duplicate stops do not extend the visual tail");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void cabinetDiscoveryUsesTicksAndForgetsUnloadedChunks(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            helper.assertTrue(Hydrants.nearby(rig.level, rig.fire, 4).contains(rig.cabinet),
                "the real cabinet tick registers it in the shared index");
            Hydrants.unload(rig.level, new ChunkPos(rig.cabinet.getBlockPos()));
            helper.assertFalse(Hydrants.nearby(rig.level, rig.fire, 4).contains(rig.cabinet),
                "unloaded cabinet entries are discarded");
            FireHydrantCabinetBlockEntity.tick(rig.level, rig.cabinet.getBlockPos(), rig.cabinet.getBlockState(),
                rig.cabinet);
            helper.assertTrue(Hydrants.nearby(rig.level, rig.fire, 4).contains(rig.cabinet),
                "a loaded cabinet announces itself again");
            rig.level.removeBlock(rig.cabinet.getBlockPos(), false);
            helper.assertFalse(Hydrants.nearby(rig.level, rig.fire, 4).contains(rig.cabinet),
                "removed cabinet entries are pruned without binding");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void cabinetDiscoveryRotatesThroughBoundedBatches(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, 128, 0));
        origin = new BlockPos((origin.getX() >> 4) << 4, origin.getY(), (origin.getZ() >> 4) << 4);
        List<FireHydrantCabinetBlockEntity> cabinets = new ArrayList<>();
        try {
            for (int i = 0; i < 80; i++) {
                BlockPos pos = origin.offset(i % 10, 0, i / 10);
                level.setBlock(pos, CreateFireFightingAdd.FIRE_HYDRANT_CABINET.get().defaultBlockState(), 2);
                var cabinet = (FireHydrantCabinetBlockEntity) level.getBlockEntity(pos);
                FireHydrantCabinetBlockEntity.tick(level, pos, cabinet.getBlockState(), cabinet);
                cabinets.add(cabinet);
            }
            Set<FireHydrantCabinetBlockEntity> seen = new LinkedHashSet<>();
            for (int i = 0; i < 4; i++) {
                var batch = Hydrants.nearby(level, origin, 0);
                helper.assertTrue(batch.size() <= 64, "a cabinet lookup checks at most 64 indexed entries");
                seen.addAll(batch);
            }
            helper.assertTrue(seen.containsAll(cabinets), "later cabinets are not hidden by the first full batch");
        } finally {
            cabinets.forEach(cabinet -> level.removeBlock(cabinet.getBlockPos(), false));
        }
        helper.succeed();
    }

    private static final class Rig implements AutoCloseable {
        final ServerLevel level;
        final Colony colony;
        final Body body;
        final Worker worker;
        final FireHydrantCabinetBlockEntity cabinet;
        final BlockPos fire;
        final HoseAdapter adapter = new HoseAdapter();
        final Set<Long> forced = new LinkedHashSet<>();

        Rig(GameTestHelper helper) {
            this(helper, 0);
        }

        Rig(GameTestHelper helper, int height) {
            this(helper, height, 0);
        }

        Rig(GameTestHelper helper, int height, int offset) {
            level = helper.getLevel();
            colony = Colonies.mint(level.getServer());
            BlockPos pos = helper.absolutePos(new BlockPos(2, 1 + height, 3 + offset));
            for (BlockPos cell : BlockPos.betweenClosed(pos.offset(-1, -1, -2), pos.offset(5, -1, 2))) {
                level.setBlock(cell, Blocks.STONE.defaultBlockState(), 2);
            }
            level.setBlock(pos, CreateFireFightingAdd.FIRE_HYDRANT_CABINET.get().defaultBlockState(), 3);
            cabinet = (FireHydrantCabinetBlockEntity) level.getBlockEntity(pos);
            cabinet.getItemStackHandler().setStackInSlot(FireHydrantCabinetBlockEntity.SLOT_HOSE,
                new ItemStack(CreateFireFightingAdd.FIRE_HOSE_ITEM.get()));
            cabinet.getItemStackHandler().setStackInSlot(FireHydrantCabinetBlockEntity.SLOT_NOZZLE,
                new ItemStack(CreateFireFightingAdd.CONE_NOZZLE_ITEM.get()));
            cabinet.getFluidHandler(null).fill(new FluidStack(Fluids.WATER, 100), FluidAction.EXECUTE);
            FireHydrantCabinetBlockEntity.tick(level, pos, cabinet.getBlockState(), cabinet);
            fire = pos.east(4);
            level.setBlock(fire.below(), Blocks.NETHERRACK.defaultBlockState(), 2);
            level.setBlock(fire, Blocks.FIRE.defaultBlockState(), 2);
            body = PersonBody.RESIDENT.get().create(level);
            body.mob().setNoAi(true);
            body.mob().setOnGround(true);
            body.mob().setPos(Vec3.atBottomCenterOf(pos.east()));
            level.addFreshEntity(body.mob());
            colony.hold(PersonContent.ID, Held.Entity.of(body.mob())).orElseThrow();
            ItemStack extinguisher = new ItemStack(CreateFireFightingAdd.FIRE_EXTINGUISHER_ITEM.get());
            new FireExtinguisherItemHandler(
                CreateFireFightingAdd.FIRE_EXTINGUISHER_FLUID, extinguisher)
                .setStoredFluid(new FluidStack(Fluids.WATER, 100));
            body.pack().setItem(0, extinguisher);
            worker = TestWorker.of(body).effects(Effects.NONE).spill(stack -> { });
        }

        void nozzle(HandheldNozzleType type) {
            cabinet.getItemStackHandler().setStackInSlot(FireHydrantCabinetBlockEntity.SLOT_NOZZLE,
                new ItemStack(type == HandheldNozzleType.CONE
                    ? CreateFireFightingAdd.CONE_NOZZLE_ITEM.get() : CreateFireFightingAdd.FLAT_NOZZLE_ITEM.get()));
        }

        WorldPos at() {
            return WorldPos.of(level, fire);
        }

        HoseSession open() {
            Vec3 standing = body.mob().position();
            body.mob().setPos(Vec3.atBottomCenterOf(cabinet.getBlockPos().east()));
            HoseSession session = (HoseSession) adapter.open(level, worker, at()).orElseThrow();
            body.mob().setPos(standing);
            return session;
        }

        public void close() {
            HoseSessions.closeColony(colony.id());
            body.mob().discard();
            colony.raze();
            cabinet.forceClearBinding();
            level.removeBlock(cabinet.getBlockPos(), false);
            forced.forEach(chunk -> level.setChunkForced(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), false));
        }
    }
}
