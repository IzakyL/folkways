package io.github.izakyl.folkways.vanilla.wares;

import io.github.izakyl.folkways.core.inventory.api.StoreWorkstations;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Produce;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class CarriedCraftGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CarriedCraftGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void carriedCraftingStonecuttingAndSmithingUseExistingRecipeExecution(GameTestHelper helper) {
        var level = helper.getLevel();
        var body = PersonBody.RESIDENT.get().create(level);
        var stranger = PersonBody.RESIDENT.get().create(level);
        try {
            var recipes = RecipeIndex.over(level, level.getRecipeManager());
            var outputs = List.of(Items.STICK, Items.STONE_SLAB, Items.NETHERITE_PICKAXE);
            var supplied = List.of(List.of(new ItemStack(Items.OAK_PLANKS, 2)), List.of(new ItemStack(Items.STONE)),
                List.of(new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                    new ItemStack(Items.DIAMOND_PICKAXE), new ItemStack(Items.NETHERITE_INGOT)));
            for (int index = 0; index < StoreWorkstations.Capability.values().length; index++) {
                var capability = StoreWorkstations.Capability.values()[index];
                var available = new AtomicBoolean(true);
                var host = new PackCraftHost(body.id(), WorldSpaces.at(body), capability, ignored -> available.get());
                var workshop = host.frozen(recipes, level, Optional.empty());
                var ask = Produce.of(WaresContent.ID, WorkSite.on(body), ItemSpec.of(outputs.get(index)),
                    1, Produce.UNBOUNDED);
                var node = workshop.options(ask, Grown.of(ask)).getFirst().replacement().nodes().getFirst();
                helper.assertTrue(workshop.bearer().filter(body.id()::equals).isPresent()
                    && node.worker().equals(workshop.bearer()) && node.spec().stances() == Stances.WHEREVER,
                    "all three instant stations are carried by their bearer");
                var input = supplied.get(index);
                helper.assertTrue(node.commit(level, TestWorker.of(stranger).supplied(() -> input))
                    instanceof Outcome.Failed,
                    "another resident cannot execute carried work");
                var result = node.commit(level, TestWorker.of(body).supplied(() -> input));
                var output = outputs.get(index);
                helper.assertTrue(result instanceof Outcome.Done done
                    && done.made().stream().anyMatch(s -> s.is(output)),
                    "carried station executes the existing native recipe for " + capability);
                available.set(false);
                helper.assertTrue(node.commit(level, TestWorker.of(body).supplied(() -> input))
                    instanceof Outcome.Failed,
                    "loss of held capability invalidates old work");
            }
        } finally {
            body.discard();
            stranger.discard();
        }
        helper.succeed();
    }
}
