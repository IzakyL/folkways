package io.github.izakyl.folkways.resources;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysResourcesMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways")
@PrefixGameTestTemplate(false)
public final class ResourceRecipeGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(ResourceRecipeGameTests.class); }

    @GameTest(template = "empty")
    public static void ordinaryRecipesConvertNarrowSourcesOneForOne(GameTestHelper helper) {
        var level = helper.getLevel();
        var sources = List.of(Items.OAK_LOG, Items.ANDESITE, Items.BIRCH_SAPLING, Items.WHEAT_SEEDS);
        var names = List.of("wood", "stone", "sapling", "seed");
        var outputs = List.of(ResourceItems.WOOD.get(), ResourceItems.STONE.get(), ResourceItems.SAPLING.get(), ResourceItems.SEED.get());
        for (int index = 0; index < names.size(); index++) {
            var holder = level.getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath("folkways", "flattening/" + names.get(index))).orElseThrow();
            var recipe = (net.minecraft.world.item.crafting.CraftingRecipe) holder.value();
            var input = net.minecraft.world.item.crafting.CraftingInput.of(1, 1, List.of(new ItemStack(sources.get(index))));
            helper.assertTrue(recipe.matches(input, level), "category member is accepted by an ordinary recipe");
            ItemStack output = recipe.assemble(input, level.registryAccess());
            helper.assertTrue(output.is(outputs.get(index)) && output.getCount() == 1, "one input gives one resource");
            helper.assertTrue(recipe.getRemainingItems(input).stream().allMatch(ItemStack::isEmpty), "no input is returned");
            for (var item : outputs) helper.assertTrue(!recipe.matches(net.minecraft.world.item.crafting.CraftingInput.of(1, 1,
                List.of(new ItemStack(item))), level), "resources cannot be fed into resource recipes");
        }
        var wood = (net.minecraft.world.item.crafting.CraftingRecipe) level.getRecipeManager().byKey(
            ResourceLocation.fromNamespaceAndPath("folkways", "flattening/wood")).orElseThrow().value();
        for (var item : List.of(Items.OAK_PLANKS, Items.OAK_SLAB, Items.OAK_DOOR)) helper.assertTrue(!wood.matches(
            net.minecraft.world.item.crafting.CraftingInput.of(1, 1, List.of(new ItemStack(item))), level), "crafted wood cannot loop back into resources");
        helper.assertTrue(ResourceCatalog.current().size() == 4, "all four category files load");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void seedResourcesStayOutsideFoodCompostAndSeedReserveGroups(GameTestHelper helper) {
        ItemStack seed = new ItemStack(ResourceItems.SEED.get());
        helper.assertTrue(!seed.has(net.minecraft.core.component.DataComponents.FOOD), "seed resources are not food");
        helper.assertTrue(!net.minecraft.world.level.block.ComposterBlock.COMPOSTABLES.containsKey(seed.getItem()), "seed resources cannot be composted");
        helper.assertTrue(io.github.izakyl.folkways.plugins.compost.SeedReserves.alternatives(Items.WHEAT_SEEDS)
            .stream().noneMatch(seed::is), "seed resources do not enter crop-specific reserves");
        helper.assertTrue(!Goods.matches(seed, ItemSpec.of(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath("c", "seeds")))), "resource identity does not join the seed tag");
        helper.succeed();
    }
}
