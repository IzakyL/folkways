package io.github.izakyl.folkways.plugins.cookery;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.engine.Enrollments;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OptionalCookeryGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(OptionalCookeryGameTests.class);
    }

    @GameTest(template = "empty")
    public static void kitchenServicesAndPagesFollowTheOptionalModWithoutLoadingItsClasses(GameTestHelper helper) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        var owner = ResourceLocation.fromNamespaceAndPath("folkways", "cookery");
        boolean installed = ModList.get().isLoaded("kaleidoscope_cookery");
        var knifeTag = ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", "kitchen_knife");
        var crafting = io.github.izakyl.folkways.core.api.vocation.Vocations.required(
            ResourceLocation.fromNamespaceAndPath("folkways", "crafting"));
        helper.assertTrue(crafting.kit().stream().anyMatch(tool -> tool.tag()
                .map(tag -> tag.location().equals(knifeTag)).orElse(false)) == installed,
            "the crafting kit only names real kitchen knives when Cookery is installed");
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
