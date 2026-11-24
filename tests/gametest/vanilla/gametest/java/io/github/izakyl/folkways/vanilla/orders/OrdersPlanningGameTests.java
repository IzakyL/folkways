package io.github.izakyl.folkways.vanilla.orders;

import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.card.api.Card;
import io.github.izakyl.folkways.core.card.api.notice.Line;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.internal.ColonyData;
import io.github.izakyl.folkways.core.colony.internal.ColonySnapshot;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.front.api.ColonyPanel;
import io.github.izakyl.folkways.core.front.engine.colony.HeldFacilities;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemFilter;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Delivery;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.internal.Haul;
import io.github.izakyl.folkways.core.testing.Proxies;
import io.github.izakyl.folkways.core.testing.Purity;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.internal.TransferNode;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.wares.WaresContent;
import java.lang.reflect.InvocationHandler;
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
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class OrdersPlanningGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(OrdersPlanningGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void orderCardsDoNotOpenLoot(GameTestHelper helper) throws ReflectiveOperationException {
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        BlockPos at = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(at, Blocks.CHEST.defaultBlockState());
        var chest = (ChestBlockEntity) level.getBlockEntity(at);
        chest.setLootTable(BuiltInLootTables.SIMPLE_DUNGEON, 21L);
        var presence = colony.service(OrdersContent.ID, OrdersPresence.class).orElseThrow();
        var field = OrdersPresence.class.getDeclaredField("orders");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var orders = (Map<Order.Key, Order>) field.get(presence);
        var order = new Order(WorldPos.of(level, at),
            ItemSpec.of(ResourceLocation.withDefaultNamespace("iron_ingot")), 1, 8, true);
        orders.put(order.key(), order);
        try {
            var claim = colony.hold(WaresContent.STORE, new Claimed.Block(order.into())).orElseThrow();
            var held = HeldFacilities.find(colony, claim.id()).orElseThrow();
            var view = colony.view(level);
            Purity.assertPure(helper, "loot order cards", () -> {
                presence.rows(ColonyPanel.Page.STOCK, view);
                held.rows(level, OrdersPresence.HERE, Map.of());
            });
            helper.assertTrue(chest.getLootTable() != null, "orders must retain unopened loot");
        } finally {
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void maintainAndDeliverIgnoreFiltersAndAllowConfigurationWithOrders(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos cell = helper.absolutePos(new BlockPos(2, 1, 2));
        level.setBlockAndUpdate(cell, Blocks.BARREL.defaultBlockState());
        WorldPos at = WorldPos.of(level, cell);
        ItemSpec bread = ItemSpec.of(Items.BREAD);
        ItemFilter iron = ItemFilter.item(ResourceLocation.withDefaultNamespace("iron_ingot"));
        ItemFilter breadFilter = ItemFilter.item(ResourceLocation.withDefaultNamespace("bread"));
        var body = PersonBody.RESIDENT.get().create(level);
        var worker = TestWorker.of(body).supplied(() -> List.of(new ItemStack(Items.BREAD)));
        var target = Containers.at(level, cell).orElseThrow();
        try {
            for (String action : List.of(OrderAct.MAINTAIN, OrderAct.DELIVER)) {
                for (int restriction = 0; restriction < 3; restriction++) {
                    target.clearContent();
                    Colony colony = Colonies.mint(level.getServer());
                    try {
                        var claim = colony.hold(WaresContent.STORE, new Claimed.Block(at)).orElseThrow();
                        var facility = HeldFacilities.find(colony, claim.id()).orElseThrow();
                        var presence = colony.service(OrdersContent.ID, OrdersPresence.class).orElseThrow();
                        var view = colony.view(level);
                        helper.assertTrue(facility.setParameter(OrdersContent.ITEM.key(),
                            new Value.Items(List.of(breadFilter))).isEmpty(), "select bread for the draft");
                        if (restriction == 0) {
                            helper.assertTrue(facility.setParameter(OrdersContent.FILTER.key(),
                                new Value.Items(List.of(iron))).isEmpty(), "select iron");
                            helper.assertTrue(facility.setParameter(OrdersPresence.FILTER_MODE,
                                new Value.Text("folkways_core:filter/only")).isEmpty(), "whitelist iron");
                        } else if (restriction == 1) {
                            helper.assertTrue(facility.setParameter(OrdersContent.FILTER.key(),
                                new Value.Items(List.of(breadFilter))).isEmpty(), "blacklist bread");
                        } else {
                            helper.assertTrue(facility.setParameter(OrdersPresence.STORE, Value.of(false)).isEmpty(),
                                "disable ordinary storage");
                        }
                        helper.assertTrue(presence.act(facility.view(), level.getServer(), action, Map.of())
                            .orElseThrow().isEmpty(), "both order modes can be filed despite incoming restrictions");
                        presence.refresh(level.getServer());
                        Delivery delivery = (Delivery) presence.goals(view).getFirst().nodes().getFirst();
                        TransferNode put = (TransferNode) Haul.DELIVERIES.expand(delivery).orElseThrow()
                            .nodes().getFirst();
                        helper.assertTrue(put.commit(level, worker) instanceof Outcome.Done
                                && Goods.countIn(target, bread) == 1,
                            action + " delivers bread under restriction " + restriction);
                        for (String mode : List.of("folkways_core:filter/except", "folkways_core:filter/only")) {
                            helper.assertTrue(facility.setParameter(OrdersPresence.FILTER_MODE,
                                new Value.Text(mode)).isEmpty(), "existing orders allow changing filter mode");
                        }
                        for (boolean allow : List.of(false, true)) {
                            helper.assertTrue(facility.setParameter(OrdersPresence.STORE, Value.of(allow)).isEmpty(),
                                "existing orders allow changing storage access");
                        }
                        helper.assertTrue(facility.setParameter(OrdersContent.FILTER.key(),
                            new Value.Items(List.of(breadFilter))).isEmpty(), "existing orders allow filtering bread");
                        helper.assertTrue(facility.setParameter(OrdersContent.FILTER.key(),
                            new Value.Items(List.of())).isEmpty(), "existing orders allow clearing the filter");
                    } finally {
                        colony.raze();
                    }
                }
            }
        } finally {
            body.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void onceOrderRetiresWhenActiveTargetIsFilledElsewhere(GameTestHelper helper)
            throws ReflectiveOperationException {
        var level = helper.getLevel();
        BlockPos cell = helper.absolutePos(new BlockPos(2, 1, 2));
        for (BlockPos floor : BlockPos.betweenClosed(cell.offset(-3, -1, -3), cell.offset(3, -1, 3))) {
            level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(floor.above(), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(floor.above(2), Blocks.AIR.defaultBlockState());
        }
        level.setBlockAndUpdate(cell, Blocks.BARREL.defaultBlockState());
        WorldPos at = WorldPos.of(level, cell);
        Colony observations = Colonies.mint(level.getServer());
        try {
            AtomicInteger withdrawals = new AtomicInteger();
            Colony colony = colony((proxy, method, args) -> switch (method.getName()) {
                case "inventory" -> observations.inventory();
                case "id" -> observations.id();
                case "submitted" -> Set.of();
                case "submit", "rule" -> null;
                case "withdraw" -> { withdrawals.incrementAndGet(); yield null; }
                default -> throw new AssertionError(method.getName());
            });
            OrdersPresence presence = new OrdersPresence(colony);
            Order order = new Order(at, ItemSpec.of(Items.IRON_INGOT), 3, 3, false);
            @SuppressWarnings("unchecked")
            Map<Order.Key, Order> orders = (Map<Order.Key, Order>) field(presence, "orders");
            orders.put(order.key(), order);
            var view = ColonySnapshot.of(new ColonyData(), level);
            presence.refresh(level.getServer());
            helper.assertTrue(presence.goals(view).size() == 1, "an empty target starts a delivery batch");
            Containers.at(level, cell).orElseThrow().setItem(0, new ItemStack(Items.IRON_INGOT, 3));
            observations.inventoryFeedback().changed(Stash.at(at));
            presence.refresh(level.getServer());
            helper.assertTrue(orders.containsKey(order.key()) && presence.goals(view).size() == 1,
                "a dirty target cannot complete the order from an unobserved change");
            ColonyData observed = ColonyData.find(level.getServer(), observations.id()).orElseThrow();
            observed.inventory().advance(level, observed.works(), Map.of());
            presence.refresh(level.getServer());
            helper.assertTrue(orders.isEmpty() && presence.goals(view).isEmpty() && withdrawals.get() == 1,
                "a filled target retires the once order and withdraws its active batch");
            OrdersPresence restored = new OrdersPresence(colony);
            helper.assertTrue(((Map<?, ?>) field(restored, "orders")).isEmpty(),
                "the retired order stays gone after reload");
        } finally {
            observations.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void dirtyOrderTargetWaitsWithoutStartingABatchOrReportingAbsence(GameTestHelper helper)
            throws ReflectiveOperationException {
        var level = helper.getLevel();
        BlockPos cell = helper.absolutePos(new BlockPos(2, 1, 2));
        level.setBlockAndUpdate(cell, Blocks.BARREL.defaultBlockState());
        WorldPos at = WorldPos.of(level, cell);
        Colony observations = Colonies.mint(level.getServer());
        try {
            Colony colony = colony((proxy, method, args) -> switch (method.getName()) {
                case "inventory" -> observations.inventory();
                case "id" -> observations.id();
                case "submitted" -> Set.of();
                case "rule", "withdraw" -> null;
                case "submit" -> throw new AssertionError("unknown stock cannot trigger replenishment");
                default -> throw new AssertionError(method.getName());
            });
            OrdersPresence presence = new OrdersPresence(colony);
            Order order = new Order(at, ItemSpec.of(Items.IRON_INGOT), 32, 64, true);
            @SuppressWarnings("unchecked")
            Map<Order.Key, Order> orders = (Map<Order.Key, Order>) field(presence, "orders");
            orders.put(order.key(), order);
            observations.inventory().observe(OrdersContent.ID, level, List.of(at));
            observations.inventoryFeedback().changed(Stash.at(at));
            presence.refresh(level.getServer());
            var view = ColonySnapshot.of(new ColonyData(), level);
            helper.assertTrue(presence.goals(view).isEmpty(), "dirty stock cannot start a new replenishment batch");
            Card.Row row = presence.rows(ColonyPanel.Page.STOCK, view).getFirst();
            helper.assertTrue(row.details().getLast() instanceof Line.Text detail
                    && detail.component().getContents() instanceof TranslatableContents translated
                    && translated.getKey().equals("folkways.panel.orders.unknown"),
                "a pending stock check is not presented as a missing target or zero stock");
        } finally {
            observations.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
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
        Order order = new Order(WorldPos.of(level, at), iron, 32, 64, true);
        var view = ColonySnapshot.of(new ColonyData(), level);
        AtomicInteger withdrawals = new AtomicInteger();
        Set<UUID> submitted = new LinkedHashSet<>();
        Colony observations = Colonies.mint(level.getServer());
        UUID colonyId = UUID.randomUUID();
        Colony colony = colony((proxy, method, args) -> switch (method.getName()) {
            case "inventory" -> observations.inventory();
            case "views" -> throw new AssertionError("orders may target containers outside marked colony cells");
            case "submitted" -> Set.copyOf(submitted);
            case "submit" -> { submitted.add(idOf((Grown) args[2])); yield null; }
            case "withdraw" -> { withdrawals.incrementAndGet(); submitted.remove(args[1]); yield null; }
            case "rule" -> null;
            case "id" -> colonyId;
            default -> throw new AssertionError(method.getName());
        });
        OrdersPresence presence = new OrdersPresence(colony);
        @SuppressWarnings("unchecked")
        Map<Order.Key, Order> orders = (Map<Order.Key, Order>) field(presence, "orders");
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
        observations.raze();
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
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
        Colony observations = Colonies.mint(level.getServer());
        Colony colony = colony((proxy, method, args) -> switch (method.getName()) {
            case "inventory" -> observations.inventory();
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
        Order standing = new Order(kitchenAt, bread, 8, 16, true);
        Order once = new Order(hallAt, iron, 4, 4, false);
        orders.put(standing.key(), standing);
        orders.put(once.key(), once);
        shelves.put(hallAt, new Shelf(hallAt, Optional.of(new Shelf.Admitting(true, iron)),
            false, false));
        presence.refresh(level.getServer());
        helper.assertTrue(ruled.get().equals(List.of(
                new StoreRule.Reserve(kitchenAt, bread),
                new StoreRule.Access(hallAt, false, false),
                new StoreRule.Admit(hallAt, true, List.of(iron)))),
            "only standing orders reserve goods and shelf access is independent of incoming goods filters");

        OrdersPresence restored = new OrdersPresence(colony);
        @SuppressWarnings("unchecked")
        Map<WorldPos, Shelf> reloaded = (Map<WorldPos, Shelf>) field(restored, "shelves");
        helper.assertTrue(reloaded.equals(shelves), "shelves survive a reload");

        level.setBlockAndUpdate(hall, Blocks.AIR.defaultBlockState());
        ColonyData observed = ColonyData.find(level.getServer(), observations.id()).orElseThrow();
        observed.inventory().advance(level, observed.works(), Map.of());
        presence.refresh(level.getServer());
        helper.assertTrue(ruled.get().equals(List.of(
                new StoreRule.Reserve(kitchenAt, bread),
                new StoreRule.Access(hallAt, false, false),
                new StoreRule.Admit(hallAt, true, List.of(iron)))) && shelves.containsKey(hallAt),
            "an unsupported observation keeps the shelf and its previous rules pending");
        observations.raze();
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 20)
    public static void containerFiltersAndAccessSurviveReload(GameTestHelper helper) {
        WorldPos at = WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 1)));
        ItemSpec iron = ItemSpec.of(ResourceLocation.withDefaultNamespace("iron_ingot"));
        ItemSpec bread = ItemSpec.of(ResourceLocation.withDefaultNamespace("bread"));
        ItemSpec gold = ItemSpec.of(ResourceLocation.withDefaultNamespace("gold_ingot"));
        ItemSpec chosen = ItemSpec.anyOf(List.of(iron, bread));
        Shelf allowing = new Shelf(at, Optional.of(new Shelf.Admitting(true, chosen)), false, false);
        helper.assertTrue(allowing.admits(iron) && allowing.admits(bread) && !allowing.admits(gold),
            "a whitelist admits all selected items and rejects other goods");
        Shelf barring = allowing.taking(Optional.of(new Shelf.Admitting(false, chosen)));
        helper.assertTrue(!barring.admits(iron) && !barring.admits(bread) && barring.admits(gold),
            "a blacklist excludes all selected items");
        helper.assertTrue(reload(barring).equals(barring), "filter and independent access choices survive reload");

        Shelf blacklist = Shelf.none(at).taking(Optional.of(new Shelf.Admitting(false, List.of())));
        Shelf whitelist = Shelf.none(at).taking(Optional.of(new Shelf.Admitting(true, List.of())));
        helper.assertTrue(blacklist.admits(iron), "an empty blacklist allows incoming goods");
        helper.assertTrue(!whitelist.admits(iron), "an empty whitelist rejects all incoming goods");
        for (Shelf shelf : List.of(blacklist, whitelist)) {
            Shelf restored = reload(shelf);
            helper.assertTrue(restored.equals(shelf), "saving an empty list preserves its explicit mode");
            helper.assertTrue(restored.rules(at).contains(new StoreRule.Admit(at,
                shelf.admitting().orElseThrow().only(), List.of())),
                "empty lists publish the selected mode to the planner");
        }
        List<ItemFilter> selected = List.of(
            ItemFilter.item(ResourceLocation.withDefaultNamespace("iron_ingot")),
            new ItemFilter(ItemFilter.Kind.QUERY, "*folkways_missing_item*"));
        Shelf original = Shelf.none(at).taking(Optional.of(new Shelf.Admitting(false, selected)));
        Shelf toggled = original.taking(Optional.of(new Shelf.Admitting(true,
            original.admitting().orElseThrow().filters())));
        Shelf restored = reload(toggled);
        helper.assertTrue(restored.admitting().orElseThrow().filters().equals(selected),
            "mode changes and reload preserve selected raw rules, including queries with no matching items");
        helper.assertTrue(restored.admitting().orElseThrow().only(), "the whitelist mode survives reload");
        helper.assertTrue(Shelf.none(at).admits(iron), "another container keeps its own default filter");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 20)
    public static void filterSummaryCountsDistinctItemsAcrossOverlappingRules(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos cell = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(cell, Blocks.BARREL.defaultBlockState());
        WorldPos at = WorldPos.of(level, cell);
        List<ItemFilter> selected = List.of(
            ItemFilter.tag(ResourceLocation.withDefaultNamespace("coals")),
            ItemFilter.item(ResourceLocation.withDefaultNamespace("coal")),
            new ItemFilter(ItemFilter.Kind.QUERY, "minecraft:coal"));
        Colony colony = Colonies.mint(level.getServer());
        try {
            var claim = colony.hold(WaresContent.STORE, new Claimed.Block(at)).orElseThrow();
            var facility = HeldFacilities.find(colony, claim.id()).orElseThrow();
            var presence = colony.service(OrdersContent.ID, OrdersPresence.class).orElseThrow();
            helper.assertTrue(facility.setParameter(OrdersContent.FILTER.key(), new Value.Items(selected)).isEmpty(),
                "set overlapping filters through the card");
            for (String mode : List.of("except", "only")) {
                var value = new Value.Text("folkways_core:filter/" + mode);
                helper.assertTrue(facility.setParameter(OrdersPresence.FILTER_MODE, value).isEmpty(),
                    "select the filter mode");
                helper.assertValueEqual(facility.parameters().get(OrdersPresence.FILTER_MODE), value,
                    "the card reads back the exclusive mode");
                Line.Text line = (Line.Text) presence.lines(facility.view(), colony.view(level)).getFirst();
                TranslatableContents summary = (TranslatableContents) line.component().getContents();
                helper.assertTrue(summary.getKey().equals("folkways.panel.orders.filter." + mode + ".count"),
                    "summary identifies the selected list mode");
                helper.assertTrue(summary.getArgs()[0].equals(2),
                    "coal tag, coal item and coal query count coal and charcoal once each");
            }
        } finally {
            colony.raze();
        }
        helper.succeed();
    }

    // A colony that keeps its saved state; everything else it is asked goes to the test's own answers.
    private static Colony colony(InvocationHandler rest) {
        AtomicReference<CompoundTag> kept = new AtomicReference<>(new CompoundTag());
        return Proxies.of(Colony.class, (proxy, method, args) -> switch (method.getName()) {
            case "kept" -> kept.get().copy();
            case "keep" -> {
                kept.set(((CompoundTag) args[1]).copy());
                yield null;
            }
            default -> rest.invoke(proxy, method, args);
        });
    }

    private static Shelf reload(Shelf shelf) {
        return Shelf.load(Reader.of(shelf.save())).orElseThrow();
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
