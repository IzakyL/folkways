package io.github.izakyl.folkways.cookery;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.ShawarmaSpitBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.cookery.testing.StationColony;
import io.github.izakyl.folkways.core.colony.api.Closing;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Produce;
import io.github.izakyl.folkways.core.runtime.HoldingFixture;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_cookery", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CookeryRecoveryGoalGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(CookeryRecoveryGoalGameTests.class); }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void aRealPresenceKeepsItsPlannedNativeRecoveryAcrossSweepsWithEveryApproachBlocked(GameTestHelper helper)
            throws ReflectiveOperationException {
        Fixture rig = new Fixture(helper);
        var provider = KitchenStations.find(rig.level.getBlockState(rig.cell)).orElseThrow();
        var reflected = CookeryPresence.class.getDeclaredField("bookings"); reflected.setAccessible(true);
        var bookings = (KitchenBookings) reflected.get(rig.presence);
        Stances stance = Stances.at(rig.level, rig.actor.blockPosition());
        var bench = provider.freeze(UUID.randomUUID(), rig.id, rig.level, rig.at, stance, bookings, () -> {}).orElseThrow();
        Produce wanted = Produce.of(CookeryContent.ID, new WorkSite.AtBlock(rig.at), Goods.specOf(new ItemStack(Items.COOKED_BEEF)), 2, Produce.UNBOUNDED);
        var production = bench.options(wanted, Grown.of(wanted)).getFirst();
        var hold = production.holds().getFirst(); helper.assertTrue(hold.take().isEmpty(), "the ordinary recipe descendant takes its actual booking");
        Node work = production.replacement().nodes().getFirst();
        HoldingFixture load = new HoldingFixture(rig.level, work, rig.worker);
        helper.assertTrue(
            load.step() instanceof Outcome.Done, "the actual registered native spit accepts both real ingredients");
        helper.assertTrue(rig.presence.goals(rig.view).stream().noneMatch(goal -> goal.completions().contains(work.id())), "an ordinary recipe descendant is never mistaken for a standalone recovery root");
        rig.power(false); hold.release(Ending.DROPPED); work.ended(rig.level, Ending.DROPPED);
        // This is the actual CookeryPresence -> Sweep -> Asking path. Only Colony's publish/submit ports are recorded.
        rig.presence.tick(rig.level.getServer());
        helper.assertValueEqual(rig.submitted.size(), 1, "the real presence sweep submits exactly one physical native recovery");
        Grown original = rig.submitted.getFirst();
        helper.assertTrue(!bookings.free(rig.at) && provider.activeRecovery(rig.id, rig.level, rig.at, bookings).getFirst() == original,
            "successful planning retains exactly the Grown submitted by the actual Asking instance");
        rig.blockEveryApproach(); rig.presence.refresh(rig.level.getServer());
        helper.assertTrue(provider.stances(rig.level, rig.at).isEmpty(), "real native collision and support now leave no valid standing place");
        helper.assertTrue(rig.presence.goals(rig.view).stream().anyMatch(goal -> goal == original), "the actual no-stance presence branch keeps the already planned root instead of withdrawing it");
        helper.onEachTick(() -> { if (!rig.closed) rig.presence.tick(rig.level.getServer()); });
        helper.startSequence().thenIdle(85).thenExecute(() -> {
            try {
                helper.assertTrue(rig.station.published >= 4, "the real forty-tick Sweep refreshed the blocked station through multiple periods");
                helper.assertValueEqual(rig.submitted.size(), 1, "blocked approaches do not manufacture replacement recovery nodes");
                helper.assertTrue(rig.withdrawn.isEmpty(), "the real Asking never withdraws the retained recovery as replaced");
                helper.assertTrue(rig.presence.goals(rig.view).stream().anyMatch(goal -> goal == original), "the same native recovery remains wanted after both periodic refreshes");
                helper.assertValueEqual(rig.spit().cookingItem.getCount(), 2, "accepted real raw meat remains in the unpowered native block, without fake completion or refund");
                helper.assertValueEqual(Goods.countIn(rig.pack, Goods.specOf(new ItemStack(Items.BEEF))), 0L, "the recovery never refunds the original accepted raw input");
                original.nodes().getFirst().ended(rig.level, Ending.REVOKED);
                rig.station.ended.accept(original.nodes().getFirst().id(), Ending.REVOKED);
                helper.assertTrue(provider.activeRecovery(rig.id, rig.level, rig.at, bookings).isEmpty(), "ending releases only the original cached recovery instance");
                helper.assertTrue(rig.presence.goals(rig.view).isEmpty(), "an inaccessible station cannot start a new recovery after the retained task ends");
            } finally { rig.close(); }
        }).thenSucceed();
    }

    private static final class Fixture implements AutoCloseable {
        final Worker worker;
        final ServerLevel level;
        final UUID id;
        final BlockPos cell;
        final WorldPos at;
        final ResidentEntity actor;
        final SimpleContainer pack = new SimpleContainer(12);
        final StationColony station;
        final Colony colony;
        final ColonyView view;
        final List<Grown> submitted;
        final List<UUID> withdrawn;
        final CookeryPresence presence;
        final Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        boolean closed;

        Fixture(GameTestHelper helper) {
            level = helper.getLevel(); cell = helper.absolutePos(new BlockPos(2, 25, 2)); at = WorldPos.of(level, cell);
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) set(cell.offset(x, -1, z), Blocks.STONE.defaultBlockState());
            }
            before.put(cell.above(), level.getBlockState(cell.above()));
            set(cell, ModBlocks.SHAWARMA_SPIT.get().defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.SOUTH));
            level.getBlockState(cell).getBlock().setPlacedBy(level, cell, level.getBlockState(cell), null, new ItemStack(ModItems.SHAWARMA_SPIT.get()));
            power(true);
            actor = PersonBody.RESIDENT.get().create(level); actor.setPos(Vec3.atBottomCenterOf(cell.south())); actor.setNoAi(true); level.addFreshEntity(actor);
            pack.setItem(0, new ItemStack(Items.BEEF, 2)); pack.setItem(1, new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get()));
            station = new StationColony(helper, cell, "the actual recovery's planned hook accepts its original exclusive booking");
            id = station.id; colony = station.colony; view = station.view;
            submitted = station.submitted; withdrawn = station.withdrawn;
            presence = new CookeryPresence(colony, level.getServer()); presence.held(station.holding); presence.refresh(level.getServer());
            worker = TestWorker.of(actor).pack(pack);
        }
        void set(BlockPos at, BlockState state) { before.putIfAbsent(at.immutable(), level.getBlockState(at)); level.setBlockAndUpdate(at, state); }
        void power(boolean on) {
            set(cell.west(), (on ? Blocks.REDSTONE_BLOCK : Blocks.AIR).defaultBlockState());
            set(cell.west().above(), (on ? Blocks.REDSTONE_BLOCK : Blocks.AIR).defaultBlockState());
        }
        void blockEveryApproach() {
            BlockPos safe = cell.east(8); set(safe.below(), Blocks.STONE.defaultBlockState()); set(safe, Blocks.AIR.defaultBlockState()); set(safe.above(), Blocks.AIR.defaultBlockState());
            actor.setPos(Vec3.atBottomCenterOf(safe));
            for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) for (int y = -2; y <= 7; y++) {
                if (x == 0 && z == 0 && (y == 0 || y == 1)) {
                    continue;
                }
                set(cell.offset(x, y, z), Blocks.STONE.defaultBlockState());
            }
        }
        ShawarmaSpitBlockEntity spit() { return (ShawarmaSpitBlockEntity) level.getBlockEntity(cell); }
        public void close() {
            closed = true; presence.closed(Closing.SHUTDOWN); station.close(); actor.discard();
            before.forEach(level::setBlockAndUpdate);
            level.getEntitiesOfClass(ItemEntity.class, new AABB(cell).inflate(8)).forEach(ItemEntity::discard);
        }
    }
}
