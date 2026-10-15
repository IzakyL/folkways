package io.github.izakyl.folkways.plugins.cookery;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.ShawarmaSpitBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Cadence;
import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.colony.Holding;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Produce;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
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

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
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
        Node load = ((Workload.Within.Turn.Piece) ((Workload.Within) work.spec().workload()).next(rig.level, rig)).node();
        helper.assertTrue(Commit.run(rig.level, load, rig) instanceof Outcome.Done, "the actual registered native spit accepts both real ingredients");
        ((Workload.Within) work.spec().workload()).ended(rig.level, load, Ending.DONE);
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
                helper.assertTrue(rig.published >= 4, "the real forty-tick Sweep refreshed the blocked station through multiple periods");
                helper.assertValueEqual(rig.submitted.size(), 1, "blocked approaches do not manufacture replacement recovery nodes");
                helper.assertTrue(rig.withdrawn.isEmpty(), "the real Asking never withdraws the retained recovery as replaced");
                helper.assertTrue(rig.presence.goals(rig.view).stream().anyMatch(goal -> goal == original), "the same native recovery remains wanted after both periodic refreshes");
                helper.assertValueEqual(rig.spit().cookingItem.getCount(), 2, "accepted real raw meat remains in the unpowered native block, without fake completion or refund");
                helper.assertValueEqual(Goods.countIn(rig.pack, Goods.specOf(new ItemStack(Items.BEEF))), 0L, "the recovery never refunds the original accepted raw input");
                original.nodes().getFirst().ended(rig.level, Ending.REVOKED);
                rig.ended.accept(original.nodes().getFirst().id(), Ending.REVOKED);
                helper.assertTrue(provider.activeRecovery(rig.id, rig.level, rig.at, bookings).isEmpty(), "ending releases only the original cached recovery instance");
                helper.assertTrue(rig.presence.goals(rig.view).isEmpty(), "an inaccessible station cannot start a new recovery after the retained task ends");
            } finally { rig.close(); }
        }).thenSucceed();
    }

    private static final class Fixture implements Worker, AutoCloseable {
        final ServerLevel level;
        final UUID id = UUID.randomUUID();
        final BlockPos cell;
        final WorldPos at;
        final ResidentEntity actor;
        final SimpleContainer pack = new SimpleContainer(12);
        final Colony colony;
        final ColonyView view;
        final CookeryPresence presence;
        final List<Grown> submitted = new ArrayList<>();
        final List<UUID> withdrawn = new ArrayList<>();
        final Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        final Map<ResourceLocation, CompoundTag> kept = new LinkedHashMap<>();
        BiConsumer<UUID, Ending> ended;
        int published;
        boolean closed;

        @SuppressWarnings("unchecked")
        Fixture(GameTestHelper helper) {
            level = helper.getLevel(); cell = helper.absolutePos(new BlockPos(2, 25, 2)); at = WorldPos.of(level, cell);
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) set(cell.offset(x, -1, z), Blocks.STONE.defaultBlockState());
            before.put(cell.above(), level.getBlockState(cell.above()));
            set(cell, ModBlocks.SHAWARMA_SPIT.get().defaultBlockState());
            level.getBlockState(cell).getBlock().setPlacedBy(level, cell, level.getBlockState(cell), null, new ItemStack(ModItems.SHAWARMA_SPIT.get()));
            power(true);
            actor = PersonBody.RESIDENT.get().create(level); actor.setPos(Vec3.atBottomCenterOf(cell.south())); actor.setNoAi(true); level.addFreshEntity(actor);
            pack.setItem(0, new ItemStack(Items.BEEF, 2)); pack.setItem(1, new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get()));
            Holding holding = new Holding(UUID.randomUUID(), CookeryContent.STATION, new Held.Block(at));
            view = (ColonyView) Proxy.newProxyInstance(ColonyView.class.getClassLoader(), new Class<?>[] { ColonyView.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "level" -> level;
                    case "dimension" -> level.dimension();
                    case "holdings" -> List.of(holding);
                    case "blocks" -> CookeryContent.STATION.equals(args[0]) ? Set.of(cell) : Set.of();
                    case "residents" -> List.of();
                    case "rankOf" -> 0;
                    default -> throw new AssertionError(method.getName());
                });
            colony = (Colony) Proxy.newProxyInstance(Colony.class.getClassLoader(), new Class<?>[] { Colony.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "id" -> id;
                    case "views" -> List.of(view);
                    case "view" -> view;
                    case "holdings" -> args == null || args.length == 0 || CookeryContent.STATION.equals(args[0]) ? List.of(holding) : List.of();
                    case "storesIn", "residents", "reservedInputs" -> List.of();
                    case "service", "store" -> Optional.empty();
                    case "publish" -> { published++; yield null; }
                    case "rule" -> null;
                    case "kept" -> kept.getOrDefault(args[0], new CompoundTag()).copy();
                    case "keep" -> { kept.put((ResourceLocation) args[0], ((CompoundTag) args[1]).copy()); yield null; }
                    case "submit" -> {
                        Grown grown = (Grown) args[2]; submitted.add(grown); ended = (BiConsumer<UUID, Ending>) args[3];
                        helper.assertTrue(grown.nodes().getFirst().planned(level).isEmpty(), "the actual recovery's planned hook accepts its original exclusive booking"); yield null;
                    }
                    case "withdraw" -> { withdrawn.add((UUID) args[1]); yield null; }
                    default -> throw new AssertionError(method.getName());
                });
            presence = new CookeryPresence(colony, level.getServer()); presence.held(holding); presence.refresh(level.getServer());
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
                if (x == 0 && z == 0 && (y == 0 || y == 1)) continue;
                set(cell.offset(x, y, z), Blocks.STONE.defaultBlockState());
            }
        }
        ShawarmaSpitBlockEntity spit() { return (ShawarmaSpitBlockEntity) level.getBlockEntity(cell); }
        public Resident resident() { return actor.resident(); }
        public Mob body() { return actor; }
        public Container pack() { return pack; }
        public List<Store> within() { return List.of(); }
        public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
        public void spill(ItemStack stack) { if (!stack.isEmpty()) throw new AssertionError("unexpected spill: " + stack); }
        public int rankOf(String perk) { return 0; }
        public List<Node> route() { return List.of(); }
        public Placement placement() { return Placement.NOWHERE; }
        public void close() {
            closed = true; presence.closed(Closing.SHUTDOWN); Cadence.forget(colony); actor.discard();
            before.forEach(level::setBlockAndUpdate);
            level.getEntitiesOfClass(ItemEntity.class, new AABB(cell).inflate(8)).forEach(ItemEntity::discard);
        }
    }
}
