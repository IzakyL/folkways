package io.github.izakyl.folkways.vanilla;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.front.api.Facing;
import io.github.izakyl.folkways.core.front.engine.Enrollments;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class OptionalCookeryGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(OptionalCookeryGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void kitchenServicesAndPagesFollowTheOptionalModWithoutLoadingItsClasses(GameTestHelper helper) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        var owner = ResourceLocation.fromNamespaceAndPath("folkways_cookery", "cookery");
        boolean installed = ModList.get().isLoaded("folkways_cookery");
        var knifeTag = ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", "kitchen_knife");
        var crafting = io.github.izakyl.folkways.core.vocation.api.Vocations.required(
            ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "crafting"));
        helper.assertFalse(crafting.kit().stream().anyMatch(tool -> tool.tag()
                .map(tag -> tag.location().equals(knifeTag)).orElse(false)),
            "a kitchen knife is shared per task and never requested as every resident's career kit");
        helper.assertTrue(io.github.izakyl.folkways.core.work.support.Tools.sharedGoods().stream().anyMatch(tool -> tool.tag()
                .map(tag -> tag.location().equals(knifeTag)).orElse(false)) == installed,
            "shared kitchen knife support is registered only when Cookery is present");
        try {
            helper.assertTrue(colony.service(owner, Object.class).isPresent() == installed,
                "the real colony only has a kitchen service when the optional mod is installed");
            helper.assertTrue(Facing.of(colony, owner).isPresent() == installed,
                "the real front only installs the optional kitchen facing");
            helper.assertTrue(Enrollments.of(owner).pages().stream().anyMatch(page -> page.id().equals(owner)) == installed,
                "the kitchen page follows the optional plugin registration");
        } finally {
            colony.raze();
        }
        helper.succeed();
    }
}
