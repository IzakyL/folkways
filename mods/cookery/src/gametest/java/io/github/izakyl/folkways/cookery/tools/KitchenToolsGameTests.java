package io.github.izakyl.folkways.cookery.tools;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_cookery", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class KitchenToolsGameTests {

    private static final KitchenTools.Inventory EMPTY = new KitchenTools.Inventory(List.of(), Map.of());

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(KitchenToolsGameTests.class);
    }

    @GameTest(template = "empty")
    public static void unknownUnloadedStockIsNotProofThatTheColonyHasNoTool(GameTestHelper helper) {
        KitchenTools pool = new KitchenTools();
        UUID colony = UUID.randomUUID();
        UUID task = UUID.randomUUID();
        var unknown = new KitchenTools.Inventory(List.of(), Map.of(), false);
        var waiting = pool.request(colony, task, family(), unknown);
        helper.assertTrue(waiting.status() == KitchenTools.Status.UNREACHABLE && !waiting.permitsProduction(),
            "an unloaded store or resident is never counted as an empty tool inventory");
        var available = new KitchenTools.Inventory(List.of(available()), Map.of(), false);
        helper.assertTrue(pool.request(colony, task, family(), available).status() == KitchenTools.Status.AVAILABLE,
            "an actually loaded spare can still be borrowed while another store is unloaded");
        helper.assertTrue(pool.request(colony, task, family(), EMPTY).permitsProduction(),
            "once all sources are observed empty, true absence permits one bootstrap tool");
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void concurrentEmptyRequestsMergeOneReplacementAndOnlyTheFirstMayManufacture(GameTestHelper helper) {
        KitchenTools pool = new KitchenTools();
        UUID colony = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ItemSpec tools = family();
        KitchenTools.Decision head = pool.request(colony, first, tools, EMPTY);
        helper.assertTrue(head.permitsProduction(), "the first missing-tool task may manufacture the first tool");
        helper.assertValueEqual(head.replenishment().orElseThrow().count(), 1L, "the default replenishment asks for one tool");
        helper.assertTrue(!pool.request(colony, second, tools, EMPTY).permitsProduction(),
            "a second simultaneous task cannot grow another missing-tool production");
        helper.assertTrue(!pool.request(colony, UUID.randomUUID(), tools, EMPTY).permitsProduction(),
            "more waiting residents never multiply the default stock");
        pool.cancel(colony, first);
        helper.assertValueEqual(pool.request(colony, second, tools, EMPTY).replenishment().orElseThrow().id(),
            head.replenishment().orElseThrow().id(), "canceling the first task retains the same merged supply identity");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void automaticAndPlayerCommitmentsDoNotOscillateOrManufactureTwice(GameTestHelper helper) {
        KitchenTools pool = new KitchenTools();
        UUID colony = UUID.randomUUID();
        UUID task = UUID.randomUUID();
        ItemSpec tools = family();
        UUID automatic = pool.request(colony, task, tools, EMPTY).replenishment().orElseThrow().id();
        var own = new KitchenTools.Inventory(List.of(), Map.of(automatic, 1L));
        helper.assertValueEqual(pool.request(colony, task, tools, own).replenishment().orElseThrow().id(), automatic,
            "the pool does not withdraw its own live production as if another order had replaced it");
        var player = new KitchenTools.Inventory(List.of(), Map.of(automatic, 1L, UUID.randomUUID(), 3L));
        var supplied = pool.request(colony, task, tools, player);
        helper.assertTrue(supplied.status() == KitchenTools.Status.BUSY && supplied.replenishment().isEmpty(),
            "an explicit player stock order supplies the missing family without an additional automatic order");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void duplicateTransitObservationsAndUnreachableToolsNeverTriggerManufacture(GameTestHelper helper) {
        KitchenTools pool = new KitchenTools();
        UUID colony = UUID.randomUUID();
        UUID task = UUID.randomUUID();
        UUID physical = UUID.randomUUID();
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        var transit = new KitchenTools.Inventory(List.of(
            new KitchenTools.Tool(physical, sword, KitchenTools.Whereabouts.AVAILABLE),
            new KitchenTools.Tool(physical, sword, KitchenTools.Whereabouts.IN_TRANSIT)), Map.of());
        helper.assertValueEqual(transit.tools().size(), 1, "two sightings of one tool remain one physical unit");
        helper.assertTrue(pool.request(colony, task, family(), transit).status() == KitchenTools.Status.BUSY,
            "the occupied sighting prevents another task taking the same tool");
        helper.assertTrue(pool.reserve(colony, task, family(), UUID.randomUUID(), transit).isEmpty(),
            "a stale available sighting cannot steal an in-transit tool");
        var unreachable = new KitchenTools.Inventory(List.of(
            new KitchenTools.Tool(physical, sword, KitchenTools.Whereabouts.UNREACHABLE)), Map.of());
        var blocked = pool.request(colony, task, family(), unreachable);
        helper.assertTrue(blocked.status() == KitchenTools.Status.UNREACHABLE && !blocked.permitsProduction(),
            "an existing tool behind an unreachable route is a route problem, never a missing-tool craft");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void actualAdditionalToolsPermitParallelLoansWithoutChangingDefaultSupply(GameTestHelper helper) {
        KitchenTools pool = new KitchenTools();
        UUID colony = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        var inventory = new KitchenTools.Inventory(List.of(available(), available()), Map.of());
        pool.request(colony, first, family(), inventory);
        pool.request(colony, second, family(), inventory);
        var one = pool.reserve(colony, first, family(), UUID.randomUUID(), inventory).orElseThrow();
        var two = pool.reserve(colony, second, family(), UUID.randomUUID(), inventory).orElseThrow();
        helper.assertTrue(!one.tool().equals(two.tool()), "two workers borrow two distinct physical tools");
        helper.assertTrue(pool.carried(one.id()) && pool.carried(two.id()), "both physical deliveries may complete");
        helper.assertTrue(!pool.request(colony, UUID.randomUUID(), family(), inventory).permitsProduction(),
            "a third task waits for a loan rather than expanding stock to resident count");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void cancellationKeepsTheToolReservedUntilItsPhysicalReturnCompletes(GameTestHelper helper) {
        KitchenTools pool = new KitchenTools();
        UUID colony = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        var inventory = new KitchenTools.Inventory(List.of(available()), Map.of());
        pool.request(colony, first, family(), inventory);
        var loan = pool.reserve(colony, first, family(), UUID.randomUUID(), inventory).orElseThrow();
        pool.request(colony, second, family(), inventory);
        pool.cancel(colony, first);
        helper.assertTrue(!pool.carried(loan.id()), "a late delivery callback cannot reactivate canceled work");
        helper.assertTrue(pool.leases(colony).getFirst().phase() == KitchenTools.Phase.RETURNING,
            "canceling work preserves ownership of its real tool until return");
        helper.assertTrue(pool.reserve(colony, second, family(), UUID.randomUUID(), inventory).isEmpty(),
            "another task cannot borrow from a stale source snapshot during the return");
        helper.assertTrue(!pool.request(colony, second, family(), EMPTY).permitsProduction(),
            "a temporarily unseen returning tool is not treated as destroyed");
        helper.assertTrue(pool.returned(loan.id()), "the actual rack or warehouse arrival releases the tool");
        helper.assertTrue(pool.reserve(colony, second, family(), UUID.randomUUID(), inventory).isPresent(),
            "the waiting task may then borrow that same returned physical unit");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void restartRecoversOnlyPhysicalReturnsWithoutRevivingOldWork(GameTestHelper helper) {
        KitchenTools before = new KitchenTools();
        UUID colony = UUID.randomUUID();
        UUID task = UUID.randomUUID();
        var inventory = new KitchenTools.Inventory(List.of(available()), Map.of());
        before.request(colony, task, family(), inventory);
        var loan = before.reserve(colony, task, family(), UUID.randomUUID(), inventory).orElseThrow();
        before.carried(loan.id());
        KitchenTools loaded = new KitchenTools();
        loaded.load(colony, before.save(colony));
        var recovered = loaded.leases(colony).getFirst();
        helper.assertTrue(recovered.phase() == KitchenTools.Phase.RETURNING && recovered.id().equals(loan.id()),
            "reload keeps a stable physical loan, with no running permit");
        helper.assertTrue(!loaded.carried(recovered.id()), "the previous task cannot resume its old execution permit");
        UUID next = UUID.randomUUID();
        helper.assertTrue(!loaded.request(colony, next, family(), EMPTY).permitsProduction(),
            "an unloaded borrower does not cause a duplicate replacement");
        helper.assertTrue(loaded.returned(recovered.id()), "a real recovery return settles the old loan once");
        helper.assertTrue(!loaded.returned(recovered.id()), "a repeated callback cannot settle it twice");
        helper.assertTrue(loaded.request(colony, next, family(), inventory).status() == KitchenTools.Status.AVAILABLE,
            "new work can use the physically returned tool after recovery");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void confirmedBreakageAllowsOneReplacementWithinItsOwnColony(GameTestHelper helper) {
        KitchenTools pool = new KitchenTools();
        UUID colony = UUID.randomUUID();
        UUID task = UUID.randomUUID();
        var inventory = new KitchenTools.Inventory(List.of(available()), Map.of());
        pool.request(colony, task, family(), inventory);
        var loan = pool.reserve(colony, task, family(), UUID.randomUUID(), inventory).orElseThrow();
        pool.carried(loan.id());
        helper.assertTrue(!pool.request(colony, task, family(), EMPTY).permitsProduction(),
            "absence alone does not mean the physical tool broke");
        helper.assertTrue(pool.lost(loan.id()), "a confirmed tool break ends the physical reservation");
        var replacement = pool.request(colony, task, family(), EMPTY).replenishment().orElseThrow();
        helper.assertValueEqual(replacement.count(), 1L, "confirmed breakage replenishes one tool");
        helper.assertTrue(!pool.request(colony, UUID.randomUUID(), family(), EMPTY).permitsProduction(),
            "waiting tasks do not each replace the same broken tool");
        var other = pool.request(UUID.randomUUID(), UUID.randomUUID(), family(), EMPTY).replenishment().orElseThrow();
        helper.assertTrue(!other.id().equals(replacement.id()), "separate colonies do not share supply identities");
        helper.succeed();
    }

    private static ItemSpec family() {
        return Goods.specOf(new ItemStack(Items.IRON_SWORD));
    }

    private static KitchenTools.Tool available() {
        return new KitchenTools.Tool(UUID.randomUUID(), new ItemStack(Items.IRON_SWORD), KitchenTools.Whereabouts.AVAILABLE);
    }
}
