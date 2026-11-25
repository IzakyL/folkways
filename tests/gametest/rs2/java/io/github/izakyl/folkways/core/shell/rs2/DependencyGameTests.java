package io.github.izakyl.folkways.core.shell.rs2;

import com.refinedmods.refinedstorage.api.autocrafting.Ingredient;
import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.PatternLayout;
import com.refinedmods.refinedstorage.api.autocrafting.PatternRepositoryImpl;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CraftingCalculatorImpl;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskPlanCraftingCalculatorListener;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.shell.crafting.CraftRefusals;
import io.github.izakyl.folkways.core.shell.crafting.PatternTimes;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.shell.crafting.ColonyRecipes;
import io.github.izakyl.folkways.core.shell.crafting.NetworkBuffers;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_core")
@PrefixGameTestTemplate(false)
public final class DependencyGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(DependencyGameTests.class);
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 1000)
    public static void colonyDependencyAppearingAfterQuoteRefusesSubmission(GameTestHelper helper) {
        NetworkRig rig = CraftingGameTests.craftingRig(helper);
        helper.runAfterDelay(120, () -> {
            rig.stock(Items.OAK_LOG, 4);
            var port = rig.port();
            var crafting = port.crafting();
            var nativePattern = crafting.getPatterns().iterator().next();
            var tally = new Tally();
            var executor = CraftingGameTests.executors(port, tally).getFirst();
            var network = (Rs2Devices.NetworkKey) port.identity().key();
            var colony = CraftingGameTests.colony(port.identity().home());
            var calculations = new Calculations(new PatternTimes(colony, Rs2Integration.OWNER));
            var recipes = new ColonyRecipes(colony);
            var buffers = new NetworkBuffers(colony, Rs2Integration.OWNER, helper.getLevel().getServer(), recipes);
            var provider = new ColonyProvider(port.identity().home(), buffers);
            Cleanup.on(helper, calculations::close);
            Cleanup.on(helper, buffers::close);
            Cleanup.on(helper, provider::destroy);
            var wanted = Goods.specOf(new ItemStack(Items.OAK_PLANKS));
            calculations.quotes.snapshot().find(network, wanted, 16);
            helper.startSequence().thenWaitUntil(() -> {
                calculations.update(network, "test", port, Set.of(new ItemResource(Items.OAK_PLANKS)));
                helper.assertTrue(calculations.quotes.snapshot().find(network, wanted, 16).isPresent(),
                    "the initial quote is feasible using only the native pattern");
            }).thenExecute(() -> {
                var quote = calculations.quotes.snapshot().find(network, wanted, 16).orElseThrow();
                var ticket = tally.ticket(UUID.randomUUID(), network, wanted, 16, List.of(executor));
                ticket.patterns(quote.patterns());
                Cleanup.on(helper, () -> {
                    ticket.release(Ending.DROPPED);
                    tally.clean();
                });
                helper.assertTrue(ticket.take().isEmpty(), "the quoted route reserves its native executor");
                helper.assertTrue(port.storage().extract(new ItemResource(Items.OAK_LOG), 4, Action.EXECUTE,
                    Rs2Devices.source(port.node())) == 4, "the quoted ingredients disappear before submission");
                rig.stock(Items.IRON_INGOT, 4);
                var colonyPattern = new Pattern(UUID.randomUUID(), PatternLayout.external(
                    List.of(new Ingredient(1, List.of(new ItemResource(Items.IRON_INGOT)))),
                    List.of(new ResourceAmount(new ItemResource(Items.OAK_LOG), 1))));
                provider.patterns(Map.of(colonyPattern, "test"));
                provider.connect(port);
                helper.assertTrue(!ticket.patterns().containsKey(Calculations.definition(colonyPattern)),
                    "the new dependency is absent from the previously checked quote");
                var repository = new PatternRepositoryImpl();
                repository.add(nativePattern, 0);
                repository.add(colonyPattern, 0);
                var plan = TaskPlanCraftingCalculatorListener.calculatePlan(
                    new CraftingCalculatorImpl(repository, port.storage()), new ItemResource(Items.OAK_PLANKS),
                    16, CancellationToken.NONE).orElseThrow();
                helper.assertTrue(plan.rootPattern().equals(nativePattern)
                        && plan.patterns().containsKey(colonyPattern),
                    "RS2's real calculator selects a colony dependency below the unchanged native root");
                var order = new CraftOrder(port.identity().home(), wanted, ticket, colony, CraftingGameTests.orderedOutputs(helper, colony));
                var collect = Rs2Devices.COLLECTION.take(port.identity().home(), Stances.WHEREVER, ticket,
                    order, colony);
                helper.assertTrue(order.start(helper.getLevel()).isEmpty(), "submission preview begins");
                helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
                    collect.refusal(helper.getLevel()).filter(CraftRefusals.RS2.calculationFailed()::equals).isPresent(),
                    "the collection node exposes the refusal so the planner can replace the route"))
                    .thenExecute(() -> {
                        ticket.release(Ending.DROPPED);
                        tally.clean();
                    }).thenIdle(40).thenExecute(() -> {
                        helper.assertTrue(tally.free(network, executor) && crafting.getStatuses().isEmpty()
                                && ticket.nativeExecutor().getTaskStatuses().isEmpty() && provider.tasks().isEmpty(),
                            "refusal leaves no native or colony task and releases the executor");
                        helper.assertTrue(provider.pushes() == 0 && provider.buffered(new ItemResource(
                                Items.IRON_INGOT)) == 0 && rig.count(Items.IRON_INGOT) == 4
                                && rig.count(Items.OAK_LOG) == 0 && rig.count(Items.OAK_PLANKS) == 0,
                            "refusal claims no inputs, even after the providers have ticked");
                    }).thenSucceed();
            });
        });
    }
}
