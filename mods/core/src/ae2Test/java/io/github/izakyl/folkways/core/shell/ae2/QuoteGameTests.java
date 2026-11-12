package io.github.izakyl.folkways.core.shell.ae2;

import appeng.api.stacks.AEItemKey;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Produce;
import io.github.izakyl.folkways.core.shell.crafting.CraftQuotes;
import io.github.izakyl.folkways.core.shell.crafting.PatternTimes;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@PrefixGameTestTemplate(false)
public final class QuoteGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(QuoteGameTests.class);
    }

    @GameTest(template = "ae2_empty", templateNamespace = "folkways_core", timeoutTicks = 1000)
    public static void previewsRejectMissingStockInvalidateAndRequireEnoughCpuBytes(GameTestHelper helper) {
        var rig = CraftingGameTests.craftingRig(helper);
        var colony = Colonies.mint(helper.getLevel().getServer());
        Cleanup.on(helper, colony::raze);
        var times = new PatternTimes(colony, Ae2Integration.OWNER);
        var calculations = new Calculations(times);
        Cleanup.on(helper, calculations::close);
        var item = Goods.specOf(new ItemStack(Items.OAK_PLANKS));
        helper.runAfterDelay(120, () -> {
            var port = rig.port();
            var network = (Devices.NetworkKey) port.identity().key();
            var request = new CraftQuotes.Request<>(network, item, 16);
            var missingSnapshot = calculations.quotes.snapshot();
            missingSnapshot.find(network, item, 16);
            helper.startSequence().thenWaitUntil(() -> {
                calculations.update(network, "test", port, Set.of(AEItemKey.of(Items.OAK_PLANKS)));
                helper.assertTrue(calculations.quotes.snapshot().quotes().containsKey(request),
                    "the missing-input calculation finished");
            }).thenExecute(() -> {
                helper.assertTrue(calculations.quotes.snapshot().find(network, item, 16).isEmpty(),
                    "a native plan with missing items is absent from planning");
                rig.stock(Items.OAK_LOG, 4);
                calculations.update(network, "test", port, Set.of(AEItemKey.of(Items.OAK_PLANKS)));
                helper.assertTrue(calculations.quotes.snapshot().find(network, item, 16).isEmpty(),
                    "stock revision invalidates the negative cache before the new computation finishes");
            }).thenWaitUntil(() -> {
                calculations.update(network, "test", port, Set.of(AEItemKey.of(Items.OAK_PLANKS)));
                helper.assertTrue(calculations.quotes.snapshot().find(network, item, 16).isPresent(),
                    "new ingredients make the exact quantity tier feasible");
            }).thenExecute(() -> {
                var quote = calculations.quotes.snapshot().find(network, item, 16).orElseThrow();
                helper.assertTrue(quote.patterns().values().stream().mapToLong(Long::longValue).sum() == 4
                        && quote.ticks() == 4 * (int) PatternTimes.DEFAULT_TICKS,
                    "cost is four pattern executions, independent of the sixteen output items");
                helper.assertTrue(missingSnapshot.quotes().isEmpty(), "published maps stay immutable after completion");
                var at = port.identity().home();
                var tally = new Tally();
                var cpu = tally.snapshot(port.node().getGrid().getCraftingService().getCpus().iterator().next());
                var depot = new Depot(UUID.randomUUID(), new WorkSite.AtBlock(at), network, tally, colony,
                    List.of(), helper.getLevel().getGameTime(), Set.of(item),
                    List.of(new Tally.Cpu(cpu.key(), Math.max(0, quote.bytes() - 1))),
                    List.of(new Depot.Device(at, Stances.WHEREVER, true, true, Optional.empty())),
                    calculations.quotes.snapshot(), Integer.MAX_VALUE);
                var want = Produce.of(Ae2Integration.OWNER, depot.site(), ItemSpec.of(Items.OAK_PLANKS), 16,
                    Produce.UNBOUNDED);
                helper.assertTrue(depot.options(want, Grown.of(want)).isEmpty(),
                    "a feasible plan exceeding every free CPU's byte capacity is not a route");
                calculations.update(network, "test", port, Set.of());
                helper.assertTrue(calculations.quotes.snapshot().find(network, item, 16).isEmpty(),
                    "removing the craftable repertoire invalidates a successful quote");
            }).thenSucceed();
        });
    }

    @GameTest(template = "ae2_empty", templateNamespace = "folkways_core")
    public static void learnedPatternAveragesPersistWithTheColonyAndPruneRemovedDefinitions(GameTestHelper helper) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        Cleanup.on(helper, colony::raze);
        var owner = ResourceLocation.fromNamespaceAndPath("folkways_core", "timing_test");
        var times = new PatternTimes(colony, owner);
        Map<String, Long> runs = Map.of("pattern", 4L);
        helper.assertTrue(times.estimate("network", runs) == 880, "cold start is per pattern execution");
        times.completed("network", runs, 80);
        int learned = times.estimate("network", runs);
        helper.assertTrue(learned == 680 && new PatternTimes(colony, owner).estimate("network", runs) == learned,
            "one completed order contributes an exponential moving sample saved in colony keeps");
        times.retain(Map.of("network", Set.of()));
        helper.assertTrue(new PatternTimes(colony, owner).estimate("network", runs) == 880,
            "a vanished pattern loses its observations");
        times.completed("network", runs, 80);
        times.retain(Map.of());
        helper.assertTrue(new PatternTimes(colony, owner).estimate("network", runs) == 880,
            "a vanished network loses its observations");
        helper.succeed();
    }
}
