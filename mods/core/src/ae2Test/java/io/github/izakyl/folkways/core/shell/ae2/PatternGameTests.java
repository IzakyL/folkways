package io.github.izakyl.folkways.core.shell.ae2;

import appeng.api.stacks.AEItemKey;
import io.github.izakyl.folkways.core.front.engine.registry.FolkwaysItems;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.WorkshopRecipes;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Need;
import java.util.List;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
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

    @GameTest(template = "ae2_empty", templateNamespace = "folkways_core")
    public static void unrepresentableRecipesAreSkipped(GameTestHelper helper) {
        var normal = ItemSpec.of(Items.DIAMOND);
        var empty = ItemSpec.of(TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath("folkways_core", "missing_pattern_test")));
        var invalid = normal.withComponents(DataComponentMap.builder()
            .set(DataComponents.MAX_STACK_SIZE, 0).build());
        var input = new Need(ItemSpec.of(Items.IRON_INGOT), 1);
        var valid = new WorkshopRecipes.Recipe(List.of(input), List.of(new Amount(normal, 1)));
        helper.assertTrue(ColonyPattern.of("test", valid, helper.getLevel()).isPresent(), "normal recipes produce a pattern");
        for (var output : List.of(empty, invalid)) {
            var recipe = new WorkshopRecipes.Recipe(List.of(input), List.of(new Amount(output, 1)));
            helper.assertTrue(ColonyPattern.of("test", recipe, helper.getLevel()).isEmpty(), "unrepresentable primary outputs are skipped");
            var secondary = new WorkshopRecipes.Recipe(List.of(input),
                List.of(new Amount(normal, 1), new Amount(output, 1)));
            helper.assertTrue(ColonyPattern.of("test", secondary, helper.getLevel()).isEmpty(), "outputs are never truncated");
        }
        for (var spec : List.of(empty, invalid)) {
            var recipe = new WorkshopRecipes.Recipe(List.of(new Need(spec, 1)), valid.outputs());
            helper.assertTrue(ColonyPattern.of("test", recipe, helper.getLevel()).isEmpty(), "unrepresentable inputs are skipped");
        }
        var mixed = new WorkshopRecipes.Recipe(
            List.of(new Need(ItemSpec.anyOf(List.of(empty, normal)), 1)), valid.outputs());
        helper.assertTrue(ColonyPattern.of("test", mixed, helper.getLevel()).isPresent(), "empty individual choices are dropped");
        helper.assertTrue(ColonyPattern.DECODER.decodePattern(
            AEItemKey.of(new ItemStack(FolkwaysItems.NETWORK_PATTERN.get())),
            helper.getLevel()) == null, "stored patterns without data are rejected");
        helper.succeed();
    }
}
