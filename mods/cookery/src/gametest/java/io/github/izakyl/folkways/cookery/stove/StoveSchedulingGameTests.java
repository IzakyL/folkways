package io.github.izakyl.folkways.cookery.stove;

import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.StoveBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.cookery.CookeryPresence;
import io.github.izakyl.folkways.cookery.testing.StationColony;
import io.github.izakyl.folkways.core.colony.api.Closing;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.internal.Commit;
import io.github.izakyl.folkways.core.work.support.Tools;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_cookery", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_cookery")
@PrefixGameTestTemplate(false)
public final class StoveSchedulingGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(StoveSchedulingGameTests.class); }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 220)
    public static void theRealPresenceKeepsIgnitionWantedAcrossSweepsBeforeAndAfterTheNativeStoveLights(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        Grown original = rig.submitted.getFirst();
        helper.onEachTick(() -> { if (!rig.closed) rig.presence.tick(rig.level.getServer()); });
        helper.startSequence().thenIdle(85).thenExecute(() -> {
            helper.assertTrue(rig.station.published >= 3, "the actual Presence refreshed its registered stove over multiple forty-tick sweeps");
            rig.kept(helper, original);
            helper.assertTrue(Tools.shared(StoveWorkshops.IGNITION).isPresent(), "ignition is available to the same task-scoped borrowing path as other kitchen tools");
            helper.assertTrue(Commit.run(rig.level, original.nodes().getFirst(), rig.worker) instanceof Outcome.Done, "the real submitted ignition node lights the native stove through core commit");
            helper.assertTrue(rig.level.getBlockState(rig.cell).getValue(StoveBlock.LIT), "the native LIT state supplies real heat");
            helper.assertValueEqual(rig.pack.getItem(0).getDamageValue(), 1, "one actual ignition wears the held flint exactly once");
        }).thenIdle(85).thenExecute(() -> {
            try {
                rig.kept(helper, original);
                helper.assertValueEqual(rig.pack.getItem(0).getDamageValue(), 1, "the lit task stays wanted for its eventual tool return without repeating ignition");
                original.nodes().getFirst().ended(rig.level, Ending.DONE); rig.station.ended.accept(original.nodes().getFirst().id(), Ending.DONE);
                helper.assertTrue(rig.presence.goals(rig.view).isEmpty(), "actual task completion releases the cached original without creating a second lit-stove task");
            } finally { rig.close(); }
        }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 140)
    public static void aWetPlannedStoveWaitsAcrossTheRealSweepAndResumesWithoutWastingItsIgnition(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        Grown original = rig.submitted.getFirst(); Node light = original.nodes().getFirst();
        rig.set(rig.cell.above(), Blocks.WATER.defaultBlockState());
        helper.assertTrue(!light.ready(rig.level), "a planned wet stove does not commit an unusable ignition");
        helper.onEachTick(() -> { if (!rig.closed) rig.presence.tick(rig.level.getServer()); });
        helper.startSequence().thenIdle(85).thenExecute(() -> {
            try {
                rig.kept(helper, original);
                helper.assertTrue(!rig.level.getBlockState(rig.cell).getValue(StoveBlock.LIT), "the real submerged stove remains cold");
                helper.assertValueEqual(rig.pack.getItem(0).getDamageValue(), 0, "waiting through several actual sweeps never spends durability");
                rig.set(rig.cell.above(), Blocks.AIR.defaultBlockState());
                helper.assertTrue(light.ready(rig.level) && Commit.run(rig.level, light, rig.worker) instanceof Outcome.Done, "the same original scheduled task resumes when its real heat source dries");
                helper.assertValueEqual(rig.pack.getItem(0).getDamageValue(), 1, "drying requires one real ignition, not one charge per wet refresh");
                light.ended(rig.level, Ending.DONE); rig.station.ended.accept(light.id(), Ending.DONE);
            } finally { rig.close(); }
        }).thenSucceed();
    }

    private static final class Rig implements AutoCloseable {
        final Worker worker;
        final ServerLevel level;
        final UUID id;
        final BlockPos cell;
        final WorldPos at;
        final ResidentEntity actor;
        final SimpleContainer pack = new SimpleContainer(6);
        final StationColony station;
        final Colony colony;
        final ColonyView view;
        final List<Grown> submitted;
        final List<UUID> withdrawn;
        final CookeryPresence presence;
        final Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        boolean closed;

        Rig(GameTestHelper helper) {
            level = helper.getLevel(); cell = helper.absolutePos(new BlockPos(2, 25, 2)); at = WorldPos.of(level, cell);
            for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
                set(cell.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                set(cell.offset(x, 0, z), Blocks.AIR.defaultBlockState()); set(cell.offset(x, 1, z), Blocks.AIR.defaultBlockState());
            }
            set(cell, ModBlocks.STOVE.get().defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.SOUTH));
            actor = PersonBody.RESIDENT.get().create(level); actor.setPos(Vec3.atBottomCenterOf(cell.south())); actor.setNoAi(true); level.addFreshEntity(actor);
            pack.setItem(0, new ItemStack(Items.FLINT_AND_STEEL));
            station = new StationColony(helper, cell, "the actual ignition submitted by Asking books its own stove");
            id = station.id; colony = station.colony; view = station.view;
            submitted = station.submitted; withdrawn = station.withdrawn;
            presence = new CookeryPresence(colony, level.getServer()); presence.held(station.holding); presence.tick(level.getServer());
            helper.assertValueEqual(submitted.size(), 1, "the actual registered stove produced its ignition root through Presence and Asking");
            worker = TestWorker.of(actor).pack(pack).heldFromPack();
        }
        void kept(GameTestHelper helper, Grown original) {
            helper.assertValueEqual(submitted.size(), 1, "the same ignition was submitted once");
            helper.assertTrue(withdrawn.isEmpty(), "the real Asking did not revoke the booked ignition as replaced");
            helper.assertTrue(presence.goals(view).size() == 1 && presence.goals(view).getFirst() == original, "the exact original Grown remains wanted across the real sweep");
        }
        void set(BlockPos pos, BlockState state) { before.putIfAbsent(pos.immutable(), level.getBlockState(pos)); level.setBlockAndUpdate(pos, state); }
        public void close() {
            closed = true; presence.closed(Closing.SHUTDOWN); station.close(); actor.discard(); before.forEach(level::setBlockAndUpdate);
        }
    }
}
