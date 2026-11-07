package io.github.izakyl.folkways.cookery;

import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.colony.api.Closing;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Produce;
import io.github.izakyl.folkways.core.runtime.HoldingFixture;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Hold;
import io.github.izakyl.folkways.core.work.api.HoldingStep;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Placement;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.support.Tools;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;

@EventBusSubscriber(modid = "folkways_cookery", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
public final class CookeryHoldingGameTests {
    private record Station(String name, Supplier<Block> block, Supplier<List<ItemStack>> inputs,
        Supplier<ItemStack> product, int loading) {}

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CookeryHoldingGameTests.class);
    }

    @GameTestGenerator
    public static Collection<TestFunction> nativeEndings() {
        List<Station> stations =
                new ArrayList<>(
                    List.of(
                        new Station(
                            "board", ModBlocks.CHOPPING_BOARD::get, () -> List.of(new ItemStack(Items.CHICKEN)),
                            () -> new ItemStack(ModItems.RAW_CUT_SMALL_MEATS.get(), 3), 1),
                        new Station(
                            "tray", ModBlocks.BAMBOO_TRAY::get, () ->
                            List.of(new ItemStack(ModItems.FRESH_TEA_LEAVES.get(), 2)),
                            () -> new ItemStack(ModItems.DRIED_TEA_LEAVES.get(), 2), 1),
                        new Station(
                            "steamer", ModBlocks.STEAMER::get,
                            () -> List.of(new ItemStack(ModItems.RAW_DOUGH.get(), 2)),
                            () -> new ItemStack(ModItems.MANTOU.get(), 2), 1),
                        new Station(
                            "spit", ModBlocks.SHAWARMA_SPIT::get, () -> List.of(new ItemStack(Items.BEEF, 2)),
                            () -> new ItemStack(Items.COOKED_BEEF, 2), 1),
                        new Station(
                            "teapot", ModBlocks.TEAPOT::get, () ->
                            List.of(
                                new ItemStack(Items.WATER_BUCKET), new ItemStack(ModItems.TIEGUANYIN_TEA_BAG.get()),
                                new ItemStack(ModItems.EMPTY_CUP.get(), 12)),
                            () ->
                            new ItemStack(
                                net.minecraft.core.registries
                                .BuiltInRegistries.ITEM
                                .get(
                                    ResourceLocation.parse(
                                        "kaleidoscope_cookery:tieguanyin")),
                                12),
                            2),
                        new Station(
                            "pot", ModBlocks.POT::get, () ->
                            List.of(
                                new ItemStack(Items.BEEF, 3), new ItemStack(ModItems.OIL.get())),
                            () -> new ItemStack(Items.COOKED_BEEF, 3), 1),
                        new Station(
                            "stockpot", ModBlocks.STOCKPOT::get, () ->
                            List.of(
                                new ItemStack(ModItems.RICE_SEED.get()), new ItemStack(Items.WATER_BUCKET),
                                new ItemStack(Items.BOWL), new ItemStack(ModItems.STOCKPOT_LID.get())),
                            () -> new ItemStack(ModItems.COOKED_RICE.get()), 1),
                        new Station(
                            "millstone", ModBlocks.MILLSTONE::get, () -> List.of(new ItemStack(Items.WHEAT, 3)),
                            () -> new ItemStack(ModItems.FLOUR.get(), 3), 1)));
        if (net.neoforged.fml.ModList.get().isLoaded("kaleidoscope_tavern")) {
            stations.add(
                new Station(
                    "tavern", com.github.ysbbbbbb.kaleidoscopetavern.init.ModBlocks.PRESSING_TUB::get, () ->
                    List.of(
                        new ItemStack(
                            com.github.ysbbbbbb.kaleidoscopetavern.init
                            .ModItems.GRAPE
                            .get(), 8),
                        new ItemStack(Items.BUCKET)),
                    () ->
                    new ItemStack(
                        com.github.ysbbbbbb.kaleidoscopetavern.init.ModItems
                        .GRAPE_BUCKET
                        .get()),
                    1));
        }
        List<TestFunction> tests = new ArrayList<>();
        for (Station station : stations) {
            for (boolean controller : List.of(false, true)) {
                String name =
                        "folkways:cookery_holding_"
                                + station.name()
                                + (controller ? "_controller" : "_revoke");
                tests.add(
                    new TestFunction(
                        "cookery_holdings", name, "folkways:empty_large", 4000, 0L, true,
                        helper -> run(helper, station, controller)));
            }
        }
        return tests;
    }

    private static void run(GameTestHelper helper, Station station, boolean controller) {
        Fixture fixture = new Fixture(helper, station);
        try {
            Node work = fixture.plan();
            HoldingFixture holding =
                    new HoldingFixture(fixture.level, work, fixture.worker()).walking();
            for (int step = 0; step < station.loading(); step++) {
                fixture.stow(holding.step());
            }
            CompoundTag ownership =
                    fixture.level.getBlockEntity(fixture.cell).getPersistentData().copy();
            List<ItemStack> cargo = fixture.cargo();
            int changes = fixture.changes.get();
            if (controller) {
                holding.softEnd();
                helper.assertTrue(
                    holding.tick() instanceof HoldingStep.Yielded,
                    "soft controller end yields without starting more native production");
                holding.finish(Ending.DROPPED);
                holding.finish(Ending.DROPPED);
            } else {
                holding.end(Ending.REVOKED);
                holding.end(Ending.REVOKED);
            }
            helper.assertValueEqual(fixture.changes.get(), changes + 1, "native node cleanup runs exactly once");
            helper.assertTrue(
                ownership.equals(fixture.level.getBlockEntity(fixture.cell).getPersistentData()),
                "ending preserves the original native batch ownership");
            fixture.assertCargo(cargo);
            fixture.claims.forEach(claim -> claim.release(Ending.REVOKED));
            fixture.replaceWorker();
            List<Grown> recovery =
                    fixture.provider.recovery(
                        fixture.colony, fixture.level, fixture.at, fixture.stances, fixture.bookings,
                        fixture.changes::incrementAndGet);
            helper.assertValueEqual(recovery.size(), 1, "one invested native batch creates one recovery");
            Node next = recovery.getFirst().nodes().getFirst();
            helper.assertTrue(next.planned(fixture.level).isEmpty(), "another resident adopts the same invested batch");
            HoldingFixture resumed =
                    new HoldingFixture(fixture.level, next, fixture.worker()).walking();
            boolean[] acquired = {false};
            helper.onEachTick(
                () -> {
                if (fixture.closed) {
                    return;
                }
                try {
                fixture.provider.tick(fixture.level, fixture.colony);
                if (!acquired[0] && !next.ready(fixture.level)) {
                    return;
                }
                acquired[0] = true;
                HoldingStep state = resumed.tick();
                resumed.settled().ifPresent(fixture::stow);
                helper.assertTrue(
                    !(state instanceof HoldingStep.Failed), "the new holding continues native work");
                if (state instanceof HoldingStep.Done) {
                    resumed.finish(Ending.DONE);
                    ItemStack product = station.product().get();
                    helper.assertValueEqual(
                        Goods.countIn(fixture.actor.pack(), Goods.specOf(product)), (long) product.getCount(),
                        "the same original batch produces its exact output once");
                    helper.assertValueEqual(
                        Goods.countIn(
                            fixture.actor.pack(), Goods.specOf(station.inputs().get().getFirst())),
                        0L, "the invested raw ingredient was neither refunded nor"
                        + " loaded again");
                    fixture.close();
                    helper.succeed();
                }
                } catch (RuntimeException | Error failure) {
                fixture.close();
                throw failure;
                }
                });
        } catch (RuntimeException | Error failure) {
            fixture.close();
            throw failure;
        }
    }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final Station station;
        final ServerLevel level;
        final BlockPos cell;
        final WorldPos at;
        final UUID colony = UUID.randomUUID();
        final KitchenBookings bookings = new KitchenBookings();
        final AtomicInteger changes = new AtomicInteger();
        final KitchenStation provider;
        final Stances stances;
        final List<Hold> claims = new ArrayList<>();
        ResidentEntity actor;
        boolean closed;

        Fixture(GameTestHelper helper, Station station) {
            this.helper = helper;
            this.station = station;
            level = helper.getLevel();
            cell = helper.absolutePos(new BlockPos(5, 2, 5));
            at = WorldPos.of(level, cell);
            for (BlockPos position :
                BlockPos.betweenClosed(cell.offset(-4, -1, -4), cell.offset(4, 4, 4))) {
                level.setBlockAndUpdate(
                    position, (position.getY() == cell.getY() - 1 ? Blocks.STONE : Blocks.AIR)
                    .defaultBlockState());
            }
            level.setBlockAndUpdate(
                cell.below(), (station.name().equals("pot") || station.name().equals("stockpot")
                    ? Blocks.CAMPFIRE
                    : Blocks.MAGMA_BLOCK)
                .defaultBlockState());
            var state = station.block().get().defaultBlockState();
            if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                state =
                        state.setValue(BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.NORTH);
            }
            level.setBlockAndUpdate(cell, state);
            station.block()
                    .get()
                    .setPlacedBy(level, cell, state, null, new ItemStack(station.block().get()));
            if (station.name().equals("spit")) {
                level.setBlockAndUpdate(cell.east(), Blocks.REDSTONE_BLOCK.defaultBlockState());
                level.setBlockAndUpdate(cell.east().above(), Blocks.REDSTONE_BLOCK.defaultBlockState());
            }
            provider = KitchenStations.find(level.getBlockState(cell)).orElseThrow();
            stances =
                    station.name().equals("millstone")
                            ? Stances.at(level, cell.offset(3, 0, 0))
                            : provider.stances(level, at).orElseThrow();
            actor = newWorker();
            station.inputs()
                    .get()
                    .forEach(
                        stack ->
                        helper.assertTrue(
                            Containers.insert(actor.pack(), stack).isEmpty(), "the original materials fit"));
            Containers.insert(actor.pack(), new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get()));
            Containers.insert(actor.pack(), new ItemStack(ModItems.KITCHEN_SHOVEL.get()));
        }

        private ResidentEntity newWorker() {
            ResidentEntity body = PersonBody.RESIDENT.get().create(level);
            var feet = stances.cells().iterator().next().block(level);
            body.setPos(Vec3.atBottomCenterOf(feet).add(0, station.name().equals("tavern") ? 0.5 : 0, 0));
            body.setNoAi(!station.name().equals("millstone") && !station.name().equals("tavern"));
            body.setOnGround(true);
            body.setInvulnerable(true);
            level.addFreshEntity(body);
            return body;
        }

        void replaceWorker() {
            ResidentEntity previous = actor;
            actor = newWorker();
            for (int slot = 0; slot < previous.pack().getContainerSize(); slot++) {
                ItemStack item = previous.pack().removeItemNoUpdate(slot);
                helper.assertTrue(
                    Containers.insert(actor.pack(), item).isEmpty(), "unspent cargo transfers physically");
            }
            previous.discard();
            provider.tick(level, colony);
        }

        Node plan() {
            ItemStack product = station.product().get();
            var workshop =
                    provider.freeze(UUID.randomUUID(), colony, level, at, stances, bookings, changes::incrementAndGet)
                            .orElseThrow();
            Produce wanted =
                    Produce.of(
                        CookeryContent.ID, new WorkSite.AtBlock(at), Goods.specOf(product), product.getCount(),
                        Produce.UNBOUNDED);
            var change =
                    workshop.options(wanted, Grown.of(wanted)).stream()
                            .filter(
                                option ->
                                option
                                .replacement()
                                .nodes()
                                .getFirst()
                                .spec()
                                .needs()
                                .stream()
                                .allMatch(
                                    need ->
                                    Goods.countIn(actor.pack(), need.spec())
                                    >= need.count()))
                            .findFirst()
                            .orElseThrow();
            claims.addAll(change.holds());
            claims.forEach(claim -> helper.assertTrue(claim.take().isEmpty(), "the native turn is booked"));
            Node node = change.replacement().nodes().getFirst();
            helper.assertTrue(node.planned(level).isEmpty(), "the native work is adopted");
            return node;
        }

        List<ItemStack> cargo() {
            return java.util.stream.IntStream.range(0, actor.pack().getContainerSize())
                    .mapToObj(slot -> actor.pack().getItem(slot).copy())
                    .toList();
        }

        void assertCargo(List<ItemStack> expected) {
            for (int slot = 0; slot < expected.size(); slot++) {
                helper.assertTrue(
                    ItemStack.matches(expected.get(slot), actor.pack().getItem(slot)),
                    "repeated cleanup preserves each physical cargo slot");
            }
        }

        void stow(Outcome outcome) {
            helper.assertTrue(outcome instanceof Outcome.Done, "the actual native operation succeeds: " + outcome);
            ((Outcome.Done) outcome)
                    .made()
                    .forEach(
                        stack ->
                        helper.assertTrue(
                            Containers.insert(actor.pack(), stack).isEmpty(), "all original native products fit"));
        }

        Worker worker() {
            return new TestWorker(() -> actor.resident(), () -> actor, () -> actor.pack(),
                Placement.NOWHERE, List.of())
                .effects(() -> actor.effects())
                .held(need -> Tools.from(actor.pack().contents(), need).orElse(ItemStack.EMPTY))
                .spill(stack -> {
                    if (!stack.isEmpty()) {
                        throw new AssertionError("unexpected spill " + stack);
                    }
                });
        }

        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            claims.forEach(claim -> claim.release(Ending.REVOKED));
            provider.release(colony, level, at);
            provider.stop(colony, Closing.RAZED);
            actor.discard();
        }
    }
}
