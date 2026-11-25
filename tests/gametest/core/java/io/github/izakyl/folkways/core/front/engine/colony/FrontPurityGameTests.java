package io.github.izakyl.folkways.core.front.engine.colony;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.item.api.ItemFilter;
import io.github.izakyl.folkways.core.colony.internal.ColonyRegistry;
import io.github.izakyl.folkways.vanilla.build.BuildContent;
import io.github.izakyl.folkways.core.testing.Purity;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FrontPurityGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(FrontPurityGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void frontMemoizationKeepsReadsPureAndInvalidatesToolRules(GameTestHelper helper) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        try {
            ColonyFront.forget(colony.id());
            Purity.assertPure(helper, "cold front memoization", () -> ColonyFront.of(colony).toolRules());
            var front = ColonyFront.of(colony);
            helper.assertTrue(ColonyFront.of(colony) == front, "cold lookup memoizes the live front");
            var rules = front.toolRules();
            helper.assertTrue(front.toolRules() == rules, "unchanged settings reuse tool rules");
            helper.assertTrue(front.setSetting(BuildContent.VOCATION, "tool", new ColonySettings.Value.Items(
                List.of(ItemFilter.item(ResourceLocation.withDefaultNamespace("bread"))))),
                "fixture changes the vocation tool list");
            helper.assertTrue(front.toolRules() != rules, "setting changes invalidate tool rules");
            helper.assertTrue(front.toolRules().tools().get(BuildContent.VOCATION).contains(Items.BREAD),
                "new tool rules reflect the changed setting");
            rules = front.toolRules();
            ColonyFront.toolsReloaded();
            helper.assertTrue(front.toolRules() != rules, "tag reload invalidates tool rules");
        } finally {
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void coldFrontReadsAndEqualKeepsDoNotDirty(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var colony = Colonies.mint(server);
        var registry = ColonyRegistry.get(server);
        try {
            CompoundTag saved = new CompoundTag();
            saved.put("holdings", new CompoundTag());
            saved.put("colony", new CompoundTag());
            colony.keep(ColonyFront.KEEP, saved);
            ColonyFront.forget(colony.id());
            Purity.assertPure(helper, "cold saved front", () -> {
                ColonyFront.of(colony).zones();
                ColonyFront.of(colony).toolRules();
            });
            registry.setDirty(false);
            Purity.assertPure(helper, "absent empty keep", () -> colony.keep(
                ResourceLocation.fromNamespaceAndPath("folkways_test", "empty_keep"), new CompoundTag()));
            Purity.assertPure(helper, "equal keep",
                () -> colony.keep(ColonyFront.KEEP, colony.kept(ColonyFront.KEEP)));
            Purity.assertPure(helper, "warm front", () -> ColonyFront.of(colony).zones());
        } finally {
            colony.raze();
        }
        helper.succeed();
    }
}
