package io.github.izakyl.folkways.cookery.audit;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.cookery.testing.NativeCookingMatrix;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_cookery", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NativeRecipeAuditGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(NativeRecipeAuditGameTests.class); }

    @GameTest(template = "empty")
    public static void everyPinnedNativeRecipeIsLoadedWithItsProductionType(GameTestHelper helper) {
        List<ResourceLocation> pinned = NativeCookingMatrix.ids(id -> true);
        helper.assertValueEqual(pinned.size(), 612, "the pinned jar index includes 580 mod IDs and 32 Minecraft IDs");
        var recipes = helper.getLevel().getRecipeManager();
        Set<ResourceLocation> types = new HashSet<>();
        for (ResourceLocation id : pinned) {
            RecipeHolder<?> holder = recipes.byKey(id).orElse(null);
            helper.assertTrue(holder != null, "the pinned native recipe is loaded: " + id);
            var type = BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType());
            // Cookery registers its serializers but uses RecipeType.simple without a type-registry entry.
            if (type == null) {
                type = ResourceLocation.tryParse(holder.value().getType().toString());
            }
            helper.assertTrue(type != null, "every native Cookery recipe names its actual production type: " + id);
            types.add(type);
        }
        helper.assertTrue(types.contains(ResourceLocation.parse("kaleidoscope_cookery:millstone"))
            && types.contains(ResourceLocation.parse("kaleidoscope_cookery:pot")), "native appliances are loaded, not just ordinary crafting");
        helper.succeed();
    }
}
