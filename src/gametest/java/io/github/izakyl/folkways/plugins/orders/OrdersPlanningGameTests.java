package io.github.izakyl.folkways.plugins.orders;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.StoreRule;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Delivery;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.colony.ColonySnapshot;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OrdersPlanningGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(OrdersPlanningGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void lowWaterStartsOneFixedBatchWhichSurvivesInventoryChangesAndReload(GameTestHelper helper)
            throws ReflectiveOperationException {
        var level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(2, 1, 2));
        for (BlockPos floor : BlockPos.betweenClosed(at.offset(-3, -1, -3), at.offset(3, -1, 3))) {
            level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(floor.above(), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(floor.above(2), Blocks.AIR.defaultBlockState());
        }
        level.setBlockAndUpdate(at, Blocks.BARREL.defaultBlockState());
        var target = Containers.at(level, at).orElseThrow();
        ItemSpec iron = ItemSpec.of(ResourceLocation.withDefaultNamespace("iron_ingot"));
        Order order = new Order(WorldPos.of(level, at), iron, 32, 64, true, 0);
        var view = ColonySnapshot.of(new ColonyData(), level);
        AtomicInteger withdrawals = new AtomicInteger();
        AtomicReference<CompoundTag> kept = new AtomicReference<>(new CompoundTag());
        Set<UUID> submitted = new LinkedHashSet<>();
        UUID colonyId = UUID.randomUUID();
        Colony colony = (Colony) Proxy.newProxyInstance(Colony.class.getClassLoader(),
            new Class<?>[] {Colony.class}, (proxy, method, args) -> switch (method.getName()) {
                case "kept" -> kept.get().copy();
                case "views" -> throw new AssertionError("orders may target containers outside marked colony cells");
                case "submitted" -> Set.copyOf(submitted);
                case "submit" -> { submitted.add(idOf((Grown) args[2])); yield null; }
                case "withdraw" -> { withdrawals.incrementAndGet(); submitted.remove(args[1]); yield null; }
                case "keep" -> { kept.set(((CompoundTag) args[1]).copy()); yield null; }
                case "rule" -> null;
                case "id" -> colonyId;
                default -> throw new AssertionError(method.getName());
            });
        OrdersPresence presence = new OrdersPresence(colony);
        var field = OrdersPresence.class.getDeclaredField("orders");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Order.Key, Order> orders = (Map<Order.Key, Order>) field.get(presence);
        orders.put(order.key(), order);
        target.setItem(0, new ItemStack(Items.IRON_INGOT, 32));
        presence.refresh(level.getServer());
        helper.assertTrue(presence.goals(view).isEmpty(), "equal to low water must not trigger");
        target.setItem(0, new ItemStack(Items.IRON_INGOT, 7));
        helper.assertTrue(presence.goals(view).isEmpty(), "reading work must not inspect inventory or create a batch");
        presence.refresh(level.getServer());
        var goal = presence.goals(view).getFirst();
        int before = withdrawals.get();
        presence.refresh(level.getServer());
        helper.assertTrue(withdrawals.get() == before && idOf(presence.goals(view).getFirst()).equals(idOf(goal)),
            "unchanged stock must keep the submitted node without starting another batch");
        helper.assertTrue(owed(goal) == 57, "batch is high minus stock at trigger time");
        target.setItem(0, new ItemStack(Items.IRON_INGOT, 2));
        presence.refresh(level.getServer());
        var active = presence.goals(view);
        helper.assertTrue(active.size() == 1 && idOf(active.getFirst()).equals(idOf(goal))
                && owed(active.getFirst()) == 57,
            "further consumption must neither resize the batch nor start another task");
        target.setItem(0, new ItemStack(Items.IRON_INGOT, 40));
        presence.refresh(level.getServer());
        var unchanged = presence.goals(view).getFirst();
        helper.assertTrue(idOf(unchanged).equals(idOf(goal)) && owed(unchanged) == 57,
            "inventory changes must preserve the already submitted task");
        arrive(goal, 8);
        presence.tick(level.getServer());
        helper.assertTrue(owed(presence.goals(view).getFirst()) == 49,
            "a finished load asks for the remainder on the next tick, without inspecting stock again");
        OrdersPresence restored = new OrdersPresence(colony);
        restored.refresh(level.getServer());
        var remaining = restored.goals(view).getFirst();
        helper.assertTrue(owed(remaining) == 49,
            "reload must restore the finite batch remainder even above low water");
        int standing = withdrawals.get();
        arrive(remaining, 49);
        restored.tick(level.getServer());
        helper.assertTrue(restored.goals(view).isEmpty() && withdrawals.get() == standing + 1,
            "completing a batch withdraws it and does not top up again to high water");
        target.setItem(0, new ItemStack(Items.IRON_INGOT, 5));
        restored.refresh(level.getServer());
        var next = restored.goals(view).getFirst();
        helper.assertTrue(!idOf(next).equals(idOf(remaining)) && owed(next) == 59,
            "after completion, crossing low water starts a new batch using current stock");

        target.setItem(0, ItemStack.EMPTY);
        restored.refresh(level.getServer());
        var running = restored.goals(view).getFirst();
        helper.assertTrue(idOf(running).equals(idOf(next)) && owed(running) == 59,
            "an active batch still runs to completion when stock is empty");
        arrive(next, 59);
        restored.refresh(level.getServer());
        var again = restored.goals(view).getFirst();
        helper.assertTrue(!idOf(again).equals(idOf(next)) && owed(again) == 64,
            "completion rechecks stock and starts another batch if still below low water");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void standingOrdersAreReservesAndShelvesRuleTheirContainer(GameTestHelper helper)
            throws ReflectiveOperationException {
        var level = helper.getLevel();
        BlockPos kitchen = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos hall = helper.absolutePos(new BlockPos(4, 1, 1));
        level.setBlockAndUpdate(kitchen, Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(hall, Blocks.BARREL.defaultBlockState());
        WorldPos kitchenAt = WorldPos.of(level, kitchen);
        WorldPos hallAt = WorldPos.of(level, hall);
        ItemSpec bread = ItemSpec.of(ResourceLocation.withDefaultNamespace("bread"));
        ItemSpec iron = ItemSpec.of(ResourceLocation.withDefaultNamespace("iron_ingot"));
        AtomicReference<List<StoreRule>> ruled = new AtomicReference<>(List.of());
        AtomicReference<CompoundTag> kept = new AtomicReference<>(new CompoundTag());
        Colony colony = (Colony) Proxy.newProxyInstance(Colony.class.getClassLoader(),
            new Class<?>[] {Colony.class}, (proxy, method, args) -> switch (method.getName()) {
                case "kept" -> kept.get().copy();
                case "keep" -> { kept.set(((CompoundTag) args[1]).copy()); yield null; }
                case "rule" -> {
                    helper.assertTrue(args[1].equals(level.dimension()), "rules are published per dimension");
                    ruled.set(List.copyOf((Collection<StoreRule>) args[2]));
                    yield null;
                }
                case "submitted" -> Set.of();
                case "submit", "withdraw" -> null;
                case "id" -> UUID.randomUUID();
                default -> throw new AssertionError(method.getName());
            });
        OrdersPresence presence = new OrdersPresence(colony);
        @SuppressWarnings("unchecked")
        Map<Order.Key, Order> orders = (Map<Order.Key, Order>) field(presence, "orders");
        @SuppressWarnings("unchecked")
        Map<WorldPos, Shelf> shelves = (Map<WorldPos, Shelf>) field(presence, "shelves");
        Order standing = new Order(kitchenAt, bread, 8, 16, true, 0);
        Order once = new Order(hallAt, iron, 4, 4, false, 0);
        orders.put(standing.key(), standing);
        orders.put(once.key(), once);
        shelves.put(hallAt, new Shelf(hallAt, Optional.of(new Shelf.Admitting(true, iron)),
            Optional.of(new Shelf.Span(1, 9))));
        presence.refresh(level.getServer());
        helper.assertTrue(ruled.get().equals(List.of(
                new StoreRule.Reserve(kitchenAt, bread),
                new StoreRule.Admit(hallAt, true, List.of(iron)),
                new StoreRule.Slots(hallAt, 0, 9))),
            "a standing order is a reserve, a one-off is not, and a shelf rules its container by slot index from 0");

        OrdersPresence restored = new OrdersPresence(colony);
        @SuppressWarnings("unchecked")
        Map<WorldPos, Shelf> reloaded = (Map<WorldPos, Shelf>) field(restored, "shelves");
        helper.assertTrue(reloaded.equals(shelves), "shelves survive a reload");

        level.setBlockAndUpdate(hall, Blocks.AIR.defaultBlockState());
        presence.refresh(level.getServer());
        helper.assertTrue(ruled.get().equals(List.of(new StoreRule.Reserve(kitchenAt, bread))),
            "a shelf goes with its container");
        helper.succeed();
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static UUID idOf(Grown goal) {
        return goal.nodes().getFirst().id();
    }

    private static Delivery deliveryOf(Grown goal) {
        return (Delivery) goal.nodes().getFirst();
    }

    private static long owed(Grown goal) {
        return deliveryOf(goal).goods().count();
    }

    private static void arrive(Grown goal, int count) {
        deliveryOf(goal).arrived().accept(count);
    }
}
