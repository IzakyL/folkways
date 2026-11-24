package io.github.izakyl.folkways.core.shell.rs2;

import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.WorkshopRecipes;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Need;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@PrefixGameTestTemplate(false)
public final class PatternGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(PatternGameTests.class);
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core")
    public static void unrepresentableRecipesAreSkipped(GameTestHelper helper) {
        var normal = ItemSpec.of(Items.DIAMOND);
        var empty = ItemSpec.of(TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath("folkways_core", "missing_pattern_test")));
        var input = new Need(ItemSpec.of(Items.IRON_INGOT), 1);
        var valid = new WorkshopRecipes.Recipe(List.of(input), List.of(new Amount(normal, 1)));
        helper.assertTrue(ColonyPattern.of("test", valid).isPresent(), "normal recipes produce a pattern");
        for (var output : List.of(empty)) {
            var recipe = new WorkshopRecipes.Recipe(List.of(input), List.of(new Amount(output, 1)));
            helper.assertTrue(ColonyPattern.of("test", recipe).isEmpty(), "unrepresentable primary outputs are skipped");
            var secondary = new WorkshopRecipes.Recipe(List.of(input),
                List.of(new Amount(normal, 1), new Amount(output, 1)));
            helper.assertTrue(ColonyPattern.of("test", secondary).isEmpty(), "outputs are never truncated");
        }
        for (var spec : List.of(empty)) {
            var recipe = new WorkshopRecipes.Recipe(List.of(new Need(spec, 1)), valid.outputs());
            helper.assertTrue(ColonyPattern.of("test", recipe).isEmpty(), "unrepresentable inputs are skipped");
        }
        var mixed = new WorkshopRecipes.Recipe(
            List.of(new Need(ItemSpec.anyOf(List.of(empty, normal)), 1)), valid.outputs());
        helper.assertTrue(ColonyPattern.of("test", mixed).isPresent(), "empty individual choices are dropped");

        helper.succeed();
    }
}
