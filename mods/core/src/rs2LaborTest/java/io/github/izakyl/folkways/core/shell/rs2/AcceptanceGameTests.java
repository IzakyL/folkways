package io.github.izakyl.folkways.core.shell.rs2;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.autocrafting.PatternProvider;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import io.github.izakyl.folkways.core.shell.crafting.ColonyRecipes;
import io.github.izakyl.folkways.core.shell.crafting.NetworkBuffers;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.testing.NetworkServiceFixture;
import java.util.List;
import java.util.Map;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@PrefixGameTestTemplate(false)
public final class AcceptanceGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(AcceptanceGameTests.class);
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 300)
    public static void simulationChecksRecipeAndWorkerAndRejectedExecuteReturnsInputs(GameTestHelper helper) {
        var rig = new NetworkRig(helper);
        helper.runAfterDelay(120, () -> {
            var fixture = new NetworkServiceFixture(helper, List.of(NetworkRig.TERMINAL));
            fixture.enable(true);
            var recipes = new ColonyRecipes(fixture.colony);
            recipes.refresh(helper.getLevel().getServer());
            var entry = recipes.entries().stream().filter(value -> value.workshop().id()
                .equals(NetworkServiceFixture.OWNER)).findFirst().orElseThrow();
            var owner = ResourceLocation.parse("folkways_core:acceptance_test");
            var buffers = new NetworkBuffers(fixture.colony, owner, helper.getLevel().getServer(), recipes);
            var port = rig.port();
            var provider = new ColonyProvider(port.identity().home(), buffers);
            provider.connect(port);
            Cleanup.on(helper, provider::destroy);
            Cleanup.on(helper, buffers::close);
            var pattern = ColonyPattern.of(entry);
            var inputs = List.of(new ResourceAmount(new ItemResource(Items.IRON_INGOT), 2),
                new ResourceAmount(new ItemResource(Items.COPPER_INGOT), 1));
            provider.patterns(Map.of(pattern, entry.reference()));
            helper.assertTrue(provider.accept(pattern, inputs, Action.SIMULATE) == PatternProvider.Result.ACCEPTED,
                "a feasible recipe simulates acceptance");
            helper.assertTrue(fixture.colony.kept(owner).getList("batches", Tag.TAG_COMPOUND).isEmpty(),
                "simulation changes no buffers or reservations");
            fixture.enable(false);
            helper.assertTrue(provider.accept(pattern, inputs, Action.SIMULATE) == PatternProvider.Result.REJECTED,
                "simulation rechecks the required worker licence");
            fixture.enable(true);
            provider.patterns(Map.of(pattern, "deleted recipe"));
            helper.assertTrue(!provider.isBusy()
                && provider.accept(pattern, inputs, Action.SIMULATE) == PatternProvider.Result.REJECTED,
                "a free worker cannot make a deleted recipe acceptable");
            helper.assertTrue(provider.accept(pattern, inputs, Action.EXECUTE) == PatternProvider.Result.REJECTED,
                "execute still rejects the obsolete pattern");
            helper.assertTrue(fixture.colony.kept(owner).getList("batches", Tag.TAG_COMPOUND).size() == 1,
                "already withdrawn inputs are durably held for return");
            buffers.tick(List.of(new NetworkBuffers.Access(port, port.node().getNetwork(), port.delivery())));
            helper.assertTrue(rig.count(Items.IRON_INGOT) == 2 && rig.count(Items.COPPER_INGOT) == 1
                && fixture.colony.kept(owner).getList("batches", Tag.TAG_COMPOUND).isEmpty(),
                "a rejected execute returns exactly the withdrawn goods");
            helper.succeed();
        });
    }
}
