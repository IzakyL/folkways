package io.github.izakyl.folkways.core.api.vocation;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.core.api.participation.Participation;
import io.github.izakyl.folkways.core.api.participation.Participations;
import io.github.izakyl.folkways.core.api.participation.Stake;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VocationOrderingGameTests {
    // Full-id order deliberately disagrees with ResourceLocation's path-first natural order.
    private static final ResourceLocation EARLY = ResourceLocation.parse("folkways_test_a:z_ordered");
    private static final ResourceLocation LATE = ResourceLocation.parse("folkways_test_z:a_ordered");
    private static final ResourceLocation DEFAULT_EARLY = ResourceLocation.parse("folkways_test_a:z_default");
    private static final ResourceLocation DEFAULT_LATE = ResourceLocation.parse("folkways_test_z:a_default");
    private static final List<ResourceLocation> EXPECTED = List.of(EARLY, LATE, DEFAULT_EARLY, DEFAULT_LATE);

    @SubscribeEvent
    public static void declare(Declaring event) {
        // Reverse declaration order must not become column or wire order.
        for (ResourceLocation id : EXPECTED.reversed()) {
            VocationSpec spec = VocationSpec.of(id);
            event.vocation(id.equals(EARLY) || id.equals(LATE) ? spec.ordered(900) : spec);
            Participations.register(PersonBody.ID, Stake.vocation(id), Participation.NONE);
        }
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(VocationOrderingGameTests.class);
    }

    @GameTest(template = "empty")
    public static void columnsUseExplicitOrderThenFullIdRegardlessOfDeclarationOrder(GameTestHelper helper) {
        List<ResourceLocation> ids = Vocations.ids();
        helper.assertValueEqual(ids.stream().filter(EXPECTED::contains).toList(), EXPECTED,
            "explicit orders precede default orders, with namespace:path ties instead of insertion order");
        helper.assertValueEqual(Vocations.all().stream().map(Vocation::id).toList(), ids,
            "cells, event indices and per-trade wire arrays share exactly the same order");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void builtInColumnsKeepTheirHistoricalOrder(GameTestHelper helper) {
        List<ResourceLocation> expected = List.of("hauling", "crafting", "building", "farming", "herding",
                "fishing", "patrolling", "conducting", "dispatching").stream()
            .map(path -> ResourceLocation.fromNamespaceAndPath("folkways", path))
            .filter(id -> Vocations.of(id).isPresent()).toList();
        helper.assertValueEqual(Vocations.ids().stream().filter(expected::contains).toList(), expected,
            "built-in work columns retain their order when optional plugins are present or absent");
        helper.succeed();
    }
}
