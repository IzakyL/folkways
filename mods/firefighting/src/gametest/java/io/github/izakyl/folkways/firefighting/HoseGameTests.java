package io.github.izakyl.folkways.firefighting;

import com.mikoalopex.createfirefightingadd.CreateFireFightingAdd;
import com.mikoalopex.createfirefightingadd.api.handheld.HandheldNozzleBindingApi;
import com.mikoalopex.createfirefightingadd.content.equipment.handheld.FireHydrantCabinetBlockEntity;
import com.mikoalopex.createfirefightingadd.content.equipment.handheld.HandheldNozzleControllerItem;
import com.mikoalopex.createfirefightingadd.content.equipment.handheld.HandheldNozzleType;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.plugins.damagecontrol.SuppressionMethods;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
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
    public static void register(RegisterGameTestsEvent event) { event.register(HoseGameTests.class); }

    @GameTest(template = "empty")
    public static void aHoseBindsTheRealCabinetAndConsumesItsWater(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            int before = HoseSessions.size();
            HoseSession session = rig.open();
            helper.assertValueEqual(HoseSessions.size(), before + 1, "one resident owns one live session");
            helper.assertTrue(rig.cabinet.isBoundTo(rig.body.id()), "the native cabinet names its resident owner");
            helper.assertValueEqual(session.hydrantId(), rig.cabinet.getHydrantId(), "the session keeps the real hydrant identity");
            helper.assertTrue(session.suppress(rig.level, rig.fire) instanceof Outcome.Done, "the resident sprays without a player");
            helper.assertTrue(rig.level.getBlockState(rig.fire).isAir(), "the native ray callback extinguishes the selected fire");
            helper.assertValueEqual(rig.cabinet.getFluid().getAmount(), 90, "one native spray tick consumes ten mB");
            session.close();
            helper.assertFalse(rig.cabinet.hasActiveBinding(), "stop releases the native binding");
            helper.assertValueEqual(HoseSessions.size(), before, "stop also removes the runtime session");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void bothNozzlesUseTheirNativeRanges(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            for (HandheldNozzleType type : List.of(HandheldNozzleType.CONE, HandheldNozzleType.FLAT)) {
                int range = NativeSprayBridge.hoseRange(type);
                helper.assertTrue(range > 4, "the hose has its configured addon range");
                Vec3 direction = new Vec3(0, 0, 1);
                BlockPos target = rig.fire.above(128);
                rig.level.getChunkAt(target);
                Vec3 point = Vec3.atCenterOf(target);
                helper.assertTrue(NativeSprayBridge.hoseReaches(rig.level, type, point.add(0, 0, -(range - 1)), direction, target),
                    "a clear centre ray reaches within the nozzle's configured range: " + type + " range=" + range
                        + " hit=" + rig.level.clip(new ClipContext(point.add(0, 0, -(range - 1)), point,
                            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                            CollisionContext.empty())).getBlockPos() + " target=" + target);
                helper.assertFalse(NativeSprayBridge.hoseReaches(rig.level, type, point.add(0, 0, -(range + 1)), direction, target),
                    "the nozzle's configured range is the upper bound");
            }
        }
        helper.succeed();
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

    @GameTest(template = "empty")
    public static void aPlayerBindingIsNeverBorrowedByAResident(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            var player = FakePlayerFactory.getMinecraft(rig.level);
            rig.cabinet.bindTo(player);
            helper.assertTrue(rig.adapter.open(rig.level, rig.worker, rig.at()).isEmpty(), "an active player keeps exclusive ownership");
            helper.assertTrue(rig.cabinet.isBoundTo(player.getUUID()), "the player's binding survives the refusal");
            rig.cabinet.clearBinding(player.getUUID());
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aPlayerCanTakeOverAndClosesTheResidentSession(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            HoseSession session = rig.open();
            var player = FakePlayerFactory.getMinecraft(rig.level);
            ItemStack controller = new ItemStack(CreateFireFightingAdd.HANDHELD_NOZZLE_CONTROLLER_ITEM.get());
            ((HandheldNozzleControllerItem) controller.getItem()).tryBindToCabinet(controller, rig.level, rig.cabinet.getBlockPos(), player);
            helper.assertTrue(rig.cabinet.isBoundTo(player.getUUID()), "native player binding takes over through forceClearBinding");
            helper.assertFalse(session.valid(), "the resident session is closed before the player takes over");
            helper.assertTrue(session.suppress(rig.level, rig.fire) instanceof Outcome.Failed, "the old owner cannot spray");
            session.close();
            helper.assertTrue(rig.cabinet.isBoundTo(player.getUUID()), "late resident cleanup cannot clear the new player");
            helper.assertValueEqual(rig.cabinet.getFluid().getAmount(), 100, "takeover does not lose water");
            HandheldNozzleControllerItem.clearBinding(rig.level, controller, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void changingCabinetEquipmentDisconnectsTheResident(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            HoseSession session = rig.open();
            rig.cabinet.getItemStackHandler().setStackInSlot(FireHydrantCabinetBlockEntity.SLOT_NOZZLE, ItemStack.EMPTY);
            helper.assertFalse(session.valid(), "changing the native nozzle forces a disconnect");
            helper.assertFalse(rig.cabinet.hasActiveBinding(), "equipment change leaves no resident binding");
            helper.assertTrue(session.suppress(rig.level, rig.fire) instanceof Outcome.Failed, "a disconnected hose cannot draw water");
            helper.assertValueEqual(rig.cabinet.getFluid().getAmount(), 100, "equipment changes conserve fluid");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void removingAndReplacingTheCabinetNeverReusesItsHydrantId(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            HoseSession session = rig.open();
            BlockPos pos = rig.cabinet.getBlockPos();
            rig.level.removeBlock(pos, false);
            rig.level.setBlock(pos, CreateFireFightingAdd.FIRE_HYDRANT_CABINET.get().defaultBlockState(), 3);
            var replacement = (FireHydrantCabinetBlockEntity) rig.level.getBlockEntity(pos);
            helper.assertFalse(replacement.getHydrantId().equals(session.hydrantId()), "a new cabinet has a different identity");
            HoseSessions.tick(new ServerTickEvent.Post(() -> true, rig.level.getServer()));
            helper.assertFalse(session.valid(), "the removed cabinet's session is closed");
            helper.assertFalse(replacement.hasActiveBinding(), "replacement inherits no stale resident binding");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void deathAndEntityUnloadReleaseTheHose(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            HoseSession session = rig.open();
            rig.body.mob().kill();
            helper.assertFalse(session.valid(), "a dead resident cannot spray");
            helper.assertFalse(rig.cabinet.hasActiveBinding(), "the death event immediately unbinds the cabinet");
        }
        try (Rig rig = new Rig(helper)) {
            rig.open();
            HoseSessions.left(new EntityLeaveLevelEvent(rig.body.mob(), rig.level));
            helper.assertFalse(rig.cabinet.hasActiveBinding(), "entity unload immediately releases the cabinet");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void chunkUnloadAndColonyCloseReleaseBindings(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            rig.open();
            HoseSessions.chunkUnloaded(new ChunkEvent.Unload(rig.level.getChunkAt(rig.cabinet.getBlockPos())));
            helper.assertFalse(rig.cabinet.hasActiveBinding(), "chunk unload clears ownership without loading another chunk");
            FireHydrantCabinetBlockEntity.tick(rig.level, rig.cabinet.getBlockPos(), rig.cabinet.getBlockState(), rig.cabinet);
            rig.open();
            HoseSessions.closeColony(rig.colony.id());
            helper.assertFalse(rig.cabinet.hasActiveBinding(), "closing the colony releases the hose");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void transientResidentBindingsAreNotSavedAsPlayers(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            rig.open();
            CompoundTag saved = rig.cabinet.saveWithoutMetadata(rig.level.registryAccess());
            helper.assertFalse(saved.hasUUID("BoundPlayer"), "a crash or reload must not restore a resident as an absent player");
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
    public static void obstructionRefusesWithoutSpendingCabinetWater(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            HoseSession session = rig.open();
            BlockPos wall = rig.fire.west();
            rig.level.setBlock(wall, Blocks.STONE.defaultBlockState(), 2);
            rig.level.setBlock(wall.above(), Blocks.STONE.defaultBlockState(), 2);
            helper.assertTrue(session.suppress(rig.level, rig.fire) instanceof Outcome.Failed, "the native ray cannot pass a wall");
            helper.assertValueEqual(rig.cabinet.getFluid().getAmount(), 100, "an obstructed attempt spends no fluid");
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
            helper.assertTrue(session.suppress(rig.level, rig.fire) instanceof Outcome.Done, "the addon allows the last partial tick");
            helper.assertTrue(rig.cabinet.getFluid().isEmpty(), "exactly the three remaining mB are consumed");
            helper.assertTrue(rig.level.getBlockState(rig.fire).isAir(), "the last partial spray still has its effect");
            session.close();
            helper.assertTrue(rig.adapter.open(rig.level, rig.worker, rig.at()).isEmpty(), "empty cabinets cannot start another session");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aHoseTakesPrecedenceAndOffersRemoteSafeFootings(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            var chosen = SuppressionMethods.all().stream().filter(method -> method.reaches(rig.level, rig.worker, rig.at())).findFirst().orElseThrow();
            helper.assertTrue(chosen instanceof HoseAdapter, "an available hydrant precedes the portable extinguisher");
            helper.assertTrue(rig.adapter.footings(rig.level, rig.worker, rig.at()).contains(rig.body.mob().blockPosition()),
                "the current safe footing is usable without walking into the fire");
            Vec3 far = Vec3.atBottomCenterOf(rig.cabinet.getBlockPos().east(10));
            rig.level.setBlock(rig.cabinet.getBlockPos().east(10).below(), Blocks.STONE.defaultBlockState(), 2);
            rig.body.mob().setPos(far);
            helper.assertTrue(rig.adapter.footings(rig.level, rig.worker, rig.at()).contains(rig.body.mob().blockPosition()),
                "hose footings are not clipped to the hand tool's four-block reach: body=" + rig.body.mob().blockPosition()
                    + " footing=" + Footing.withFeetAt(rig.level, rig.body.mob().blockPosition())
                    + " reaches=" + rig.adapter.reaches(rig.level, rig.worker, rig.at()));
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void flatNozzlesConsumeTheSameNativeTickCost(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            rig.cabinet.getItemStackHandler().setStackInSlot(FireHydrantCabinetBlockEntity.SLOT_NOZZLE,
                new ItemStack(CreateFireFightingAdd.FLAT_NOZZLE_ITEM.get()));
            HoseSession session = rig.open();
            helper.assertTrue(session.suppress(rig.level, rig.fire) instanceof Outcome.Done, "the flat nozzle uses its native fan sampler");
            helper.assertTrue(rig.level.getBlockState(rig.fire).isAir(), "the flat centre ray extinguishes fire");
            helper.assertValueEqual(rig.cabinet.getFluid().getAmount(), 90, "the flat nozzle also consumes ten mB per spray tick");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void oneSessionCannotSprayTwiceOnTheSameTick(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        HoseSession session = rig.open();
        helper.assertTrue(session.suppress(rig.level, rig.fire) instanceof Outcome.Done, "the first pulse succeeds");
        rig.level.setBlock(rig.fire, Blocks.FIRE.defaultBlockState(), 2);
        helper.assertTrue(session.suppress(rig.level, rig.fire) instanceof Outcome.Failed, "a repeated call cannot overdraw in the same native tick");
        helper.assertValueEqual(rig.cabinet.getFluid().getAmount(), 90, "the second call leaves fluid unchanged");
        helper.startSequence().thenExecuteAfter(1, () -> {
            try {
                helper.assertTrue(session.suppress(rig.level, rig.fire) instanceof Outcome.Done, "the next server tick permits another pulse");
                helper.assertValueEqual(rig.cabinet.getFluid().getAmount(), 80, "two ticks consume exactly twenty mB");
            } finally { rig.close(); }
        }).thenSucceed();
    }

    @GameTest(template = "empty")
    public static void sprayEffectsAreClippedToTheApprovedFire(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            BlockPos outside = rig.fire.north();
            rig.level.setBlock(outside.below(), Blocks.NETHERRACK.defaultBlockState(), 2);
            rig.level.setBlock(outside, Blocks.FIRE.defaultBlockState(), 2);
            HoseSession session = rig.open();
            session.suppress(rig.level, rig.fire);
            helper.assertTrue(rig.level.getBlockState(rig.fire).isAir(), "the selected fire is suppressed");
            helper.assertTrue(rig.level.getBlockState(outside).is(Blocks.FIRE), "other cells do not receive unapproved spray effects");
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
            rig.cabinet.getItemStackHandler().setStackInSlot(FireHydrantCabinetBlockEntity.SLOT_NOZZLE,
                new ItemStack(CreateFireFightingAdd.FLAT_NOZZLE_ITEM.get()));
            rig.body.mob().setPos(Vec3.atBottomCenterOf(rig.fire.west()));
            HoseSession session = rig.open();
            helper.assertTrue(session.suppress(rig.level, rig.fire) instanceof Outcome.Done, "native rays test the fire's block volume");
            helper.assertTrue(rig.level.getBlockState(rig.fire).isAir(), "a nearby fire can still receive a flat spray");
            helper.assertValueEqual(rig.cabinet.getFluid().getAmount(), 90, "a close shot has the same native cost");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aSameTickStopKeepsTheLastBurstUntilItFinishes(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            long now = rig.level.getGameTime();
            HoseSession session = rig.open();
            session.suppress(rig.level, rig.fire);
            session.close();
            helper.assertFalse(rig.cabinet.hasActiveBinding(), "visual tails never prolong cabinet ownership");
            helper.assertValueEqual(rig.cabinet.getFluid().getAmount(), 90, "the visual tail costs no more water");
            SprayTail tail = SprayTail.start(now).stop(now);
            helper.assertTrue(tail.hose(now), "start and stop received in one frame still show the hose");
            helper.assertFalse(tail.finished(now, true), "even a burst without projectiles gets a short visible hose");
            helper.assertFalse(tail.hose(now + 4), "the disconnected hose disappears after four client ticks");
            helper.assertFalse(tail.finished(now + 4, false), "already emitted native projectiles keep ticking");
            helper.assertTrue(tail.finished(now + 40, true), "the view retires only after its projectiles finish");
            helper.assertValueEqual(tail.stop(now + 3), tail, "duplicate stops do not extend the visual tail");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void cabinetDiscoveryUsesTicksAndForgetsUnloadedChunks(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            helper.assertTrue(Hydrants.nearby(rig.level, rig.fire, 4).contains(rig.cabinet),
                "the real cabinet tick registers it in the shared index");
            Hydrants.unload(rig.level, new ChunkPos(rig.cabinet.getBlockPos()));
            helper.assertFalse(Hydrants.nearby(rig.level, rig.fire, 4).contains(rig.cabinet), "unloaded cabinet entries are discarded");
            FireHydrantCabinetBlockEntity.tick(rig.level, rig.cabinet.getBlockPos(), rig.cabinet.getBlockState(), rig.cabinet);
            helper.assertTrue(Hydrants.nearby(rig.level, rig.fire, 4).contains(rig.cabinet), "a loaded cabinet announces itself again");
            rig.level.removeBlock(rig.cabinet.getBlockPos(), false);
            helper.assertFalse(Hydrants.nearby(rig.level, rig.fire, 4).contains(rig.cabinet), "removed cabinet entries are pruned without binding");
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

        Rig(GameTestHelper helper) {
            level = helper.getLevel();
            colony = Colonies.mint(level.getServer());
            BlockPos pos = helper.absolutePos(new BlockPos(2, 1, 3));
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
            body.mob().setPos(Vec3.atBottomCenterOf(pos.east()));
            level.addFreshEntity(body.mob());
            colony.hold(PersonContent.ID, Held.Entity.of(body.mob())).orElseThrow();
            ItemStack extinguisher = new ItemStack(CreateFireFightingAdd.FIRE_EXTINGUISHER_ITEM.get());
            new com.mikoalopex.createfirefightingadd.content.equipment.extinguisher.FireExtinguisherItemHandler(
                CreateFireFightingAdd.FIRE_EXTINGUISHER_FLUID, extinguisher).setStoredFluid(new FluidStack(Fluids.WATER, 100));
            body.pack().setItem(0, extinguisher);
            worker = (Worker) Proxy.newProxyInstance(Worker.class.getClassLoader(), new Class<?>[] {Worker.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "body" -> body.mob();
                    case "resident" -> body.resident();
                    case "pack" -> body.pack();
                    case "effects" -> Effects.NONE;
                    case "within", "route", "supplied" -> List.of();
                    case "placement" -> Placement.NOWHERE;
                    case "held" -> ItemStack.EMPTY;
                    default -> null;
                });
        }

        WorldPos at() { return WorldPos.of(level, fire); }
        HoseSession open() { return (HoseSession) adapter.open(level, worker, at()).orElseThrow(); }
        public void close() {
            HoseSessions.closeColony(colony.id());
            body.mob().discard();
            colony.raze();
            cabinet.forceClearBinding();
            level.removeBlock(cabinet.getBlockPos(), false);
        }
    }
}
