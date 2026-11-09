package io.github.izakyl.folkways.core.vocation.api;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.resident.api.Participation;
import io.github.izakyl.folkways.core.resident.api.Participations;
import io.github.izakyl.folkways.core.resident.api.Stake;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
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

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void columnsUseExplicitOrderThenFullIdRegardlessOfDeclarationOrder(GameTestHelper helper) {
        List<ResourceLocation> ids = Vocations.ids();
        helper.assertValueEqual(ids.stream().filter(EXPECTED::contains).toList(), EXPECTED,
            "explicit orders precede default orders, with namespace:path ties instead of insertion order");
        helper.assertValueEqual(Vocations.all().stream().map(Vocation::id).toList(), ids,
            "cells, event indices and per-trade wire arrays share exactly the same order");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void builtInColumnsRunGroupByGroup(GameTestHelper helper) {
        List<ResourceLocation> expected = List.of("farming", "herding", "fishing", "crafting", "building",
                "hauling", "conducting", "dispatching", "patrolling", "damage_control").stream()
            .map(path -> ResourceLocation.fromNamespaceAndPath(switch (path) {
                case "hauling" -> "folkways_core";
                case "conducting", "dispatching" -> "folkways_create";
                default -> "folkways_vanilla";
            }, path))
            .filter(id -> Vocations.of(id).isPresent()).toList();
        helper.assertValueEqual(Vocations.ids().stream().filter(expected::contains).toList(), expected,
            "built-in work columns run gathering, processing, construction, transport, then defence");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void eachGroupHoldsOneContiguousRunOfColumns(GameTestHelper helper) {
        List<VocationGroup> seen = new java.util.ArrayList<>();
        for (Vocation vocation : Vocations.all()) {
            VocationGroup group = vocation.group();
            if (!seen.isEmpty() && seen.getLast().equals(group)) {
                continue;
            }
            helper.assertFalse(seen.contains(group), "group " + group.id() + " is split across columns");
            seen.add(group);
        }
        helper.assertValueEqual(Vocations.of(ResourceLocation.fromNamespaceAndPath("folkways_core", "hauling"))
            .map(Vocation::group).orElseThrow(), VocationGroup.TRANSPORT, "hauling is transport work");
        helper.succeed();
    }
}
